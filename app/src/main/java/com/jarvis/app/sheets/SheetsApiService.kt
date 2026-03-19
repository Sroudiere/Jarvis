package com.jarvis.app.sheets

import com.jarvis.app.llm.models.AppendResponse
import com.jarvis.app.llm.models.DriveFile
import com.jarvis.app.llm.models.DriveFileList
import com.jarvis.app.llm.models.DriveFileUpdate
import com.jarvis.app.llm.models.ValueRange
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface SheetsApiService {

    /** Read values from a range (e.g. "Sheet1!A1:Z100") */
    @GET("spreadsheets/{spreadsheetId}/values/{range}")
    suspend fun getValues(
        @Path("spreadsheetId") spreadsheetId: String,
        @Path("range") range: String,
        @Query("majorDimension") majorDimension: String = "ROWS"
    ): Response<ValueRange>

    /** Append rows after the last row with data */
    @POST("spreadsheets/{spreadsheetId}/values/{range}:append")
    suspend fun appendValues(
        @Path("spreadsheetId") spreadsheetId: String,
        @Path("range") range: String,
        @Query("valueInputOption") valueInputOption: String = "USER_ENTERED",
        @Query("insertDataOption") insertDataOption: String = "INSERT_ROWS",
        @Body body: ValueRange
    ): Response<AppendResponse>

    /** Update a specific range */
    @retrofit2.http.PUT("spreadsheets/{spreadsheetId}/values/{range}")
    suspend fun updateValues(
        @Path("spreadsheetId") spreadsheetId: String,
        @Path("range") range: String,
        @Query("valueInputOption") valueInputOption: String = "USER_ENTERED",
        @Body body: ValueRange
    ): Response<ValueRange>
}

interface DriveApiService {

    /** List all Google Sheets files accessible by the user */
    @GET("drive/v3/files")
    suspend fun listSheets(
        @Query("q") query: String = "mimeType='application/vnd.google-apps.spreadsheet' and trashed=false",
        @Query("fields") fields: String = "files(id,name,description,modifiedTime)",
        @Query("orderBy") orderBy: String = "modifiedTime desc",
        @Query("pageSize") pageSize: Int = 100
    ): Response<DriveFileList>

    /** Get metadata for a single file (to read its description) */
    @GET("drive/v3/files/{fileId}")
    suspend fun getFile(
        @Path("fileId") fileId: String,
        @Query("fields") fields: String = "id,name,description,modifiedTime"
    ): Response<DriveFile>

    /** Update a file's metadata (e.g. description) */
    @PATCH("drive/v3/files/{fileId}")
    suspend fun updateFile(
        @Path("fileId") fileId: String,
        @Body body: DriveFileUpdate
    ): Response<DriveFile>
}
