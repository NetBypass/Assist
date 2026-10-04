package com.netbypass.assist.tools

/**
 * Tool names (and, for the generic shell escape hatch, command patterns) that are considered
 * destructive / hard to undo. When "confirm dangerous actions" is enabled in settings, the
 * assistant will speak a confirmation question and wait for a clear "yes" before executing
 * these instead of running them immediately.
 */
object DangerousActions {

    val ALWAYS_CONFIRM: Set<String> = setOf(
        "reboot_device",
        "shutdown_device",
        "uninstall_app",
        "wipe_app_data",
        "disable_system_ui",
        "factory_reset"
    )

    private val RISKY_SHELL_PATTERNS = listOf(
        Regex("rm\\s+-rf?\\s+/(?!data/local/tmp)"),
        Regex("mkfs"),
        Regex("\\bdd\\b.+of="),
        Regex("format\\s"),
        Regex("wipe"),
        Regex(">\\s*/dev/block"),
        Regex("factory.?reset"),
        Regex("recovery --wipe_data")
    )

    fun isDangerous(toolName: String, argumentsJson: String): Boolean {
        if (toolName in ALWAYS_CONFIRM) return true
        if (toolName == "run_shell") {
            return RISKY_SHELL_PATTERNS.any { it.containsMatchIn(argumentsJson) }
        }
        if (toolName == "write_file") {
            return argumentsJson.contains("\"path\"") && Regex("/(system|data/system|vendor)/").containsMatchIn(argumentsJson)
        }
        return false
    }
}
