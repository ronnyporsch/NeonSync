package neonsync

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Normalized screen rectangle (0.0 .. 1.0), origin top-left. */
@Serializable
data class Zone(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(left in 0.0..1.0 && right in 0.0..1.0 && top in 0.0..1.0 && bottom in 0.0..1.0) { "zone values must be within 0..1" }
        require(left < right && top < bottom) { "zone must have left < right and top < bottom" }
    }
}

@Serializable
data class HomeAssistantConfig(
    /** Base URL, e.g. `http://homeassistant.local:8123`. */
    val url: String = "http://homeassistant.local:8123",
    /** Long-lived access token (HA profile → Security → Long-lived access tokens). */
    val token: String = "",
)

@Serializable
data class CaptureConfig(
    /** Upper bound of analysed frames per second. Frames are dropped before scaling. */
    val maxFps: Int = 30,
    /** Size of the downscaled analysis image. */
    val analysisWidth: Int = 64,
    val analysisHeight: Int = 36,
)

@Serializable
data class ColorConfig(
    /** Updates per second sent to each light. Zigbee can't handle much more than ~5-10 per bulb. */
    val updatesPerSecond: Double = 5.0,
    /**
     * Bulb-side transition in ms. `-1` = automatically match the update interval, which lets the bulb
     * interpolate smoothly between updates.
     */
    val transitionMs: Int = -1,
    /** Time constant (ms) of the exponential smoothing applied to the screen colour. */
    val smoothingMs: Int = 150,
    /** Colour change (sum of |Δx|+|Δy| in CIE xy) that bypasses smoothing, e.g. on scene cuts. */
    val snapThreshold: Double = 0.15,
    /** Saturation boost around the white point; 1.0 = unchanged. */
    val saturation: Double = 1.25,
    /** How strongly saturated pixels outweigh grey ones in the zone average. 0 = plain average. */
    val saturatedPixelWeight: Double = 3.0,
    /** Brightness range sent to the lights (1..255). */
    val minBrightness: Int = 3,
    val maxBrightness: Int = 255,
    /** Gamma applied to the screen brightness before mapping to the light brightness range. */
    val brightnessGamma: Double = 1.0,
    /** Minimal change before a new command is sent: CIE xy distance and brightness steps. */
    val minColorDelta: Double = 0.004,
    val minBrightnessDelta: Int = 3,
)

@Serializable
data class AudioConfig(
    /** How much the audio modulates brightness in "screen + audio" mode: 0 = not at all, 1 = silent parts go fully dark. */
    val depth: Double = 0.6,
    /** Share of the bass band (< 150 Hz) in the reaction vs. the full signal; 1 = bass only. */
    val bassWeight: Double = 0.7,
    /** Envelope times: how fast the lights follow rising and falling loudness. */
    val attackMs: Int = 10,
    val releaseMs: Int = 250,
    /** Bulb transition while audio reaction is active. Shorter = punchier, longer = smoother. */
    val transitionMs: Int = 100,
    /** Audio-only mode: lights switch off below this normalized loudness (0..1). */
    val offThreshold: Double = 0.05,
)

@Serializable
enum class StopAction {
    /** Restore the state the lights had before bias lighting was started. */
    RESTORE,
    OFF,

    /** Leave the lights in their last colour. */
    KEEP,
}

@Serializable
data class Config(
    val homeAssistant: HomeAssistantConfig = HomeAssistantConfig(),
    /** Lights, positions, mode and colours chosen in the UI. */
    val setup: Setup = Setup(),
    val capture: CaptureConfig = CaptureConfig(),
    val color: ColorConfig = ColorConfig(),
    val audio: AudioConfig = AudioConfig(),
    val onStop: StopAction = StopAction.RESTORE,
    /** What to do with the lights while the screen is locked. */
    val onScreenLock: StopAction = StopAction.OFF,
    /** Start capturing immediately instead of waiting for "Enable" in the tray menu. */
    val autoStart: Boolean = true,
)

enum class Mode(val label: String) {
    SCREEN("Screen"),
    SCREEN_AUDIO("Screen + Audio"),

    /** No screen capture; colour and brightness come from the sound only, silence = lights off. */
    AUDIO("Audio only"),
    ;

    val usesScreen get() = this != AUDIO
    val usesAudio get() = this != SCREEN
}

enum class AudioColorMode { SPECTRUM, FIXED }

/** Frequency bands a light can react to in the audio modes. */
enum class Band(val label: String) {
    BASS("Bass"),
    MIDS("Mids"),
    HIGHS("Highs"),
    ;

    companion object {
        val ALL: Set<Band> = entries.toSet()
    }
}

/**
 * Position in the abstract room. Units: the screen is 1 wide, centred at the origin, facing +z
 * (towards the viewer). x = right, y = up, z = towards the viewer. The floor is at y = -1.
 */
@Serializable
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(f: Double) = Vec3(x * f, y * f, z * f)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length get() = kotlin.math.sqrt(this dot this)
    fun normalized() = this * (1.0 / length)

    fun clampToRoom() = Vec3(x.coerceIn(-ROOM_X, ROOM_X), y.coerceIn(FLOOR_Y, CEILING_Y), z.coerceIn(-ROOM_BACK, ROOM_FRONT))

    companion object {
        const val ROOM_X = 3.0
        const val FLOOR_Y = -1.0
        const val CEILING_Y = 2.0
        const val ROOM_BACK = 2.0
        const val ROOM_FRONT = 4.0
    }
}

/** Everything chosen in the UI; stored as the `setup` section of config.json. */
@Serializable
data class Setup(
    val mode: Mode = Mode.SCREEN,
    /** Areas whose lights all react, including lights added to the area later. */
    val selectedAreas: Set<String> = emptySet(),
    /** Individually selected lights. */
    val selectedLights: Set<String> = emptySet(),
    /** Positions in the room, by entity id. Kept for deselected lights so they keep their spot. */
    val placements: Map<String, Vec3> = emptyMap(),
    /** Bands each light reacts to in the audio modes; lights not listed react to all. */
    val lightBands: Map<String, Set<Band>> = emptyMap(),
    val audioColorMode: AudioColorMode = AudioColorMode.SPECTRUM,
    /** Colour for [AudioColorMode.FIXED], as `#rrggbb`. */
    val audioFixedColor: String = "#ff5a1f",
    /** Colours mixed by loudness of the bass (< 150 Hz), mid and high (> 2 kHz) band in [AudioColorMode.SPECTRUM]. */
    val audioBassColor: String = "#ff0000",
    val audioMidColor: String = "#00ff00",
    val audioHighColor: String = "#0000ff",
    /** Global brightness multiplier (0.05..1), set with the slider or the tray wheel. */
    val brightness: Double = 1.0,
) {
    fun bandsOf(entityId: String): Set<Band> = lightBands[entityId]?.takeIf { it.isNotEmpty() } ?: Band.ALL

    /** Lights that should react, given the area of every known light. */
    fun activeLights(areaOf: Map<String, String?>): Set<String> =
        selectedLights + areaOf.filter { (_, area) -> area != null && area in selectedAreas }.keys
}

@OptIn(ExperimentalSerializationApi::class)
val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    allowComments = true
    allowTrailingComma = true
    encodeDefaults = true
}

class ConfigStore(private val file: TextFile) {
    val location get() = file.location

    fun load(requireHomeAssistant: Boolean = true): Config {
        val text = file.readText() ?: throw ConfigException("No config found at ${file.location}. Start the app once to create one.")
        val config = try {
            json.decodeFromString<Config>(text)
        } catch (e: Exception) {
            throw ConfigException("Invalid config ${file.location}: ${e.message}")
        }
        validate(config, requireHomeAssistant)
        return config
    }

    /** For the UI app: creates a default config if there is none, HA settings may still be empty. */
    fun loadOrCreate(): Config {
        if (file.readText() == null) save(Config())
        return load(requireHomeAssistant = false)
    }

    /** The file holds the HA token; [TextFile] implementations keep it private. */
    fun save(config: Config) = file.writeText(json.encodeToString(config) + "\n")

    /**
     * Saves the parts the app manages, keeping everything else as it currently is on disk, so
     * values edited by hand while the app runs aren't overwritten.
     */
    fun update(change: (Config) -> Config) {
        val onDisk = try {
            json.decodeFromString<Config>(file.readText()!!)
        } catch (_: Exception) {
            Config()
        }
        save(change(onDisk))
    }

    private fun validate(c: Config, requireHomeAssistant: Boolean) {
        fun check(ok: Boolean, msg: String) { if (!ok) throw ConfigException(msg) }
        if (requireHomeAssistant) check(c.homeAssistant.token.isNotBlank(), "homeAssistant.token is empty")
        check(c.homeAssistant.url.startsWith("http://") || c.homeAssistant.url.startsWith("https://"), "homeAssistant.url must start with http:// or https://")
        check(c.color.updatesPerSecond in 0.5..30.0, "color.updatesPerSecond must be within 0.5..30")
        check(c.color.minBrightness in 1..255 && c.color.maxBrightness in 1..255 && c.color.minBrightness <= c.color.maxBrightness, "invalid brightness range")
        check(c.audio.depth in 0.0..1.0, "audio.depth must be within 0..1")
        check(c.audio.bassWeight in 0.0..1.0, "audio.bassWeight must be within 0..1")
        check(c.audio.transitionMs >= 0, "audio.transitionMs must be >= 0")
        check(c.capture.maxFps in 1..240, "capture.maxFps must be within 1..240")
        check(c.capture.analysisWidth in 4..1024 && c.capture.analysisHeight in 4..1024, "invalid analysis size")
    }
}

class ConfigException(message: String) : Exception(message)
