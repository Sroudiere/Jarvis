package com.jarvis.app.auth

import android.accounts.Account
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.jarvis.app.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages retrieving and caching a Google OAuth2 access token.
 *
 * The token is fetched via GoogleAuthUtil on a background thread.
 * GoogleAuthUtil caches it internally and refreshes automatically.
 */
class GoogleAuthManager(private val context: Context) {

    companion object {
        private const val TAG = "GoogleAuthManager"
        private const val PREFS_NAME = "jarvis_prefs"
        private const val KEY_ACCOUNT_EMAIL = "google_account_email"

        private val SCOPE = "oauth2:${Config.GOOGLE_SCOPES.joinToString(" ")}"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns true if the user has previously signed in. */
    fun isSignedIn(): Boolean =
        GoogleSignIn.getLastSignedInAccount(context) != null

    /**
     * Returns a valid access token for Google APIs, refreshing it if needed.
     * Must be called from a coroutine (runs on IO dispatcher).
     * Returns null if the user is not signed in.
     */
    suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        try {
            val account = GoogleSignIn.getLastSignedInAccount(context)
                ?: return@withContext null
            val androidAccount = Account(account.email, "com.google")
            GoogleAuthUtil.getToken(context, androidAccount, SCOPE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get access token", e)
            null
        }
    }

    /** Invalidates the cached token so the next call to getAccessToken() fetches a fresh one. */
    suspend fun invalidateToken() = withContext(Dispatchers.IO) {
        try {
            val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext
            val androidAccount = Account(account.email, "com.google")
            val token = GoogleAuthUtil.getToken(context, androidAccount, SCOPE)
            GoogleAuthUtil.clearToken(context, token)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to invalidate token", e)
        }
    }
}
