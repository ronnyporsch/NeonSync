package neonsync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import neonsync.NeonSyncApp
import neonsync.ColorMath
import neonsync.HaLight
import neonsync.LightColor
import neonsync.LightController
import neonsync.LightState
import neonsync.Setup
import neonsync.XY
import neonsync.toFixed

/** A group in the list: an HA area, or the lights without area (id = null). */
private data class AreaGroup(val id: String?, val name: String, val lights: List<HaLight>)

@Composable
fun LightsScreen(app: NeonSyncApp) {
    val ui by app.ui.collectAsState()
    val live by app.live.collectAsState()
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val dir = ui.directory
    val groups = remember(dir) {
        dir.areas.map { a -> AreaGroup(a.id, a.name, dir.lights.filter { it.areaId == a.id }) }.filter { it.lights.isNotEmpty() } +
            listOfNotNull(dir.lights.filter { it.areaId == null }.takeIf { it.isNotEmpty() }?.let { AreaGroup(null, "No area", it) })
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Home Assistant lights", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${ui.activeLights.size} of ${dir.lights.count { it.supportsColor }} colour lights react. " +
                        "Selecting an area includes lights added to it later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ui.directoryLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            TextButton(onClick = { groups.forEach { g -> collapsed[g.key] = false } }) { Text("Expand all") }
            TextButton(onClick = { groups.forEach { g -> collapsed[g.key] = true } }) { Text("Collapse all") }
            IconButton(onClick = { app.reloadLights() }, enabled = ui.haConnected) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Reload")
            }
        }
        Spacer(Modifier.padding(top = 8.dp))

        when {
            !ui.haConnected && dir.lights.isEmpty() -> Hint(ui.haError ?: "Connecting to Home Assistant… (see Settings)")
            dir.lights.isEmpty() && !ui.directoryLoading -> Hint("No lights found in Home Assistant.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups, key = { it.key }) { group ->
                    AreaCard(
                        group = group,
                        setup = ui.setup,
                        active = ui.activeLights,
                        states = live.lights,
                        stats = live.stats,
                        expanded = collapsed[group.key] != true,
                        onToggleExpanded = { collapsed[group.key] = collapsed[group.key] != true },
                        app = app,
                    )
                }
            }
        }
    }
}

private val AreaGroup.key get() = id ?: "__none__"

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AreaCard(
    group: AreaGroup,
    setup: Setup,
    active: Set<String>,
    states: Map<String, LightState>,
    stats: Map<String, LightController.Stats>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    app: NeonSyncApp,
) {
    val selectable = group.lights.filter { it.supportsColor }
    val areaSelected = group.id != null && group.id in setup.selectedAreas
    val selectedCount = selectable.count { it.entityId in active }
    val state = when {
        areaSelected -> ToggleableState.On
        selectedCount == 0 -> ToggleableState.Off
        selectedCount == selectable.size && group.id == null -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    val ids = selectable.map { it.entityId }.toSet()

    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, contentDescription = null)
                TriStateCheckbox(
                    state = state,
                    enabled = selectable.isNotEmpty(),
                    onClick = {
                        app.changeSetup { s ->
                            when {
                                // Turning an area off also drops its individually selected lights
                                state != ToggleableState.Off -> s.copy(
                                    selectedAreas = s.selectedAreas - setOfNotNull(group.id),
                                    selectedLights = s.selectedLights - ids,
                                )
                                group.id != null -> s.copy(selectedAreas = s.selectedAreas + group.id, selectedLights = s.selectedLights - ids)
                                else -> s.copy(selectedLights = s.selectedLights + ids)
                            }
                        }
                    },
                )
                Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    if (areaSelected) "whole area" else "$selectedCount / ${selectable.size} selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            if (expanded) {
                group.lights.forEach { light ->
                    LightRow(light, light.entityId in active, states[light.entityId] ?: light.state, stats[light.entityId]) { checked ->
                        app.changeSetup { s ->
                            if (checked) {
                                s.copy(selectedLights = s.selectedLights + light.entityId)
                            } else if (areaSelected) {
                                // Unchecking one light of a selected area: keep the rest as individual selections
                                s.copy(
                                    selectedAreas = s.selectedAreas - group.id!!,
                                    selectedLights = s.selectedLights + ids - light.entityId,
                                )
                            } else {
                                s.copy(selectedLights = s.selectedLights - light.entityId)
                            }
                        }
                    }
                }
                Spacer(Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

@Composable
/** [stats] is set while NeonSync drives the light. */
private fun LightRow(light: HaLight, checked: Boolean, state: LightState, stats: LightController.Stats?, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 40.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = light.supportsColor)
        ColorDot(state.color)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(light.name, style = MaterialTheme.typography.bodyLarge)
            Text(light.entityId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val note = when {
            !light.supportsColor -> "no colour support"
            else -> state.state
        }
        Column(Modifier.width(120.dp)) {
            Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (stats != null) {
                Text(
                    "${stats.updatesPerSecond.toFixed(1)} upd/s, ${stats.avgLatencyMs.toFixed(0)} ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ColorDot(color: LightColor?, size: Int = 14) {
    val c = color.toComposeColor()
    Box(
        Modifier.size(size.dp).background(c ?: MaterialTheme.colorScheme.surfaceVariant, CircleShape),
    )
}

/** Display colour of a light (full saturation, alpha by brightness); null when unknown or off. */
fun LightColor?.toComposeColor(): Color? {
    if (this == null || brightness <= 0) return null
    val (r, g, b) = ColorMath.xyToSrgb8(XY(x, y))
    return Color(r, g, b).copy(alpha = (0.35 + 0.65 * brightness).toFloat().coerceIn(0f, 1f))
}
