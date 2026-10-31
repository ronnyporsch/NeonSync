package neonsync

import org.freedesktop.gstreamer.Bus
import org.freedesktop.gstreamer.FlowReturn
import org.freedesktop.gstreamer.Gst
import org.freedesktop.gstreamer.Pipeline
import org.freedesktop.gstreamer.State
import org.freedesktop.gstreamer.elements.AppSink
import org.slf4j.LoggerFactory
import java.nio.ByteOrder

/**
 * Captures the monitor of the default PipeWire output ("what you hear", no microphone) in stereo
 * with GStreamer and feeds it to an [AudioAnalyzer].
 */
class PipeWireAudio(config: AudioConfig) : AudioSource {
    private val log = LoggerFactory.getLogger(PipeWireAudio::class.java)
    private val analyzer = AudioAnalyzer(config)
    private var pipeline: Pipeline? = null

    override val running get() = pipeline != null

    override fun levels(): AudioLevels = analyzer.levels()

    override suspend fun start() {
        if (pipeline != null) return
        ScreenCapture.ensureGstInit()
        val desc = "pipewiresrc client-name=neonsync stream-properties=\"props,stream.capture.sink=true\" " +
            "! audioconvert ! audioresample ! audio/x-raw,format=F32LE,channels=2,rate=${AudioAnalyzer.SAMPLE_RATE} " +
            "! appsink name=sink emit-signals=true sync=false max-buffers=4 drop=true"
        val p = Gst.parseLaunch(desc) as Pipeline
        val sink = p.getElementByName("sink") as AppSink
        sink.connect(AppSink.NEW_SAMPLE { elem ->
            val sample = elem.pullSample()
            try {
                val buffer = sample.buffer
                val bb = buffer.map(false)
                try {
                    val floats = bb.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val samples = FloatArray(floats.remaining())
                    floats.get(samples)
                    analyzer.process(samples)
                } finally {
                    buffer.unmap()
                }
            } catch (e: Exception) {
                log.warn("Audio processing failed", e)
            } finally {
                sample.dispose()
            }
            FlowReturn.OK
        })
        p.bus.connect(Bus.ERROR { source, _, message ->
            log.error("Audio capture error from {}: {}", source?.name, message)
        })
        p.play()
        pipeline = p
        log.info("Audio capture started")
    }

    override fun stop() {
        pipeline?.let {
            it.setState(State.NULL)
            it.dispose()
            log.info("Audio capture stopped")
        }
        pipeline = null
        analyzer.reset()
    }
}
