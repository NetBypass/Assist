package com.netbypass.assist.ai

import com.netbypass.assist.SettingsStore
import com.netbypass.assist.tools.DangerousActions
import com.netbypass.assist.tools.ToolRegistry
import com.netbypass.assist.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val SYSTEM_PROMPT = """
You are "Assist", a friendly, concise voice assistant running directly on a rooted Android
device, similar in spirit to Google Assistant or Siri but with full local device control.
You speak your replies out loud through text-to-speech, so keep answers short and
conversational (1-3 sentences) unless the user asks for detail.

You have tools to control this Android device: Wi-Fi, Bluetooth, mobile data, airplane mode,
volume, brightness, screen power, headless/smart-speaker mode, launching/stopping/installing/
uninstalling apps, reading/writing files, taking screenshots, battery/device/network info,
location, clipboard, vibration, flashlight, opening URLs, rebooting/shutting down, media
playback control, raw settings, and an open-ended root shell command tool for anything else.

Rules:
- When the user asks you to do something on the device, call the appropriate tool rather than
  just describing how. Prefer the specific tool over the generic shell tool when one exists.
- For destructive or hard-to-undo actions (reboot, shutdown, uninstalling apps, wiping data,
  disabling system UI, dangerous shell commands) the app itself will ask the user to confirm
  out loud before running them — you do not need to ask twice, just call the tool.
- After a tool result comes back, summarize what happened briefly and naturally, as you would
  speak it, not as raw JSON or logs.
- If a tool fails, explain briefly what went wrong and suggest a next step (e.g. granting root).
- Never invent device state; always use a tool to check battery, network, apps, files, etc.
- Keep spoken replies free of markdown, bullet points, or code blocks.
"""

/**
 * Owns the conversation history and the "ask model -> run tools -> ask model again" loop
 * against an OpenAI-compatible endpoint. Voice I/O is injected via [confirmCallback] so this
 * class has no dependency on Android speech APIs and stays easy to reason about.
 */
class ConversationManager(
    private val settings: SettingsStore,
    private val toolRegistry: ToolRegistry,
    private val confirmCallback: suspend (question: String) -> Boolean
) {
    private val client = OpenAiClient()
    private val history = mutableListOf(ChatMessage.system(SYSTEM_PROMPT.trim()))

    @Synchronized
    fun reset() {
        history.clear()
        history.add(ChatMessage.system(SYSTEM_PROMPT.trim()))
    }

    suspend fun fetchModels(): Result<List<String>> =
        client.listModels(settings.baseUrl, settings.apiKey)

    /** Feeds one recognized user utterance through the tool-calling loop and returns the final spoken reply. */
    suspend fun handleUserUtterance(text: String): String {
        if (text.isBlank()) return ""
        synchronized(this) { history.add(ChatMessage.user(text)) }
        trimHistory()

        var guard = 0
        while (guard++ < 6) {
            val snapshot = synchronized(this) { history.toList() }
            val result = client.chatCompletion(
                baseUrl = settings.baseUrl,
                apiKey = settings.apiKey,
                model = settings.model,
                messages = snapshot,
                tools = ToolSchema.all()
            )

            when (result) {
                is ChatResult.Text -> {
                    synchronized(this) { history.add(ChatMessage(role = "assistant", content = result.content)) }
                    return result.content.ifBlank { "Done." }
                }

                is ChatResult.ToolCalls -> {
                    synchronized(this) {
                        history.add(ChatMessage(role = "assistant", content = null, toolCalls = result.calls))
                    }
                    for (call in result.calls) {
                        val args = try { JSONObject(call.argumentsJson) } catch (e: Exception) { JSONObject() }
                        val needsConfirm = settings.confirmDangerous && DangerousActions.isDangerous(call.name, call.argumentsJson)
                        val resultJson: JSONObject = if (needsConfirm) {
                            val confirmed = confirmCallback(confirmationQuestion(call.name, args))
                            if (confirmed) {
                                Logger.i("Conversation", "Confirmed dangerous tool ${call.name}")
                                withContext(Dispatchers.IO) { toolRegistry.execute(call.name, args) }
                            } else {
                                Logger.i("Conversation", "User declined ${call.name}")
                                JSONObject().put("ok", false).put("error", "User declined to confirm this action.")
                            }
                        } else {
                            withContext(Dispatchers.IO) { toolRegistry.execute(call.name, args) }
                        }
                        synchronized(this) {
                            history.add(ChatMessage.toolResult(call.id, call.name, resultJson.toString()))
                        }
                    }
                    // loop again so the model can respond to the tool result(s)
                }

                is ChatResult.Error -> {
                    Logger.e("Conversation", "Chat error: ${result.message}")
                    return "Sorry, I had trouble reaching the AI service. ${result.message}"
                }
            }
        }
        return "Sorry, that required too many steps. Please try a simpler request."
    }

    private fun confirmationQuestion(toolName: String, args: JSONObject): String {
        val readable = toolName.replace('_', ' ')
        return "Are you sure you want me to $readable? Say yes to confirm, or no to cancel."
    }

    @Synchronized
    private fun trimHistory() {
        val maxMessages = 40
        if (history.size > maxMessages) {
            val system = history.first()
            val tail = history.takeLast(maxMessages - 1)
            history.clear()
            history.add(system)
            history.addAll(tail)
        }
    }
}
