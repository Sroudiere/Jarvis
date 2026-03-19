package com.jarvis.app.llm.models

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

// ─── Request models ───────────────────────────────────────────────────────────

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<Tool>? = null,
    @SerializedName("tool_choice") val toolChoice: String? = null,
    val temperature: Double = 0.2
)

data class ChatMessage(
    val role: String,                       // "system" | "user" | "assistant" | "tool"
    val content: String?,
    @SerializedName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerializedName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null
)

data class Tool(
    val type: String = "function",
    val function: FunctionDefinition
)

data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

// ─── Response models ──────────────────────────────────────────────────────────

data class ChatResponse(
    val id: String,
    val choices: List<Choice>,
    val usage: Usage?
)

data class Choice(
    val message: ChatMessage,
    @SerializedName("finish_reason") val finishReason: String
)

data class Usage(
    @SerializedName("prompt_tokens") val promptTokens: Int,
    @SerializedName("completion_tokens") val completionTokens: Int,
    @SerializedName("total_tokens") val totalTokens: Int
)

data class ToolCall(
    val id: String,
    val type: String,
    val function: FunctionCallData
)

data class FunctionCallData(
    val name: String,
    val arguments: String    // raw JSON string
)
