package neonsync

import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

object AppPaths {
    /** `-Dneonsync.home=/some/dir` puts config and state there (for testing). */
    private val home: Path? = System.getProperty("neonsync.home")?.let { Paths.get(it) }

    private fun xdg(env: String, fallback: String): Path =
        home ?: System.getenv(env)?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), fallback)

    val configDir: Path = xdg("XDG_CONFIG_HOME", ".config").resolve("neonsync")
    val stateDir: Path = xdg("XDG_STATE_HOME", ".local/state").resolve("neonsync")
    val configFile: Path = configDir.resolve("config.json")
    val stateFile: Path = stateDir.resolve("state.json")

    val configStore = ConfigStore(PathTextFile(configFile))
}

/** Runtime state persisted between runs (not meant to be edited). */
@Serializable
data class PersistentState(
    /** Screencast portal restore token, lets KDE re-use the previously selected screen without asking. */
    val restoreToken: String? = null,
)

object StateStore {
    fun load(): PersistentState = try {
        if (AppPaths.stateFile.exists()) json.decodeFromString(AppPaths.stateFile.readText()) else PersistentState()
    } catch (_: Exception) {
        PersistentState()
    }

    fun save(state: PersistentState) {
        AppPaths.stateDir.createDirectories()
        AppPaths.stateFile.writeText(json.encodeToString(state))
    }
}
