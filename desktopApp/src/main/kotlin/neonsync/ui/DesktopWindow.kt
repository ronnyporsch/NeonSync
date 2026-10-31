package neonsync.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import neonsync.DesktopShell
import neonsync.Logo
import neonsync.NeonSyncApp

/** Runs the Compose UI on the calling thread. The window hides on close; the app keeps running in the tray. */
fun runUi(app: NeonSyncApp, shell: DesktopShell) = application(exitProcessOnExit = false) {
    val visible by shell.windowVisible.collectAsState()
    val focusRequests by shell.focusRequests.collectAsState()
    val windowState = rememberWindowState(size = DpSize(1180.dp, 760.dp))
    val icon = remember { BitmapPainter(Logo.render(256).asComposeImageBitmap()) }
    Window(onCloseRequest = shell::hideWindow, visible = visible, title = "NeonSync", icon = icon, state = windowState) {
        LaunchedEffect(focusRequests, visible) {
            if (visible) bringToFront(window)
        }
        App(app)
    }
}

/**
 * Raises and focuses the window, e.g. when a second start of the app asks for it. A plain toFront()
 * is often ignored by the window manager (focus stealing prevention), briefly going always-on-top
 * reliably raises it.
 */
private fun bringToFront(window: java.awt.Frame) {
    if (window.extendedState and java.awt.Frame.ICONIFIED != 0) {
        window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
    }
    window.isAlwaysOnTop = true
    window.toFront()
    window.requestFocus()
    window.isAlwaysOnTop = false
}
