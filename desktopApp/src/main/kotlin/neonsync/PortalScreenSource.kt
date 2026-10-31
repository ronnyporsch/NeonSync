package neonsync

import org.freedesktop.dbus.connections.impl.DBusConnection

/**
 * Screen capture through the xdg-desktop-portal ScreenCast API and PipeWire. The portal's restore
 * token is kept in state.json, so KDE re-uses the chosen screen without asking again.
 */
class PortalScreenSource(bus: DBusConnection, private val config: CaptureConfig) : ScreenSource {
    private val portal = ScreenCastPortal(bus)
    private var capture: ScreenCapture? = null
    private var persistent = StateStore.load()

    override val selectionHint = "KDE asks which screen to share the first time and remembers the choice."

    override suspend fun start(reselect: Boolean, onFrame: (Frame) -> Unit, onStopped: (error: String?) -> Unit) {
        val token = if (reselect) null else persistent.restoreToken
        val (stream, newToken) = portal.open(token) { onStopped(null) }
        persistent = persistent.copy(restoreToken = newToken)
        StateStore.save(persistent)
        capture = ScreenCapture(config, onFrame, onStopped).also { it.start(stream) }
    }

    override fun stop() {
        capture?.stop()
        capture = null
        portal.close()
    }

    override fun cancelPending() = portal.cancelPending()

    override fun dispose() = portal.dispose()
}
