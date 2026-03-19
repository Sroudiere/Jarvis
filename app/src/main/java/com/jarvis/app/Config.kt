package com.jarvis.app

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object Config {

    val PICOVOICE_ACCESS_KEY: String get() = BuildConfig.PICOVOICE_ACCESS_KEY
    val OPENAI_API_KEY: String get() = BuildConfig.OPENAI_API_KEY

    const val OPENAI_MODEL = "gpt-4o"
    const val OPENAI_BASE_URL = "https://api.openai.com/v1/"

    const val SHEETS_BASE_URL = "https://sheets.googleapis.com/v4/"
    const val DRIVE_BASE_URL = "https://www.googleapis.com/"

    /** OAuth scopes requested from the user during Google Sign-In */
    val GOOGLE_SCOPES = listOf(
        "https://www.googleapis.com/auth/spreadsheets",
        "https://www.googleapis.com/auth/drive.metadata"
    )

    fun buildSystemPrompt(): String {
        val now = LocalDateTime.now()
        val dateStr = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy"))
        val timeStr = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        return """
            You are Jarvis, a personal voice assistant with access to the user's Google Sheets.
            Current date: $dateStr  Current time: $timeStr

            Your job is to update and query the user's spreadsheets based on voice commands.

            Workflow:
            1. Understand what the user wants to record or query.
            2. Use list_spreadsheets to find the right sheet (use the description to identify it).
            3. Use read_sheet to understand the existing structure (headers, last row, etc.).
            4. Use append_row or update_row as appropriate.
            5. If the spreadsheet description needs updating, call update_spreadsheet_description.
            6. Confirm what you did in a short, conversational sentence (you will be read aloud).

            Guidelines:
            - Be concise; your response will be spoken via TTS.
            - Use the current date/time automatically — never ask the user for it.
            - Dates in sheets: use DD/MM/YYYY format unless the sheet already uses another format.
            - Times in sheets: use HH:MM (24h) format.
            - If you cannot identify the right spreadsheet, list them and ask the user to clarify.
        """.trimIndent()
    }
}
