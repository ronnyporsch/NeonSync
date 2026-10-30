package neonsync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
    private val log = LoggerFactory.getLogger(HomeAssistantClient::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>()
    private val subscriptions = ConcurrentHashMap<Int, (JsonObject) -> Unit>()
    private val sendMutex = Mutex()

    @Volatile
    private var socket: WebSocket? = null
    private var loop: Job? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val wsUri: URI get() = URI.create(config.url.trimEnd('/').replaceFirst(Regex("^http"), "ws") + "/api/websocket")

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
                    log.warn("Home Assistant connection to {} failed: {}", wsUri, e.message ?: e.javaClass.simpleName)
                    _lastError.value = "Connection failed: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    _connected.value = false
                    socket?.abort()
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
        loop?.cancel()
        loop = null
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
    }

    /** Switches to new connection settings and reconnects immediately. */
    fun reconfigure(newConfig: HomeAssistantConfig) {
        close()
        socket?.abort()
        config = newConfig
        start()
    }

    private class AuthException(message: String) : Exception(message)

    private suspend fun runConnection(onAuthenticated: () -> Unit) {
        val incoming = Channel<String>(Channel.UNLIMITED)
        val ws = http.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(wsUri, Listener(incoming))
            .await()
        socket = ws

        val first = json.parseToJsonElement(incoming.receive()).jsonObject
        if (first.type != "auth_required") throw HomeAssistantException("unexpected greeting: $first")
        send(ws, buildJsonObject { put("type", "auth"); put("access_token", config.token) })
        val auth = json.parseToJsonElement(incoming.receive()).jsonObject
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
                    ws.abort()
                }
            }
        }
        try {
            for (text in incoming) handleMessage(json.parseToJsonElement(text).jsonObject)
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

    private suspend fun send(ws: WebSocket, obj: JsonObject) {
        // java.net.http.WebSocket forbids overlapping sendText calls
        sendMutex.withLock { ws.sendText(obj.toString(), true).await() }
    }

    /** Sends a command (an `id` is added) and returns its `result`. */
    suspend fun request(command: JsonObject): JsonElement? = request(command, ids.getAndIncrement())

    /**
     * Subscribes to an HA event type for the current connection. [handler] runs on the socket
     * thread and must be quick. Call again after a reconnect (see [connections]).
     */
    suspend fun subscribeEvents(eventType: String, handler: (JsonObject) -> Unit) {
        val id = ids.getAndIncrement()
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
            send(ws, JsonObject(command + ("id" to kotlinx.serialization.json.JsonPrimitive(id))))
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

    private class Listener(private val incoming: Channel<String>) : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                incoming.trySend(buffer.toString())
                buffer.setLength(0)
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*>? {
            incoming.close()
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            incoming.close(error)
        }
    }
}
