@file:OptIn(ExperimentalWasmJsInterop::class)

package neonsync.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import neonsync.AudioAnalyzer
import neonsync.AudioConfig
import neonsync.AudioLevels
import neonsync.AudioSource
import neonsync.CaptureConfig
import neonsync.Frame
import neonsync.ScreenSource
import kotlin.js.Promise

/** capture.js */
private external object NeonSyncCapture : JsAny {
    fun open(onEnded: () -> Unit): Promise<JsAny>
    fun openAudioInput(onEnded: () -> Unit): Promise<JsAny>
    fun close(stream: JsAny)
    fun hasAudio(stream: JsAny): Boolean
    fun frameGrabber(stream: JsAny, width: Int, height: Int): FrameGrabber
    fun audioTap(stream: JsAny, onSamples: (JsAny) -> Unit): AudioTap
}

private external interface FrameGrabber : JsAny {
    /** RGBA bytes (Uint8ClampedArray), null before the first frame. */
    fun grab(): JsAny?
    fun stop()
}

private external interface AudioTap : JsAny {
    fun stop()
}

private fun isString(value: JsAny): Boolean = js("typeof value === 'string'")
private fun lengthOf(array: JsAny): Int = js("array.length")
private fun byteAt(array: JsAny, index: Int): Int = js("array[index]")
private fun floatAt(array: JsAny, index: Int): Float = js("array[index]")

/**
 * One `getDisplayMedia` stream shared by [WebScreenSource] and [WebAudioSource], so the browser
 * asks only once in "Screen + Audio" mode. Closed when its last user releases it.
 */
class DisplayCapture {
    private var stream: JsAny? = null

    /** Counts opened streams, to tell which one an "ended" event belongs to. */
    private var generation = 0

    /** Users and what to do when the stream ends without them releasing it. */
    private val users = mutableMapOf<Any, () -> Unit>()

    /** [reselect]: ask again even if a stream is open; other users are told it ended. */
    suspend fun acquire(user: Any, reselect: Boolean = false, onEnded: () -> Unit): JsAny {
        if (reselect) end(except = user)
        val current = stream
        if (current != null) {
            users[user] = onEnded
            return current
        }
        val opening = ++generation
        // Ended by the user in the browser UI; ignored if that stream was replaced meanwhile
        val result = NeonSyncCapture.open { if (opening == generation) end(except = null) }.await<JsAny>()
        if (isString(result)) throw IllegalStateException(result.toString())
        stream = result
        users[user] = onEnded
        return result
    }

    /** Joins the stream if one is open, e.g. the audio source in "Screen + Audio" mode. */
    fun join(user: Any, onEnded: () -> Unit): JsAny? = stream?.also { users[user] = onEnded }

    fun release(user: Any) {
        users.remove(user)
        if (users.isEmpty()) close()
    }

    private fun end(except: Any?) {
        val others = users.filterKeys { it != except }.values.toList()
        users.keys.retainAll { it == except }
        close()
        others.forEach { it() }
    }

    private fun close() {
        stream?.let(NeonSyncCapture::close)
        stream = null
        generation++
    }
}

/** Shares a screen, window or tab through the browser. */
class WebScreenSource(private val capture: DisplayCapture, private val config: CaptureConfig) : ScreenSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var grabber: FrameGrabber? = null
    private var job: Job? = null

    override val selectionHint =
        "The browser asks what to share each time syncing starts. In “Screen + Audio” mode, share a tab or the whole screen with its audio " +
            "(Chrome and Edge); otherwise the browser asks for an audio input."

    override suspend fun start(reselect: Boolean, onFrame: (Frame) -> Unit, onStopped: (error: String?) -> Unit) {
        val stream = capture.acquire(this, reselect) { onStopped(null) }
        val w = config.analysisWidth
        val h = config.analysisHeight
        val g = NeonSyncCapture.frameGrabber(stream, w, h)
        grabber = g
        job = scope.launch {
            while (isActive) {
                delay(1000L / config.maxFps)
                val rgba = g.grab() ?: continue
                onFrame(toBgrx(rgba, w, h))
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        grabber?.stop()
        grabber = null
        capture.release(this)
    }

    private fun toBgrx(rgba: JsAny, width: Int, height: Int): Frame {
        val data = ByteArray(width * height * 4)
        for (i in 0 until minOf(lengthOf(rgba), data.size) step 4) {
            data[i] = byteAt(rgba, i + 2).toByte()
            data[i + 1] = byteAt(rgba, i + 1).toByte()
            data[i + 2] = byteAt(rgba, i).toByte()
        }
        return Frame(width, height, width * 4, data)
    }
}

/**
 * Analyses the sound of the shared tab or screen when there is one with sound ("Screen + Audio"
 * in Chrome/Edge), otherwise of an audio input the user picks: needed in Firefox, which can't share
 * sound with the screen, and in "Audio only" mode, which shouldn't ask for a screen.
 */
class WebAudioSource(private val capture: DisplayCapture, config: AudioConfig) : AudioSource {
    private val analyzer = AudioAnalyzer(config)
    private var tap: AudioTap? = null

    /** Our own audio input stream, if the shared screen had no sound. */
    private var input: JsAny? = null

    /** Counts opened inputs, to tell which one an "ended" event belongs to. */
    private var generation = 0

    override val running get() = tap != null

    override fun levels(): AudioLevels = analyzer.levels()

    override suspend fun start() {
        if (tap != null) return
        val shared = capture.join(this) { detach() }
        val stream = if (shared != null && NeonSyncCapture.hasAudio(shared)) {
            shared
        } else {
            if (shared != null) capture.release(this)
            openInput()
        }
        tap = NeonSyncCapture.audioTap(stream) { samples ->
            analyzer.process(FloatArray(lengthOf(samples)) { floatAt(samples, it) })
        }
    }

    private suspend fun openInput(): JsAny {
        val opening = ++generation
        val result = NeonSyncCapture.openAudioInput { if (opening == generation) detach() }.await<JsAny>()
        if (isString(result)) throw IllegalStateException(result.toString())
        input = result
        return result
    }

    override fun stop() {
        detach()
        capture.release(this)
    }

    /** Ended by the user or replaced; [start] attaches to the next stream. */
    private fun detach() {
        tap?.stop()
        tap = null
        input?.let(NeonSyncCapture::close)
        input = null
        generation++
        analyzer.reset()
    }
}
