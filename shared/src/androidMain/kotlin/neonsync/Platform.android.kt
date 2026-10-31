package neonsync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual fun logger(name: String): Logger = object : Logger {
    // Logcat tags are limited in length on older Android versions
    private val tag = name.takeLast(23)

    override fun debug(message: String, vararg args: Any?) = log(Log.DEBUG, message, args)
    override fun info(message: String, vararg args: Any?) = log(Log.INFO, message, args)
    override fun warn(message: String, vararg args: Any?) = log(Log.WARN, message, args)
    override fun error(message: String, vararg args: Any?) = log(Log.ERROR, message, args)

    private fun log(priority: Int, message: String, args: Array<out Any?>) {
        if (priority == Log.DEBUG && !Log.isLoggable(tag, Log.DEBUG)) return
        val error = args.lastOrNull() as? Throwable
        val text = formatLogMessage(message, args)
        Log.println(priority, tag, if (error != null) text + "\n" + Log.getStackTraceString(error) else text)
    }
}

private class ConcurrentHashMapImpl<K : Any, V : Any> : ConcurrentHashMap<K, V>(), ConcurrentMap<K, V>

@Suppress("UNCHECKED_CAST")
actual fun <K, V> concurrentMapOf(): ConcurrentMap<K, V> = ConcurrentHashMapImpl<Any, Any>() as ConcurrentMap<K, V>

/** A file in the app's private storage, written atomically. */
class AndroidTextFile(private val file: File) : TextFile {
    override val location: String get() = file.path

    override fun readText(): String? = if (file.exists()) file.readText() else null

    override fun writeText(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) throw java.io.IOException("Could not write $file")
    }
}

/**
 * Encrypted with an AES key in the Android Keystore, which can't be read out of it (and is kept in
 * secure hardware where the device has some). The ciphertext lives in [file].
 */
class KeystoreSecretStore(private val file: File) : SecretStore {
    private val log = logger("neonsync.KeystoreSecretStore")

    override val location get() = "the Android Keystore"

    override suspend fun read(): String? {
        if (!file.exists()) return null
        return try {
            val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: return null
            val data = Base64.decode(file.readText(), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, IV_SIZE))
            cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE).decodeToString()
        } catch (e: GeneralSecurityException) {
            // E.g. restored from a backup: the key stays on the device it was made on
            log.warn("Could not decrypt the access token: {}", e.message)
            null
        } catch (e: IllegalArgumentException) {
            log.warn("Could not decrypt the access token: {}", e.message)
            null
        }
    }

    override suspend fun write(secret: String) {
        if (secret.isBlank()) {
            file.delete()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(secret.encodeToByteArray())
        AndroidTextFile(file).writeText(Base64.encodeToString(data, Base64.NO_WRAP))
    }

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun key(): SecretKey = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
        ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "neonsync.token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
