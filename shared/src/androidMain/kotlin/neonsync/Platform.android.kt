package neonsync

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
