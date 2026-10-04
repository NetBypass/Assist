package com.netbypass.assist.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.netbypass.assist.SettingsStore
import com.netbypass.assist.util.Logger

/** Restarts the assistant service after boot / app update if the user had it running before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val settings = SettingsStore(context)
        if (!settings.startOnBoot || !settings.serviceEnabled) {
            Logger.i("BootReceiver", "Not starting Assist: startOnBoot=${settings.startOnBoot} serviceEnabled=${settings.serviceEnabled}")
            return
        }

        Logger.i("BootReceiver", "Boot detected, starting AssistantService")
        val serviceIntent = Intent(context, AssistantService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
