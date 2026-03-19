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

        val useCaseBlock = if (UseCaseConfig.all.isEmpty()) {
            "Aucun cas d'usage configuré — utilise list_spreadsheets pour identifier la bonne feuille."
        } else {
            UseCaseConfig.all.joinToString("\n\n") { uc ->
                val header = "- ${uc.label} → spreadsheetId=\"${uc.sheetId}\", onglet=\"${uc.sheetTab}\" (mots-clés : ${uc.keywords.joinToString(", ")})"
                if (uc.customInstructions.isNotBlank()) {
                    val indented = uc.customInstructions.lines().joinToString("\n") { "    $it" }
                    "$header\n$indented"
                } else {
                    header
                }
            }
        }

        return """
            Tu es Jarvis, un assistant vocal personnel ayant accès aux Google Sheets de l'utilisateur.
            Date actuelle : $dateStr  Heure actuelle : $timeStr

            CAS D'USAGE CONFIGURÉS (utilise directement ces spreadsheetId — n'appelle PAS list_spreadsheets) :
            $useCaseBlock

            Ton rôle est de mettre à jour et d'interroger les feuilles de calcul de l'utilisateur en fonction de ses commandes vocales.

            Workflow :
            1. Identifie le cas d'usage à partir des mots-clés de la commande vocale.
            2. Utilise le spreadsheetId correspondant directement — tu n'as pas besoin de list_spreadsheets.
            3. Utilise read_sheet pour comprendre la structure existante (en-têtes, dernière ligne, etc.).
            4. Utilise append_row ou update_cell_range selon le cas.
            5. Confirme ce que tu as fait en une phrase courte et naturelle (ta réponse sera lue à voix haute).

            Consignes :
            - Sois concis ; ta réponse sera prononcée par synthèse vocale.
            - Utilise la date et l'heure actuelles automatiquement — ne les demande jamais à l'utilisateur.
            - Dates dans les feuilles : format JJ/MM/AAAA sauf si la feuille utilise déjà un autre format.
            - Heures : format HH:MM (24h).
            - Si la commande ne correspond à aucun cas d'usage configuré, demande à l'utilisateur de préciser.
            - Réponds toujours en français.
        """.trimIndent()
    }
}
