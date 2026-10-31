@file:Suppress("FunctionName")

package neonsync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import kotlin.time.Duration.Companion.minutes

/** Two return values; the type arguments tell dbus-java how to unpack them. */
class DBusPair<A, B>(@field:Position(0) val first: A, @field:Position(1) val second: B) : Tuple() {
    operator fun component1() = first
    operator fun component2() = second
}

/** `(oayays)`: session, algorithm parameters, value, content type. */
class Secret(
    @field:Position(0) val session: DBusPath,
    @field:Position(1) val parameters: ByteArray,
    @field:Position(2) val value: ByteArray,
    @field:Position(3) val contentType: String,
) : Struct()

@DBusInterfaceName("org.freedesktop.Secret.Service")
@JvmSuppressWildcards
interface SecretServiceInterface : DBusInterface {
    fun OpenSession(algorithm: String, input: Variant<*>): DBusPair<Variant<*>, DBusPath>
    fun SearchItems(attributes: Map<String, String>): DBusPair<List<DBusPath>, List<DBusPath>>
    fun Unlock(objects: List<DBusPath>): DBusPair<List<DBusPath>, DBusPath>
    fun ReadAlias(name: String): DBusPath
}

@DBusInterfaceName("org.freedesktop.Secret.Collection")
@JvmSuppressWildcards
interface SecretCollectionInterface : DBusInterface {
    fun CreateItem(properties: Map<String, Variant<*>>, secret: Secret, replace: Boolean): DBusPair<DBusPath, DBusPath>
}

@DBusInterfaceName("org.freedesktop.Secret.Item")
interface SecretItemInterface : DBusInterface {
    fun GetSecret(session: DBusPath): Secret
    fun Delete(): DBusPath
}

@DBusInterfaceName("org.freedesktop.Secret.Session")
interface SecretSessionInterface : DBusInterface {
    fun Close()
}

@DBusInterfaceName("org.freedesktop.Secret.Prompt")
interface SecretPromptInterface : DBusInterface {
    fun Prompt(windowId: String)

    class Completed(path: String, val dismissed: Boolean, val result: Variant<*>) : DBusSignal(path, dismissed, result)
}

class SecretServiceException(message: String) : Exception(message)

/**
 * Keeps the token in the desktop's Secret Service (KDE Wallet, GNOME Keyring, KeePassXC, …), which
 * stores it encrypted and unlocks it with the login.
 */
class SecretServiceStore : SecretStore {
    override val location: String
        get() {
            val desktop = System.getenv("XDG_CURRENT_DESKTOP").orEmpty().uppercase()
            return when {
                desktop.contains("KDE") -> "KDE Wallet"
                desktop.contains("GNOME") -> "GNOME Keyring"
                else -> "the Secret Service"
            }
        }

    override suspend fun read(): String? = withSession { session -> find().firstNotNullOfOrNull { readItem(it, session) } }

    override suspend fun write(secret: String) = withSession { session ->
        if (secret.isBlank()) {
            val items = find()
            unlock(items)
            items.forEach { prompt(it.item<SecretItemInterface>().Delete()) }
        } else {
            val collection = service.ReadAlias("default").takeIf { it.path != "/" }
                ?: throw SecretServiceException("there is no default wallet / keyring")
            unlock(listOf(collection))
            val properties = mapOf(
                "org.freedesktop.Secret.Item.Label" to Variant(LABEL),
                "org.freedesktop.Secret.Item.Attributes" to Variant(ATTRIBUTES, "a{ss}"),
            )
            val value = Secret(session, ByteArray(0), secret.toByteArray(), "text/plain; charset=utf8")
            val (_, promptPath) = bus.getRemoteObject(DESTINATION, collection.path, SecretCollectionInterface::class.java)
                .CreateItem(properties, value, true)
            prompt(promptPath)
        }
    }

    private class Connection(val bus: DBusConnection) {
        val service: SecretServiceInterface = bus.getRemoteObject(DESTINATION, OBJECT_PATH, SecretServiceInterface::class.java)

        inline fun <reified T : DBusInterface> DBusPath.item(): T = bus.getRemoteObject(DESTINATION, path, T::class.java)

        fun find(): List<DBusPath> = service.SearchItems(ATTRIBUTES).let { (unlocked, locked) -> unlocked + locked }

        suspend fun readItem(item: DBusPath, session: DBusPath): String? {
            unlock(listOf(item))
            return item.item<SecretItemInterface>().GetSecret(session).value.decodeToString().ifBlank { null }
        }

        suspend fun unlock(objects: List<DBusPath>) {
            if (objects.isNotEmpty()) prompt(service.Unlock(objects).second)
        }

        /** Shows the Secret Service's dialog (e.g. the wallet password), if it needs one, and waits for it. */
        suspend fun prompt(path: DBusPath) {
            if (path.path == "/") return
            val done = CompletableDeferred<SecretPromptInterface.Completed>()
            bus.addSigHandler(SecretPromptInterface.Completed::class.java) { signal ->
                if (signal.path == path.path) done.complete(signal)
            }.use {
                path.item<SecretPromptInterface>().Prompt("")
                val result = withTimeout(5.minutes) { done.await() }
                if (result.dismissed) throw SecretServiceException("unlocking was cancelled")
            }
        }
    }

    /** Calls are rare (start, saving the settings), so each one gets its own connection. */
    private suspend fun <T> withSession(block: suspend Connection.(session: DBusPath) -> T): T = withContext(Dispatchers.IO) {
        val bus = try {
            DBusConnectionBuilder.forSessionBus().withShared(false).build()
        } catch (e: Exception) {
            throw SecretServiceException("no D-Bus session bus: ${e.message}")
        }
        bus.use {
            val connection = Connection(bus)
            // "plain" sends the secret unencrypted, which is fine on the user's own session bus
            val session = try {
                connection.service.OpenSession("plain", Variant("")).second
            } catch (e: DBusExecutionException) {
                throw SecretServiceException("no Secret Service (KDE Wallet, GNOME Keyring, KeePassXC, …) is running: ${e.message}")
            }
            try {
                connection.block(session)
            } finally {
                runCatching { bus.getRemoteObject(DESTINATION, session.path, SecretSessionInterface::class.java).Close() }
            }
        }
    }

    companion object {
        const val DESTINATION = "org.freedesktop.secrets"
        const val OBJECT_PATH = "/org/freedesktop/secrets"
        const val LABEL = "NeonSync Home Assistant access token"
        val ATTRIBUTES = mapOf("application" to "neonsync", "xdg:schema" to "io.github.neonsync.HomeAssistantToken")
    }
}
