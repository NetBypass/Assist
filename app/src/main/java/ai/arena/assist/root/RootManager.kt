package ai.arena.assist.root

import android.content.Context
import java.util.concurrent.TimeUnit

object RootManager {
    data class Result(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean = false
    ) {
        val successful: Boolean get() = exitCode == 0 && !timedOut
        fun asToolText(): String = buildString {
            append("exit_code=").append(exitCode)
            if (timedOut) append(" timed_out=true")
            if (output.isNotBlank()) append('\n').append(output.trim())
        }
    }

    fun isRootAvailable(): Boolean = run("id", 8).let {
        it.successful && it.output.contains("uid=0")
    }

    fun run(command: String, timeoutSeconds: Int = 30): Result {
        val process = try {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (error: Exception) {
            return Result(-1, "Unable to start su: ${error.message.orEmpty()}")
        }

        val output = StringBuilder()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { stream ->
                    val buffer = CharArray(2048)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (output.length < MAX_OUTPUT) {
                            val allowed = minOf(count, MAX_OUTPUT - output.length)
                            output.append(buffer, 0, allowed)
                        }
                    }
                }
            }
        }, "assist-root-output").apply { start() }

        val finished = runCatching {
            process.waitFor(timeoutSeconds.coerceIn(1, 120).toLong(), TimeUnit.SECONDS)
        }.getOrDefault(false)
        if (!finished) process.destroyForcibly()
        reader.join(1_500)

        if (output.length >= MAX_OUTPUT) output.append("\n[output truncated]")
        return Result(
            exitCode = if (finished) process.exitValue() else -1,
            output = output.toString(),
            timedOut = !finished
        )
    }

    fun configureHeadless(context: Context): Result {
        val packageName = context.packageName
        val voiceComponent = "$packageName/$packageName.voice.AssistVoiceInteractionService"
        val command = """
            pm grant ${shellQuote(packageName)} android.permission.RECORD_AUDIO 2>/dev/null || true
            pm grant ${shellQuote(packageName)} android.permission.POST_NOTIFICATIONS 2>/dev/null || true
            pm grant ${shellQuote(packageName)} android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true
            cmd deviceidle whitelist +${shellQuote(packageName)} 2>/dev/null || true
            cmd appops set ${shellQuote(packageName)} RUN_IN_BACKGROUND allow 2>/dev/null || true
            cmd appops set ${shellQuote(packageName)} RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
            cmd appops set ${shellQuote(packageName)} SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
            cmd role add-role-holder --user 0 android.app.role.ASSISTANT ${shellQuote(packageName)} 2>/dev/null || true
            settings put secure assistant ${shellQuote(voiceComponent)} 2>/dev/null || true
            settings put secure voice_interaction_service ${shellQuote(voiceComponent)} 2>/dev/null || true
            echo "Root permissions, battery whitelist, and default assistant role configured."
        """.trimIndent()
        return run(command, 30)
    }

    fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private const val MAX_OUTPUT = 100_000
}
