package neonsync.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.media.projection.MediaProjectionManager
import kotlin.time.Duration.Companion.seconds

/**
 * Asks the user for what capturing other apps' sound needs: the audio recording permission and
 * the screen/audio capture consent. Both dialogs need an activity, which [attach]es itself.
 */
class CaptureConsent(private val context: Context) {
    private class Launchers(
        val permission: ActivityResultLauncher<String>,
        val projection: ActivityResultLauncher<Intent>,
    )

    private val launchers = MutableStateFlow<Launchers?>(null)
    private var permissionResult: CompletableDeferred<Boolean>? = null
    private var projectionResult: CompletableDeferred<ActivityResult>? = null

    /** Call from [ComponentActivity.onCreate] (launchers must be registered before the activity starts). */
    fun attach(activity: ComponentActivity) {
        val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { permissionResult?.complete(it) }
        val projection = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { projectionResult?.complete(it) }
        launchers.value = Launchers(permission, projection)
    }

    fun detach() {
        launchers.value = null
        cancel()
    }

    /** Fails a pending [request]. */
    fun cancel() {
        permissionResult?.cancel()
        projectionResult?.cancel()
    }

    /** Returns the consent for [MediaProjectionManager.getMediaProjection]; throws if declined. */
    suspend fun request(): Intent = withContext(Dispatchers.Main) {
        // On autostart the activity may still be on its way
        val l = withTimeoutOrNull(10.seconds) { launchers.filterNotNull().first() }
            ?: throw IllegalStateException("Open NeonSync to allow audio capture")

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val granted = CompletableDeferred<Boolean>().also { permissionResult = it }
            l.permission.launch(Manifest.permission.RECORD_AUDIO)
            if (!granted.await()) throw IllegalStateException("NeonSync needs the audio recording permission to hear what is playing")
        }

        val result = CompletableDeferred<ActivityResult>().also { projectionResult = it }
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        l.projection.launch(manager.createScreenCaptureIntent())
        val r = result.await()
        r.data?.takeIf { r.resultCode == Activity.RESULT_OK } ?: throw IllegalStateException("Audio capture was not allowed")
    }
}
