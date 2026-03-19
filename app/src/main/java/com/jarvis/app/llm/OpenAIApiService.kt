package com.jarvis.app.llm

import com.jarvis.app.llm.models.ChatRequest
import com.jarvis.app.llm.models.ChatResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface OpenAIApiService {

    @POST("chat/completions")
    suspend fun chatCompletion(
        @Body request: ChatRequest
    ): Response<ChatResponse>
}
