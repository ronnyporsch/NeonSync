package neonsync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Everything the UI shows except fast-changing values (see [LiveState]). */
data class UiState(
    val statusText: String = "Disabled",
    val enabled: Boolean = false,
    val haConfig: HomeAssistantConfig = HomeAssistantConfig(),
    val haConnected: Boolean = false,
    val haError: String? = null,
    val directory: LightDirectory = LightDirectory.EMPTY,
    val directoryLoading: Boolean = false,
    val setup: Setup = Setup(),
    /** Lights that react: selected individually or through an area. */
    val activeLights: Set<String> = emptySet(),
    val brightness: Double = 1.0,
)

/** Values that change many times per second, polled by the UI. */
data class LiveState(
    /** Actual state of every light according to Home Assistant, kept current via state_changed events. */
    val lights: Map<String, LightState> = emptyMap(),
    val stats: Map<String, LightController.Stats> = emptyMap(),
    val audio: AudioLevels = AudioLevels.SILENT,
)

/**
 * Wires everything together. All state changes run sequentially on one thread fed by [commands], so
 * UI actions, tray clicks, screen lock, stream errors and shutdown can't race each other. After
 * every change [reconcile] brings capture, audio and the light loops in line with the desired state.
 */
class NeonSyncApp(private var config: Config, private val bus: DBusConnection) {
    private val log = LoggerFactory.getLogger(NeonSyncApp::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val control = Executors.newSingleThreadExecutor { r -> Thread(r, "control").apply { isDaemon = true } }
    private val ha = HomeAssistantClient(config.homeAssistant, scope)
    private val portal = ScreenCastPortal(bus)
    private val analyzer = ZoneAnalyzer(config.color.saturatedPixelWeight)
    private var persistent = StateStore.load()
    private val audio = AudioReactor(config.audio)

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val capture = ScreenCapture(config.capture, ::onFrame) { error -> commands.trySend(Command.CaptureStopped(error)) }
    private val lockMonitor = ScreenLockMonitor(bus) { locked -> commands.trySend(Command.Lock(locked)) }
    private val tray = Tray(bus, ::menu, onActivate = { showWindow() }, onScroll = { delta ->
        commands.trySend(Command.Brightness((setupBrightness + 0.05 * delta.sign).coerceIn(0.05, 1.0)))
    })

    private val _ui = MutableStateFlow(UiState(haConfig = config.homeAssistant, brightness = config.setup.brightness))
    val ui: StateFlow<UiState> = _ui
    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live

    private val _windowVisible = MutableStateFlow(false)
    val windowVisible: StateFlow<Boolean> = _windowVisible

    /** Incremented to bring the window to the front. */
    private val _focusRequests = MutableStateFlow(0)
    val focusRequests: StateFlow<Int> = _focusRequests

    private sealed interface Command {
        data class SetEnabled(val enabled: Boolean, val reselect: Boolean = false) : Command
        data object Toggle : Command
        data class Lock(val locked: Boolean) : Command
        data class CaptureStopped(val error: String?) : Command
        data class Brightness(val value: Double) : Command
        data class ChangeSetup(val change: (Setup) -> Setup) : Command
        data class Move(val entityId: String, val position: Vec3) : Command
        data class SaveHomeAssistant(val config: HomeAssistantConfig) : Command
        data object ReloadLights : Command

        /** Re-evaluate the desired state, e.g. after the HA connection came up or dropped. */
        data object Refresh : Command
        data class DirectoryLoaded(val directory: LightDirectory) : Command

        /** Write the setup to config.json. */
        data object Persist : Command
        data class Shutdown(val done: CompletableDeferred<Unit>) : Command
    }

    // ---- State owned by the control thread ----------------------------------------------------

    private var setup: Setup = config.setup
        set(value) {
            field = value
            palette = AudioPalette.of(value)
            setupBrightness = value.brightness
            lightBands = value.lightBands
        }
    private var directory = LightDirectory.EMPTY

    /** Read by the light loops on every tick, so colour and brightness changes apply immediately. */
    @Volatile
    private var palette = AudioPalette.of(config.setup)

    @Volatile
    private var setupBrightness = config.setup.brightness

    /** Per-light band selection, read live by the light loops. */
    @Volatile
    private var lightBands = config.setup.lightBands

    /** Light states from HA (initial list + state_changed events). Written by the socket thread. */
    private val lightStates = java.util.concurrent.ConcurrentHashMap<String, LightState>()

    /** Connection number the state_changed subscription belongs to. */
    @Volatile
    private var subscribedConnection = -1
    /** Written by the control thread, read by [toggle]. */
    @Volatile
    private var enabled = false
    private var locked = false
    private var captureRunning = false
    private var starting = false
    private var error: String? = null

    /** Lights are paused while the screen is locked, unless configured to keep running. */
    private val paused get() = locked && config.onScreenLock != StopAction.KEEP

    @Volatile
    private var controllers: List<LightController> = emptyList()
    private var lightJobs: List<Job> = emptyList()

    /** State of each light before we first touched it, for [StopAction.RESTORE]. */
    private val savedStates = HashMap<String, JsonObject>()
    private var saveJob: Job? = null

    // ---- Public API (thread-safe, used by UI) -------------------------------------------------

    fun setEnabled(enabled: Boolean) {
        // A screen-share dialog may be open and block the control thread; disabling closes it
        if (!enabled) portal.cancelPending()
        commands.trySend(Command.SetEnabled(enabled))
    }
    fun toggle() {
        if (enabled) portal.cancelPending()
        commands.trySend(Command.Toggle)
    }

    fun selectScreen() = commands.trySend(Command.SetEnabled(true, reselect = true))
    fun setMode(mode: Mode) = changeSetup { it.copy(mode = mode) }

    /** Bands a light reacts to in the audio modes; all bands is the default and isn't stored. */
    fun setBands(entityId: String, bands: Set<Band>) = changeSetup {
        it.copy(lightBands = if (bands.isEmpty() || bands.containsAll(Band.ALL)) it.lightBands - entityId else it.lightBands + (entityId to bands))
    }
    fun setBrightness(value: Double) = commands.trySend(Command.Brightness(value.coerceIn(0.05, 1.0)))
    fun changeSetup(change: (Setup) -> Setup) = commands.trySend(Command.ChangeSetup(change))
    fun move(entityId: String, position: Vec3) = commands.trySend(Command.Move(entityId, position.clampToRoom()))
    fun saveHomeAssistant(config: HomeAssistantConfig) = commands.trySend(Command.SaveHomeAssistant(config))
    fun reloadLights() = commands.trySend(Command.ReloadLights)

    fun showWindow() {
        _windowVisible.value = true
        _focusRequests.update { it + 1 }
    }

    fun hideWindow() {
        _windowVisible.value = false
    }

    /** Exits the process; the shutdown hook restores the lights. */
    fun quit() {
        Thread { kotlin.system.exitProcess(0) }.start()
    }

    /** Starts the background part. Returns immediately; the UI runs on the caller's thread. */
    fun start(showWindow: Boolean) {
        Runtime.getRuntime().addShutdownHook(Thread {
            portal.cancelPending()
            val done = CompletableDeferred<Unit>()
            if (commands.trySend(Command.Shutdown(done)).isSuccess) {
                runBlocking { withTimeoutOrNull(8.seconds) { done.await() } }
            }
        })

        ha.start()
        tray.start()
        lockMonitor.start()
        scope.launch { liveUpdater() }
        scope.launch { ha.connections.drop(1).collect { commands.send(Command.ReloadLights) } }
        if (showWindow || config.homeAssistant.token.isBlank() || (setup.selectedLights.isEmpty() && setup.selectedAreas.isEmpty())) {
            showWindow()
        }
        if (config.autoStart) commands.trySend(Command.SetEnabled(true))

        control.execute {
            runBlocking {
                publish()
                for (cmd in commands) {
                    try {
                        if (!handle(cmd)) break
                    } catch (e: Exception) {
                        log.error("Failed to handle {}", cmd, e)
                    }
                    publish()
                }
            }
        }
    }

    /** Returns false when the app should exit. */
    private suspend fun handle(cmd: Command): Boolean {
        when (cmd) {
            Command.Toggle -> handle(Command.SetEnabled(!enabled))
            is Command.SetEnabled -> {
                if (!cmd.enabled) {
                    if (enabled) {
                        enabled = false
                        reconcile()
                    }
                    error = null
                } else {
                    error = null
                    enabled = true
                    if (cmd.reselect && captureRunning) stopCapture()
                    reconcile(reselect = cmd.reselect)
                }
            }
            is Command.Lock -> {
                locked = cmd.locked
                if (enabled && config.onScreenLock != StopAction.KEEP) {
                    log.info(if (cmd.locked) "Screen locked, pausing" else "Screen unlocked, resuming")
                    reconcile(pauseAction = config.onScreenLock)
                }
            }
            is Command.CaptureStopped -> if (captureRunning) {
                log.info("Capture stopped{}", cmd.error?.let { ": $it" } ?: "")
                captureRunning = false
                enabled = false
                reconcile()
                error = cmd.error ?: "Screen sharing was stopped"
            }
            is Command.Brightness -> {
                setup = setup.copy(brightness = cmd.value)
                persistSoon()
                controllers.forEach { it.invalidate() }
            }
            is Command.ChangeSetup -> {
                val old = setup
                setup = withPlacements(cmd.change(setup))
                persist()
                // Colours are picked up live by the light loops; only the mode and the set of lights need a restart
                if (old.mode != setup.mode || directory.active(old) != directory.active(setup)) {
                    reconcile(restartLights = true)
                }
            }
            is Command.Move -> {
                setup = setup.copy(placements = setup.placements + (cmd.entityId to cmd.position))
                controllers.find { it.entityId == cmd.entityId }?.position = cmd.position
                persistSoon()
            }
            Command.Persist -> persist()
            is Command.SaveHomeAssistant -> {
                config = config.copy(homeAssistant = cmd.config)
                ConfigStore.update { it.copy(homeAssistant = cmd.config) }
                directory = LightDirectory.EMPTY
                lightStates.clear()
                ha.reconfigure(cmd.config)
            }
            Command.Refresh -> reconcile()
            Command.ReloadLights -> {
                _ui.update { it.copy(directoryLoading = true) }
                scope.launch {
                    val dir = try {
                        ha.awaitConnected(5)
                        subscribeStates()
                        LightDirectory.load(ha)
                    } catch (e: Exception) {
                        log.warn("Could not load lights: {}", e.message)
                        null
                    }
                    commands.send(Command.DirectoryLoaded(dir ?: directory))
                }
            }
            is Command.DirectoryLoaded -> {
                val before = directory.active(setup)
                directory = cmd.directory
                // putIfAbsent: an event may already have delivered a newer state
                directory.lights.forEach { lightStates.putIfAbsent(it.entityId, it.state) }
                val placed = withPlacements(setup)
                if (placed != setup) {
                    setup = placed
                    persist()
                }
                if (directory.active(setup) != before) reconcile(restartLights = true)
                _ui.update { it.copy(directoryLoading = false) }
            }
            is Command.Shutdown -> {
                log.info("Shutting down")
                enabled = false
                reconcile()
                saveJob?.cancel()
                persist()
                tray.stop()
                lockMonitor.stop()
                portal.dispose()
                ha.close()
                cmd.done.complete(Unit)
                commands.close()
                scope.launch { delay(200); bus.disconnect() }
                return false
            }
        }
        return true
    }

    /** Lights that should react; before the light list is loaded only individually selected ones are known. */
    private fun activeIds(): List<String> = directory.active(setup).sorted()

    /**
     * Brings capture, audio and light loops in line with the desired state.
     * @param pauseAction what to do with lights that stop being driven because of a screen lock
     */
    private suspend fun reconcile(restartLights: Boolean = false, reselect: Boolean = false, pauseAction: StopAction = config.onStop) {
        // Only ask for the screen (KDE shows a dialog the first time) once there is something to drive
        val wantCapture = enabled && setup.mode.usesScreen && ha.connected.value && activeIds().isNotEmpty()
        if (wantCapture && !captureRunning) {
            starting = true
            publish()
            captureRunning = startCapture(reselect)
            starting = false
            if (!captureRunning) enabled = false
        } else if (!wantCapture && captureRunning) {
            stopCapture()
        }

        val wanted = if (enabled && !paused && ha.connected.value) activeIds() else emptyList()
        val running = controllers.map { it.entityId }
        if (restartLights || wanted != running) {
            stopLights()
            val released = running - wanted.toSet()
            if (released.isNotEmpty()) {
                applyAction(if (enabled && paused) pauseAction else config.onStop, released)
            }
            if (wanted.isNotEmpty()) startLights(wanted)
        }
        if (!enabled) savedStates.clear()

        if (lightJobs.isNotEmpty() && setup.mode.usesAudio) {
            try {
                audio.start()
            } catch (e: Exception) {
                log.error("Could not start audio capture: {}", e.message)
            }
        } else {
            audio.stop()
        }
        tray.menuChanged()
    }

    private suspend fun startCapture(reselect: Boolean): Boolean = try {
        val token = if (reselect) null else persistent.restoreToken
        val (stream, newToken) = portal.open(token) { commands.trySend(Command.CaptureStopped(null)) }
        persistent = persistent.copy(restoreToken = newToken)
        StateStore.save(persistent)
        capture.start(stream)
        true
    } catch (e: Exception) {
        log.error("Could not start screen capture: {}", e.message)
        error = e.message
        portal.close()
        false
    }

    private fun stopCapture() {
        capture.stop()
        portal.close()
        captureRunning = false
    }

    private suspend fun startLights(ids: List<String>) {
        val unsaved = ids.filter { it !in savedStates }
        if (unsaved.isNotEmpty()) savedStates += snapshotLights(unsaved.toSet())
        controllers = ids.map { id ->
            LightController(
                id, setup.placements[id] ?: Placement.defaultPosition(0), setup.mode, config, ha,
                brightness = { setupBrightness },
                audio = { audio.levels() },
                palette = { palette },
                bands = { lightBands[id]?.takeIf { it.isNotEmpty() } ?: Band.ALL },
            )
        }
        lightJobs = controllers.map { it.run(scope) }
    }

    private suspend fun stopLights() {
        lightJobs.forEach { it.cancelAndJoin() }
        lightJobs = emptyList()
        controllers = emptyList()
    }

    private fun onFrame(frame: Frame) {
        for (c in controllers) c.onSample(analyzer.analyze(frame, c.zone))
    }

    /** Gives every active light without a position a default spot. */
    private fun withPlacements(s: Setup): Setup {
        val missing = directory.active(s).filter { it !in s.placements }.sorted()
        if (missing.isEmpty()) return s
        val placements = s.placements.toMutableMap()
        // Lights with a left/right hint first, so they get their edge before others take it
        for (id in missing.sortedByDescending { m -> nameOf(m).lowercase().let { n -> "left" in n || "right" in n } }) {
            placements[id] = Placement.defaultPosition(nameOf(id), placements.values)
        }
        return s.copy(placements = placements)
    }

    private fun nameOf(id: String) = directory.byId[id]?.name ?: id

    private fun persist() {
        saveJob?.cancel()
        try {
            ConfigStore.update { it.copy(setup = setup) }
        } catch (e: Exception) {
            log.warn("Could not save config: {}", e.message)
        }
    }

    /** Dragging and sliders produce many changes; write the file once they settle. */
    private fun persistSoon() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500.milliseconds)
            commands.send(Command.Persist)
        }
    }

    /** Keeps [lightStates] current. Subscribes once per HA connection. */
    private suspend fun subscribeStates() {
        val connection = ha.connections.value
        if (connection == subscribedConnection) return
        subscribedConnection = connection
        try {
            ha.subscribeEvents("state_changed") { event ->
                val data = event["data"] as? JsonObject ?: return@subscribeEvents
                val id = data["entity_id"]?.jsonPrimitive?.content ?: return@subscribeEvents
                if (!id.startsWith("light.")) return@subscribeEvents
                val newState = data["new_state"] as? JsonObject
                if (newState == null) lightStates.remove(id) else lightStates[id] = LightState.of(newState)
            }
        } catch (e: Exception) {
            subscribedConnection = -1
            log.warn("Could not subscribe to light state changes: {}", e.message)
        }
    }

    // ---- Light state snapshot / restore -------------------------------------------------------

    private suspend fun snapshotLights(ids: Set<String>): Map<String, JsonObject> = try {
        ha.getStates().filter { it["entity_id"]?.jsonPrimitive?.content in ids }
            .associateBy { it["entity_id"]!!.jsonPrimitive.content }
    } catch (e: Exception) {
        log.warn("Could not read light states: {}", e.message)
        emptyMap()
    }

    private suspend fun applyAction(action: StopAction, ids: Collection<String>) {
        if (action == StopAction.KEEP) return
        val jobs = ids.map { id ->
            scope.launch {
                try {
                    val saved = savedStates[id]
                    if (action == StopAction.OFF || saved == null) {
                        ha.callService("light", "turn_off", id, buildJsonObject { put("transition", 0.5) })
                    } else {
                        restore(id, saved)
                    }
                } catch (e: Exception) {
                    log.warn("Could not reset {}: {}", id, e.message)
                }
            }
        }
        withTimeoutOrNull(5.seconds) { jobs.joinAll() }
    }

    private suspend fun restore(entityId: String, saved: JsonObject) {
        val attrs = saved["attributes"]?.jsonObject ?: JsonObject(emptyMap())
        when (saved["state"]?.jsonPrimitive?.content) {
            "off" -> ha.callService("light", "turn_off", entityId, buildJsonObject { put("transition", 0.5) })
            "on" -> ha.callService(
                "light", "turn_on", entityId,
                buildJsonObject {
                    put("transition", 0.5)
                    attrs["brightness"]?.takeIf { it !is JsonNull }?.let { put("brightness", it) }
                    when (attrs["color_mode"]?.jsonPrimitive?.content) {
                        "color_temp" -> attrs["color_temp_kelvin"]?.takeIf { it !is JsonNull }?.let { put("color_temp_kelvin", it) }
                        "xy", "hs", "rgb", "rgbw", "rgbww" -> (attrs["xy_color"] as? JsonArray)?.let { put("xy_color", it) }
                    }
                },
            )
            else -> log.debug("{} was unavailable before start, not restoring", entityId)
        }
    }

    // ---- Publishing state ---------------------------------------------------------------------

    private fun statusText(): String = when {
        starting -> "Starting…"
        error != null -> "Error: $error"
        !enabled -> "Disabled"
        !ha.connected.value -> "Waiting for Home Assistant…"
        paused -> "Paused (screen locked)"
        activeIds().isEmpty() -> "No lights selected"
        else -> "Running · ${setup.mode.label}"
    }

    private fun publish() {
        _ui.update {
            it.copy(
                statusText = statusText(),
                enabled = enabled,
                haConfig = config.homeAssistant,
                haConnected = ha.connected.value,
                haError = ha.lastError.value,
                directory = directory,
                setup = setup,
                activeLights = directory.active(setup),
                brightness = setupBrightness,
            )
        }
    }

    private suspend fun liveUpdater() {
        var wasConnected = false
        while (scope.isActive) {
            val cs = controllers
            val states = HashMap(lightStates)
            _live.value = LiveState(states, cs.associate { it.entityId to it.stats }, audio.levels())

            val connected = ha.connected.value
            if (connected != wasConnected) {
                wasConnected = connected
                // Lights only run while connected
                commands.send(Command.Refresh)
            }
            _ui.update { it.copy(haConnected = connected, haError = ha.lastError.value, brightness = setupBrightness) }

            val running = lightJobs.isNotEmpty()
            val tooltip = buildList {
                add(_ui.value.statusText)
                add("Brightness: ${(setupBrightness * 100).roundToInt()} %")
                cs.forEach { add("${it.entityId}: %.1f upd/s, %.0f ms".format(it.stats.updatesPerSecond, it.stats.avgLatencyMs)) }
            }
            tray.update(TrayState(running, "NeonSync", tooltip.joinToString("<br>")))
            delay(100)
        }
    }

    private fun menu(): List<MenuItem> = listOf(
        MenuItem("Open NeonSync", iconName = "configure", onClick = { showWindow() }),
        MenuItem(separator = true),
        MenuItem("Enabled", toggle = false to enabled, onClick = { toggle() }),
        MenuItem(
            "Mode",
            children = Mode.entries.map { m ->
                MenuItem(m.label, toggle = true to (setup.mode == m), onClick = { setMode(m) })
            },
        ),
        MenuItem(
            "Brightness",
            iconName = "brightness-high",
            children = listOf(1.0, 0.75, 0.5, 0.25, 0.1).map { level ->
                MenuItem(
                    "${(level * 100).roundToInt()} %",
                    toggle = true to ((setupBrightness - level) in -0.001..0.001),
                    onClick = { setBrightness(level) },
                )
            },
        ),
        MenuItem("Select screen…", iconName = "video-display", onClick = { selectScreen() }),
        MenuItem(separator = true),
        MenuItem("Quit", iconName = "application-exit", onClick = { quit() }),
    )
}
