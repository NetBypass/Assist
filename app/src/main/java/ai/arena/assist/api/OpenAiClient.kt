package ai.arena.assist.api

import ai.arena.assist.data.AssistSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenAiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    data class Completion(val message: JSONObject)

    fun complete(
        settings: AssistSettings,
        messages: List<JSONObject>,
        tools: JSONArray
    ): Completion {
        val payload = JSONObject()
            .put("model", settings.model)
            .put("messages", JSONArray(messages))
            .put("tools", tools)
            .put("tool_choice", "auto")
            .put("temperature", 0.2)

        val request = requestBuilder(
            EndpointNormalizer.chatCompletionsUrl(settings.endpoint),
            settings.apiKey
        ).post(
            payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        ).build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(apiError(response.code, body))
            }
            val root = runCatching { JSONObject(body) }
                .getOrElse { throw IOException("The endpoint returned invalid JSON") }
            val choices = root.optJSONArray("choices")
                ?: throw IOException(root.optJSONObject("error")?.optString("message")
                    ?: "The endpoint returned no choices")
            if (choices.length() == 0) throw IOException("The endpoint returned no choices")
            val message = choices.getJSONObject(0).optJSONObject("message")
                ?: throw IOException("The endpoint response has no assistant message")
            return Completion(message)
        }
    }

    fun listModels(endpoint: String, apiKey: String): List<String> {
        val request = requestBuilder(EndpointNormalizer.modelsUrl(endpoint), apiKey).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(apiError(response.code, body))
            val data = runCatching { JSONObject(body).optJSONArray("data") }
                .getOrNull() ?: throw IOException("Connected, but /models returned an unsupported response")
            return buildList {
                for (index in 0 until data.length()) {
                    data.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }.sorted()
        }
    }

    private fun requestBuilder(url: String, apiKey: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Assist-Android/0.1")
            .apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            }
    }

    private fun apiError(code: Int, body: String): String {
        val message = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty()
        return "API $code: ${message.ifBlank { body.take(500).ifBlank { "request failed" } }}"
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
