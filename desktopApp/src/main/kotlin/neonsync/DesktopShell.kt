package neonsync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.freedesktop.dbus.connections.impl.DBusConnection

/** The KDE desktop around the app: window focus and screen lock. */
class DesktopShell(private val app: NeonSyncApp, bus: DBusConnection) {
    private val lockMonitor = ScreenLockMonitor(bus) { locked -> app.setScreenLocked(locked) }

    /** Incremented to bring the window to the front. */
    private val _focusRequests = MutableStateFlow(0)
    val focusRequests: StateFlow<Int> = _focusRequests

    fun start() {
        lockMonitor.start()
    }

    fun stop() {
        lockMonitor.stop()
    }

    fun showWindow() {
        _focusRequests.update { it + 1 }
    }

    /** Exits the process; the shutdown hook restores the lights. */
    fun quit() {
        Thread { kotlin.system.exitProcess(0) }.start()
    }
}
