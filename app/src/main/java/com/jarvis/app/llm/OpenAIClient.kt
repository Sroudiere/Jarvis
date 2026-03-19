package com.jarvis.app.llm

import android.util.Log
import com.google.gson.Gson
import com.jarvis.app.Config
import com.jarvis.app.llm.models.ChatMessage
import com.jarvis.app.llm.models.ChatRequest
import com.jarvis.app.tools.ToolRegistry
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Handles the full OpenAI tool-calling loop:
 *  1. Send user message + tool definitions
 *  2. If the model wants to call a tool → execute it → send result back
 *  3. Repeat until finish_reason == "stop"
 *  4. Return the final text response
 */
class OpenAIClient(private val toolRegistry: ToolRegistry) {

    companion object {
        private const val TAG = "OpenAIClient"
        private const val MAX_TOOL_ROUNDS = 8      // prevent infinite loops
    }

    private val gson = Gson()

    private val api: OpenAIApiService by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer ${Config.OPENAI_API_KEY}")
                    .addHeader("Content-Type", "application/json")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(Config.OPENAI_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenAIApiService::class.java)
    }

    /**
     * Process a voice command through the LLM with tool calling.
     * Returns the final assistant response text.
     */
    suspend fun processCommand(userText: String): String {
        val messages = mutableListOf(
            ChatMessage(role = "system", content = Config.buildSystemPrompt()),
            ChatMessage(role = "user", content = userText)
        )

        repeat(MAX_TOOL_ROUNDS) { round ->
            Log.d(TAG, "LLM round $round, messages: ${messages.size}")

            val response = api.chatCompletion(
                ChatRequest(
                    model = Config.OPENAI_MODEL,
                    messages = messages,
                    tools = toolRegistry.getToolDefinitions()
                )
            )

            if (!response.isSuccessful) {
                val err = response.errorBody()?.string()
                Log.e(TAG, "OpenAI error ${response.code()}: $err")
                return "Sorry, I encountered an error calling the AI service."
            }

            val choice = response.body()?.choices?.firstOrNull()
                ?: return "Sorry, I got an empty response."

            val assistantMessage = choice.message

            when (choice.finishReason) {
                "stop" -> {
                    return assistantMessage.content ?: "Done."
                }

                "tool_calls" -> {
                    // Add assistant message with tool_calls to conversation
                    messages.add(assistantMessage)

                    // Execute each tool call and add results
                    val toolCalls = assistantMessage.toolCalls ?: emptyList()
                    for (toolCall in toolCalls) {
                        Log.d(TAG, "Calling tool: ${toolCall.function.name} args=${toolCall.function.arguments}")
                        val result = try {
                            toolRegistry.execute(toolCall)
                        } catch (e: Exception) {
                            Log.e(TAG, "Tool ${toolCall.function.name} threw", e)
                            """{"error": "${e.message}"}"""
                        }
                        Log.d(TAG, "Tool result: $result")
                        messages.add(
                            ChatMessage(
                                role = "tool",
                                content = result,
                                toolCallId = toolCall.id
                            )
                        )
                    }
                    // Continue loop to send tool results back to LLM
                }

                else -> {
                    return assistantMessage.content ?: "Done."
                }
            }
        }

        return "I ran too many steps trying to fulfill your request."
    }
}
