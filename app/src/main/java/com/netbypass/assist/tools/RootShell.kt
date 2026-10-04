package com.netbypass.assist.tools

import com.netbypass.assist.util.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class ShellResult(val exitCode: Int, val output: String, val error: String) {
    val success: Boolean get() = exitCode == 0
}

/**
 * Keeps a single persistent `su` process open and pipes commands into it, which is both faster
 * and more reliable than spawning a new `su -c "..."` process per call (fewer root prompts,
 * fewer race conditions with Magisk/SuperSU UI grant dialogs).
 *
 * Every command is wrapped in a brace group with its stderr merged into stdout and terminated
 * with a unique sentinel line carrying the exit code, so [exec] can reliably tell where one
 * command's output ends even when the command itself prints multiple lines or nothing at all.
 */
class RootShell {

    @Volatile private var process: Process? = null
    @Volatile private var stdin: OutputStream? = null
    @Volatile private var stdoutReader: BufferedReader? = null
    private val busy = AtomicBoolean(false)
    @Volatile var rootGranted: Boolean = false
        private set

    @Synchronized
    fun isAlive(): Boolean {
        val p = process ?: return false
        return try {
            p.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }

    @Synchronized
    fun open(): Boolean {
        if (isAlive()) return rootGranted
        return try {
            val p = ProcessBuilder("su").redirectErrorStream(false).start()
            process = p
            stdin = p.outputStream
            stdoutReader = BufferedReader(InputStreamReader(p.inputStream))
            // Probe for actual root (uid=0); `su` may exist but the grant may be denied.
            val probe = execInternal("id", 10_000)
            rootGranted = probe.output.contains("uid=0")
            if (!rootGranted) {
                Logger.w("RootShell", "su started but root was not granted: ${probe.output} ${probe.error}")
            } else {
                Logger.i("RootShell", "Root shell acquired: ${probe.output}")
            }
            rootGranted
        } catch (e: Exception) {
            Logger.e("RootShell", "Unable to start su", e)
            rootGranted = false
            false
        }
    }

    @Synchronized
    fun close() {
        try {
            stdin?.write("exit\n".toByteArray())
            stdin?.flush()
        } catch (_: Exception) {
        }
        try { process?.destroy() } catch (_: Exception) {
        }
        process = null
        stdin = null
        stdoutReader = null
        rootGranted = false
    }

    /** Public entry point: ensures the shell is open, then runs [command], serialized. */
    @Synchronized
    fun exec(command: String, timeoutMs: Long = 20_000): ShellResult {
        if (!isAlive()) {
            if (!open()) return ShellResult(-1, "", "Root access not granted (su unavailable or denied)")
        }
        return execInternal(command, timeoutMs)
    }

    private fun execInternal(command: String, timeoutMs: Long): ShellResult {
        val inStream = stdin
        val reader = stdoutReader
        if (inStream == null || reader == null) return ShellResult(-1, "", "Shell not open")

        val marker = "__ASSIST_DONE_${UUID.randomUUID().toString().replace("-", "")}__"
        val wrapped = buildString {
            append("{\n")
            append(command)
            append("\n} 2>&1\n")
            append("echo \"$marker:$?\"\n")
        }

        return try {
            inStream.write(wrapped.toByteArray())
            inStream.flush()

            val output = StringBuilder()
            var exitCode = -1
            val deadline = System.currentTimeMillis() + timeoutMs
            var found = false
            while (System.currentTimeMillis() < deadline) {
                if (!reader.ready()) {
                    Thread.sleep(25)
                    continue
                }
                val line = reader.readLine() ?: break
                if (line.startsWith(marker)) {
                    exitCode = line.substringAfter(":").trim().toIntOrNull() ?: -1
                    found = true
                    break
                } else {
                    if (output.isNotEmpty()) output.append('\n')
                    output.append(line)
                }
            }
            if (!found) {
                ShellResult(-1, output.toString(), "Timed out waiting for shell output")
            } else {
                ShellResult(exitCode, output.toString(), "")
            }
        } catch (e: Exception) {
            Logger.e("RootShell", "exec failed for: $command", e)
            close()
            ShellResult(-1, "", "shell error: ${e.message}")
        }
    }

    companion object {
        /** Base64 round-trip avoids shell-quoting / injection issues for arbitrary file content. */
        fun b64(text: String): String =
            android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

        fun unb64(text: String): String =
            String(android.util.Base64.decode(text.trim(), android.util.Base64.NO_WRAP), Charsets.UTF_8)

        fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"
    }
}
