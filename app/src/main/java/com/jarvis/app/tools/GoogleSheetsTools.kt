package com.jarvis.app.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jarvis.app.llm.models.Tool
import com.jarvis.app.llm.models.FunctionDefinition
import com.jarvis.app.sheets.GoogleSheetsClient

/**
 * Implements all Google Sheets / Drive tools that the LLM can call.
 *
 * Each tool is described by a JSON schema and has a matching suspend implementation.
 * Add new tools here — register them in [ToolRegistry].
 */
class GoogleSheetsTools(private val client: GoogleSheetsClient) {

    private val gson = Gson()

    // ─── Tool definitions (JSON schemas) ──────────────────────────────────────

    val definitions: List<Tool> = listOf(

        tool(
            name = "list_spreadsheets",
            description = "Lister tous les classeurs Google Sheets accessibles par l'utilisateur, avec leurs identifiants, noms et descriptions. Utilise les descriptions pour identifier le bon classeur.",
            parameters = params("""{}""")
        ),

        tool(
            name = "read_sheet",
            description = "Lire les données d'un classeur Google Sheets. Retourne les lignes sous forme de tableaux de chaînes. Utilise cela pour comprendre la structure existante (en-têtes, dernière ligne, format) avant d'écrire.",
            parameters = params("""{
                "spreadsheetId": {"type": "string", "description": "L'identifiant du classeur"},
                "range": {"type": "string", "description": "Plage en notation A1, ex. 'Sheet1' (feuille entière) ou 'Sheet1!A1:D20'. Par défaut : 'Sheet1'"}
            }""", required = listOf("spreadsheetId"))
        ),

        tool(
            name = "append_row",
            description = "Ajouter une nouvelle ligne à la fin d'une feuille. Utilise cela pour enregistrer une nouvelle entrée (ex. : une mesure de poids quotidienne, une nouvelle entrée de journal de trajet).",
            parameters = params("""{
                "spreadsheetId": {"type": "string", "description": "L'identifiant du classeur"},
                "sheetName": {"type": "string", "description": "Nom de l'onglet dans le classeur, ex. 'Sheet1'"},
                "values": {"type": "array", "items": {"type": "string"}, "description": "Les valeurs des cellules pour la nouvelle ligne, dans l'ordre des colonnes"}
            }""", required = listOf("spreadsheetId", "values"))
        ),

        tool(
            name = "update_cell_range",
            description = "Mettre à jour une ou plusieurs cellules dans une feuille existante. Utilise cela pour remplir une valeur manquante dans une ligne existante (ex. : l'heure d'arrivée quand l'heure de départ a déjà été enregistrée).",
            parameters = params("""{
                "spreadsheetId": {"type": "string", "description": "L'identifiant du classeur"},
                "range": {"type": "string", "description": "Cellule ou plage en notation A1 à mettre à jour, ex. 'Sheet1!C5'"},
                "values": {"type": "array", "items": {"type": "array", "items": {"type": "string"}}, "description": "Tableau 2D de valeurs (lignes × colonnes)"}
            }""", required = listOf("spreadsheetId", "range", "values"))
        ),

        tool(
            name = "update_spreadsheet_description",
            description = "Mettre à jour la description metadata d'un fichier Google Sheets. Utilise cela pour garder la description à jour afin que les futures commandes vocales puissent identifier le classeur.",
            parameters = params("""{
                "spreadsheetId": {"type": "string", "description": "L'identifiant du classeur (ID du fichier dans Drive)"},
                "description": {"type": "string", "description": "La nouvelle description du classeur"}
            }""", required = listOf("spreadsheetId", "description"))
        )
    )

    // ─── Tool implementations ─────────────────────────────────────────────────

    suspend fun execute(name: String, argsJson: String): String {
        val args = JsonParser.parseString(argsJson).asJsonObject

        return when (name) {
            "list_spreadsheets" -> listSpreadsheets()
            "read_sheet" -> readSheet(args)
            "append_row" -> appendRow(args)
            "update_cell_range" -> updateCellRange(args)
            "update_spreadsheet_description" -> updateDescription(args)
            else -> """{"error": "Unknown tool: $name"}"""
        }
    }

    private suspend fun listSpreadsheets(): String {
        val files = client.listSpreadsheets()
        return gson.toJson(files.map { mapOf("id" to it.id, "name" to it.name, "description" to (it.description ?: "")) })
    }

    private suspend fun readSheet(args: JsonObject): String {
        val id = args.get("spreadsheetId").asString
        val range = args.get("range")?.asString ?: "Sheet1"
        val rows = client.readSheet(id, range)
        return gson.toJson(mapOf("rows" to rows, "rowCount" to rows.size))
    }

    private suspend fun appendRow(args: JsonObject): String {
        val id = args.get("spreadsheetId").asString
        val sheet = args.get("sheetName")?.asString ?: "Sheet1"
        val values = args.getAsJsonArray("values").map { it.asString }
        val ok = client.appendRow(id, sheet, values)
        return if (ok) """{"success": true}""" else """{"success": false, "error": "Append failed"}"""
    }

    private suspend fun updateCellRange(args: JsonObject): String {
        val id = args.get("spreadsheetId").asString
        val range = args.get("range").asString
        val rawValues = args.getAsJsonArray("values")
        val values = rawValues.map { row ->
            row.asJsonArray.map { it.asString }
        }
        val ok = client.updateRange(id, range, values)
        return if (ok) """{"success": true}""" else """{"success": false, "error": "Update failed"}"""
    }

    private suspend fun updateDescription(args: JsonObject): String {
        val id = args.get("spreadsheetId").asString
        val desc = args.get("description").asString
        val ok = client.updateSpreadsheetDescription(id, desc)
        return if (ok) """{"success": true}""" else """{"success": false, "error": "Description update failed"}"""
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun tool(name: String, description: String, parameters: JsonObject) = Tool(
        function = FunctionDefinition(
            name = name,
            description = description,
            parameters = parameters
        )
    )

    private fun params(propertiesJson: String, required: List<String> = emptyList()): JsonObject {
        val obj = JsonObject()
        obj.addProperty("type", "object")
        obj.add("properties", JsonParser.parseString(propertiesJson).asJsonObject)
        if (required.isNotEmpty()) {
            val arr = com.google.gson.JsonArray()
            required.forEach { arr.add(it) }
            obj.add("required", arr)
        }
        return obj
    }
}
