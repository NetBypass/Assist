package com.netbypass.assist.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.netbypass.assist.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Executes every tool advertised in [com.netbypass.assist.ai.ToolSchema]. Most privileged
 * actions run through [RootShell] so they work regardless of which normal Android permissions
 * are granted; a handful of things (clipboard, vibration, torch, notifications, battery /
 * network info) are simpler and more reliable via plain Android APIs.
 */
class ToolRegistry(private val context: Context, private val rootShell: RootShell) {

    private fun ok(build: JSONObject.() -> Unit = {}): JSONObject = JSONObject().apply {
        put("ok", true)
        build()
    }

    private fun fail(message: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("error", message)
    }

    private fun sh(cmd: String, timeoutMs: Long = 20_000): ShellResult = rootShell.exec(cmd, timeoutMs)

    fun execute(name: String, args: JSONObject): JSONObject {
        return try {
            when (name) {
                "run_shell" -> toolRunShell(args)
                "set_volume" -> toolSetVolume(args)
                "wifi_power" -> toolWifiPower(args)
                "bluetooth_power" -> toolBluetoothPower(args)
                "mobile_data_power" -> toolMobileDataPower(args)
                "airplane_mode" -> toolAirplaneMode(args)
                "screen_power" -> toolScreenPower(args)
                "set_brightness" -> toolSetBrightness(args)
                "launch_app" -> toolLaunchApp(args)
                "kill_app" -> toolKillApp(args)
                "list_apps" -> toolListApps(args)
                "send_notification" -> toolSendNotification(args)
                "get_battery_status" -> toolGetBatteryStatus()
                "get_device_info" -> toolGetDeviceInfo()
                "get_network_info" -> toolGetNetworkInfo()
                "get_location" -> toolGetLocation()
                "take_screenshot" -> toolTakeScreenshot()
                "set_headless_mode" -> toolSetHeadlessMode(args)
                "read_file" -> toolReadFile(args)
                "write_file" -> toolWriteFile(args)
                "list_dir" -> toolListDir(args)
                "reboot_device" -> toolRebootDevice(args)
                "shutdown_device" -> toolShutdownDevice()
                "media_control" -> toolMediaControl(args)
                "get_clipboard" -> toolGetClipboard()
                "set_clipboard" -> toolSetClipboard(args)
                "vibrate" -> toolVibrate(args)
                "set_flashlight" -> toolSetFlashlight(args)
                "open_url" -> toolOpenUrl(args)
                "install_apk" -> toolInstallApk(args)
                "uninstall_app" -> toolUninstallApp(args)
                "set_system_setting" -> toolSetSystemSetting(args)
                "disable_system_ui" -> toolDisableSystemUi(args)
                else -> fail("Unknown tool: $name")
            }
        } catch (t: Throwable) {
            Logger.e("ToolRegistry", "Tool '$name' threw", t)
            fail("Internal error running $name: ${t.message}")
        }
    }

    // ---- Generic shell ----------------------------------------------------------------

    private fun toolRunShell(args: JSONObject): JSONObject {
        val cmd = args.optString("command").ifBlank { return fail("Missing 'command'") }
        val timeout = if (args.has("timeout_ms")) args.optLong("timeout_ms", 20_000) else 20_000
        val result = sh(cmd, timeout)
        return ok {
            put("exit_code", result.exitCode)
            put("output", result.output.take(8000))
            if (result.error.isNotBlank()) put("error_detail", result.error)
        }
    }

    // ---- Connectivity -----------------------------------------------------------------

    private fun onOff(args: JSONObject): Boolean = args.optString("state").equals("on", ignoreCase = true)

    private fun toolWifiPower(args: JSONObject): JSONObject {
        val r = sh("svc wifi ${if (onOff(args)) "enable" else "disable"}")
        return if (r.success) ok() else fail(r.output.ifBlank { "Failed to toggle Wi-Fi" })
    }

    private fun toolBluetoothPower(args: JSONObject): JSONObject {
        val r = sh("svc bluetooth ${if (onOff(args)) "enable" else "disable"}")
        return if (r.success) ok() else fail(r.output.ifBlank { "Failed to toggle Bluetooth" })
    }

    private fun toolMobileDataPower(args: JSONObject): JSONObject {
        val r = sh("svc data ${if (onOff(args)) "enable" else "disable"}")
        return if (r.success) ok() else fail(r.output.ifBlank { "Failed to toggle mobile data" })
    }

    private fun toolAirplaneMode(args: JSONObject): JSONObject {
        val enable = onOff(args)
        val r = sh(
            "settings put global airplane_mode_on ${if (enable) 1 else 0} && " +
                "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state ${enable}"
        )
        return if (r.success) ok() else fail(r.output.ifBlank { "Failed to toggle airplane mode" })
    }

    private fun toolGetNetworkInfo(): JSONObject {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return ok {
            put("has_internet", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
            put("is_wifi", caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
            put("is_cellular", caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
            try {
                put("wifi_ssid", wifiMgr?.connectionInfo?.ssid ?: "")
            } catch (_: Exception) { }
        }
    }

    // ---- Power / screen -----------------------------------------------------------------

    private fun toolScreenPower(args: JSONObject): JSONObject {
        val wantOn = onOff(args)
        val state = sh("dumpsys power | grep -m1 mWakefulness=").output
        val isAwake = state.contains("Awake")
        return if (wantOn == isAwake) {
            ok { put("note", "Screen already ${if (wantOn) "on" else "off"}") }
        } else {
            val r = sh("input keyevent 26") // KEYCODE_POWER toggles
            if (r.success) ok() else fail("Failed to toggle screen power")
        }
    }

    private fun toolSetBrightness(args: JSONObject): JSONObject {
        val percent = args.optInt("percent", -1)
        if (percent !in 0..100) return fail("percent must be 0-100")
        val value = (percent * 255 / 100).coerceIn(1, 255)
        val r = sh("settings put system screen_brightness_mode 0 && settings put system screen_brightness $value")
        return if (r.success) ok() else fail(r.output.ifBlank { "Failed to set brightness" })
    }

    private fun toolSetHeadlessMode(args: JSONObject): JSONObject {
        val enabled = args.optBoolean("enabled", true)
        return if (enabled) {
            sh("dumpsys deviceidle whitelist +${context.packageName}")
            sh("settings put global stay_on_while_plugged_in 0")
            sh("input keyevent 26") // turn screen off if currently on; harmless if already off since this toggles
            ok { put("note", "Headless mode enabled: Assist keeps running in the background with the screen off.") }
        } else {
            sh("settings put global stay_on_while_plugged_in 3")
            ok { put("note", "Headless mode disabled.") }
        }
    }

    // ---- Volume / media -----------------------------------------------------------------

    private fun streamFor(name: String): Int = when (name.lowercase()) {
        "music" -> AudioManager.STREAM_MUSIC
        "ring" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "call" -> AudioManager.STREAM_VOICE_CALL
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "system" -> AudioManager.STREAM_SYSTEM
        else -> AudioManager.STREAM_MUSIC
    }

    private fun toolSetVolume(args: JSONObject): JSONObject {
        val stream = streamFor(args.optString("stream", "music"))
        val percent = args.optInt("percent", -1)
        if (percent !in 0..100) return fail("percent must be 0-100")
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            val max = am.getStreamMaxVolume(stream)
            val target = (max * percent / 100.0).toInt().coerceIn(0, max)
            am.setStreamVolume(stream, target, 0)
            ok { put("level", target); put("max", max) }
        } catch (e: Exception) {
            fail("Failed to set volume: ${e.message}")
        }
    }

    private fun toolMediaControl(args: JSONObject): JSONObject {
        val keyEvent = when (args.optString("action")) {
            "play_pause" -> 85
            "next" -> 87
            "previous" -> 88
            "volume_up" -> 24
            "volume_down" -> 25
            else -> return fail("Unknown media action")
        }
        val r = sh("input keyevent $keyEvent")
        return if (r.success) ok() else fail("Failed to send media key")
    }

    // ---- Apps -----------------------------------------------------------------------------

    private fun toolLaunchApp(args: JSONObject): JSONObject {
        val pkg = args.optString("package_name").ifBlank { return fail("Missing package_name") }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            ok()
        } else {
            val r = sh("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
            if (r.success) ok() else fail("App not found or has no launcher activity: $pkg")
        }
    }

    private fun toolKillApp(args: JSONObject): JSONObject {
        val pkg = args.optString("package_name").ifBlank { return fail("Missing package_name") }
        val r = sh("am force-stop $pkg")
        return if (r.success) ok() else fail(r.output.ifBlank { "Failed to stop $pkg" })
    }

    private fun toolListApps(args: JSONObject): JSONObject {
        val filter = args.optString("filter", "user")
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
        val arr = JSONArray()
        for (appInfo in apps) {
            val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val include = when (filter) {
                "system" -> isSystem
                "all" -> true
                else -> !isSystem
            }
            if (!include) continue
            arr.put(JSONObject().apply {
                put("package", appInfo.packageName)
                put("label", pm.getApplicationLabel(appInfo).toString())
                put("system", isSystem)
            })
        }
        return ok { put("apps", arr); put("count", arr.length()) }
    }

    private fun toolInstallApk(args: JSONObject): JSONObject {
        val path = args.optString("path").ifBlank { return fail("Missing path") }
        val r = sh("pm install -r ${RootShell.quote(path)}", 60_000)
        return if (r.output.contains("Success", ignoreCase = true)) ok() else fail(r.output.ifBlank { "Install failed" })
    }

    private fun toolUninstallApp(args: JSONObject): JSONObject {
        val pkg = args.optString("package_name").ifBlank { return fail("Missing package_name") }
        val r = sh("pm uninstall --user 0 $pkg", 60_000)
        return if (r.output.contains("Success", ignoreCase = true)) ok() else fail(r.output.ifBlank { "Uninstall failed" })
    }

    // ---- Notifications ----------------------------------------------------------------

    private fun toolSendNotification(args: JSONObject): JSONObject {
        val title = args.optString("title").ifBlank { "Assist" }
        val message = args.optString("message").ifBlank { return fail("Missing message") }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "assist_messages"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Assist messages", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), notification)
        return ok()
    }

    // ---- Status -------------------------------------------------------------------------

    private fun toolGetBatteryStatus(): JSONObject {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return ok {
            put("level_percent", level)
            put("charging", charging)
        }
    }

    private fun toolGetDeviceInfo(): JSONObject {
        val stat = sh("df -h /data | tail -n1").output
        return ok {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("android_version", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("root_granted", rootShell.rootGranted)
            put("storage_data", stat)
        }
    }

    private fun toolGetLocation(): JSONObject {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            for (p in providers) {
                try {
                    val loc = lm.getLastKnownLocation(p) ?: continue
                    return ok {
                        put("latitude", loc.latitude)
                        put("longitude", loc.longitude)
                        put("provider", p)
                        put("accuracy_m", loc.accuracy)
                    }
                } catch (_: SecurityException) {
                }
            }
            fail("No last known location available (permission missing or location off)")
        } catch (e: Exception) {
            fail("Location error: ${e.message}")
        }
    }

    private fun toolTakeScreenshot(): JSONObject {
        val dir = "/sdcard/Pictures/Assist"
        val filename = "screenshot_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png"
        val path = "$dir/$filename"
        val r = sh("mkdir -p $dir && screencap -p ${RootShell.quote(path)}")
        return if (r.success) ok { put("path", path) } else fail(r.output.ifBlank { "Screenshot failed" })
    }

    // ---- Filesystem -----------------------------------------------------------------------

    private fun toolReadFile(args: JSONObject): JSONObject {
        val path = args.optString("path").ifBlank { return fail("Missing path") }
        val r = sh("base64 ${RootShell.quote(path)}")
        if (!r.success) return fail(r.output.ifBlank { "Could not read $path" })
        return try {
            ok { put("content", RootShell.unb64(r.output)) }
        } catch (e: Exception) {
            fail("Could not decode file content: ${e.message}")
        }
    }

    private fun toolWriteFile(args: JSONObject): JSONObject {
        val path = args.optString("path").ifBlank { return fail("Missing path") }
        val content = if (args.has("content")) args.optString("content") else return fail("Missing content")
        val append = args.optBoolean("append", false)
        val redirect = if (append) ">>" else ">"
        val b64 = RootShell.b64(content)
        val r = sh("echo '$b64' | base64 -d $redirect ${RootShell.quote(path)}")
        return if (r.success) ok() else fail(r.output.ifBlank { "Could not write $path" })
    }

    private fun toolListDir(args: JSONObject): JSONObject {
        val path = args.optString("path").ifBlank { return fail("Missing path") }
        val r = sh("ls -la ${RootShell.quote(path)}")
        return if (r.success) ok { put("listing", r.output) } else fail(r.output.ifBlank { "Could not list $path" })
    }

    // ---- Power actions ----------------------------------------------------------------

    private fun toolRebootDevice(args: JSONObject): JSONObject {
        val mode = args.optString("mode", "normal")
        val cmd = when (mode) {
            "recovery" -> "reboot recovery"
            "bootloader" -> "reboot bootloader"
            else -> "reboot"
        }
        sh(cmd, 5000)
        return ok { put("note", "Rebooting ($mode)...") }
    }

    private fun toolShutdownDevice(): JSONObject {
        sh("reboot -p", 5000)
        return ok { put("note", "Shutting down...") }
    }

    // ---- Clipboard / vibrate / torch / url ---------------------------------------------

    private fun toolGetClipboard(): JSONObject {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.safePrimaryClip()?.let { clip ->
            if (clip.itemCount > 0) clip.getItemAt(0).coerceToText(context)?.toString() else null
        } ?: ""
        return ok { put("text", text) }
    }

    private fun ClipboardManager.safePrimaryClip(): ClipData? = try { primaryClip } catch (_: Exception) { null }

    private fun toolSetClipboard(args: JSONObject): JSONObject {
        val text = args.optString("text")
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("assist", text))
        return ok()
    }

    private fun toolVibrate(args: JSONObject): JSONObject {
        val ms = args.optLong("milliseconds", 200).coerceIn(10, 10_000)
        return try {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            ok()
        } catch (e: Exception) {
            fail("Vibrate failed: ${e.message}")
        }
    }

    private fun toolSetFlashlight(args: JSONObject): JSONObject {
        val wantOn = onOff(args)
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return fail("No flash unit available")
            cameraManager.setTorchMode(camId, wantOn)
            ok()
        } catch (e: Exception) {
            fail("Flashlight failed: ${e.message}")
        }
    }

    private fun toolOpenUrl(args: JSONObject): JSONObject {
        val url = args.optString("url").ifBlank { return fail("Missing url") }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ok()
        } catch (e: Exception) {
            fail("Could not open url: ${e.message}")
        }
    }

    // ---- System settings / kiosk --------------------------------------------------------

    private fun toolSetSystemSetting(args: JSONObject): JSONObject {
        val namespace = args.optString("namespace").ifBlank { return fail("Missing namespace") }
        val key = args.optString("key").ifBlank { return fail("Missing key") }
        val value = args.optString("value")
        if (namespace !in listOf("system", "secure", "global")) return fail("Invalid namespace")
        val r = sh("settings put $namespace ${RootShell.quote(key)} ${RootShell.quote(value)}")
        return if (r.success) ok() else fail(r.output.ifBlank { "Failed to set $namespace/$key" })
    }

    private fun toolDisableSystemUi(args: JSONObject): JSONObject {
        val enabled = args.optBoolean("enabled", true)
        val cmd = if (enabled) "pm enable com.android.systemui" else "pm disable-user --user 0 com.android.systemui"
        val r = sh(cmd)
        return if (r.success) ok() else fail(r.output.ifBlank { "Failed to change System UI state" })
    }

    companion object {
        fun storageInfo(): String {
            val stat = android.os.StatFs(Environment.getDataDirectory().path)
            val free = stat.availableBytes
            val total = stat.totalBytes
            return "${free / (1024 * 1024)}MB free / ${total / (1024 * 1024)}MB total"
        }
    }
}
