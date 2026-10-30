package neonsync

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreenCaptureTest {
    @Test
    fun `pipeline scales, converts and rate limits frames`() {
        assumeTrue(runCatching { ScreenCapture.ensureGstInit() }.isSuccess, "GStreamer not available")
        val frames = AtomicInteger()
        val last = AtomicReference<Frame>()
        val latch = CountDownLatch(5)
        val capture = ScreenCapture(CaptureConfig(maxFps = 10, analysisWidth = 32, analysisHeight = 18), { f ->
            last.set(f)
            frames.incrementAndGet()
            latch.countDown()
        }, { error -> if (error != null) println("stopped: $error") })
        // 1080p red test image at 60 fps
        capture.startPipeline("videotestsrc name=src is-live=true pattern=red ! video/x-raw,format=BGRA,width=1920,height=1080,framerate=60/1")
        assertTrue(latch.await(5, TimeUnit.SECONDS), "no frames received")
        Thread.sleep(1000)
        capture.stop()

        val f = last.get()
        assertEquals(32, f.width)
        assertEquals(18, f.height)
        // BGRx red
        assertEquals(0xff, f.data[2].toInt() and 0xff)
        assertEquals(0, f.data[1].toInt() and 0xff)
        // ~1.5 s at max 10 fps, 60 fps source
        assertTrue(frames.get() in 8..20, "rate limit not applied: ${frames.get()} frames")
    }
}
