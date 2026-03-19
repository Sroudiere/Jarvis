package com.jarvis.app.sheets

import android.util.Log
import com.jarvis.app.Config
import com.jarvis.app.auth.GoogleAuthManager
import com.jarvis.app.llm.models.DriveFile
import com.jarvis.app.llm.models.DriveFileUpdate
import com.jarvis.app.llm.models.ValueRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Thin client around the Google Sheets and Drive REST APIs.
 * Injects the OAuth Bearer token from GoogleAuthManager into every request.
 */
class GoogleSheetsClient(private val authManager: GoogleAuthManager) {

    companion object {
        private const val TAG = "GoogleSheetsClient"
    }

    private suspend fun buildOkHttp(): OkHttpClient {
        val token = authManager.getAccessToken()
            ?: throw IllegalStateException("User is not signed into Google")

        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()
    }

    private suspend fun sheetsApi(): SheetsApiService {
        val client = buildOkHttp()
        return Retrofit.Builder()
            .baseUrl(Config.SHEETS_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SheetsApiService::class.java)
    }

    private suspend fun driveApi(): DriveApiService {
        val client = buildOkHttp()
        return Retrofit.Builder()
            .baseUrl(Config.DRIVE_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DriveApiService::class.java)
    }

    // ─── Drive operations ─────────────────────────────────────────────────────

    /** Returns all Google Sheets files with their id, name, and description. */
    suspend fun listSpreadsheets(): List<DriveFile> = withContext(Dispatchers.IO) {
        val response = driveApi().listSheets()
        if (response.isSuccessful) {
            response.body()?.files ?: emptyList()
        } else {
            Log.e(TAG, "listSheets failed: ${response.code()} ${response.errorBody()?.string()}")
            emptyList()
        }
    }

    /** Updates the description of a Google Drive file (i.e. the spreadsheet). */
    suspend fun updateSpreadsheetDescription(fileId: String, description: String): Boolean =
        withContext(Dispatchers.IO) {
            val response = driveApi().updateFile(fileId, DriveFileUpdate(description))
            if (!response.isSuccessful) {
                Log.e(TAG, "updateFile failed: ${response.code()} ${response.errorBody()?.string()}")
            }
            response.isSuccessful
        }

    // ─── Sheets operations ────────────────────────────────────────────────────

    /**
     * Reads all values from a sheet tab.
     * [range] example: "Sheet1" (entire sheet) or "Sheet1!A1:Z100"
     */
    suspend fun readSheet(spreadsheetId: String, range: String = "Sheet1"): List<List<String>> =
        withContext(Dispatchers.IO) {
            val response = sheetsApi().getValues(spreadsheetId, range)
            if (response.isSuccessful) {
                response.body()?.values ?: emptyList()
            } else {
                Log.e(TAG, "getValues failed: ${response.code()} ${response.errorBody()?.string()}")
                emptyList()
            }
        }

    /**
     * Appends a new row at the bottom of the sheet.
     * Returns true on success.
     */
    suspend fun appendRow(
        spreadsheetId: String,
        sheetName: String = "Sheet1",
        values: List<String>
    ): Boolean = withContext(Dispatchers.IO) {
        val body = ValueRange(
            range = sheetName,
            values = listOf(values)
        )
        val response = sheetsApi().appendValues(spreadsheetId, sheetName, body = body)
        if (!response.isSuccessful) {
            Log.e(TAG, "appendValues failed: ${response.code()} ${response.errorBody()?.string()}")
        }
        response.isSuccessful
    }

    /**
     * Updates a specific range in the sheet.
     * [range] example: "Sheet1!B5" or "Sheet1!A2:C2"
     * [values] is a 2-D list (rows × columns).
     */
    suspend fun updateRange(
        spreadsheetId: String,
        range: String,
        values: List<List<String>>
    ): Boolean = withContext(Dispatchers.IO) {
        val body = ValueRange(range = range, values = values)
        val response = sheetsApi().updateValues(spreadsheetId, range, body = body)
        if (!response.isSuccessful) {
            Log.e(TAG, "updateValues failed: ${response.code()} ${response.errorBody()?.string()}")
        }
        response.isSuccessful
    }
}
