package neonsync.android

import android.app.Application
import android.os.Build
import neonsync.AndroidTextFile
import neonsync.Config
import neonsync.ConfigStore
import neonsync.NeonSyncApp
import neonsync.Platform
import java.io.File

/**
 * Owns the [NeonSyncApp] so it outlives activities. Android supports the "Audio only" mode: the
 * sound other apps play, from Android 10 on.
 */
class NeonSyncApplication : Application() {
    lateinit var app: NeonSyncApp
        private set
    lateinit var consent: CaptureConsent
        private set

    override fun onCreate() {
        super.onCreate()
        val file = AndroidTextFile(File(filesDir, "config.json"))
        // The config can't be edited by hand here, so an unreadable one is simply replaced
        val config = runCatching { ConfigStore(file).loadOrCreate() }.getOrElse { Config() }
        consent = CaptureConsent(this)
        val audio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) PlaybackAudioSource(this, consent, config.audio) else null
        app = NeonSyncApp(config, Platform(configFile = file, audio = audio))
        app.start()
    }
}
