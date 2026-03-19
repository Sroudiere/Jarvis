package com.jarvis.app.tools

import android.util.Log
import com.jarvis.app.llm.models.Tool
import com.jarvis.app.llm.models.ToolCall

/**
 * Central registry of all tools available to the LLM.
 * Add more tool groups (e.g. CalendarTools, ReminderTools) here as the app grows.
 */
class ToolRegistry(
    private val sheetsTools: GoogleSheetsTools
) {
    companion object {
        private const val TAG = "ToolRegistry"
    }

    /** Returns the tool definitions (JSON schema) to be passed in the OpenAI request. */
    fun getToolDefinitions(): List<Tool> = sheetsTools.definitions

    /**
     * Executes a tool call from the LLM and returns the result as a JSON string.
     * This is called in the LLM tool-calling loop.
     */
    suspend fun execute(toolCall: ToolCall): String {
        val name = toolCall.function.name
        val args = toolCall.function.arguments
        Log.d(TAG, "Executing tool '$name' with args: $args")

        return when {
            sheetsTools.definitions.any { it.function.name == name } ->
                sheetsTools.execute(name, args)

            else -> {
                Log.w(TAG, "No handler for tool: $name")
                """{"error": "Tool '$name' is not implemented"}"""
            }
        }
    }
}
