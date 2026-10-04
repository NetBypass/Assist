package com.netbypass.assist.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the OpenAI "tools" (function-calling) schema array advertised to the model on every
 * request. Keep this list in sync with [com.netbypass.assist.tools.ToolRegistry].
 */
object ToolSchema {

    private fun obj(build: JSONObject.() -> Unit): JSONObject = JSONObject().apply(build)

    private fun strProp(desc: String, enum: List<String>? = null): JSONObject = obj {
        put("type", "string")
        put("description", desc)
        if (enum != null) put("enum", JSONArray(enum))
    }

    private fun numProp(desc: String): JSONObject = obj {
        put("type", "number")
        put("description", desc)
    }

    private fun boolProp(desc: String): JSONObject = obj {
        put("type", "boolean")
        put("description", desc)
    }

    private fun params(properties: Map<String, JSONObject>, required: List<String> = emptyList()): JSONObject = obj {
        put("type", "object")
        put("properties", obj { properties.forEach { (k, v) -> put(k, v) } })
        if (required.isNotEmpty()) put("required", JSONArray(required))
        put("additionalProperties", false)
    }

    private fun function(name: String, description: String, parameters: JSONObject): JSONObject = obj {
        put("type", "function")
        put("function", obj {
            put("name", name)
            put("description", description)
            put("parameters", parameters)
        })
    }

    fun all(): JSONArray {
        val list = JSONArray()

        list.put(
            function(
                "run_shell",
                "Run an arbitrary command as root on the device shell. Use this as a fallback for " +
                    "anything not covered by a more specific tool. Returns stdout+stderr merged and the exit code.",
                params(
                    mapOf(
                        "command" to strProp("The full shell command to execute, e.g. 'pm list packages -3'"),
                        "timeout_ms" to numProp("Optional timeout in milliseconds, default 20000")
                    ),
                    required = listOf("command")
                )
            )
        )

        list.put(
            function(
                "set_volume",
                "Set the device volume for a given audio stream.",
                params(
                    mapOf(
                        "stream" to strProp("Audio stream to change", listOf("music", "ring", "alarm", "call", "notification", "system")),
                        "percent" to numProp("Target volume from 0 to 100")
                    ),
                    required = listOf("stream", "percent")
                )
            )
        )

        list.put(function("wifi_power", "Turn Wi-Fi on or off.", params(mapOf("state" to strProp("on or off", listOf("on", "off"))), listOf("state"))))
        list.put(function("bluetooth_power", "Turn Bluetooth on or off.", params(mapOf("state" to strProp("on or off", listOf("on", "off"))), listOf("state"))))
        list.put(function("mobile_data_power", "Turn mobile data on or off.", params(mapOf("state" to strProp("on or off", listOf("on", "off"))), listOf("state"))))
        list.put(function("airplane_mode", "Turn airplane mode on or off.", params(mapOf("state" to strProp("on or off", listOf("on", "off"))), listOf("state"))))

        list.put(
            function(
                "screen_power",
                "Turn the display on or off. Used for headless / smart-speaker style operation where the " +
                    "screen stays off while the assistant keeps listening.",
                params(mapOf("state" to strProp("on or off", listOf("on", "off"))), listOf("state"))
            )
        )

        list.put(function("set_brightness", "Set screen brightness.", params(mapOf("percent" to numProp("0 to 100")), listOf("percent"))))

        list.put(function("launch_app", "Launch an installed app by package name.", params(mapOf("package_name" to strProp("Android package name, e.g. com.spotify.music")), listOf("package_name"))))
        list.put(function("kill_app", "Force-stop a running app by package name.", params(mapOf("package_name" to strProp("Android package name")), listOf("package_name"))))
        list.put(
            function(
                "list_apps",
                "List installed applications.",
                params(mapOf("filter" to strProp("Which apps to list", listOf("all", "user", "system"))))
            )
        )

        list.put(
            function(
                "send_notification",
                "Show a notification on the device.",
                params(mapOf("title" to strProp("Notification title"), "message" to strProp("Notification body")), listOf("title", "message"))
            )
        )

        list.put(function("get_battery_status", "Get battery level, charging state and temperature.", params(emptyMap())))
        list.put(function("get_device_info", "Get device model, manufacturer, Android version, root status and storage info.", params(emptyMap())))
        list.put(function("get_network_info", "Get Wi-Fi / mobile network connectivity info.", params(emptyMap())))
        list.put(function("get_location", "Get the last known GPS/network location.", params(emptyMap())))
        list.put(function("take_screenshot", "Capture a screenshot and save it to storage, returns the file path.", params(emptyMap())))

        list.put(
            function(
                "set_headless_mode",
                "Enable or disable headless smart-speaker mode: keeps the assistant service alive, " +
                    "prevents the CPU from sleeping, and manages the screen so the device can run " +
                    "voice-only with the display off.",
                params(mapOf("enabled" to boolProp("true to enable headless mode, false to disable")), listOf("enabled"))
            )
        )

        list.put(function("read_file", "Read a text file from the filesystem (root access).", params(mapOf("path" to strProp("Absolute file path")), listOf("path"))))
        list.put(
            function(
                "write_file",
                "Write text content to a file on the filesystem (root access). Can create or overwrite files anywhere.",
                params(
                    mapOf(
                        "path" to strProp("Absolute file path"),
                        "content" to strProp("Text content to write"),
                        "append" to boolProp("Append instead of overwrite, default false")
                    ),
                    required = listOf("path", "content")
                )
            )
        )
        list.put(function("list_dir", "List files in a directory (root access).", params(mapOf("path" to strProp("Absolute directory path")), listOf("path"))))

        list.put(
            function(
                "reboot_device",
                "Reboot the device, optionally into recovery or bootloader.",
                params(mapOf("mode" to strProp("Reboot target", listOf("normal", "recovery", "bootloader"))))
            )
        )
        list.put(function("shutdown_device", "Power off the device completely.", params(emptyMap())))

        list.put(
            function(
                "media_control",
                "Control currently playing media.",
                params(mapOf("action" to strProp("Media action", listOf("play_pause", "next", "previous", "volume_up", "volume_down"))), listOf("action"))
            )
        )

        list.put(function("get_clipboard", "Read the current clipboard text.", params(emptyMap())))
        list.put(function("set_clipboard", "Set the clipboard text.", params(mapOf("text" to strProp("Text to place on the clipboard")), listOf("text"))))
        list.put(function("vibrate", "Vibrate the device.", params(mapOf("milliseconds" to numProp("Duration in milliseconds")), listOf("milliseconds"))))
        list.put(function("set_flashlight", "Turn the camera flashlight torch on or off.", params(mapOf("state" to strProp("on or off", listOf("on", "off"))), listOf("state"))))
        list.put(function("open_url", "Open a URL in the default browser.", params(mapOf("url" to strProp("The URL to open")), listOf("url"))))

        list.put(function("install_apk", "Install an APK file from a path on the device.", params(mapOf("path" to strProp("Absolute path to the .apk file")), listOf("path"))))
        list.put(function("uninstall_app", "Uninstall an app by package name.", params(mapOf("package_name" to strProp("Android package name")), listOf("package_name"))))

        list.put(
            function(
                "set_system_setting",
                "Directly read/write an Android settings key (system, secure or global namespace).",
                params(
                    mapOf(
                        "namespace" to strProp("Settings namespace", listOf("system", "secure", "global")),
                        "key" to strProp("Setting key name"),
                        "value" to strProp("New value to set")
                    ),
                    required = listOf("namespace", "key", "value")
                )
            )
        )

        list.put(
            function(
                "disable_system_ui",
                "Advanced kiosk control: enable or disable the Android System UI (status bar, " +
                    "navigation, launcher chrome) to make the device behave fully headless. Disabling " +
                    "it can make the screen unusable until re-enabled, only use when explicitly asked.",
                params(mapOf("enabled" to boolProp("false disables System UI, true re-enables it")), listOf("enabled"))
            )
        )

        return list
    }
}
