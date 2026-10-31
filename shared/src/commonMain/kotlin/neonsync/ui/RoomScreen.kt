package neonsync.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import neonsync.Band
import neonsync.NeonSyncApp
import neonsync.HaLight
import neonsync.LightState
import neonsync.Placement
import neonsync.Vec3
import neonsync.Zone
import neonsync.toFixed
import kotlin.math.hypot

/** Shown for the zone preview while a light is off or its state is unknown. */
private val NEUTRAL = Color(0xFF8A8F99)

@Composable
fun RoomScreen(app: NeonSyncApp) {
    val ui by app.ui.collectAsState()
    val live by app.live.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    val lights = ui.activeLights.sorted().map { id -> id to (ui.setup.placements[id] ?: Placement.defaultPosition(0)) }
    val names = ui.directory.byId

    val room = @Composable { modifier: Modifier ->
        Box(modifier.padding(12.dp)) {
            Room3D(
                lights = lights,
                names = names,
                states = live.lights,
                selected = selected,
                onSelect = { selected = it },
                onMove = { id, p -> app.move(id, p) },
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                "Drag a light to move it on the floor plane · Shift+drag: height · drag empty space or right-drag: rotate · scroll: zoom",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
            if (lights.isEmpty()) {
                Text(
                    "Select lights in the Lights tab to place them here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
    val panel = @Composable { modifier: Modifier ->
        SidePanel(
            lights = lights,
            names = names,
            states = live.lights,
            selected = selected,
            onSelect = { selected = it },
            onMove = { id, p -> app.move(id, p) },
            bandsOf = ui.setup::bandsOf,
            onBands = app::setBands,
            audioMode = ui.setup.mode.usesAudio,
            modifier = modifier,
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth < COMPACT_WIDTH) {
            val panelHeight = maxHeight * 0.45f
            Column(Modifier.fillMaxSize()) {
                room(Modifier.weight(1f).fillMaxWidth())
                panel(Modifier.fillMaxWidth().heightIn(max = panelHeight))
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                room(Modifier.weight(1f).fillMaxHeight())
                panel(Modifier.width(300.dp).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun SidePanel(
    lights: List<Pair<String, Vec3>>,
    names: Map<String, HaLight>,
    states: Map<String, LightState>,
    selected: String?,
    onSelect: (String) -> Unit,
    onMove: (String, Vec3) -> Unit,
    bandsOf: (String) -> Set<Band>,
    onBands: (String, Set<Band>) -> Unit,
    audioMode: Boolean,
    modifier: Modifier,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Placed lights", style = MaterialTheme.typography.titleMedium)
            Column {
                lights.forEach { (id, _) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (id == selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable { onSelect(id) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ColorDot(states[id]?.color)
                        Text(names[id]?.name ?: id, Modifier.padding(start = 10.dp), maxLines = 1)
                    }
                }
            }
            val sel = lights.find { it.first == selected }
            if (sel != null) {
                val (id, p) = sel
                Spacer(Modifier.padding(top = 4.dp))
                Text(names[id]?.name ?: id, style = MaterialTheme.typography.titleSmall)
                AxisSlider("Left ↔ Right", p.x, -Vec3.ROOM_X, Vec3.ROOM_X) { onMove(id, p.copy(x = it)) }
                AxisSlider("Down ↔ Up", p.y, Vec3.FLOOR_Y, Vec3.CEILING_Y) { onMove(id, p.copy(y = it)) }
                AxisSlider("Behind ↔ In front of screen", p.z, -Vec3.ROOM_BACK, Vec3.ROOM_FRONT) { onMove(id, p.copy(z = it)) }
                Text("Screen area this light shows", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ZonePreview(Placement.zoneFor(p), states[id]?.color.toComposeColor() ?: NEUTRAL)
                Text(
                    "Stereo: " + Placement.panFor(p).let { if (it < -0.2) "left" else if (it > 0.2) "right" else "centre" },
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BandSelector(bandsOf(id), audioMode) { onBands(id, it) }
                Row {
                    TextButton(onClick = { onMove(id, Vec3(-0.6, 0.0, -0.15)) }) { Text("Behind left") }
                    TextButton(onClick = { onMove(id, Vec3(0.6, 0.0, -0.15)) }) { Text("Behind right") }
                }
                OutlinedButton(onClick = { onMove(id, Vec3(0.0, 0.4, -0.15)) }) { Text("Behind top") }
            } else if (lights.isNotEmpty()) {
                Text("Click a light to edit its position.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Which frequency bands the light follows in the audio modes. At least one stays selected. */
@Composable
private fun BandSelector(bands: Set<Band>, audioMode: Boolean, onChange: (Set<Band>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Reacts to", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Band.entries.forEach { band ->
                val on = band in bands
                FilterChip(
                    selected = on,
                    // The last selected band can't be removed: a light must react to something
                    onClick = { if (!on) onChange(bands + band) else if (bands.size > 1) onChange(bands - band) },
                    label = { Text(band.label) },
                )
            }
        }
        Text(
            if (audioMode) "In the audio modes this light follows only the selected bands, and uses only their colours."
            else "Used in the “Screen + Audio” and “Audio only” modes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AxisSlider(label: String, value: Double, min: Double, max: Double, onChange: (Double) -> Unit) {
    Column {
        Text("$label  ${value.toFixed(2)}", style = MaterialTheme.typography.labelMedium)
        Slider(value = value.toFloat(), onValueChange = { onChange(it.toDouble()) }, valueRange = min.toFloat()..max.toFloat())
    }
}

@Composable
private fun ZonePreview(zone: Zone, color: Color) {
    Canvas(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        drawRect(Color(0xFF0D0E11))
        drawRect(
            color.copy(alpha = 0.75f),
            topLeft = Offset((zone.left * size.width).toFloat(), (zone.top * size.height).toFloat()),
            size = Size(((zone.right - zone.left) * size.width).toFloat(), ((zone.bottom - zone.top) * size.height).toFloat()),
        )
        drawRect(Color(0x66FFFFFF), style = Stroke(1.5f))
    }
}

private enum class DragMode { NONE, ORBIT, MOVE, HEIGHT }

@Composable
private fun Room3D(
    lights: List<Pair<String, Vec3>>,
    names: Map<String, HaLight>,
    states: Map<String, LightState>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onMove: (String, Vec3) -> Unit,
    modifier: Modifier,
) {
    var camera by remember { mutableStateOf(Camera()) }
    val textMeasurer = rememberTextMeasurer()
    // Latest values for the gesture handler, which is installed once
    val currentLights by rememberUpdated(lights)
    val currentCamera by rememberUpdated(camera)

    Canvas(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF101115))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var mode = DragMode.NONE
                    var dragId: String? = null
                    var grabOffset = Vec3(0.0, 0.0, 0.0)
                    var last = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.first()
                        val size = Size(this.size.width.toFloat(), this.size.height.toFloat())
                        val cam = currentCamera
                        when (event.type) {
                            PointerEventType.Scroll -> camera = cam.zoom(change.scrollDelta.y)
                            PointerEventType.Press -> {
                                last = change.position
                                val hit = if (event.buttons.isSecondaryPressed) null else hitTest(currentLights, cam, size, change.position)
                                onSelect(hit ?: selected)
                                if (hit != null) {
                                    dragId = hit
                                    mode = if (event.keyboardModifiers.isShiftPressed) DragMode.HEIGHT else DragMode.MOVE
                                    val p = currentLights.first { it.first == hit }.second
                                    val grab = grabPoint(cam, size, change.position, p, mode)
                                    grabOffset = if (grab != null) p - grab else Vec3(0.0, 0.0, 0.0)
                                } else {
                                    mode = DragMode.ORBIT
                                }
                            }
                            PointerEventType.Move -> if (change.pressed) {
                                val delta = change.position - last
                                last = change.position
                                when (mode) {
                                    DragMode.ORBIT -> camera = cam.orbit(delta.x, delta.y)
                                    DragMode.MOVE, DragMode.HEIGHT -> {
                                        val id = dragId ?: continue
                                        val p = currentLights.firstOrNull { it.first == id }?.second ?: continue
                                        val hit = grabPoint(cam, size, change.position, p, mode) ?: continue
                                        val target = hit + grabOffset
                                        onMove(id, if (mode == DragMode.MOVE) Vec3(target.x, p.y, target.z) else p.copy(y = target.y))
                                    }
                                    DragMode.NONE -> {}
                                }
                                change.consume()
                            }
                            PointerEventType.Release -> {
                                mode = DragMode.NONE
                                dragId = null
                            }
                        }
                    }
                }
            },
    ) {
        drawRoom(camera)
        drawScreen(camera, selected?.let { id -> lights.find { it.first == id } }?.let { Placement.zoneFor(it.second) to (states[it.first]?.color.toComposeColor() ?: NEUTRAL) })
        drawViewer(camera)

        // Far to near so closer lights are drawn on top
        val sorted = lights.mapNotNull { (id, p) -> camera.project(p, size)?.let { Triple(id, p, it) } }.sortedByDescending { it.third.depth }
        for ((id, p, proj) in sorted) {
            val floor = camera.project(Vec3(p.x, Vec3.FLOOR_Y, p.z), size)
            if (floor != null) {
                drawLine(Color(0x55FFFFFF), proj.pos, floor.pos, strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                drawOval(Color(0x33000000), topLeft = floor.pos - Offset(12f, 4f), size = Size(24f, 8f))
            }
            val state = states[id]
            val c = state?.color.toComposeColor()
            val radius = (80 / proj.depth).toFloat().coerceIn(5f, 22f)
            if (c != null) {
                // On: glowing in its current colour, glow strength by brightness
                drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.55f * c.alpha), Color.Transparent), proj.pos, radius * 3), radius * 3, proj.pos)
                drawCircle(c.copy(alpha = 1f), radius, proj.pos)
            } else {
                // Off (or unavailable/unknown): an unlit bulb without glow
                drawCircle(Color(0xFF2A2C33), radius, proj.pos)
                val outline = if (state?.state == "off") Color(0x99FFFFFF) else Color(0x55FF8A80)
                drawCircle(
                    outline, radius, proj.pos,
                    style = Stroke(1.5f, pathEffect = if (state?.state == "off") null else PathEffect.dashPathEffect(floatArrayOf(4f, 4f))),
                )
            }
            if (id == selected) drawCircle(Color.White, radius + 4, proj.pos, style = Stroke(2f))
            val suffix = when (state?.state) {
                "on" -> ""
                null -> " (unknown)"
                else -> " (${state.state})"
            }
            val label = textMeasurer.measure((names[id]?.name ?: id) + suffix, TextStyle(color = Color(0xDDFFFFFF), fontSize = 12.sp))
            drawText(label, topLeft = proj.pos + Offset(-label.size.width / 2f, radius + 6))
        }
    }
}

@Composable
private fun <T> rememberUpdated(value: T) = androidx.compose.runtime.rememberUpdatedState(value)

private fun hitTest(lights: List<Pair<String, Vec3>>, cam: Camera, size: Size, point: Offset): String? =
    lights.mapNotNull { (id, p) -> cam.project(p, size)?.let { Triple(id, it, hypot(it.pos.x - point.x, it.pos.y - point.y)) } }
        .filter { (_, proj, d) -> d < (80 / proj.depth).toFloat().coerceIn(5f, 22f) + 10 }
        .minByOrNull { it.second.depth }?.first

/** Where the pointer ray meets the drag plane through [p]: horizontal for moving, vertical for height. */
private fun grabPoint(cam: Camera, size: Size, point: Offset, p: Vec3, mode: DragMode): Vec3? =
    if (mode == DragMode.HEIGHT) cam.intersect(point, size, p, cam.verticalPlaneNormal) else cam.intersect(point, size, p, Vec3(0.0, 1.0, 0.0))

private fun DrawScope.line3(cam: Camera, a: Vec3, b: Vec3, color: Color, width: Float = 1f) {
    val pa = cam.project(a, size) ?: return
    val pb = cam.project(b, size) ?: return
    drawLine(color, pa.pos, pb.pos, strokeWidth = width)
}

private fun DrawScope.drawRoom(cam: Camera) {
    val y = Vec3.FLOOR_Y
    val grid = Color(0x22FFFFFF)
    var x = -Vec3.ROOM_X
    while (x <= Vec3.ROOM_X + 1e-9) {
        line3(cam, Vec3(x, y, -Vec3.ROOM_BACK), Vec3(x, y, Vec3.ROOM_FRONT), grid)
        x += 0.5
    }
    var z = -Vec3.ROOM_BACK
    while (z <= Vec3.ROOM_FRONT + 1e-9) {
        line3(cam, Vec3(-Vec3.ROOM_X, y, z), Vec3(Vec3.ROOM_X, y, z), grid)
        z += 0.5
    }
    // Back wall outline
    val wall = Color(0x18FFFFFF)
    val top = Vec3.CEILING_Y
    line3(cam, Vec3(-Vec3.ROOM_X, y, -Vec3.ROOM_BACK), Vec3(-Vec3.ROOM_X, top, -Vec3.ROOM_BACK), wall)
    line3(cam, Vec3(Vec3.ROOM_X, y, -Vec3.ROOM_BACK), Vec3(Vec3.ROOM_X, top, -Vec3.ROOM_BACK), wall)
    line3(cam, Vec3(-Vec3.ROOM_X, top, -Vec3.ROOM_BACK), Vec3(Vec3.ROOM_X, top, -Vec3.ROOM_BACK), wall)
}

/** The monitor at the origin, a desk below it, and the zone of the selected light on the screen. */
private fun DrawScope.drawScreen(cam: Camera, zone: Pair<Zone, Color>?) {
    val w = Placement.SCREEN_HALF_W
    val h = Placement.SCREEN_HALF_H
    val corners = listOf(Vec3(-w, h, 0.0), Vec3(w, h, 0.0), Vec3(w, -h, 0.0), Vec3(-w, -h, 0.0)).map { cam.project(it, size) ?: return }
    val path = Path().apply {
        moveTo(corners[0].pos.x, corners[0].pos.y)
        corners.drop(1).forEach { lineTo(it.pos.x, it.pos.y) }
        close()
    }
    drawPath(path, Color(0xFF22252D))
    zone?.let { (z, color) ->
        fun at(u: Double, v: Double) = cam.project(Vec3(-w + u * 2 * w, h - v * 2 * h, 0.01), size)?.pos
        val pts = listOf(at(z.left, z.top), at(z.right, z.top), at(z.right, z.bottom), at(z.left, z.bottom))
        if (pts.all { it != null }) {
            val zp = Path().apply {
                moveTo(pts[0]!!.x, pts[0]!!.y)
                pts.drop(1).forEach { lineTo(it!!.x, it.y) }
                close()
            }
            drawPath(zp, color.copy(alpha = 0.5f))
        }
    }
    drawPath(path, Color(0xAAFFFFFF), style = Stroke(1.5f))
    // Stand and desk
    line3(cam, Vec3(0.0, -h, -0.05), Vec3(0.0, -0.45, -0.05), Color(0x88FFFFFF), 2f)
    val d = -0.45
    line3(cam, Vec3(-0.9, d, -0.35), Vec3(0.9, d, -0.35), Color(0x44FFFFFF))
    line3(cam, Vec3(0.9, d, -0.35), Vec3(0.9, d, 0.45), Color(0x44FFFFFF))
    line3(cam, Vec3(0.9, d, 0.45), Vec3(-0.9, d, 0.45), Color(0x44FFFFFF))
    line3(cam, Vec3(-0.9, d, 0.45), Vec3(-0.9, d, -0.35), Color(0x44FFFFFF))
}

private fun DrawScope.drawViewer(cam: Camera) {
    val head = cam.project(Vec3(0.0, -0.05, 1.6), size) ?: return
    val r = (70 / head.depth).toFloat().coerceIn(4f, 16f)
    drawCircle(Color(0x668FB8FF), r, head.pos)
    cam.project(Vec3(0.0, -0.55, 1.65), size)?.let { body ->
        drawLine(Color(0x668FB8FF), head.pos, body.pos, strokeWidth = r * 1.2f)
    }
}
