package neonsync

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.time.TimeSource

/**
 * What NeonSync needs from the platform it runs on. Features a platform can't provide are null;
 * the modes that need them are then unavailable.
 */
class Platform(
    /** Where config.json lives. */
    val configFile: TextFile,
    val screen: ScreenSource? = null,
    val audio: AudioSource? = null,
    /** Opens the config file in an editor, if the platform can. */
    val openConfigFile: (() -> Unit)? = null,
) {
    fun supports(mode: Mode) = (!mode.usesScreen || screen != null) && (!mode.usesAudio || audio != null)
}

/** Delivers downscaled screen frames. */
interface ScreenSource {
    /** Shown in the settings next to "Select a different screen…". */
    val selectionHint: String

    /**
     * Starts capturing; the user may be asked which screen to share ([reselect]: always ask).
     * Throws if that fails or is cancelled. [onStopped] is called when the capture ends on its own.
     */
    suspend fun start(reselect: Boolean, onFrame: (Frame) -> Unit, onStopped: (error: String?) -> Unit)

    fun stop()

    /** Closes an open screen selection dialog, which makes [start] fail. */
    fun cancelPending() {}

    fun dispose() {}
}

/** Analyses the sound that is currently playing. */
interface AudioSource {
    val running: Boolean

    /**
     * Starts capturing; the user may be asked for permission first. Throws if that fails or is
     * declined. Does nothing while already running.
     */
    suspend fun start()
    fun stop()
    fun levels(): AudioLevels

    /** Closes an open permission dialog, which makes [start] fail. */
    fun cancelPending() {}
}

/** Downscaled BGRx frame. */
class Frame(val width: Int, val height: Int, val stride: Int, val data: ByteArray)

/** A small text file; written atomically and kept private where the platform supports it. */
interface TextFile {
    /** For display, e.g. a path. */
    val location: String

    /** Null if the file doesn't exist. */
    fun readText(): String?
    fun writeText(text: String)
}

/** Logging facade with SLF4J style `{}` placeholders; a Throwable as last argument is logged with its stack trace. */
interface Logger {
    fun debug(message: String, vararg args: Any?)
    fun info(message: String, vararg args: Any?)
    fun warn(message: String, vararg args: Any?)
    fun error(message: String, vararg args: Any?)
}

expect fun logger(name: String): Logger

/** A map that can be used from several threads at once. */
interface ConcurrentMap<K, V> : MutableMap<K, V> {
    /** Atomically adds [value] unless [key] is present; returns the present value or null. */
    fun putIfAbsent(key: K, value: V): V?
}

expect fun <K, V> concurrentMapOf(): ConcurrentMap<K, V>

/** Replaces `{}` placeholders with [args], for loggers that lack them. */
internal fun formatLogMessage(message: String, args: Array<out Any?>): String {
    val sb = StringBuilder()
    var arg = 0
    var i = 0
    while (i < message.length) {
        if (message.startsWith("{}", i) && arg < args.size) {
            sb.append(args[arg++])
            i += 2
        } else {
            sb.append(message[i++])
        }
    }
    return sb.toString()
}

private val clockStart = TimeSource.Monotonic.markNow()

/** Monotonic time in nanoseconds, like `System.nanoTime()`. */
fun nanoTime(): Long = clockStart.elapsedNow().inWholeNanoseconds

/** Formats with a fixed number of decimals, like `"%.2f".format(this)`. */
fun Double.toFixed(decimals: Int): String {
    val factor = 10.0.pow(decimals).toLong()
    val scaled = (abs(this) * factor).roundToLong()
    val sign = if (this < 0 && scaled != 0L) "-" else ""
    val fraction = if (decimals > 0) "." + (scaled % factor).toString().padStart(decimals, '0') else ""
    return "$sign${scaled / factor}$fraction"
}
