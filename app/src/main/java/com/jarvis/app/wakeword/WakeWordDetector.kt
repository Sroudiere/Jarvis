package com.jarvis.app.wakeword

import android.content.Context
import android.util.Log
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.WakeWordModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Continuously listens for the "Hey Jarvis" wake word using openWakeWord (ONNX Runtime).
 *
 * Requires hey_jarvis_v0.1.onnx in app/src/main/assets/.
 * No API key needed — fully on-device inference.
 *
 * Usage:
 *   detector.init()
 *   detector.start { /* called on detection */ }   // suspends until stopped
 *   detector.stop()
 */
class WakeWordDetector(private val context: Context) {

    companion object {
        private const val TAG = "WakeWordDetector"
        private const val MODEL_ASSET = "hey_jarvis_v0.1.onnx"
        private const val THRESHOLD = 0.5f
    }

    private var engine: WakeWordEngine? = null
    @Volatile private var listening = false

    /**
     * Initialise the openWakeWord engine. Call once before start().
     */
    fun init() {
        val models = listOf(
            WakeWordModel("hey_jarvis", MODEL_ASSET, threshold = THRESHOLD)
        )
        engine = WakeWordEngine(context, models)
        Log.d(TAG, "openWakeWord engine initialised (model: $MODEL_ASSET)")
    }

    /**
     * Suspending loop that listens for the wake word.
     * [onDetected] is invoked on every detection.
     * Returns when stop() is called.
     */
    suspend fun start(onDetected: () -> Unit) = withContext(Dispatchers.IO) {
        val e = engine ?: throw IllegalStateException("Call init() before start()")
        listening = true
        Log.d(TAG, "Wake word detection started")
        try {
            e.startListening().collect { detection ->
                Log.d(TAG, "Wake word detected! (score=${detection.score})")
                onDetected()
            }
        } finally {
            listening = false
            Log.d(TAG, "Wake word detection stopped")
        }
    }

    /** Signals the detection loop to stop. No-op if not currently listening. */
    fun stop() {
        if (listening) {
            listening = false
            engine?.stopListening()
        }
    }

    /** Releases resources. Call when done with the detector. */
    fun release() {
        stop()
        engine = null
    }
}
