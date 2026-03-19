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
        private const val MAX_TOOL_ROUNDS = 8           // prevent infinite loops
        private const val HISTORY_TIMEOUT_MS = 20_000L  // 20 seconds
    }

    private val gson = Gson()

    /** Messages from the last conversation (excludes system message). */
    private var previousMessages: List<ChatMessage> = emptyList()
    private var lastConversationEndTime: Long = 0L

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
     * If the previous conversation ended less than [HISTORY_TIMEOUT_MS] ago, its
     * messages are prepended so the LLM can answer follow-up questions in context.
     * Returns the final assistant response text.
     */
    suspend fun processCommand(userText: String): String {
        // Reuse history only if it is still fresh
        val history = previousMessages
            .takeIf { it.isNotEmpty() && System.currentTimeMillis() - lastConversationEndTime < HISTORY_TIMEOUT_MS }
            ?: emptyList()

        if (history.isNotEmpty()) {
            Log.d(TAG, "Resuming conversation with ${history.size} previous messages")
        }

        val messages = mutableListOf<ChatMessage>()
        messages.add(ChatMessage(role = "system", content = Config.buildSystemPrompt()))
        messages.addAll(history)
        messages.add(ChatMessage(role = "user", content = userText))

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
                return "Désolé, une erreur est survenue lors de l'appel au service IA."
            }

            val choice = response.body()?.choices?.firstOrNull()
                ?: return "Désolé, j'ai reçu une réponse vide."

            val assistantMessage = choice.message

            when (choice.finishReason) {
                "stop" -> {
                    // Save history (without system message) for potential follow-up
                    previousMessages = messages.drop(1) + assistantMessage
                    lastConversationEndTime = System.currentTimeMillis()
                    return assistantMessage.content ?: "Terminé."
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
                    previousMessages = messages.drop(1) + assistantMessage
                    lastConversationEndTime = System.currentTimeMillis()
                    return assistantMessage.content ?: "Terminé."
                }
            }
        }

        // Clear history on unresolved state
        previousMessages = emptyList()
        return "J'ai effectué trop d'étapes pour répondre à votre demande."
    }
}
