package com.jarvis.app.wakeword

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineActivationException
import ai.picovoice.porcupine.PorcupineActivationLimitException
import ai.picovoice.porcupine.PorcupineException
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Continuously listens for the "Jarvis" wake word using the Picovoice Porcupine SDK.
 *
 * Usage:
 *   detector.start { /* called on detection */ }   // suspends until stopped or error
 *   detector.stop()
 *
 * The caller must hold RECORD_AUDIO permission before calling start().
 */
class WakeWordDetector(
    private val context: Context,
    private val accessKey: String
) {
    companion object {
        private const val TAG = "WakeWordDetector"
        private const val SAMPLE_RATE = 16_000
    }

    @Volatile
    private var running = false

    private var porcupine: Porcupine? = null
    private var audioRecord: AudioRecord? = null

    /**
     * Initialise the Porcupine engine. Call once before start().
     * Throws PorcupineException if the access key is invalid.
     */
    fun init() {
        porcupine = Porcupine.Builder()
            .setAccessKey(accessKey)
            .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
            .build(context)
        Log.d(TAG, "Porcupine initialised (frame length: ${porcupine!!.frameLength})")
    }

    /**
     * Blocking (suspending) loop that reads mic audio and detects the wake word.
     * [onDetected] is invoked on every detection (on the IO dispatcher thread).
     * Returns when stop() is called.
     */
    suspend fun start(onDetected: () -> Unit) = withContext(Dispatchers.IO) {
        val engine = porcupine
            ?: throw IllegalStateException("Call init() before start()")

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }

        val frameLength = engine.frameLength
        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(frameLength * 2, minBufSize)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        audioRecord = recorder
        recorder.startRecording()
        running = true
        Log.d(TAG, "Wake word detection started")

        val pcmBuffer = ShortArray(frameLength)

        try {
            while (isActive && running) {
                val read = recorder.read(pcmBuffer, 0, frameLength)
                if (read == frameLength) {
                    val keywordIndex = engine.process(pcmBuffer)
                    if (keywordIndex >= 0) {
                        Log.d(TAG, "Wake word detected!")
                        onDetected()
                    }
                }
            }
        } catch (e: PorcupineActivationLimitException) {
            Log.e(TAG, "Porcupine activation limit reached", e)
        } catch (e: PorcupineActivationException) {
            Log.e(TAG, "Porcupine activation error", e)
        } catch (e: PorcupineException) {
            Log.e(TAG, "Porcupine error", e)
        } finally {
            recorder.stop()
            recorder.release()
            audioRecord = null
            Log.d(TAG, "Wake word detection stopped")
        }
    }

    /** Signals the detection loop to stop at the next iteration. */
    fun stop() {
        running = false
        audioRecord?.stop()
    }

    /** Releases native resources. Call when done with the detector. */
    fun release() {
        stop()
        porcupine?.delete()
        porcupine = null
    }
}
