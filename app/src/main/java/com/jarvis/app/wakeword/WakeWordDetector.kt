package com.jarvis.app.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
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
import java.nio.FloatBuffer

/**
 * Continuously listens for "Hey Jarvis" using the openWakeWord 3-stage ONNX pipeline:
 *   1. melspectrogram.onnx  — raw audio → mel spectrogram frames
 *   2. embedding_model.onnx — mel frames → 96-dim speech embeddings
 *   3. hey_jarvis_v0.1.onnx — embeddings → detection score
 *
 * All three ONNX files must be placed in app/src/main/assets/.
 * Run app/src/main/assets/download_models.sh to fetch them.
 * No API key or internet connection required at runtime.
 */
class WakeWordDetector(private val context: Context) {

    companion object {
        private const val TAG = "WakeWordDetector"

        // Audio
        private const val SAMPLE_RATE    = 16_000
        private const val CHUNK_SAMPLES  = 1280        // 80 ms @ 16 kHz

        // openWakeWord pipeline constants (from official documentation)
        private const val MEL_BINS          = 32       // mel frequency bins per frame
        private const val FRAMES_PER_CHUNK  = 5        // mel frames produced per 1280-sample chunk
        private const val MEL_WINDOW        = 76       // frames fed to embedding model
        private const val MEL_SLIDE         = 8        // frames to advance after each embedding
        private const val EMBED_DIM         = 96       // embedding dimension
        private const val EMBED_WINDOW      = 16       // embeddings fed to wake word model
        private const val THRESHOLD         = 0.5f
    }

    private val ortEnv = OrtEnvironment.getEnvironment()
    private var melSession:   OrtSession? = null
    private var embedSession: OrtSession? = null
    private var wakeSession:  OrtSession? = null

    // Sliding buffers
    private val melBuf   = ArrayDeque<FloatArray>()  // float[MEL_BINS] per entry
    private val embedBuf = ArrayDeque<FloatArray>()  // float[EMBED_DIM] per entry

    @Volatile private var listening = false

    // ─── Public API ───────────────────────────────────────────────────────────

    fun init() {
        fun loadAsset(name: String): ByteArray {
            val bytes = context.assets.open(name).readBytes()
            Log.d(TAG, "Loaded asset $name: ${bytes.size} bytes")
            return bytes
        }
        melSession   = ortEnv.createSession(loadAsset("melspectrogram.onnx"))
        Log.d(TAG, "melspectrogram session OK")
        embedSession = ortEnv.createSession(loadAsset("embedding_model.onnx"))
        Log.d(TAG, "embedding_model session OK")
        wakeSession  = ortEnv.createSession(loadAsset("hey_jarvis_v0.1.onnx"))
        Log.d(TAG, "hey_jarvis session OK")
        Log.d(TAG, "openWakeWord ONNX engine ready")
    }

    suspend fun start(onDetected: () -> Unit) = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) throw SecurityException("RECORD_AUDIO permission not granted")

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(CHUNK_SAMPLES * 2, minBuf)
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize (state=${recorder.state})"
        }

        melBuf.clear()
        embedBuf.clear()
        listening = true
        Log.d(TAG, ">>> AudioRecord.startRecording()")
        recorder.startRecording()
        Log.d(TAG, "<<< AudioRecord.startRecording() recordingState=${recorder.recordingState}")

        // If recording didn't actually start (e.g. no mic on emulator, AppOps denied),
        // release without calling stop() — avoids "Operation not started" in AppOps.
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            listening = false
            Log.w(TAG, ">>> AudioRecord.release() (recording never started)")
            recorder.release()
            Log.w(TAG, "<<< AudioRecord.release()")
            throw IllegalStateException("AudioRecord failed to start recording (state=${recorder.recordingState}). Check RECORD_AUDIO permission and mic availability.")
        }

        Log.d(TAG, "Wake word detection started")
        val pcm = ShortArray(CHUNK_SAMPLES)
        var consecutiveErrors = 0
        var chunkCount = 0
        // Log listening status every ~2 s (2s * 16000Hz / 1280 samples ≈ 25 chunks)
        val LOG_INTERVAL = 25
        try {
            while (isActive && listening) {
                val read = recorder.read(pcm, 0, CHUNK_SAMPLES)
                if (read < 0) {
                    Log.e(TAG, "AudioRecord.read() error: $read")
                    if (++consecutiveErrors >= 10) {
                        Log.e(TAG, "Too many consecutive read errors, stopping wake word detection")
                        break
                    }
                    continue
                }
                consecutiveErrors = 0
                if (read != CHUNK_SAMPLES) continue

                if (chunkCount % LOG_INTERVAL == 0) {
                    val maxAmp = pcm.maxOf { kotlin.math.abs(it.toInt()) }
                    Log.d(TAG, "Listening… audio level (max amp): $maxAmp")
                }
                chunkCount++

                // Stage 1: mel spectrogram
                val newFrames = runMelModel(pcm) ?: continue
                for (f in newFrames) melBuf.addLast(f)

                // Stage 2: embedding (run whenever enough mel frames are buffered)
                while (melBuf.size >= MEL_WINDOW) {
                    val emb = runEmbedModel() ?: break
                    embedBuf.addLast(emb)
                    if (embedBuf.size > EMBED_WINDOW) embedBuf.removeFirst()
                    repeat(MEL_SLIDE) { if (melBuf.isNotEmpty()) melBuf.removeFirst() }
                }

                // Stage 3: detection
                if (embedBuf.size == EMBED_WINDOW) {
                    val score = runWakeModel() ?: continue
                    if (score >= THRESHOLD) {
                        Log.d(TAG, "Wake word detected! score=%.3f".format(score))
                        onDetected()
                        // Clear embed buffer to enforce a natural cooldown
                        embedBuf.clear()
                    }
                }
            }
        } finally {
            listening = false
            Log.d(TAG, ">>> cleanup: recordingState=${recorder.recordingState}")
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
                Log.d(TAG, "<<< AudioRecord.stop()")
            }
            recorder.release()
            Log.d(TAG, "<<< AudioRecord.release() — wake word detection stopped")
        }
    }

    fun stop() {
        listening = false
    }

    fun release() {
        stop()
        melSession?.close();   melSession   = null
        embedSession?.close(); embedSession = null
        wakeSession?.close();  wakeSession  = null
    }

    // ─── ONNX inference helpers ───────────────────────────────────────────────

    /**
     * Runs the mel spectrogram model on one 1280-sample chunk.
     * Input:  [1, 1280] float32 (audio normalised to [-1, 1])
     * Output: FRAMES_PER_CHUNK frames, each float[MEL_BINS], after (x/10)+2 transform.
     */
    private fun runMelModel(pcm: ShortArray): List<FloatArray>? {
        val session = melSession ?: return null
        val audio = FloatArray(CHUNK_SAMPLES) { pcm[it].toFloat() / 32768.0f }
        val inputName = session.inputInfo.keys.first()
        val tensor = OnnxTensor.createTensor(
            ortEnv, FloatBuffer.wrap(audio), longArrayOf(1L, CHUNK_SAMPLES.toLong()))
        return try {
            session.run(mapOf(inputName to tensor)).use { result ->
                tensor.close()
                val outName = session.outputInfo.keys.first()
                val outTensor = result.get(outName).get() as OnnxTensor
                val flat = FloatArray(outTensor.floatBuffer.remaining())
                outTensor.floatBuffer.get(flat)
                // Apply normalisation: (x / 10) + 2
                for (i in flat.indices) flat[i] = (flat[i] / 10f) + 2f
                // Split into individual frames of size MEL_BINS
                val frameCount = flat.size / MEL_BINS
                (0 until frameCount).map { fi ->
                    FloatArray(MEL_BINS) { bi -> flat[fi * MEL_BINS + bi] }
                }
            }
        } catch (e: IllegalStateException) {
            null  // session was closed mid-inference during shutdown
        }
    }

    /**
     * Runs the embedding model on the current mel buffer window.
     * Input:  [1, MEL_WINDOW, MEL_BINS, 1] float32
     * Output: float[EMBED_DIM]
     */
    private fun runEmbedModel(): FloatArray? {
        val session = embedSession ?: return null
        val flat = FloatArray(MEL_WINDOW * MEL_BINS)
        for (fi in 0 until MEL_WINDOW) {
            val frame = melBuf[fi]
            for (bi in 0 until MEL_BINS) flat[fi * MEL_BINS + bi] = frame[bi]
        }
        val inputName = session.inputInfo.keys.first()
        val tensor = OnnxTensor.createTensor(
            ortEnv, FloatBuffer.wrap(flat),
            longArrayOf(1L, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1L))
        return try {
            session.run(mapOf(inputName to tensor)).use { result ->
                tensor.close()
                val outName = session.outputInfo.keys.first()
                val outTensor = result.get(outName).get() as OnnxTensor
                val outFlat = FloatArray(outTensor.floatBuffer.remaining())
                outTensor.floatBuffer.get(outFlat)
                // Output is [1, 1, 1, EMBED_DIM] — the last EMBED_DIM values are the embedding
                outFlat.takeLast(EMBED_DIM).toFloatArray()
            }
        } catch (e: IllegalStateException) {
            null  // session was closed mid-inference during shutdown
        }
    }

    /**
     * Runs the hey-jarvis classifier on the current embedding window.
     * Input:  [1, EMBED_WINDOW, EMBED_DIM] float32
     * Output: detection score in [0, 1]
     */
    private fun runWakeModel(): Float? {
        val session = wakeSession ?: return null
        val flat = FloatArray(EMBED_WINDOW * EMBED_DIM)
        for (ei in 0 until EMBED_WINDOW) {
            val emb = embedBuf[ei]
            for (di in 0 until EMBED_DIM) flat[ei * EMBED_DIM + di] = emb[di]
        }
        val inputName = session.inputInfo.keys.first()
        val tensor = OnnxTensor.createTensor(
            ortEnv, FloatBuffer.wrap(flat),
            longArrayOf(1L, EMBED_WINDOW.toLong(), EMBED_DIM.toLong()))
        return try {
            session.run(mapOf(inputName to tensor)).use { result ->
                tensor.close()
                val outName = session.outputInfo.keys.first()
                val outTensor = result.get(outName).get() as OnnxTensor
                val outFlat = FloatArray(outTensor.floatBuffer.remaining())
                outTensor.floatBuffer.get(outFlat)
                outFlat.firstOrNull()
            }
        } catch (e: IllegalStateException) {
            null  // session was closed mid-inference during shutdown
        }
    }
}
