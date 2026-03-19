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
            description = "List all Google Sheets spreadsheets the user has access to, with their IDs, names, and descriptions. Use descriptions to identify the right sheet.",
            parameters = params("""{}""")
        ),

        tool(
            name = "read_sheet",
            description = "Read data from a Google Sheets spreadsheet. Returns rows as arrays of strings. Use this to understand the existing structure (headers, last row, format) before writing.",
            parameters = params("""{
                "spreadsheetId": {"type": "string", "description": "The spreadsheet ID from list_spreadsheets"},
                "range": {"type": "string", "description": "A1 notation range, e.g. 'Sheet1' (entire sheet) or 'Sheet1!A1:D20'. Default: 'Sheet1'"}
            }""", required = listOf("spreadsheetId"))
        ),

        tool(
            name = "append_row",
            description = "Append a new row at the end of a sheet. Use this to add a new entry (e.g. a daily weight measurement, a new travel log entry).",
            parameters = params("""{
                "spreadsheetId": {"type": "string", "description": "The spreadsheet ID"},
                "sheetName": {"type": "string", "description": "Tab name within the spreadsheet, e.g. 'Sheet1'"},
                "values": {"type": "array", "items": {"type": "string"}, "description": "The cell values for the new row, in column order"}
            }""", required = listOf("spreadsheetId", "values"))
        ),

        tool(
            name = "update_cell_range",
            description = "Update one or more cells in an existing sheet. Use this to fill in a missing value in an existing row (e.g. the arrival time when the departure time was already recorded).",
            parameters = params("""{
                "spreadsheetId": {"type": "string", "description": "The spreadsheet ID"},
                "range": {"type": "string", "description": "A1 notation for the cell or range to update, e.g. 'Sheet1!C5'"},
                "values": {"type": "array", "items": {"type": "array", "items": {"type": "string"}}, "description": "2-D array of values (rows × columns)"}
            }""", required = listOf("spreadsheetId", "range", "values"))
        ),

        tool(
            name = "update_spreadsheet_description",
            description = "Update the description metadata of a Google Sheets file. Use this to keep the description current so future voice commands can identify the spreadsheet.",
            parameters = params("""{
                "spreadsheetId": {"type": "string", "description": "The spreadsheet ID (file ID in Drive)"},
                "description": {"type": "string", "description": "The new description for the spreadsheet"}
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
