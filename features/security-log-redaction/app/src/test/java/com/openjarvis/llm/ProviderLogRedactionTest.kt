package com.openjarvis.llm

import com.openjarvis.llm.providers.GeminiRequestFactory
import com.openjarvis.mcp.MCPClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLogRedactionTest {
    // This src/test test runs under both testDebugUnitTest and testReleaseUnitTest.
    // HttpClient deliberately has no BuildConfig.DEBUG branch.
    @Test
    fun capturedHttpLogsExcludeSecretsUrlsHeadersPromptsAndResponses() {
        val server = MockWebServer()
        server.start()
        try {
            val apiKey = "SENTINEL_GEMINI_KEY_ISSUE14"
            val authorization = "Bearer SENTINEL_AUTHORIZATION_ISSUE14"
            val prompt = "Merhaba JARVIS, bu özel Türkçe istem loglara yazılmamalı."
            val responseBody = "SENTINEL_RESPONSE_BODY_ISSUE14"
            val capturedLogs = mutableListOf<String>()
            server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))

            val requestUrl = server.url("/private/generate?key=$apiKey")
            val request = Request.Builder()
                .url(requestUrl)
                .header("Authorization", authorization)
                .post(prompt.toRequestBody("text/plain".toMediaType()))
                .build()

            HttpClient.buildClient(logSink = { capturedLogs += it })
                .newCall(request)
                .execute()
                .use { response ->
                    assertEquals(200, response.code)
                    assertEquals(responseBody, response.body?.string())
                }

            val captured = capturedLogs.joinToString("\n")
            assertEquals(listOf("HTTP POST 200"), capturedLogs)
            listOf(
                apiKey,
                authorization,
                "Authorization",
                prompt,
                responseBody,
                requestUrl.toString(),
                "/private/generate"
            ).forEach { sensitive ->
                assertFalse("HTTP log leaked sensitive data: $sensitive", captured.contains(sensitive))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun mcpHttpClientUsesTheSameRedactedLoggingPolicy() {
        val server = MockWebServer()
        server.start()
        try {
            val apiKey = "SENTINEL_MCP_URL_KEY_ISSUE14"
            val authorization = "Bearer SENTINEL_MCP_AUTH_ISSUE14"
            val prompt = "Gizli MCP isteği: toplantı notlarını getir."
            val responseBody = "SENTINEL_MCP_RESPONSE_ISSUE14"
            val capturedLogs = mutableListOf<String>()
            server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))

            val requestUrl = server.url("/mcp?token=$apiKey")
            val request = Request.Builder()
                .url(requestUrl)
                .header("Authorization", authorization)
                .post(prompt.toRequestBody("text/plain".toMediaType()))
                .build()

            MCPClient.createHttpClient(capturedLogs::add)
                .newCall(request)
                .execute()
                .use { response ->
                    assertEquals(200, response.code)
                    assertEquals(responseBody, response.body?.string())
                }

            val captured = capturedLogs.joinToString("\n")
            assertEquals(listOf("HTTP POST 200"), capturedLogs)
            listOf(apiKey, authorization, prompt, responseBody, requestUrl.toString()).forEach {
                assertFalse("MCP log leaked sensitive data", captured.contains(it))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun geminiGenerateContentUsesHeaderAndNeverPlacesKeyInUrlOrBody() {
        val apiKey = "SENTINEL_GEMINI_HEADER_KEY_ISSUE14"
        val prompt = "Türkçe özel istem"
        val body = """{"contents":[{"parts":[{"text":"$prompt"}]}]}"""
        val request = GeminiRequestFactory.generateContentRequest(
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            model = "gemini-1.5-flash",
            apiKey = apiKey,
            requestBody = body
        )

        assertNull(request.url.query)
        assertEquals(apiKey, request.header("x-goog-api-key"))
        assertFalse(request.url.toString().contains(apiKey))
        val capturedBody = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertTrue(capturedBody.contains(prompt))
        assertFalse(capturedBody.contains(apiKey))
    }

    @Test
    fun geminiListModelsUsesHeaderAndNeverPlacesKeyInUrl() {
        val apiKey = "SENTINEL_GEMINI_MODELS_KEY_ISSUE14"
        val request = GeminiRequestFactory.listModelsRequest(
            "https://generativelanguage.googleapis.com/v1beta",
            apiKey
        )

        assertNull(request.url.query)
        assertEquals(apiKey, request.header("x-goog-api-key"))
        assertFalse(request.url.toString().contains(apiKey))
    }
}
