package com.jarvis.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.IBinder
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.app.Config
import com.jarvis.app.MainActivity
import com.jarvis.app.R
import com.jarvis.app.auth.GoogleAuthManager
import com.jarvis.app.llm.OpenAIClient
import com.jarvis.app.sheets.GoogleSheetsClient
import com.jarvis.app.speech.SpeechManager
import com.jarvis.app.tools.GoogleSheetsTools
import com.jarvis.app.tools.ToolRegistry
import com.jarvis.app.wakeword.WakeWordDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground service that runs the full Jarvis pipeline:
 *   wake word detection → speech recognition → LLM + tools → TTS response
 *
 * The service is started/stopped by MainActivity.
 * Broadcast events are sent back to MainActivity to update the UI.
 */
class JarvisService : Service() {

    companion object {
        private const val TAG = "JarvisService"

        const val CHANNEL_ID = "jarvis_channel"
        const val NOTIFICATION_ID = 1

        // Actions for the service intent
        const val ACTION_START = "com.jarvis.app.START"
        const val ACTION_STOP  = "com.jarvis.app.STOP"

        // Broadcasts sent to MainActivity
        const val BROADCAST_ACTION = "com.jarvis.app.SERVICE_EVENT"
        const val EXTRA_STATE      = "state"
        const val EXTRA_TEXT       = "text"

        // States
        const val STATE_LISTENING  = "listening"
        const val STATE_AWAKE      = "awake"
        const val STATE_PROCESSING = "processing"
        const val STATE_IDLE       = "idle"
        const val STATE_ERROR      = "error"

        // Wake-word debounce: ignore detections within this window
        private const val WAKE_DEBOUNCE_MS = 3_000L

        const val PREFS_NAME       = "jarvis_prefs"
        const val PREF_PAUSE_ON_LOCK = "pause_on_screen_lock"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeWordJob: Job? = null

    private lateinit var wakeWordDetector: WakeWordDetector
    private lateinit var speechManager: SpeechManager
    private lateinit var openAIClient: OpenAIClient
    private lateinit var tts: TextToSpeech
    private lateinit var prefs: SharedPreferences

    @Volatile private var lastWakeTime = 0L
    @Volatile private var isAwake = false        // true while listening for speech / processing
    @Volatile private var isPausedByScreen = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (prefs.getBoolean(PREF_PAUSE_ON_LOCK, false) && !isAwake) {
                        Log.d(TAG, "Screen off — pausing wake word detection")
                        isPausedByScreen = true
                        wakeWordJob?.cancel()
                        wakeWordDetector.stop()
                        updateNotification("paused")
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (isPausedByScreen) {
                        Log.d(TAG, "Screen on — resuming wake word detection")
                        isPausedByScreen = false
                        resumeWakeWord()
                    }
                }
            }
        }
    }

    // ─── Service lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
        // Must call startForeground() within 5 seconds of startForegroundService()
        startForeground(NOTIFICATION_ID, buildNotification(STATE_IDLE))

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, screenFilter)

        // Build dependency graph
        val authManager     = GoogleAuthManager(applicationContext)
        val sheetsClient    = GoogleSheetsClient(authManager)
        val sheetsTools     = GoogleSheetsTools(sheetsClient)
        val toolRegistry    = ToolRegistry(sheetsTools)

        wakeWordDetector = WakeWordDetector(applicationContext, Config.PICOVOICE_ACCESS_KEY)
        speechManager    = SpeechManager(applicationContext)
        openAIClient     = OpenAIClient(toolRegistry)

        initTts()
        speechManager.init()
        try {
            wakeWordDetector.init()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialise wake word detector — check PICOVOICE_ACCESS_KEY in local.properties", e)
            broadcast(STATE_ERROR, "Invalid or missing Picovoice access key")
            stopSelf()
            return
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startListening()
            ACTION_STOP  -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        unregisterReceiver(screenReceiver)
        wakeWordJob?.cancel()
        wakeWordDetector.release()
        speechManager.release()
        tts.stop()
        tts.shutdown()
        broadcast(STATE_IDLE, "")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Wake word loop ───────────────────────────────────────────────────────

    private fun startListening() {
        startForeground(NOTIFICATION_ID, buildNotification(STATE_LISTENING))
        broadcast(STATE_LISTENING, "")

        wakeWordJob = serviceScope.launch {
            try {
                wakeWordDetector.start {
                    onWakeWordDetected()
                }
            } catch (e: CancellationException) {
                throw e  // expected when job is cancelled intentionally
            } catch (e: Exception) {
                Log.e(TAG, "Wake word loop error", e)
                broadcast(STATE_ERROR, e.message ?: "Wake word error")
                stopSelf()
            }
        }
    }

    // ─── Wake word → STT → LLM pipeline ──────────────────────────────────────

    private fun onWakeWordDetected() {
        val now = System.currentTimeMillis()
        if (isAwake || now - lastWakeTime < WAKE_DEBOUNCE_MS) {
            Log.d(TAG, "Wake word ignored (debounce or already awake)")
            return
        }
        lastWakeTime = now
        isAwake = true

        Log.d(TAG, "Wake word confirmed — starting STT")
        updateNotification(STATE_AWAKE)
        broadcast(STATE_AWAKE, "")

        // Stop Porcupine audio capture so the mic is free for SpeechRecognizer
        wakeWordDetector.stop()

        // Play a short "ready" beep via TTS (or you could play a tone)
        tts.speak("Oui ?", TextToSpeech.QUEUE_FLUSH, null, "ready_cue")

        // Give TTS a moment to finish, then start STT
        serviceScope.launch {
            delay(600)
            startSpeechRecognition()
        }
    }

    private fun startSpeechRecognition() {
        speechManager.startListening(
            onResult = { text ->
                if (text.isNotBlank()) {
                    Log.d(TAG, "Transcript: $text")
                    broadcast(STATE_PROCESSING, text)
                    updateNotification(STATE_PROCESSING)
                    serviceScope.launch { processWithLLM(text) }
                } else {
                    Log.d(TAG, "Empty transcript, going back to listening")
                    finishAndResumePorcupine()
                }
            },
            onError = { errorCode ->
                Log.w(TAG, "STT error: $errorCode")
                val msg = when (errorCode) {
                    SpeechRecognizer.ERROR_NO_MATCH      -> "Je n'ai pas compris."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Je n'ai rien entendu."
                    else -> "Erreur de reconnaissance vocale."
                }
                tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, null)
                finishAndResumePorcupine()
            }
        )
    }

    private suspend fun processWithLLM(userText: String) {
        try {
            val response = openAIClient.processCommand(userText)
            Log.d(TAG, "LLM response: $response")
            broadcast(STATE_LISTENING, response)
            tts.speak(response, TextToSpeech.QUEUE_FLUSH, null, "llm_response")
        } catch (e: Exception) {
            Log.e(TAG, "LLM error", e)
            tts.speak("Désolé, j'ai eu un problème pour traiter ça.", TextToSpeech.QUEUE_FLUSH, null, null)
        } finally {
            finishAndResumePorcupine()
        }
    }

    private fun finishAndResumePorcupine() {
        isAwake = false
        updateNotification(STATE_LISTENING)
        broadcast(STATE_LISTENING, "")
        resumeWakeWord()
    }

    private fun resumeWakeWord() {
        wakeWordJob?.cancel()
        wakeWordJob = serviceScope.launch {
            try {
                wakeWordDetector.start { onWakeWordDetected() }
            } catch (e: CancellationException) {
                throw e  // expected when job is cancelled intentionally
            } catch (e: Exception) {
                Log.e(TAG, "Wake word loop restart error", e)
                stopSelf()
            }
        }
    }

    // ─── TTS ──────────────────────────────────────────────────────────────────

    private fun initTts() {
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.FRENCH
                Log.d(TAG, "TTS initialised")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    // ─── Notifications ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(state: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val (title, text) = when (state) {
            STATE_AWAKE      -> Pair(getString(R.string.app_name), getString(R.string.notification_text_awake))
            STATE_PROCESSING -> Pair(getString(R.string.app_name), getString(R.string.notification_text_processing))
            "paused"         -> Pair(getString(R.string.app_name), getString(R.string.notification_text_paused))
            else             -> Pair(getString(R.string.notification_title_listening), getString(R.string.notification_text_listening))
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(state: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(state))
    }

    // ─── Broadcasts to UI ─────────────────────────────────────────────────────

    private fun broadcast(state: String, text: String) {
        val intent = Intent(BROADCAST_ACTION).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_TEXT, text)
        }
        sendBroadcast(intent)
    }
}
