package neonsync.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import neonsync.Config
import neonsync.ConfigStore
import neonsync.LocalStorageTextFile
import neonsync.NeonSyncApp
import neonsync.Platform
import neonsync.ui.App

/** Screen and sound come from the browser's screen sharing. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Decrypting the token is asynchronous in the browser
    MainScope().launch {
        val file = LocalStorageTextFile("neonsync.config")
        val secrets = WebSecretStore("neonsync.token")
        val config = runCatching { ConfigStore(file, secrets).loadOrCreate() }.getOrElse { Config() }
        val capture = DisplayCapture()
        val platform = Platform(
            configFile = file,
            secrets = secrets,
            screen = WebScreenSource(capture, config.capture),
            audio = WebAudioSource(capture, config.audio),
        )
        // Browsers allow screen sharing only right after a click, so wait for the switch
        val app = NeonSyncApp(config.copy(autoStart = false), platform)
        app.start()
        ComposeViewport { App(app) }
    }
}
