package com.jarvis.app.llm.models

import com.google.gson.annotations.SerializedName

// ─── Google Drive file listing ────────────────────────────────────────────────

data class DriveFileList(
    val files: List<DriveFile>
)

data class DriveFile(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerializedName("modifiedTime") val modifiedTime: String? = null
)

// ─── Google Sheets API ────────────────────────────────────────────────────────

data class ValueRange(
    val range: String,
    @SerializedName("majorDimension") val majorDimension: String = "ROWS",
    val values: List<List<String>>? = null
)

data class AppendResponse(
    val spreadsheetId: String,
    val tableRange: String?,
    val updates: UpdatedData?
)

data class UpdatedData(
    val updatedRange: String,
    val updatedRows: Int,
    val updatedColumns: Int,
    val updatedCells: Int
)

data class BatchUpdateRequest(
    val requests: List<SheetRequest>
)

data class SheetRequest(
    @SerializedName("updateSpreadsheetProperties") val updateProps: UpdateSpreadsheetProperties? = null
)

data class UpdateSpreadsheetProperties(
    val properties: SpreadsheetProperties,
    val fields: String
)

data class SpreadsheetProperties(
    val title: String? = null,
    val description: String? = null      // Note: description lives on the Drive file, not the sheet itself
)

// ─── Drive file metadata update ───────────────────────────────────────────────

data class DriveFileUpdate(
    val description: String
)
