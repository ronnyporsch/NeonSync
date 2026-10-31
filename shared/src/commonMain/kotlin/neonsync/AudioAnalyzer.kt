package neonsync

import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Normalized audio analysis (all values 0..1).
 *
 * @property left loudness of the left channel (bass weighted), [right] of the right one
 * @property low per channel (left, right): bass < 150 Hz; [mid] 150 Hz - 2 kHz; [high] > 2 kHz.
 *   Each band is normalized on its own, so a light can follow e.g. only the hi-hats.
 * @property silence 0 while sound plays, fades to 1 during silence
 */
data class AudioLevels(
    val left: Double,
    val right: Double,
    val low: Pair<Double, Double>,
    val mid: Pair<Double, Double>,
    val high: Pair<Double, Double>,
    val silence: Double,
) {
    /** Loudness of one band at a stereo position (-1 = left .. 1 = right). */
    fun band(band: Band, pan: Double): Double {
        val (l, r) = when (band) {
            Band.BASS -> low
            Band.MIDS -> mid
            Band.HIGHS -> high
        }
        return mix(l, r, pan)
    }

    /**
     * Loudness at a stereo position, for a light reacting to [bands]. With all bands this is the
     * bass-weighted overall loudness; with a subset, the loudest of the selected bands.
     */
    fun levelAt(pan: Double, bands: Set<Band> = Band.ALL): Double =
        if (bands.containsAll(Band.ALL)) mix(left, right, pan) else bands.maxOf { band(it, pan) }

    private fun mix(l: Double, r: Double, pan: Double): Double {
        val t = ((pan + 1) / 2).coerceIn(0.0, 1.0)
        return l * (1 - t) + r * t
    }

    companion object {
        private val ZERO = 0.0 to 0.0
        val SILENT = AudioLevels(0.0, 0.0, ZERO, ZERO, ZERO, 1.0)
    }
}

/**
 * Turns stereo samples of the sound that is playing into [AudioLevels].
 *
 * Splits each channel into bass/mid/high bands, follows loudness with attack/release envelopes and
 * normalizes with a slow automatic gain, so it reacts the same at any volume. The platform's
 * [AudioSource] feeds it with [process].
 */
class AudioAnalyzer(private val config: AudioConfig) {
    @Volatile
    private var current = AudioLevels.SILENT

    @Volatile
    private var lastBufferNs = 0L

    // DSP state, only touched from the thread calling process(). Per channel: [0] = left, [1] = right
    private val bassLp = DoubleArray(2)
    private val lp2k = DoubleArray(2)
    private val envFull = DoubleArray(2)
    private val envBass = DoubleArray(2)

    /** Band envelopes [channel][band], bands: 0 = low, 1 = mid, 2 = high. */
    private val envBand = Array(2) { DoubleArray(3) }
    private var peakFull = MIN_PEAK
    private var peakBass = MIN_PEAK
    private val peakBand = DoubleArray(3) { MIN_PEAK }
    private var silentSeconds = 0.0
    private var silence = 1.0

    /** Latest analysis; silent when no audio arrived recently (e.g. the output is suspended). */
    fun levels(): AudioLevels = if (nanoTime() - lastBufferNs > STALE_NS) AudioLevels.SILENT else current

    /** Forgets the current levels, e.g. when capturing stopped. */
    fun reset() {
        current = AudioLevels.SILENT
    }

    /** Processes interleaved stereo samples at [SAMPLE_RATE]. */
    fun process(interleaved: FloatArray) {
        val frames = interleaved.size / 2
        if (frames == 0) return
        val dt = frames.toDouble() / SAMPLE_RATE
        val aBass = 1 - exp(-2 * PI * BASS_HZ / SAMPLE_RATE)
        val a2k = 1 - exp(-2 * PI * HIGH_HZ / SAMPLE_RATE)
        val sumFull = DoubleArray(2)
        val sumBass = DoubleArray(2)
        val sumBand = Array(2) { DoubleArray(3) }
        var sumMono = 0.0
        for (i in 0 until frames) {
            for (ch in 0..1) {
                val s = interleaved[i * 2 + ch].toDouble()
                bassLp[ch] += (s - bassLp[ch]) * aBass
                lp2k[ch] += (s - lp2k[ch]) * a2k
                val mid = lp2k[ch] - bassLp[ch]
                val high = s - lp2k[ch]
                sumFull[ch] += s * s
                sumBass[ch] += bassLp[ch] * bassLp[ch]
                sumBand[ch][0] += bassLp[ch] * bassLp[ch]
                sumBand[ch][1] += mid * mid
                sumBand[ch][2] += high * high
            }
            val mono = (interleaved[i * 2] + interleaved[i * 2 + 1]) / 2.0
            sumMono += mono * mono
        }
        lastBufferNs = nanoTime()

        if (sqrt(sumMono / frames) < SILENCE_RMS) {
            silentSeconds += dt
            if (silentSeconds > SILENCE_SECONDS) {
                silence += (1 - silence) * (1 - exp(-dt / SILENCE_FADE_SECONDS))
                envFull.fill(0.0); envBass.fill(0.0); envBand.forEach { it.fill(0.0) }
                current = AudioLevels.SILENT.copy(silence = silence)
                return
            }
        } else {
            silentSeconds = 0.0
            silence = 0.0
        }

        val decay = exp(-dt / AGC_SECONDS)
        for (ch in 0..1) {
            envFull[ch] = envelope(envFull[ch], sqrt(sumFull[ch] / frames), dt)
            envBass[ch] = envelope(envBass[ch], sqrt(sumBass[ch] / frames), dt)
        }
        // Shared gain for both channels, so a sound panned hard left doesn't get amplified on the right
        peakFull = max(max(envFull.max(), peakFull * decay), MIN_PEAK)
        peakBass = max(max(envBass.max(), peakBass * decay), MIN_PEAK)
        for (b in 0..2) {
            for (ch in 0..1) envBand[ch][b] = envelope(envBand[ch][b], sqrt(sumBand[ch][b] / frames), dt)
            // Shared between channels, like the overall gain
            peakBand[b] = max(max(max(envBand[0][b], envBand[1][b]), peakBand[b] * decay), MIN_PEAK)
        }

        fun channel(ch: Int): Double {
            // Squaring makes peaks stand out against the average loudness
            val full = (envFull[ch] / peakFull).coerceIn(0.0, 1.0).let { it * it }
            val bass = (envBass[ch] / peakBass).coerceIn(0.0, 1.0).let { it * it }
            return config.bassWeight * bass + (1 - config.bassWeight) * full
        }
        fun band(b: Int): Pair<Double, Double> {
            fun norm(ch: Int) = (envBand[ch][b] / peakBand[b]).coerceIn(0.0, 1.0).let { it * it }
            return norm(0) to norm(1)
        }
        current = AudioLevels(channel(0), channel(1), band(0), band(1), band(2), silence)
    }

    private fun envelope(env: Double, input: Double, dt: Double): Double {
        val tau = (if (input > env) config.attackMs else config.releaseMs).coerceAtLeast(1) / 1000.0
        return env + (input - env) * (1 - exp(-dt / tau))
    }

    companion object {
        const val SAMPLE_RATE = 48000
        private const val BASS_HZ = 150.0
        private const val HIGH_HZ = 2000.0
        private const val AGC_SECONDS = 8.0

        /** About -40 dBFS: quieter signals are not amplified further. */
        private const val MIN_PEAK = 0.01

        /** About -66 dBFS. */
        private const val SILENCE_RMS = 0.0005
        private const val SILENCE_SECONDS = 1.0
        private const val SILENCE_FADE_SECONDS = 0.5
        private const val STALE_NS = 500_000_000L
    }
}
