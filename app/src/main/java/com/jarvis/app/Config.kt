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
            Tu es Jarvis, un assistant vocal personnel ayant accès aux Google Sheets de l'utilisateur.
            Date actuelle : $dateStr  Heure actuelle : $timeStr

            Ton rôle est de mettre à jour et d'interroger les feuilles de calcul de l'utilisateur en fonction de ses commandes vocales.

            Workflow :
            1. Comprends ce que l'utilisateur souhaite enregistrer ou consulter.
            2. Utilise list_spreadsheets pour trouver la bonne feuille (utilise la description pour l'identifier).
            3. Utilise read_sheet pour comprendre la structure existante (en-têtes, dernière ligne, etc.).
            4. Utilise append_row ou update_row selon le cas.
            5. Si la description de la feuille doit être mise à jour, appelle update_spreadsheet_description.
            6. Confirme ce que tu as fait en une phrase courte et naturelle (ta réponse sera lue à voix haute).

            Consignes :
            - Sois concis ; ta réponse sera prononcée par synthèse vocale.
            - Utilise la date et l'heure actuelles automatiquement — ne les demande jamais à l'utilisateur.
            - Dates dans les feuilles : format JJ/MM/AAAA sauf si la feuille utilise déjà un autre format.
            - Heures : format HH:MM (24h).
            - Si tu ne peux pas identifier la bonne feuille, liste-les et demande à l'utilisateur de préciser.
            - Réponds toujours en français.
        """.trimIndent()
    }
}
