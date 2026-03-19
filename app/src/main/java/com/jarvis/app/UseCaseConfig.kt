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
        val sheetTab: String = "Sheet1",
        /**
         * Optional: extra instructions injected into the system prompt for this use case.
         * Use this to describe the sheet's column layout and any specific rules the LLM
         * should follow (e.g. "always fill the last empty arrival cell, never ask").
         */
        val customInstructions: String = ""
    )

    // ── Add / edit your use-cases here ───────────────────────────────────────

    val all: List<UseCase> = listOf(

        UseCase(
            label = "Suivi du poids",
            sheetId = "1M-0ru3dcHEkfl7YAAGe3hLI6DrWcLB1rZ7SdsFpLYcc",
            keywords = listOf("kg", "kilo", "kilos", "poids", "pesé", "peser", "balance", "masse"),
            sheetTab = "Sheet1"
        ),

        UseCase(
            label = "Temps de trajet",
            sheetId = "1QhxqcRA_gzxaz1GyvAwW0bfuHLP87QffGgQT32FJTS8",
            keywords = listOf(
                "trajet", "route", "conduite", "conduire", "voiture",
                "départ", "arrivée", "arrivé", "parti", "minutes", "durée"
            ),
            sheetTab = "Sheet1",
            customInstructions = """
                Colonnes : Date | Départ | Arrivée | Durée (calculée automatiquement).
                Règles :
                - Heure de départ → ajoute une nouvelle ligne avec la date du jour et l'heure de départ ; laisse Arrivée et Durée vides.
                - Heure d'arrivée → trouve la DERNIÈRE ligne dont la colonne Arrivée est vide et remplis-la. Ne demande JAMAIS à l'utilisateur quelle ligne modifier.
                - Si toutes les lignes ont déjà une arrivée, ajoute une nouvelle ligne avec uniquement l'arrivée.
            """.trimIndent()
        )

        UseCase(
            label = "Liste de courses",
            sheetId = "REPLACE_WITH_YOUR_GROCERY_SHEET_ID",
            keywords = listOf(
                "courses", "course", "liste", "acheter", "achète", "achat",
                "supermarché", "marché", "épicerie", "rajoute", "ajoute",
                "manque", "faut", "besoin"
            ),
            sheetTab = "Sheet1",
            customInstructions = """
                Structure : colonne A uniquement, chaque ligne est un article (ex. "3 Oignons", "Farine", "Tomates").
                Règles :
                - L'utilisateur peut dicter un ou plusieurs articles en une seule commande (ex. "ajoute du lait et des œufs").
                - Ajoute chaque article sur une nouvelle ligne séparée via append_row.
                - Formule les articles de façon naturelle : inclus la quantité si mentionnée, sinon juste le nom.
                - Ne demande jamais de confirmation avant d'ajouter.
                - Confirme en listant les articles ajoutés (ex. "J'ai ajouté : lait, œufs.").
            """.trimIndent()
        )

        // Example of an additional use-case — uncomment and fill in to activate:
        // UseCase(
        //     label = "Journal alimentaire",
        //     sheetId = "REPLACE_WITH_YOUR_FOOD_SHEET_ID",
        //     keywords = listOf("mangé", "repas", "calories", "déjeuner", "dîner", "petit-déjeuner"),
        //     sheetTab = "Sheet1"
        // )
    )
}
