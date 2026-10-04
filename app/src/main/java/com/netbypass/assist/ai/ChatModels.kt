package com.netbypass.assist.ai

import org.json.JSONArray
import org.json.JSONObject

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

/**
 * A single OpenAI chat message. [toolCalls] is populated on assistant messages that invoked
 * tools; [toolCallId] + [name] identify which call a role="tool" message is answering.
 */
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val name: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        // content must be present (can be null/empty string) for most servers; use JSONObject.NULL when absent.
        put("content", content ?: if (toolCalls != null) JSONObject.NULL else "")
        if (!toolCallId.isNullOrEmpty()) put("tool_call_id", toolCallId)
        if (!name.isNullOrEmpty()) put("name", name)
        if (!toolCalls.isNullOrEmpty()) {
            put("tool_calls", JSONArray().apply {
                toolCalls.forEach { tc ->
                    put(JSONObject().apply {
                        put("id", tc.id)
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", tc.name)
                            put("arguments", tc.argumentsJson)
                        })
                    })
                }
            })
        }
    }

    companion object {
        fun system(text: String) = ChatMessage(role = "system", content = text)
        fun user(text: String) = ChatMessage(role = "user", content = text)
        fun toolResult(toolCallId: String, name: String, content: String) =
            ChatMessage(role = "tool", content = content, toolCallId = toolCallId, name = name)
    }
}

/** Result of one call to the chat completions endpoint. */
sealed class ChatResult {
    data class Text(val content: String) : ChatResult()
    data class ToolCalls(val calls: List<ToolCall>) : ChatResult()
    data class Error(val message: String) : ChatResult()
}

object ChatParser {

    fun parseToolCalls(message: JSONObject): List<ToolCall>? {
        val arr = message.optJSONArray("tool_calls") ?: return null
        if (arr.length() == 0) return null
        val result = mutableListOf<ToolCall>()
        for (i in 0 until arr.length()) {
            val call = arr.getJSONObject(i)
            val fn = call.optJSONObject("function") ?: continue
            result.add(
                ToolCall(
                    id = call.optString("id", "call_$i"),
                    name = fn.optString("name"),
                    argumentsJson = fn.optString("arguments", "{}")
                )
            )
        }
        return result
    }

    fun parseChatResponse(body: String): ChatResult {
        return try {
            val root = JSONObject(body)
            if (root.has("error")) {
                val err = root.get("error")
                val msg = if (err is JSONObject) err.optString("message", err.toString()) else err.toString()
                return ChatResult.Error(msg)
            }
            val choices = root.optJSONArray("choices") ?: return ChatResult.Error("No choices in response: $body")
            if (choices.length() == 0) return ChatResult.Error("Empty choices array")
            val message = choices.getJSONObject(0).optJSONObject("message") ?: return ChatResult.Error("No message in choice")
            val toolCalls = parseToolCalls(message)
            if (toolCalls != null) {
                ChatResult.ToolCalls(toolCalls)
            } else {
                val content = message.optString("content", "")
                ChatResult.Text(content)
            }
        } catch (e: Exception) {
            ChatResult.Error("Failed to parse response: ${e.message}")
        }
    }
}
