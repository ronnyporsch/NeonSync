@file:Suppress("FunctionName")

package neonsync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.FileDescriptor
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

@DBusInterfaceName("org.freedesktop.portal.ScreenCast")
@JvmSuppressWildcards
interface ScreenCastInterface : DBusInterface {
    fun CreateSession(options: Map<String, Variant<*>>): DBusPath
    fun SelectSources(sessionHandle: DBusPath, options: Map<String, Variant<*>>): DBusPath
    fun Start(sessionHandle: DBusPath, parentWindow: String, options: Map<String, Variant<*>>): DBusPath
    fun OpenPipeWireRemote(sessionHandle: DBusPath, options: Map<String, Variant<*>>): FileDescriptor
}

@DBusInterfaceName("org.freedesktop.portal.Request")
interface RequestInterface : DBusInterface {
    @JvmSuppressWildcards
    class Response(path: String, val response: UInt32, val results: Map<String, Variant<*>>) : DBusSignal(path, response, results)
}

@DBusInterfaceName("org.freedesktop.portal.Session")
interface SessionInterface : DBusInterface {
    fun Close()

    @JvmSuppressWildcards
    class Closed(path: String, val details: Map<String, Variant<*>>) : DBusSignal(path, details)
}

class PortalException(message: String) : Exception(message)

/** An active screencast: PipeWire node to read from and (optionally) a dedicated PipeWire connection fd. */
data class ScreenCastStream(val nodeId: Int, val pipeWireFd: Int?, val width: Int?, val height: Int?)

/**
 * Talks to `org.freedesktop.portal.ScreenCast`, the only sanctioned way to capture the screen on
 * KDE Wayland. KDE shows its own picker dialog the first time; afterwards the restore token lets
 * the session start silently.
 */
class ScreenCastPortal(private val bus: DBusConnection) {
    private val log = LoggerFactory.getLogger(ScreenCastPortal::class.java)
    private val portal = bus.getRemoteObject(DESTINATION, OBJECT_PATH, ScreenCastInterface::class.java)
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<RequestInterface.Response>>()
    private val requestHandler = bus.addSigHandler(RequestInterface.Response::class.java) { signal ->
        pendingRequests.remove(signal.path)?.complete(signal)
    }
    private var sessionHandle: DBusPath? = null
    private var closedHandler: AutoCloseable? = null

    /** Unique bus name `:1.42` becomes `1_42` in request object paths. */
    private val senderToken = bus.uniqueName.removePrefix(":").replace('.', '_')

    /**
     * Opens a monitor screencast. Returns the stream and the new restore token (store it, tokens are single-use).
     * [onClosed] is invoked when the compositor ends the session (e.g. "stop sharing" in the KDE tray).
     */
    suspend fun open(restoreToken: String?, onClosed: () -> Unit): Pair<ScreenCastStream, String?> {
        close()
        val sessionToken = token()
        val created = request { handle ->
            portal.CreateSession(mapOf("handle_token" to Variant(handle), "session_handle_token" to Variant(sessionToken)))
        }
        // Specified as string, but some portal versions send an object path
        val session = when (val h = created["session_handle"]?.value) {
            is String -> DBusPath(h)
            is DBusPath -> h
            else -> throw PortalException("no session handle")
        }
        sessionHandle = session
        closedHandler = bus.addSigHandler(SessionInterface.Closed::class.java) { signal ->
            if (signal.path == session.path) {
                log.info("Screencast session was closed by the compositor")
                sessionHandle = null
                onClosed()
            }
        }

        val selectOptions = buildMap<String, Variant<*>> {
            put("types", Variant(UInt32(SOURCE_MONITOR)))
            put("multiple", Variant(false))
            put("cursor_mode", Variant(UInt32(CURSOR_HIDDEN)))
            put("persist_mode", Variant(UInt32(PERSIST_UNTIL_REVOKED)))
            if (!restoreToken.isNullOrBlank()) put("restore_token", Variant(restoreToken))
        }
        request { handle -> portal.SelectSources(session, selectOptions + ("handle_token" to Variant(handle))) }

        // The user may need to pick a screen in a dialog, so allow plenty of time.
        val started = request(timeoutMinutes = 5) { handle -> portal.Start(session, "", mapOf("handle_token" to Variant(handle))) }
        val newToken = started["restore_token"]?.value as? String
        val stream = parseStreams(started["streams"]?.value).firstOrNull() ?: throw PortalException("portal returned no streams")

        val fd = try {
            portal.OpenPipeWireRemote(session, emptyMap()).intFileDescriptor
        } catch (e: Exception) {
            log.warn("OpenPipeWireRemote failed ({}), falling back to the default PipeWire connection", e.message)
            null
        }
        log.info("Screencast started: PipeWire node {}, size {}x{}", stream.nodeId, stream.width, stream.height)
        return stream.copy(pipeWireFd = fd) to newToken
    }

    fun close() {
        closedHandler?.close()
        closedHandler = null
        sessionHandle?.let { handle ->
            runCatching { bus.getRemoteObject(DESTINATION, handle.path, SessionInterface::class.java).Close() }
        }
        sessionHandle = null
    }

    /** Aborts requests waiting for the user (e.g. the screen selection dialog); [open] then fails. */
    fun cancelPending() {
        pendingRequests.values.forEach { it.completeExceptionally(PortalException("cancelled")) }
    }

    fun dispose() {
        close()
        requestHandler.close()
    }

    /**
     * Portal methods return immediately with a Request object; the actual result arrives as a
     * `Response` signal on it. The path is predictable from `handle_token`, so the handler is
     * registered before the call to avoid races.
     */
    private suspend fun request(timeoutMinutes: Int = 1, call: (String) -> DBusPath): Map<String, Variant<*>> {
        val handle = token()
        val path = "/org/freedesktop/portal/desktop/request/$senderToken/$handle"
        val deferred = CompletableDeferred<RequestInterface.Response>()
        pendingRequests[path] = deferred
        try {
            val returned = call(handle).path
            if (returned != path) {
                // Old portal versions ignore handle_token
                pendingRequests.remove(path)
                pendingRequests[returned] = deferred
            }
            val response = withTimeout(timeoutMinutes.minutes) { deferred.await() }
            when (response.response.toInt()) {
                0 -> return response.results
                1 -> throw PortalException("screen selection was cancelled")
                else -> throw PortalException("portal request failed")
            }
        } finally {
            pendingRequests.values.remove(deferred)
        }
    }

    private fun token() = "neonsync_" + UUID.randomUUID().toString().replace("-", "").take(12)

    /** `streams` has the signature a(ua{sv}); depending on how dbus-java unpacks variants we get Structs or arrays. */
    private fun parseStreams(value: Any?): List<ScreenCastStream> {
        val items: List<Any?> = when (value) {
            is List<*> -> value
            is Array<*> -> value.toList()
            else -> return emptyList()
        }
        return items.mapNotNull { item ->
            val parts: List<Any?> = when (item) {
                is Struct -> item.parameters.toList()
                is Array<*> -> item.toList()
                is List<*> -> item
                else -> return@mapNotNull null
            }
            val node = (parts.getOrNull(0) as? Number)?.toInt() ?: return@mapNotNull null
            val props = parts.getOrNull(1) as? Map<*, *> ?: emptyMap<Any, Any>()
            val size = props["size"].unwrapVariant()
            val dims: List<Any?> = when (size) {
                is Struct -> size.parameters.toList()
                is Array<*> -> size.toList()
                is List<*> -> size
                else -> emptyList()
            }
            ScreenCastStream(node, null, (dims.getOrNull(0) as? Number)?.toInt(), (dims.getOrNull(1) as? Number)?.toInt())
        }
    }

    private fun Any?.unwrapVariant(): Any? = if (this is Variant<*>) value else this

    companion object {
        const val DESTINATION = "org.freedesktop.portal.Desktop"
        const val OBJECT_PATH = "/org/freedesktop/portal/desktop"
        const val SOURCE_MONITOR = 1L
        const val CURSOR_HIDDEN = 1L
        const val PERSIST_UNTIL_REVOKED = 2L
    }
}
