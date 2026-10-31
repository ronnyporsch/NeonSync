package neonsync.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred

/**
 * Android only allows capturing other apps' sound from a foreground service, which also keeps
 * NeonSync running while it is in the background. Shows the "lights follow the sound" notification.
 */
class AudioCaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sound capture", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("NeonSync")
            .setContentText("Your lights follow the sound that is playing")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        started?.complete(Unit)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 1
        private var started: CompletableDeferred<Unit>? = null

        /** Returns once the service is in the foreground, as MediaProjection requires. */
        suspend fun start(context: Context) {
            val done = CompletableDeferred<Unit>().also { started = it }
            context.startForegroundService(Intent(context, AudioCaptureService::class.java))
            done.await()
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AudioCaptureService::class.java))
        }
    }
}
