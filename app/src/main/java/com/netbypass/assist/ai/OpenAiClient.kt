package com.netbypass.assist.ai

import com.netbypass.assist.util.Logger
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Minimal client for any OpenAI-compatible `/v1/chat/completions` and `/v1/models` endpoint.
 * Works with or without an API key (some self-hosted / tunneled endpoints are open).
 */
class OpenAiClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun authHeader(apiKey: String): String? =
        if (apiKey.isNotBlank()) "Bearer $apiKey" else null

    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> = suspendCoroutine { cont ->
        val url = "${baseUrl.trimEnd('/')}/models"
        val builder = Request.Builder().url(url).get()
        authHeader(apiKey)?.let { builder.addHeader("Authorization", it) }
        httpClient.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resume(Result.failure(e))
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use { resp ->
                    try {
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            cont.resume(Result.failure(IOException("HTTP ${resp.code}: $body")))
                            return
                        }
                        val root = JSONObject(body)
                        val data = root.optJSONArray("data") ?: JSONArray()
                        val ids = mutableListOf<String>()
                        for (i in 0 until data.length()) {
                            ids.add(data.getJSONObject(i).optString("id"))
                        }
                        cont.resume(Result.success(ids))
                    } catch (e: Exception) {
                        cont.resume(Result.failure(e))
                    }
                }
            }
        })
    }

    /**
     * Sends the full conversation + tool schema to the chat completions endpoint and returns a
     * parsed [ChatResult]. Non-streaming for simplicity and robustness across varied
     * OpenAI-compatible server implementations.
     */
    suspend fun chatCompletion(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        tools: JSONArray?,
        temperature: Double = 0.4
    ): ChatResult = suspendCoroutine { cont ->
        val url = "${baseUrl.trimEnd('/')}/chat/completions"
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
            if (tools != null && tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
            put("temperature", temperature)
            put("stream", false)
        }

        Logger.d("OpenAiClient", "POST $url model=$model messages=${messages.size}")

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(jsonMedia))
            .apply { authHeader(apiKey)?.let { addHeader("Authorization", it) } }
            .addHeader("Content-Type", "application/json")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Logger.e("OpenAiClient", "Request failed", e)
                cont.resume(ChatResult.Error("Network error: ${e.message}"))
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use { resp ->
                    val body = try {
                        resp.body?.string().orEmpty()
                    } catch (e: Exception) {
                        cont.resume(ChatResult.Error("Failed reading response: ${e.message}"))
                        return
                    }
                    if (!resp.isSuccessful) {
                        Logger.e("OpenAiClient", "HTTP ${resp.code}: ${body.take(500)}")
                        cont.resume(ChatResult.Error("Server returned HTTP ${resp.code}: ${body.take(300)}"))
                        return
                    }
                    cont.resume(ChatParser.parseChatResponse(body))
                }
            }
        })
    }
}
