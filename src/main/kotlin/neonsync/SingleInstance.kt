@file:Suppress("FunctionName")

package neonsync

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.interfaces.DBusInterface

@DBusInterfaceName("io.github.neonsync.App")
interface AppInterface : DBusInterface {
    fun ShowWindow()
}

/**
 * Makes sure only one instance runs. The well-known D-Bus name can only have one owner; a second
 * start asks the owner to focus its window (e.g. when launched from the application menu while
 * running in the tray) and exits.
 */
object SingleInstance {
    private const val BUS_NAME = "io.github.neonsync"
    private const val PATH = "/io/github/neonsync"

    /**
     * Returns true if this process is the first instance. Otherwise, if [focusRunning], the running
     * instance is asked to show and focus its window.
     */
    fun acquire(bus: DBusConnection, focusRunning: Boolean): Boolean = try {
        bus.requestBusName(BUS_NAME)
        true
    } catch (_: DBusException) {
        if (focusRunning) runCatching { bus.getRemoteObject(BUS_NAME, PATH, AppInterface::class.java).ShowWindow() }
        false
    }

    fun export(bus: DBusConnection, onShow: () -> Unit) {
        bus.exportObject(PATH, object : AppInterface {
            override fun ShowWindow() = onShow()
            override fun getObjectPath() = PATH
        })
    }
}
