package com.openjarvis.llm.providers

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Builds native Gemini REST requests without placing credentials in URL query parameters.
 * The native generateContent and models endpoints support x-goog-api-key.
 */
internal object GeminiRequestFactory {
    fun generateContentRequest(
        baseUrl: String,
        model: String,
        apiKey: String,
        requestBody: String
    ): Request = Request.Builder()
        .url("$baseUrl/models/$model:generateContent")
        .header("x-goog-api-key", apiKey)
        .header("Content-Type", "application/json")
        .post(requestBody.toRequestBody("application/json".toMediaType()))
        .build()

    fun listModelsRequest(baseUrl: String, apiKey: String): Request =
        Request.Builder()
            .url("$baseUrl/models")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
}
