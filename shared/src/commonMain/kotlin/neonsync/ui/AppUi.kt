package neonsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
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

/**
 * The whole UI, for embedding in a platform window, activity or web page. [modifier] applies to
 * the content inside the background, e.g. to keep it clear of system bars.
 */
@Composable
fun App(app: NeonSyncApp, modifier: Modifier = Modifier) {
    MaterialTheme(colorScheme = AppColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier) { MainScreen(app) }
        }
    }
}

/** Below this width (phones) the navigation moves to the bottom and panels stack. */
val COMPACT_WIDTH = 720.dp

enum class Tab(val label: String, val icon: ImageVector) {
    LIGHTS("Lights", Icons.Outlined.Lightbulb),
    ROOM("Room", Icons.Outlined.ViewInAr),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

@Composable
fun MainScreen(app: NeonSyncApp, initialTab: Tab? = null) {
    val ui by app.ui.collectAsState()
    var tab by rememberSaveable { mutableStateOf(initialTab ?: if (ui.haConfig.token.isBlank()) Tab.SETTINGS else Tab.LIGHTS) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < COMPACT_WIDTH
        val content = @Composable { modifier: Modifier ->
            Column(modifier) {
                TopBar(app, compact)
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
        if (compact) {
            Column(Modifier.fillMaxSize()) {
                content(Modifier.weight(1f).fillMaxWidth())
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    Spacer(Modifier.padding(top = 8.dp))
                    Tab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
                content(Modifier.fillMaxSize())
            }
        }
    }
}

/** Status, mode and brightness. In [compact] layout the parts wrap onto several lines. */
@Composable
private fun TopBar(app: NeonSyncApp, compact: Boolean) {
    val ui by app.ui.collectAsState()
    // Local slider value so dragging is smooth; committed to the app continuously
    var brightness by remember(ui.brightness) { mutableStateOf(ui.brightness.toFloat()) }

    val status = @Composable { modifier: Modifier ->
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Switch(checked = ui.enabled, onCheckedChange = { app.setEnabled(it) })
            Column {
                Text("NeonSync", style = MaterialTheme.typography.titleMedium)
                Text(
                    ui.statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.statusText.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
    val modes = @Composable {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mode.entries.forEach { m ->
                FilterChip(
                    selected = ui.setup.mode == m,
                    onClick = { app.setMode(m) },
                    label = { Text(m.label) },
                    enabled = app.platform.supports(m),
                )
            }
        }
    }
    val brightnessSlider = @Composable { modifier: Modifier ->
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.WbSunny, contentDescription = "Brightness", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = brightness,
                onValueChange = {
                    brightness = it
                    app.setBrightness(it.toDouble())
                },
                valueRange = 0.05f..1f,
                modifier = (if (compact) Modifier.weight(1f) else Modifier.width(180.dp)).fillMaxHeight(0f),
            )
            Text("${(brightness * 100).roundToInt()} %", Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall)
        }
    }

    if (compact) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            status(Modifier.fillMaxWidth())
            modes()
            brightnessSlider(Modifier.fillMaxWidth())
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            status(Modifier.width(330.dp))
            Box(Modifier.weight(1f)) { modes() }
            brightnessSlider(Modifier)
        }
    }
}
