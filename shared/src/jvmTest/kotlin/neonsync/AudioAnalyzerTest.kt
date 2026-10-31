package neonsync

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class AudioAnalyzerTest {
    private val frames = 480 // 10 ms

    /** Feeds a stereo sine; [left]/[right] are the channel amplitudes. */
    private fun AudioAnalyzer.feed(seconds: Double, left: Double, right: Double = left, freq: Double = 60.0) {
        var t = 0
        repeat((seconds * AudioAnalyzer.SAMPLE_RATE / frames).toInt()) {
            val buf = FloatArray(frames * 2)
            for (i in 0 until frames) {
                val s = sin(2 * PI * freq * (t++) / AudioAnalyzer.SAMPLE_RATE)
                buf[i * 2] = (left * s).toFloat()
                buf[i * 2 + 1] = (right * s).toFloat()
            }
            process(buf)
        }
    }

    @Test
    fun `loud bass gives a high level, quiet parts a low one`() {
        val r = AudioAnalyzer(AudioConfig())
        r.feed(1.0, 0.5)
        assertTrue(r.levels().left > 0.9, "loud: ${r.levels()}")
        r.feed(0.5, 0.05)
        assertTrue(r.levels().left < 0.2, "quiet: ${r.levels()}")
        assertTrue(r.levels().silence == 0.0)
    }

    @Test
    fun `automatic gain makes the reaction volume independent`() {
        val r = AudioAnalyzer(AudioConfig())
        r.feed(2.0, 0.05)
        assertTrue(r.levels().left > 0.9, "steady quiet signal should normalize up: ${r.levels()}")
    }

    @Test
    fun `silence is reported and levels drop to zero`() {
        val r = AudioAnalyzer(AudioConfig())
        r.feed(1.0, 0.5)
        r.feed(4.0, 0.0)
        val l = r.levels()
        assertTrue(l.silence > 0.95, "silence: $l")
        assertTrue(l.left == 0.0 && l.right == 0.0)
    }

    @Test
    fun `stereo channels are separated`() {
        val r = AudioAnalyzer(AudioConfig())
        r.feed(1.0, 0.5, 0.0)
        val l = r.levels()
        assertTrue(l.left > 0.9 && l.right < 0.05, "$l")
        assertTrue(l.levelAt(-1.0) > 0.9 && l.levelAt(1.0) < 0.05)
    }

    @Test
    fun `bands follow frequency`() {
        val r = AudioAnalyzer(AudioConfig())
        // Establish gain for all bands, then play treble only
        r.feed(1.0, 0.5, freq = 60.0)
        r.feed(1.0, 0.5, freq = 800.0)
        r.feed(1.0, 0.5, freq = 6000.0)
        val l = r.levels()
        assertTrue(l.band(Band.HIGHS, 0.0) > 0.8 && l.band(Band.BASS, 0.0) < 0.1, "$l")
    }

    @Test
    fun `a light can follow only selected bands`() {
        val r = AudioAnalyzer(AudioConfig())
        r.feed(1.0, 0.5, freq = 60.0)
        r.feed(1.0, 0.5, freq = 6000.0)
        r.feed(0.3, 0.5, freq = 60.0) // now only bass plays
        val l = r.levels()
        assertTrue(l.levelAt(0.0, setOf(Band.BASS)) > 0.8, "bass light: $l")
        assertTrue(l.levelAt(0.0, setOf(Band.HIGHS)) < 0.2, "highs light stays dark: $l")
        assertTrue(l.levelAt(0.0, setOf(Band.BASS, Band.HIGHS)) > 0.8, "loudest selected band wins")
    }

    @Test
    fun `bands are separated per stereo channel`() {
        val r = AudioAnalyzer(AudioConfig())
        r.feed(1.0, 0.0, 0.5, freq = 6000.0) // hi-hat on the right only
        val l = r.levels()
        assertTrue(l.band(Band.HIGHS, 1.0) > 0.8 && l.band(Band.HIGHS, -1.0) < 0.05, "$l")
    }
}
