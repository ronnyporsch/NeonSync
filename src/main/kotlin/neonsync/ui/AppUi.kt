package neonsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import neonsync.Logo
import neonsync.NeonSyncApp
import neonsync.Mode
import kotlin.math.roundToInt

val AppColors = darkColorScheme(
    primary = Color(0xFFFFB36B),
    onPrimary = Color(0xFF3A1F00),
    secondary = Color(0xFF8FB8FF),
    background = Color(0xFF15161A),
    surface = Color(0xFF1C1D22),
    surfaceVariant = Color(0xFF2A2C33),
)

/** Runs the Compose UI on the calling thread. The window hides on close; the app keeps running in the tray. */
fun runUi(app: NeonSyncApp) = application(exitProcessOnExit = false) {
    val visible by app.windowVisible.collectAsState()
    val focusRequests by app.focusRequests.collectAsState()
    val windowState = rememberWindowState(size = DpSize(1180.dp, 760.dp))
    val icon = remember { BitmapPainter(Logo.render(256).asComposeImageBitmap()) }
    Window(onCloseRequest = app::hideWindow, visible = visible, title = "NeonSync", icon = icon, state = windowState) {
        LaunchedEffect(focusRequests, visible) {
            if (visible) bringToFront(window)
        }
        MaterialTheme(colorScheme = AppColors) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                MainScreen(app)
            }
        }
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

enum class Tab(val label: String) { LIGHTS("Lights"), ROOM("Room"), SETTINGS("Settings") }

@Composable
fun MainScreen(app: NeonSyncApp, initialTab: Tab? = null) {
    val ui by app.ui.collectAsState()
    var tab by rememberSaveable { mutableStateOf(initialTab ?: if (ui.haConfig.token.isBlank()) Tab.SETTINGS else Tab.LIGHTS) }

    Row(Modifier.fillMaxSize()) {
        NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
            Spacer(Modifier.padding(top = 8.dp))
            Tab.entries.forEach { t ->
                NavigationRailItem(
                    selected = tab == t,
                    onClick = { tab = t },
                    icon = {
                        Icon(
                            when (t) {
                                Tab.LIGHTS -> Icons.Outlined.Lightbulb
                                Tab.ROOM -> Icons.Outlined.ViewInAr
                                Tab.SETTINGS -> Icons.Outlined.Settings
                            },
                            contentDescription = t.label,
                        )
                    },
                    label = { Text(t.label) },
                )
            }
        }
        Column(Modifier.fillMaxSize()) {
            TopBar(app)
            HorizontalDivider()
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    Tab.LIGHTS -> LightsScreen(app)
                    Tab.ROOM -> RoomScreen(app)
                    Tab.SETTINGS -> SettingsScreen(app)
                }
            }
        }
    }
}

@Composable
private fun TopBar(app: NeonSyncApp) {
    val ui by app.ui.collectAsState()
    // Local slider value so dragging is smooth; committed to the app continuously
    var brightness by remember(ui.brightness) { mutableStateOf(ui.brightness.toFloat()) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Switch(checked = ui.enabled, onCheckedChange = { app.setEnabled(it) })
        Column(Modifier.width(260.dp)) {
            Text("NeonSync", style = MaterialTheme.typography.titleMedium)
            Text(
                ui.statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (ui.statusText.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mode.entries.forEach { m ->
                FilterChip(selected = ui.setup.mode == m, onClick = { app.setMode(m) }, label = { Text(m.label) })
            }
        }
        Spacer(Modifier.weight(1f))
        Icon(Icons.Outlined.WbSunny, contentDescription = "Brightness", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = brightness,
            onValueChange = {
                brightness = it
                app.setBrightness(it.toDouble())
            },
            valueRange = 0.05f..1f,
            modifier = Modifier.width(180.dp).fillMaxHeight(0f),
        )
        Text("${(brightness * 100).roundToInt()} %", Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall)
    }
}
