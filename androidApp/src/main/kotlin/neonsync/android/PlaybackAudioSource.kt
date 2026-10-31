package neonsync.android

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import neonsync.AudioAnalyzer
import neonsync.AudioConfig
import neonsync.AudioLevels
import neonsync.AudioSource
import neonsync.logger
import kotlin.concurrent.thread

/**
 * Captures what other apps play (music, videos, games) through Android's playback capture. Apps
 * can opt out of being captured, those stay silent for NeonSync. Needs Android 10.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class PlaybackAudioSource(private val context: Context, private val consent: CaptureConsent, config: AudioConfig) : AudioSource {
    private val log = logger("PlaybackAudioSource")
    private val analyzer = AudioAnalyzer(config)

    @Volatile
    private var record: AudioRecord? = null
    private var projection: MediaProjection? = null

    override val running get() = record != null

    override fun levels(): AudioLevels = analyzer.levels()

    // RECORD_AUDIO is checked by CaptureConsent.request()
    @SuppressLint("MissingPermission")
    override suspend fun start() {
        if (record != null) return
        val data = consent.request()
        AudioCaptureService.start(context)
        try {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            val p = manager.getMediaProjection(android.app.Activity.RESULT_OK, data)
                ?: throw IllegalStateException("Audio capture was not allowed")
            // Called when the user ends sharing from the status bar
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = stop()
            }, Handler(Looper.getMainLooper()))
            projection = p

            val capture = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(AudioAnalyzer.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val r = AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(capture)
                .setBufferSizeInBytes(maxOf(AudioRecord.getMinBufferSize(AudioAnalyzer.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT), 4 * CHUNK * 4))
                .build()
            r.startRecording()
            record = r
            thread(name = "audio-capture", isDaemon = true) { readLoop(r) }
            log.info("Audio capture started")
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    private fun readLoop(r: AudioRecord) {
        val buffer = FloatArray(CHUNK)
        while (record === r) {
            val n = r.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (n < 0) {
                log.warn("Audio capture failed: {}", n)
                break
            }
            if (n > 0) analyzer.process(if (n == buffer.size) buffer else buffer.copyOf(n))
        }
    }

    override fun stop() {
        val r = record
        record = null
        r?.let {
            it.stop()
            it.release()
            log.info("Audio capture stopped")
        }
        projection?.stop()
        projection = null
        AudioCaptureService.stop(context)
        analyzer.reset()
    }

    override fun cancelPending() = consent.cancel()

    companion object {
        /** 10 ms of interleaved stereo samples. */
        private const val CHUNK = AudioAnalyzer.SAMPLE_RATE / 100 * 2
    }
}
