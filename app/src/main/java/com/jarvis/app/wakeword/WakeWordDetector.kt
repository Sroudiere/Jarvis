package com.jarvis.app.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
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
        private const val TAG             = "WakeWordDetector"

        // Audio
        private const val SAMPLE_RATE     = 16_000
        private const val CHUNK_SAMPLES   = 1280          // 80 ms @ 16 kHz

        // 2-second rolling buffer — matches training context window
        private const val ROLLING_SAMPLES = 32_000

        // openWakeWord pipeline constants
        private const val MEL_BINS        = 32
        private const val MEL_WINDOW      = 76
        private const val MEL_SLIDE       = 8
        private const val EMBED_DIM       = 96
        private const val THRESHOLD       = 0.5f

        // Minimum chunks between successive detections (~2 s)
        private const val COOLDOWN_CHUNKS = 25
    }

    private val ortEnv = OrtEnvironment.getEnvironment()
    private var melSession:   OrtSession? = null
    private var embedSession: OrtSession? = null
    private var wakeSession:  OrtSession? = null

    // Read from the wake model's input shape at init time — never hardcoded
    private var nEmbedWindows = 0

    // Circular rolling audio buffer (int16-magnitude float32 values, ~[-32768, 32767])
    private val rollingAudio  = FloatArray(ROLLING_SAMPLES)
    private var rollingHead   = 0   // next write position
    private var rollingFilled = 0   // samples written so far, capped at ROLLING_SAMPLES

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

        // Determine how many embedding windows the classifier expects
        val tensorInfo = wakeSession!!.inputInfo.values.first().info as TensorInfo
        nEmbedWindows = if (tensorInfo.shape[1] > 0) {
            tensorInfo.shape[1].toInt()
        } else {
            // Dynamic input shape: derive from a silent pass through the mel model
            computeNWindows()
        }
        Log.d(TAG, "Wake model shape=${tensorInfo.shape.toList()}, nEmbedWindows=$nEmbedWindows")
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

        rollingHead   = 0
        rollingFilled = 0
        listening = true
        Log.d(TAG, ">>> AudioRecord.startRecording()")
        recorder.startRecording()
        Log.d(TAG, "<<< AudioRecord.startRecording() recordingState=${recorder.recordingState}")

        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            listening = false
            Log.w(TAG, ">>> AudioRecord.release() (recording never started)")
            recorder.release()
            Log.w(TAG, "<<< AudioRecord.release()")
            throw IllegalStateException(
                "AudioRecord failed to start recording (state=${recorder.recordingState}). " +
                "Check RECORD_AUDIO permission and mic availability.")
        }

        Log.d(TAG, "Wake word detection started")
        val pcm = ShortArray(CHUNK_SAMPLES)
        var consecutiveErrors = 0
        var chunkCount = 0
        var cooldown = 0
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

                // Honour cooldown between detections
                if (cooldown > 0) { cooldown--; continue }

                // Push new samples into the circular rolling buffer
                for (s in pcm) {
                    rollingAudio[rollingHead] = s.toFloat()
                    rollingHead = (rollingHead + 1) % ROLLING_SAMPLES
                    if (rollingFilled < ROLLING_SAMPLES) rollingFilled++
                }

                // Stage 1: mel spectrogram on the full rolling buffer
                val audioSlice = getOrderedRollingAudio()
                val melFrames = runMelModel(audioSlice) ?: continue
                if (melFrames.size < MEL_WINDOW) continue

                // Stage 2: all sliding-window embeddings in a single batched call
                val embeddings = runEmbedModelAll(melFrames) ?: continue
                if (embeddings.isEmpty()) continue

                // Stage 3: left-pad / trim to nEmbedWindows, then classify
                val score = runWakeModel(embeddings) ?: continue

                if (chunkCount % LOG_INTERVAL == 0) {
                    Log.d(TAG, "Score: ${"%.4f".format(score)}")
                }
                if (score >= THRESHOLD) {
                    Log.d(TAG, "Wake word detected! score=${"%.3f".format(score)}")
                    onDetected()
                    cooldown = COOLDOWN_CHUNKS
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

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun computeNWindows(): Int {
        val mel = runMelModel(FloatArray(ROLLING_SAMPLES)) ?: return 8
        val n = maxOf((mel.size - MEL_WINDOW) / MEL_SLIDE + 1, 1)
        Log.d(TAG, "Computed nEmbedWindows=$n from ${mel.size} mel frames (dynamic shape)")
        return n
    }

    /** Returns the rolling buffer contents in chronological order. */
    private fun getOrderedRollingAudio(): FloatArray {
        if (rollingFilled < ROLLING_SAMPLES) {
            // Buffer not yet full: data sits at [0, rollingFilled) in order
            return rollingAudio.copyOf(rollingFilled)
        }
        val out = FloatArray(ROLLING_SAMPLES)
        val tail = ROLLING_SAMPLES - rollingHead
        System.arraycopy(rollingAudio, rollingHead, out, 0, tail)
        if (rollingHead > 0) System.arraycopy(rollingAudio, 0, out, tail, rollingHead)
        return out
    }

    // ─── ONNX inference ──────────────────────────────────────────────────────

    /**
     * Stage 1 — mel spectrogram.
     * Input:  [1, N] float32 (int16-magnitude values, range ~[-32768, 32767])
     * Output: array of mel frames, each float[MEL_BINS], after (x / 10) + 2 normalisation.
     */
    private fun runMelModel(audio: FloatArray): Array<FloatArray>? {
        val session = melSession ?: return null
        val inputName = session.inputInfo.keys.first()
        val tensor = OnnxTensor.createTensor(
            ortEnv, FloatBuffer.wrap(audio), longArrayOf(1L, audio.size.toLong()))
        return try {
            session.run(mapOf(inputName to tensor)).use { result ->
                tensor.close()
                val outTensor = result.get(session.outputInfo.keys.first()).get() as OnnxTensor
                val flat = FloatArray(outTensor.floatBuffer.remaining())
                outTensor.floatBuffer.get(flat)
                for (i in flat.indices) flat[i] = (flat[i] / 10f) + 2f
                val frameCount = flat.size / MEL_BINS
                Array(frameCount) { fi -> FloatArray(MEL_BINS) { bi -> flat[fi * MEL_BINS + bi] } }
            }
        } catch (e: Exception) {
            Log.e(TAG, "runMelModel failed: $e")
            null
        }
    }

    /**
     * Stage 2 — embeddings for all sliding windows, batched in a single inference call.
     * Input:  [nWins, 76, 32, 1] float32
     * Output: Array[nWins][96]  (from raw [nWins, 1, 1, 96])
     */
    private fun runEmbedModelAll(melFrames: Array<FloatArray>): Array<FloatArray>? {
        val session = embedSession ?: return null
        val starts = (0..melFrames.size - MEL_WINDOW step MEL_SLIDE).toList()
        val nWins = starts.size
        if (nWins == 0) return emptyArray()

        val flat = FloatArray(nWins * MEL_WINDOW * MEL_BINS)
        for (wi in 0 until nWins) {
            val base = wi * MEL_WINDOW * MEL_BINS
            for (fi in 0 until MEL_WINDOW) {
                System.arraycopy(melFrames[starts[wi] + fi], 0, flat, base + fi * MEL_BINS, MEL_BINS)
            }
        }

        val inputName = session.inputInfo.keys.first()
        val tensor = OnnxTensor.createTensor(
            ortEnv, FloatBuffer.wrap(flat),
            longArrayOf(nWins.toLong(), MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1L))
        return try {
            session.run(mapOf(inputName to tensor)).use { result ->
                tensor.close()
                val outTensor = result.get(session.outputInfo.keys.first()).get() as OnnxTensor
                val outFlat = FloatArray(outTensor.floatBuffer.remaining())
                outTensor.floatBuffer.get(outFlat)
                // [nWins, 1, 1, 96] flattened → each block of EMBED_DIM belongs to one window
                Array(nWins) { wi -> FloatArray(EMBED_DIM) { di -> outFlat[wi * EMBED_DIM + di] } }
            }
        } catch (e: Exception) {
            Log.e(TAG, "runEmbedModelAll failed: $e")
            null
        }
    }

    /**
     * Stage 3 — wake word classifier.
     * Left-pads with zeros when fewer embeddings are available than nEmbedWindows,
     * trims the oldest when more are available.
     * Input:  [1, nEmbedWindows, 96] float32
     * Output: max score across all output positions, range [0, 1]
     */
    private fun runWakeModel(embeddings: Array<FloatArray>): Float? {
        val session = wakeSession ?: return null
        val nExp = nEmbedWindows

        // Left-pad with zero vectors if needed; take the last nExp if we have more
        val padded = Array(nExp) { i ->
            val src = embeddings.size - nExp + i
            if (src < 0) FloatArray(EMBED_DIM) else embeddings[src]
        }

        val flat = FloatArray(nExp * EMBED_DIM)
        for (ei in 0 until nExp) System.arraycopy(padded[ei], 0, flat, ei * EMBED_DIM, EMBED_DIM)

        val inputName = session.inputInfo.keys.first()
        val tensor = OnnxTensor.createTensor(
            ortEnv, FloatBuffer.wrap(flat),
            longArrayOf(1L, nExp.toLong(), EMBED_DIM.toLong()))
        return try {
            session.run(mapOf(inputName to tensor)).use { result ->
                tensor.close()
                val outTensor = result.get(session.outputInfo.keys.first()).get() as OnnxTensor
                val outFlat = FloatArray(outTensor.floatBuffer.remaining())
                outTensor.floatBuffer.get(outFlat)
                outFlat.maxOrNull()
            }
        } catch (e: Exception) {
            Log.e(TAG, "runWakeModel failed: $e")
            null
        }
    }
}
