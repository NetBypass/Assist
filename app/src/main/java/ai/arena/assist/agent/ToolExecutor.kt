package ai.arena.assist.agent

import ai.arena.assist.data.SettingsRepository
import ai.arena.assist.root.RootManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class ToolExecutor(private val context: Context) {
    fun definitions(): JSONArray = JSONArray().apply {
        put(tool("get_device_info", "Get Android, hardware, battery, root, and device state information."))
        put(tool("run_shell", "Execute a shell command as root. Use only when a narrower tool cannot do the job.", obj(
            "command" to string("Exact shell command to execute"),
            "timeout_seconds" to integer("Timeout from 1 to 120 seconds")
        ), required = arrayOf("command")))
        put(tool("read_file", "Read a text file as root.", obj(
            "path" to string("Absolute file path"),
            "max_bytes" to integer("Maximum bytes to return, up to 100000")
        ), required = arrayOf("path")))
        put(tool("write_file", "Write or append UTF-8 text to a file as root.", obj(
            "path" to string("Absolute destination path"),
            "content" to string("UTF-8 text content"),
            "append" to bool("Append instead of replacing the file")
        ), required = arrayOf("path", "content")))
        put(tool("list_directory", "List a directory as root, including permissions and hidden entries.", obj(
            "path" to string("Absolute directory path")
        ), required = arrayOf("path")))
        put(tool("list_apps", "List installed third-party Android package names."))
        put(tool("launch_app", "Launch an installed Android app.", obj(
            "package_name" to string("Android package name")
        ), required = arrayOf("package_name")))
        put(tool("stop_app", "Force-stop an Android app.", obj(
            "package_name" to string("Android package name")
        ), required = arrayOf("package_name")))
        put(tool("open_url", "Open a URL in the device's default app.", obj(
            "url" to string("HTTP, HTTPS, or other Android URI")
        ), required = arrayOf("url")))
        put(tool("tap", "Tap screen coordinates using root input injection.", obj(
            "x" to integer("Horizontal pixel coordinate"),
            "y" to integer("Vertical pixel coordinate")
        ), required = arrayOf("x", "y")))
        put(tool("swipe", "Swipe between screen coordinates using root input injection.", obj(
            "x1" to integer("Start x"), "y1" to integer("Start y"),
            "x2" to integer("End x"), "y2" to integer("End y"),
            "duration_ms" to integer("Gesture duration in milliseconds")
        ), required = arrayOf("x1", "y1", "x2", "y2")))
        put(tool("type_text", "Type text into the currently focused Android control.", obj(
            "text" to string("Text to type")
        ), required = arrayOf("text")))
        put(tool("key_event", "Send an Android key event, such as HOME, BACK, POWER, or 24 for volume up.", obj(
            "keycode" to string("Android keycode name or number")
        ), required = arrayOf("keycode")))
        put(tool("set_media_volume", "Set media volume to an absolute index.", obj(
            "level" to integer("Volume index, usually 0 to 25")
        ), required = arrayOf("level")))
        put(tool("screenshot", "Capture the current display to /sdcard/Pictures/Assist and return its path.", obj(
            "name" to string("Optional short filename without extension")
        )))
        put(tool("reboot", "Reboot, power off, or reboot to recovery/bootloader. This interrupts the conversation.", obj(
            "mode" to enumString("Power action", "reboot", "shutdown", "recovery", "bootloader")
        ), required = arrayOf("mode")))
    }

    fun execute(name: String, args: JSONObject): String {
        if (name == "get_device_info") return deviceInfo()
        if (!SettingsRepository(context).load().rootToolsEnabled) {
            return "DENIED: Root tools are disabled in Assist settings. The device owner must enable them explicitly."
        }
        if (!RootManager.isRootAvailable()) {
            return "ERROR: su did not grant root access. Open Assist and approve the root prompt."
        }

        return runCatching {
            when (name) {
                "run_shell" -> RootManager.run(
                    requireText(args, "command"),
                    args.optInt("timeout_seconds", 30).coerceIn(1, 120)
                ).asToolText()
                "read_file" -> readFile(args)
                "write_file" -> writeFile(args)
                "list_directory" -> shell("ls -la -- ${quote(requireText(args, "path"))}")
                "list_apps" -> shell("pm list packages -3 | sort")
                "launch_app" -> shell("monkey -p ${quote(requireText(args, "package_name"))} -c android.intent.category.LAUNCHER 1")
                "stop_app" -> shell("am force-stop ${quote(requireText(args, "package_name"))}")
                "open_url" -> shell("am start -a android.intent.action.VIEW -d ${quote(requireText(args, "url"))}")
                "tap" -> shell("input tap ${args.getInt("x")} ${args.getInt("y")}")
                "swipe" -> shell(
                    "input swipe ${args.getInt("x1")} ${args.getInt("y1")} " +
                        "${args.getInt("x2")} ${args.getInt("y2")} " +
                        args.optInt("duration_ms", 350).coerceIn(50, 10_000)
                )
                "type_text" -> typeText(requireText(args, "text"))
                "key_event" -> shell("input keyevent ${quote(requireText(args, "keycode"))}")
                "set_media_volume" -> shell(
                    "cmd media_session volume --show --stream 3 --set ${args.getInt("level").coerceIn(0, 100)}"
                )
                "screenshot" -> screenshot(args.optString("name"))
                "reboot" -> reboot(requireText(args, "mode"))
                else -> "ERROR: Unknown tool '$name'"
            }
        }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
    }

    private fun readFile(args: JSONObject): String {
        val path = requireText(args, "path")
        val maxBytes = args.optInt("max_bytes", 50_000).coerceIn(1, 100_000)
        return shell("head -c $maxBytes -- ${quote(path)}")
    }

    private fun writeFile(args: JSONObject): String {
        val path = requireText(args, "path")
        val content = requireText(args, "content")
        require(content.toByteArray().size <= 500_000) { "Content is limited to 500000 bytes" }
        val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val redirect = if (args.optBoolean("append", false)) ">>" else ">"
        val parent = path.substringBeforeLast('/', "/")
        return shell(
            "mkdir -p -- ${quote(parent)} && printf %s ${quote(encoded)} | base64 -d $redirect ${quote(path)}"
        )
    }

    private fun typeText(text: String): String {
        val inputEncoded = text.replace("%", "%25").replace(" ", "%s")
        return shell("input text ${quote(inputEncoded)}")
    }

    private fun screenshot(rawName: String): String {
        val safeName = rawName.ifBlank { "assist-${System.currentTimeMillis()}" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(80)
            .removeSuffix(".png")
        val path = "/sdcard/Pictures/Assist/$safeName.png"
        val result = RootManager.run("mkdir -p /sdcard/Pictures/Assist && screencap -p ${quote(path)}", 30)
        return if (result.successful) "Saved screenshot: $path" else result.asToolText()
    }

    private fun reboot(mode: String): String = when (mode.lowercase()) {
        "reboot" -> shell("svc power reboot || reboot")
        "shutdown" -> shell("svc power shutdown || reboot -p")
        "recovery" -> shell("reboot recovery")
        "bootloader" -> shell("reboot bootloader")
        else -> "ERROR: mode must be reboot, shutdown, recovery, or bootloader"
    }

    private fun shell(command: String): String = RootManager.run(command).asToolText()
    private fun quote(value: String): String = RootManager.shellQuote(value)

    private fun deviceInfo(): String {
        val battery = context.getSystemService(BatteryManager::class.java)
        val batteryPercent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return buildString {
            appendLine("manufacturer=${Build.MANUFACTURER}")
            appendLine("model=${Build.MODEL}")
            appendLine("device=${Build.DEVICE}")
            appendLine("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("build=${Build.DISPLAY}")
            appendLine("battery_percent=$batteryPercent")
            appendLine("time=${Instant.now()}")
            append("root_available=${RootManager.isRootAvailable()}")
        }
    }

    private fun requireText(args: JSONObject, key: String): String =
        args.optString(key).takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing required argument: $key")

    private fun tool(
        name: String,
        description: String,
        properties: JSONObject = JSONObject(),
        required: Array<String> = emptyArray()
    ): JSONObject = JSONObject().put("type", "function").put(
        "function",
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", JSONArray(required))
                .put("additionalProperties", false))
    )

    private fun obj(vararg fields: Pair<String, JSONObject>): JSONObject =
        JSONObject().apply { fields.forEach { (key, schema) -> put(key, schema) } }

    private fun string(description: String) = JSONObject().put("type", "string").put("description", description)
    private fun integer(description: String) = JSONObject().put("type", "integer").put("description", description)
    private fun bool(description: String) = JSONObject().put("type", "boolean").put("description", description)
    private fun enumString(description: String, vararg values: String) = string(description).put("enum", JSONArray(values))
}
