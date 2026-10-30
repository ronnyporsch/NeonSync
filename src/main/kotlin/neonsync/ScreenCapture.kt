package neonsync

import org.freedesktop.gstreamer.Bus
import org.freedesktop.gstreamer.FlowReturn
import org.freedesktop.gstreamer.Gst
import org.freedesktop.gstreamer.PadProbeReturn
import org.freedesktop.gstreamer.PadProbeType
import org.freedesktop.gstreamer.Pipeline
import org.freedesktop.gstreamer.State
import org.freedesktop.gstreamer.Version
import org.freedesktop.gstreamer.elements.AppSink
import org.slf4j.LoggerFactory

/** Downscaled BGRx frame. */
class Frame(val width: Int, val height: Int, val stride: Int, val data: ByteArray)

/**
 * Reads the portal's PipeWire stream with GStreamer and delivers small BGRx frames.
 *
 * Frames above `maxFps` are dropped by a pad probe right after the source, so the (comparatively
 * expensive) full resolution scaling only runs for frames we actually use.
 */
class ScreenCapture(
    private val config: CaptureConfig,
    private val onFrame: (Frame) -> Unit,
    private val onStopped: (error: String?) -> Unit,
) {
    private val log = LoggerFactory.getLogger(ScreenCapture::class.java)
    private var pipeline: Pipeline? = null

    fun start(stream: ScreenCastStream) {
        val fdPart = stream.pipeWireFd?.let { "fd=$it " } ?: ""
        startPipeline("pipewiresrc name=src ${fdPart}path=${stream.nodeId} do-timestamp=true keepalive-time=1000")
    }

    /** [source] must contain an element named `src`. */
    internal fun startPipeline(source: String) {
        stop()
        ensureGstInit()
        val desc = "$source " +
            "! videoscale method=bilinear2 add-borders=false " +
            "! video/x-raw,width=${config.analysisWidth},height=${config.analysisHeight} " +
            "! videoconvert ! video/x-raw,format=BGRx " +
            "! appsink name=sink emit-signals=true sync=false max-buffers=1 drop=true"
        log.debug("GStreamer pipeline: {}", desc)
        val p = Gst.parseLaunch(desc) as Pipeline

        val minIntervalNs = 1_000_000_000L / config.maxFps
        var lastAccepted = 0L
        p.getElementByName("src").getStaticPad("src").addProbe(PadProbeType.BUFFER) { _, _ ->
            val now = System.nanoTime()
            if (now - lastAccepted < minIntervalNs) {
                PadProbeReturn.DROP
            } else {
                lastAccepted = now
                PadProbeReturn.OK
            }
        }

        val sink = p.getElementByName("sink") as AppSink
        sink.connect(AppSink.NEW_SAMPLE { elem ->
            val sample = elem.pullSample()
            try {
                val s = sample.caps.getStructure(0)
                val w = s.getInteger("width")
                val h = s.getInteger("height")
                val buffer = sample.buffer
                val bb = buffer.map(false)
                try {
                    val bytes = ByteArray(bb.remaining())
                    bb.get(bytes)
                    onFrame(Frame(w, h, bytes.size / h, bytes))
                } finally {
                    buffer.unmap()
                }
            } catch (e: Exception) {
                log.warn("Frame processing failed", e)
            } finally {
                sample.dispose()
            }
            FlowReturn.OK
        })

        p.bus.connect(Bus.ERROR { source, _, message ->
            log.error("GStreamer error from {}: {}", source?.name, message)
            onStopped(message)
        })
        p.bus.connect(Bus.EOS {
            log.info("Screencast stream ended")
            onStopped(null)
        })
        p.play()
        pipeline = p
    }

    fun stop() {
        pipeline?.let {
            it.setState(State.NULL)
            it.dispose()
        }
        pipeline = null
    }

    companion object {
        @Volatile
        private var initialized = false

        @Synchronized
        fun ensureGstInit() {
            if (initialized) return
            Gst.init(Version.of(1, 18), "neonsync")
            initialized = true
        }
    }
}
