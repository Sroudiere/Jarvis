package com.jarvis.app

/**
 * Maps each voice use-case to its Google Sheets spreadsheet ID.
 *
 * HOW TO CONFIGURE
 * ─────────────────
 * 1. Open the target Google Sheet in a browser.
 * 2. Copy the spreadsheet ID from the URL:
 *    https://docs.google.com/spreadsheets/d/<SPREADSHEET_ID>/edit
 * 3. Fill in the sheetId below.
 * 4. Add any French keywords the user might say to refer to this use-case.
 *    Jarvis will pick the right sheet automatically based on context.
 *
 * ADD A NEW USE-CASE: copy one of the UseCase(...) blocks, fill in your values.
 */
object UseCaseConfig {

    data class UseCase(
        /** Human-readable label injected into the system prompt */
        val label: String,
        /** Google Sheets spreadsheet ID (from the URL) */
        val sheetId: String,
        /** Keywords the user might say — helps GPT pick the right sheet */
        val keywords: List<String>,
        /** Optional: name of the tab inside the spreadsheet (default: "Sheet1") */
        val sheetTab: String = "Sheet1"
    )

    // ── Add / edit your use-cases here ───────────────────────────────────────

    val all: List<UseCase> = listOf(

        UseCase(
            label = "Suivi du poids",
            sheetId = "REPLACE_WITH_YOUR_WEIGHT_SHEET_ID",
            keywords = listOf("kg", "kilo", "kilos", "poids", "pesé", "peser", "balance", "masse"),
            sheetTab = "Sheet1"
        ),

        UseCase(
            label = "Temps de trajet",
            sheetId = "REPLACE_WITH_YOUR_DRIVING_SHEET_ID",
            keywords = listOf(
                "trajet", "route", "conduite", "conduire", "voiture",
                "départ", "arrivée", "arrivé", "parti", "minutes", "durée"
            ),
            sheetTab = "Sheet1"
        )

        // Example of a third use-case — uncomment and fill in to activate:
        // UseCase(
        //     label = "Journal alimentaire",
        //     sheetId = "REPLACE_WITH_YOUR_FOOD_SHEET_ID",
        //     keywords = listOf("mangé", "repas", "calories", "déjeuner", "dîner", "petit-déjeuner"),
        //     sheetTab = "Sheet1"
        // )
    )
}
