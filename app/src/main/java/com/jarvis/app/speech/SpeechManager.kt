package com.jarvis.app.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Wraps Android's SpeechRecognizer to capture a single utterance after the wake word.
 *
 * Android's SpeechRecognizer must be created and used on the main thread.
 */
class SpeechManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechManager"
    }

    private val handler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var resultCallback: ((String) -> Unit)? = null
    private var errorCallback: ((Int) -> Unit)? = null

    fun init() {
        handler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.e(TAG, "Speech recognition not available on this device")
                return@post
            }
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle) {
                        val matches =
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        Log.d(TAG, "Recognized: $text")
                        resultCallback?.invoke(text)
                        resultCallback = null
                        errorCallback = null
                    }

                    override fun onError(error: Int) {
                        Log.w(TAG, "Recognition error code: $error")
                        errorCallback?.invoke(error)
                        resultCallback = null
                        errorCallback = null
                    }

                    // ── Unused callbacks ──────────────────────────────────────
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            Log.d(TAG, "SpeechRecognizer initialised")
        }
    }

    /**
     * Starts listening for a single utterance.
     * [onResult] receives the transcribed text.
     * [onError] receives the Android error code.
     */
    fun startListening(onResult: (String) -> Unit, onError: (Int) -> Unit = {}) {
        handler.post {
            val sr = recognizer
            if (sr == null) {
                Log.e(TAG, "SpeechRecognizer not initialised")
                onError(-1)
                return@post
            }
            resultCallback = onResult
            errorCallback = onError

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Give the user up to 8 seconds of speech
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            }
            sr.startListening(intent)
            Log.d(TAG, "Listening for speech…")
        }
    }

    fun stopListening() {
        handler.post {
            recognizer?.stopListening()
        }
    }

    fun release() {
        handler.post {
            recognizer?.destroy()
            recognizer = null
        }
    }
}
