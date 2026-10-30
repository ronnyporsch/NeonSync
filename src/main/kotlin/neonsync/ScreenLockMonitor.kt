package neonsync

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.slf4j.LoggerFactory

@DBusInterfaceName("org.freedesktop.ScreenSaver")
interface ScreenSaverInterface : DBusInterface {
    class ActiveChanged(path: String, val active: Boolean) : DBusSignal(path, active)
}

/** Reports screen lock / unlock (KDE emits `org.freedesktop.ScreenSaver.ActiveChanged`). */
class ScreenLockMonitor(private val bus: DBusConnection, private val onChange: (locked: Boolean) -> Unit) {
    private val log = LoggerFactory.getLogger(ScreenLockMonitor::class.java)
    private var handler: AutoCloseable? = null

    @Volatile
    private var locked = false

    fun start() {
        try {
            handler = bus.addSigHandler(ScreenSaverInterface.ActiveChanged::class.java) { sig ->
                // KDE sends the signal on two object paths; only forward real changes
                if (sig.active != locked) {
                    locked = sig.active
                    onChange(sig.active)
                }
            }
        } catch (e: Exception) {
            log.warn("Screen lock detection unavailable: {}", e.message)
        }
    }

    fun stop() {
        handler?.close()
    }
}
