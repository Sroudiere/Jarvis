package com.jarvis.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import android.content.SharedPreferences
import com.jarvis.app.databinding.ActivityMainBinding
import com.jarvis.app.service.JarvisService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var prefs: SharedPreferences

    private var isServiceRunning = false

    // ─── Permission launchers ─────────────────────────────────────────────────

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (allGranted) {
            startJarvisService()
        } else {
            Toast.makeText(this, getString(R.string.permission_rationale), Toast.LENGTH_LONG).show()
        }
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(Exception::class.java)
            Log.d(TAG, "Signed in as ${account.email}")
            updateSignInButton()
            Toast.makeText(this, "Signed in as ${account.email}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Sign-in failed", e)
            Toast.makeText(this, "Google sign-in failed", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Broadcast receiver ───────────────────────────────────────────────────

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(JarvisService.EXTRA_STATE) ?: return
            val text  = intent.getStringExtra(JarvisService.EXTRA_TEXT) ?: ""
            updateUiForState(state, text)
        }
    }

    // ─── Activity lifecycle ───────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(JarvisService.PREFS_NAME, Context.MODE_PRIVATE)
        setupGoogleSignIn()
        setupButtons()
        updateSignInButton()
        setupPauseOnLockSwitch()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(JarvisService.BROADCAST_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(serviceReceiver)
    }

    // ─── Setup ────────────────────────────────────────────────────────────────

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(
                Scope("https://www.googleapis.com/auth/spreadsheets"),
                Scope("https://www.googleapis.com/auth/drive.metadata")
            )
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun setupButtons() {
        binding.btnToggle.setOnClickListener {
            if (isServiceRunning) {
                stopJarvisService()
            } else {
                checkPermissionsAndStart()
            }
        }

        binding.btnGoogleSignIn.setOnClickListener {
            val account = GoogleSignIn.getLastSignedInAccount(this)
            if (account != null) {
                // Already signed in → sign out
                googleSignInClient.signOut().addOnCompleteListener {
                    updateSignInButton()
                    Toast.makeText(this, "Signed out", Toast.LENGTH_SHORT).show()
                }
            } else {
                googleSignInLauncher.launch(googleSignInClient.signInIntent)
            }
        }
    }

    private fun setupPauseOnLockSwitch() {
        binding.switchPauseOnLock.isChecked =
            prefs.getBoolean(JarvisService.PREF_PAUSE_ON_LOCK, false)
        binding.switchPauseOnLock.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(JarvisService.PREF_PAUSE_ON_LOCK, checked).apply()
        }
    }

    // ─── Service control ──────────────────────────────────────────────────────

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startJarvisService()
        } else {
            micPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startJarvisService() {
        val intent = Intent(this, JarvisService::class.java).apply {
            action = JarvisService.ACTION_START
        }
        startForegroundService(intent)
        isServiceRunning = true
        binding.btnToggle.text = getString(R.string.btn_stop)
        updateUiForState(JarvisService.STATE_LISTENING, "")
    }

    private fun stopJarvisService() {
        val intent = Intent(this, JarvisService::class.java).apply {
            action = JarvisService.ACTION_STOP
        }
        stopService(intent)
        isServiceRunning = false
        binding.btnToggle.text = getString(R.string.btn_start)
        updateUiForState(JarvisService.STATE_IDLE, "")
    }

    // ─── UI updates ───────────────────────────────────────────────────────────

    private fun updateUiForState(state: String, text: String) {
        val (statusText, dotColorRes) = when (state) {
            JarvisService.STATE_LISTENING  -> Pair(getString(R.string.status_listening),  R.color.status_listening)
            JarvisService.STATE_AWAKE      -> Pair(getString(R.string.status_awake),      R.color.status_awake)
            JarvisService.STATE_PROCESSING -> Pair(getString(R.string.status_processing), R.color.status_processing)
            JarvisService.STATE_ERROR      -> Pair("Error: $text",                        R.color.status_idle)
            else                           -> Pair(getString(R.string.status_idle),       R.color.status_idle)
        }

        binding.tvStatus.text = statusText
        setDotColor(dotColorRes)

        // Show transcript when processing
        if (state == JarvisService.STATE_PROCESSING && text.isNotBlank()) {
            binding.tvTranscript.text = "\"$text\""
            binding.tvTranscript.visibility = View.VISIBLE
        }

        // Show LLM response when back to listening (text contains the response)
        if (state == JarvisService.STATE_LISTENING && text.isNotBlank()) {
            binding.tvResponse.text = text
            binding.tvResponse.visibility = View.VISIBLE
        }

        if (state == JarvisService.STATE_IDLE) {
            binding.tvTranscript.visibility = View.GONE
            binding.tvResponse.visibility = View.GONE
        }
    }

    private fun setDotColor(colorRes: Int) {
        val color = ContextCompat.getColor(this, colorRes)
        val drawable = binding.viewStatusDot.background.mutate() as? GradientDrawable
            ?: return
        drawable.setColor(color)
    }

    private fun updateSignInButton() {
        val account = GoogleSignIn.getLastSignedInAccount(this)
        binding.btnGoogleSignIn.text = if (account != null) {
            getString(R.string.sign_out)
        } else {
            getString(R.string.sign_in_google)
        }
    }
}
