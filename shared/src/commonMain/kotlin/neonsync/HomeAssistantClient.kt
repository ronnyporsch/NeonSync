package neonsync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.time.Duration.Companion.seconds

class HomeAssistantException(message: String) : Exception(message)

/**
 * Minimal Home Assistant WebSocket API client with automatic reconnect.
 *
 * WebSocket is used instead of REST because it keeps one connection open (no per-request TCP/TLS
 * handshake) and `call_service` results arrive once the integration finished the command — for ZHA
 * that is when the Zigbee command was acknowledged, which gives natural back-pressure.
 */
class HomeAssistantClient(@Volatile private var config: HomeAssistantConfig, private val scope: CoroutineScope) {
    private val log = logger("HomeAssistantClient")
    private val http = HttpClient { install(WebSockets) }
    private val ids = AtomicInt(1)
    private val pending = concurrentMapOf<Int, CompletableDeferred<JsonElement?>>()
    private val subscriptions = concurrentMapOf<Int, (JsonObject) -> Unit>()

    @Volatile
    private var socket: DefaultClientWebSocketSession? = null
    private var loop: Job? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val wsUri: String get() = config.url.trimEnd('/').replaceFirst(Regex("^http"), "ws") + "/api/websocket"

    /** Emits after every successful (re)connect, e.g. to reload the light list. */
    private val _connections = MutableStateFlow(0)
    val connections: StateFlow<Int> = _connections

    /** Last connection problem, for display; null while connected. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    fun start() {
        if (loop != null) return
        if (config.token.isBlank()) {
            _lastError.value = "No access token configured"
            return
        }
        loop = scope.launch {
            var backoff = 1.seconds
            while (isActive) {
                try {
                    runConnection { backoff = 1.seconds }
                } catch (e: AuthException) {
                    log.error("Home Assistant rejected the access token: {}", e.message)
                    _lastError.value = "Access token rejected"
                    backoff = 60.seconds
                } catch (e: Exception) {
                    if (!isActive) break
                    log.warn("Home Assistant connection to {} failed: {}", wsUri, e.message ?: e::class.simpleName)
                    _lastError.value = "Connection failed: ${e.message ?: e::class.simpleName}"
                } finally {
                    _connected.value = false
                    socket?.cancel()
                    socket = null
                    pending.values.forEach { it.completeExceptionally(HomeAssistantException("connection lost")) }
                    pending.clear()
                    // Subscriptions die with the connection; owners re-subscribe after reconnect
                    subscriptions.clear()
                }
                if (!isActive) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30.seconds)
            }
        }
    }

    /** Suspends until connected (or [timeoutSeconds] passed). */
    suspend fun awaitConnected(timeoutSeconds: Int = 15) {
        withTimeout(timeoutSeconds.seconds) { connected.first { it } }
    }

    fun close() {
        socket?.let { s -> scope.launch { runCatching { s.close(CloseReason(CloseReason.Codes.NORMAL, "bye")) } } }
        loop?.cancel()
        loop = null
    }

    /** Switches to new connection settings and reconnects immediately. */
    fun reconfigure(newConfig: HomeAssistantConfig) {
        close()
        socket?.cancel()
        config = newConfig
        start()
    }

    private class AuthException(message: String) : Exception(message)

    private suspend fun runConnection(onAuthenticated: () -> Unit) {
        val ws = withTimeout(10.seconds) { http.webSocketSession(wsUri) }
        socket = ws
        // Ktor reassembles fragmented messages; only text frames carry API messages
        suspend fun receive(): JsonObject {
            while (true) {
                val frame = ws.incoming.receive()
                if (frame is Frame.Text) return json.parseToJsonElement(frame.readText()).jsonObject
            }
        }

        val first = receive()
        if (first.type != "auth_required") throw HomeAssistantException("unexpected greeting: $first")
        send(ws, buildJsonObject { put("type", "auth"); put("access_token", config.token) })
        val auth = receive()
        when (auth.type) {
            "auth_ok" -> log.info("Connected to Home Assistant {} ({})", config.url, auth["ha_version"]?.jsonPrimitive?.content)
            "auth_invalid" -> throw AuthException(auth["message"]?.jsonPrimitive?.content ?: "auth_invalid")
            else -> throw HomeAssistantException("unexpected auth reply: $auth")
        }
        onAuthenticated()
        _lastError.value = null
        _connected.value = true
        _connections.value++

        val heartbeat = scope.launch {
            while (isActive) {
                delay(30.seconds)
                try {
                    withTimeout(10.seconds) { request(buildJsonObject { put("type", "ping") }) }
                } catch (_: Exception) {
                    log.warn("Home Assistant did not answer ping, reconnecting")
                    ws.cancel()
                }
            }
        }
        try {
            for (frame in ws.incoming) if (frame is Frame.Text) handleMessage(json.parseToJsonElement(frame.readText()).jsonObject)
        } finally {
            heartbeat.cancel()
        }
        throw HomeAssistantException("connection closed")
    }

    private fun handleMessage(msg: JsonObject) {
        val id = msg["id"]?.jsonPrimitive?.int ?: return
        if (msg.type == "event") {
            val event = msg["event"] as? JsonObject ?: return
            try {
                subscriptions[id]?.invoke(event)
            } catch (e: Exception) {
                log.warn("Event handler failed", e)
            }
            return
        }
        val deferred = pending.remove(id) ?: return
        when (msg.type) {
            "pong" -> deferred.complete(null)
            "result" -> {
                if (msg["success"]?.jsonPrimitive?.content == "true") {
                    deferred.complete(msg["result"])
                } else {
                    val err = msg["error"]?.jsonObject
                    deferred.completeExceptionally(
                        HomeAssistantException("${err?.get("code")?.jsonPrimitive?.content}: ${err?.get("message")?.jsonPrimitive?.content}"),
                    )
                }
            }
        }
    }

    private suspend fun send(ws: DefaultClientWebSocketSession, obj: JsonObject) = ws.send(Frame.Text(obj.toString()))

    /** Sends a command (an `id` is added) and returns its `result`. */
    suspend fun request(command: JsonObject): JsonElement? = request(command, ids.fetchAndAdd(1))

    /**
     * Subscribes to an HA event type for the current connection. [handler] runs in the connection's
     * receive loop and must be quick. Call again after a reconnect (see [connections]).
     */
    suspend fun subscribeEvents(eventType: String, handler: (JsonObject) -> Unit) {
        val id = ids.fetchAndAdd(1)
        subscriptions[id] = handler
        try {
            request(buildJsonObject { put("type", "subscribe_events"); put("event_type", eventType) }, id)
        } catch (e: Exception) {
            subscriptions.remove(id)
            throw e
        }
    }

    private suspend fun request(command: JsonObject, id: Int): JsonElement? {
        val ws = socket?.takeIf { _connected.value } ?: throw HomeAssistantException("not connected")
        val deferred = CompletableDeferred<JsonElement?>()
        pending[id] = deferred
        try {
            send(ws, JsonObject(command + ("id" to JsonPrimitive(id))))
            return deferred.await()
        } finally {
            pending.remove(id)
        }
    }

    suspend fun callService(domain: String, service: String, entityId: String, data: JsonObject = JsonObject(emptyMap())) {
        request(
            buildJsonObject {
                put("type", "call_service")
                put("domain", domain)
                put("service", service)
                put("service_data", data)
                putJsonObject("target") { put("entity_id", entityId) }
            },
        )
    }

    suspend fun getStates(): List<JsonObject> =
        (request(buildJsonObject { put("type", "get_states") }) as? JsonArray)?.map { it.jsonObject } ?: emptyList()

    private val JsonObject.type get() = this["type"]?.jsonPrimitive?.content
}
