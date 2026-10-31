package neonsync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/** Colours for audio-only mode: [fixed] if set, otherwise the band colours mixed by loudness. */
data class AudioPalette(val fixed: XY?, val bass: LinearRgb, val mid: LinearRgb, val high: LinearRgb) {
    fun colorOf(band: Band) = when (band) {
        Band.BASS -> bass
        Band.MIDS -> mid
        Band.HIGHS -> high
    }

    companion object {
        private val RED = LinearRgb(1.0, 0.0, 0.0)
        private val GREEN = LinearRgb(0.0, 1.0, 0.0)
        private val BLUE = LinearRgb(0.0, 0.0, 1.0)

        fun of(setup: Setup) = AudioPalette(
            fixed = if (setup.audioColorMode == AudioColorMode.FIXED) {
                LinearRgb.parse(setup.audioFixedColor)?.let { ColorMath.clampToGamut(it.toXy()) }
            } else {
                null
            },
            bass = LinearRgb.parse(setup.audioBassColor) ?: RED,
            mid = LinearRgb.parse(setup.audioMidColor) ?: GREEN,
            high = LinearRgb.parse(setup.audioHighColor) ?: BLUE,
        )
    }
}

/**
 * Drives one light: turns screen and/or audio into a colour, smooths it and sends it to Home Assistant.
 *
 * Zigbee has very limited bandwidth (roughly 20-50 unicast commands per second for the whole
 * network), so updates are
 *  - rate limited to `updatesPerSecond`,
 *  - only sent when the colour changed noticeably,
 *  - never pipelined: a new command is sent only after HA/ZHA acknowledged the previous one, so a
 *    slow mesh automatically lowers the update rate instead of building up a queue of stale colours.
 * The bulb's own transition (matched to the update interval) interpolates between updates.
 */
class LightController(
    val entityId: String,
    position: Vec3,
    private val mode: Mode,
    private val config: Config,
    private val ha: HomeAssistantClient,
    /** Global brightness multiplier (tray / UI). */
    private val brightness: () -> Double,
    private val audio: () -> AudioLevels,
    /** Colours for audio-only mode, read on every tick so changes apply immediately. */
    private val palette: () -> AudioPalette,
    /** Bands this light reacts to in the audio modes, read on every tick. */
    private val bands: () -> Set<Band> = { Band.ALL },
) {
    private val color = config.color
    private val log = logger("light.$entityId")
    private val intervalNs = (1_000_000_000.0 / color.updatesPerSecond).toLong()
    private val transitionSeconds = when {
        mode.usesAudio -> config.audio.transitionMs / 1000.0
        color.transitionMs >= 0 -> color.transitionMs / 1000.0
        else -> 1.0 / color.updatesPerSecond
    }

    /** Where the light stands; can be changed while running (dragging in the room view). */
    @Volatile
    var position: Vec3 = position
        set(value) {
            field = value
            zone = Placement.zoneFor(value)
            pan = Placement.panFor(value)
        }

    @Volatile
    var zone: Zone = Placement.zoneFor(position)
        private set

    @Volatile
    private var pan: Double = Placement.panFor(position)

    /** Latest screen colour of this light's zone (brightness = screen level 0..1). */
    @Volatile
    private var screenTarget: LightColor? = null
    private var smoothed: LightColor? = null
    private var spectrumChroma = ColorMath.WHITE

    /** What was last sent; brightness in HA units (1..255), 0 = switched off. */
    @Volatile
    private var lastSent: LightColor? = null
    private var lastSendNs = 0L
    private val inFlight = AtomicBoolean(false)

    @Volatile
    var stats = Stats(0.0, 0.0)
        private set

    data class Stats(val updatesPerSecond: Double, val avgLatencyMs: Double)

    private val statCount = AtomicInt(0)
    private val statLatencyNs = AtomicLong(0)
    private var statWindowStart = nanoTime()

    /** Called from the capture thread for every analysed frame. */
    fun onSample(sample: ZoneSample) {
        val chroma = ColorMath.clampToGamut(ColorMath.saturate(sample.chroma, color.saturation))
        screenTarget = LightColor(chroma.x, chroma.y, sample.brightness.pow(color.brightnessGamma))
    }

    /** Forces the next tick to send, e.g. after the light was turned back on. */
    fun invalidate() {
        lastSent = null
    }

    fun run(scope: CoroutineScope): Job = scope.launch {
        var lastTick = nanoTime()
        while (isActive) {
            delay(TICK_MS)
            val now = nanoTime()
            val dt = (now - lastTick) / 1e9
            lastTick = now
            val target = target() ?: continue
            val next = smooth(target, dt)
            updateStats(now)

            if (now - lastSendNs < intervalNs || inFlight.load() || !ha.connected.value) continue
            val out = output(next)
            val prev = lastSent
            if (prev != null) {
                val unchanged = if (out.brightness == 0.0 || prev.brightness == 0.0) {
                    out.brightness == prev.brightness
                } else {
                    prev.colorDistance(out) < color.minColorDelta && abs(prev.brightness - out.brightness) < color.minBrightnessDelta
                }
                if (unchanged) continue
            }
            lastSendNs = now
            lastSent = out
            inFlight.store(true)
            launch { send(out) }
        }
    }

    /** Desired colour with a level 0..1, before smoothing. */
    private fun target(): LightColor? {
        if (mode == Mode.AUDIO) {
            val a = audio()
            val p = palette()
            val b = bands()
            val chroma = p.fixed ?: spectrumColor(a, p, b)
            return LightColor(chroma.x, chroma.y, a.levelAt(pan, b) * (1 - a.silence))
        }
        return screenTarget
    }

    private fun spectrumColor(a: AudioLevels, p: AudioPalette, bands: Set<Band>): XY {
        // Each selected band contributes its colour by its loudness; keep the last colour while (almost) nothing plays
        val levels = bands.associateWith { a.band(it, pan) }
        if (levels.values.sum() > 0.05) {
            val mix = levels.entries.fold(LinearRgb(0.0, 0.0, 0.0)) { acc, (band, level) -> acc + p.colorOf(band) * level }
            spectrumChroma = ColorMath.clampToGamut(mix.toXy())
        }
        return spectrumChroma
    }

    private fun smooth(t: LightColor, dt: Double): LightColor {
        val s = smoothed
        val next = if (s == null || color.smoothingMs <= 0 || t.colorDistance(s) > color.snapThreshold) {
            t
        } else {
            val a = 1 - exp(-dt * 1000.0 / color.smoothingMs)
            // Audio-only brightness is already shaped by the audio envelope; smoothing it would blunt it
            val b = if (mode == Mode.AUDIO) t.brightness else s.brightness + (t.brightness - s.brightness) * a
            LightColor(s.x + (t.x - s.x) * a, s.y + (t.y - s.y) * a, b)
        }
        smoothed = next
        return next
    }

    /** Converts to HA brightness (1..255) or 0 = off. */
    private fun output(c: LightColor): LightColor {
        val range = color.maxBrightness - color.minBrightness
        val bri = when (mode) {
            Mode.SCREEN -> (color.minBrightness + range * c.brightness) * brightness()
            Mode.SCREEN_AUDIO -> {
                val a = audio()
                val pulse = 1 - config.audio.depth + config.audio.depth * a.levelAt(pan, bands())
                val factor = pulse + (1 - pulse) * a.silence
                (color.minBrightness + range * c.brightness) * brightness() * factor
            }
            Mode.AUDIO -> {
                if (c.brightness < config.audio.offThreshold) return c.copy(brightness = 0.0)
                (color.minBrightness + range * c.brightness) * brightness()
            }
        }
        return c.copy(brightness = bri.roundToInt().coerceIn(1, 255).toDouble())
    }

    private suspend fun send(c: LightColor) {
        val started = nanoTime()
        try {
            if (c.brightness == 0.0) {
                ha.callService("light", "turn_off", entityId, buildJsonObject { put("transition", transitionSeconds) })
            } else {
                ha.callService(
                    "light", "turn_on", entityId,
                    buildJsonObject {
                        putJsonArray("xy_color") {
                            add(JsonPrimitive(round4(c.x)))
                            add(JsonPrimitive(round4(c.y)))
                        }
                        put("brightness", c.brightness.toInt())
                        put("transition", transitionSeconds)
                    },
                )
            }
            statLatencyNs.addAndFetch(nanoTime() - started)
            statCount.addAndFetch(1)
        } catch (e: Exception) {
            log.debug("Update failed: {}", e.message)
            lastSent = null
        } finally {
            inFlight.store(false)
        }
    }

    private fun updateStats(now: Long) {
        val window = now - statWindowStart
        if (window < 5_000_000_000L) return
        val count = statCount.exchange(0)
        val latencyNs = statLatencyNs.exchange(0)
        stats = Stats(count / (window / 1e9), if (count > 0) latencyNs / count / 1e6 else 0.0)
        log.debug("{} updates/s, {} ms avg. latency", stats.updatesPerSecond.toFixed(1), stats.avgLatencyMs.toFixed(0))
        statWindowStart = now
    }

    private fun round4(v: Double) = (v * 10000).roundToInt() / 10000.0

    companion object {
        private const val TICK_MS = 20L
    }
}
