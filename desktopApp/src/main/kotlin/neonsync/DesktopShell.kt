package neonsync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.freedesktop.dbus.connections.impl.DBusConnection
import kotlin.math.roundToInt
import kotlin.math.sign

/** The KDE desktop around the app: tray icon, window visibility and screen lock. */
class DesktopShell(private val app: NeonSyncApp, bus: DBusConnection) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tray = Tray(bus, ::menu, onActivate = { showWindow() }, onScroll = { delta -> app.stepBrightness(delta.sign) })
    private val lockMonitor = ScreenLockMonitor(bus) { locked -> app.setScreenLocked(locked) }

    private val _windowVisible = MutableStateFlow(false)
    val windowVisible: StateFlow<Boolean> = _windowVisible

    /** Incremented to bring the window to the front. */
    private val _focusRequests = MutableStateFlow(0)
    val focusRequests: StateFlow<Int> = _focusRequests

    fun start(showWindow: Boolean) {
        tray.start()
        lockMonitor.start()
        scope.launch { trayUpdater() }
        scope.launch {
            app.ui.map { Triple(it.enabled, it.setup.mode, it.brightness) }.distinctUntilChanged().collect { tray.menuChanged() }
        }
        if (showWindow) showWindow()
    }

    fun stop() {
        scope.cancel()
        tray.stop()
        lockMonitor.stop()
    }

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

    private suspend fun trayUpdater() {
        while (scope.isActive) {
            val ui = app.ui.value
            val tooltip = buildList {
                add(ui.statusText)
                add("Brightness: ${(ui.brightness * 100).roundToInt()} %")
                app.live.value.stats.forEach { (id, s) -> add("$id: %.1f upd/s, %.0f ms".format(s.updatesPerSecond, s.avgLatencyMs)) }
            }
            tray.update(TrayState(ui.running, "NeonSync", tooltip.joinToString("<br>")))
            delay(100)
        }
    }

    private fun menu(): List<MenuItem> {
        val ui = app.ui.value
        return listOf(
            MenuItem("Open NeonSync", iconName = "configure", onClick = { showWindow() }),
            MenuItem(separator = true),
            MenuItem("Enabled", toggle = false to ui.enabled, onClick = { app.toggle() }),
            MenuItem(
                "Mode",
                children = Mode.entries.map { m ->
                    MenuItem(m.label, toggle = true to (ui.setup.mode == m), onClick = { app.setMode(m) })
                },
            ),
            MenuItem(
                "Brightness",
                iconName = "brightness-high",
                children = listOf(1.0, 0.75, 0.5, 0.25, 0.1).map { level ->
                    MenuItem(
                        "${(level * 100).roundToInt()} %",
                        toggle = true to ((ui.brightness - level) in -0.001..0.001),
                        onClick = { app.setBrightness(level) },
                    )
                },
            ),
            MenuItem("Select screen…", iconName = "video-display", onClick = { app.selectScreen() }),
            MenuItem(separator = true),
            MenuItem("Quit", iconName = "application-exit", onClick = { quit() }),
        )
    }
}
