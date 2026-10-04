package ai.arena.assist.receiver

import ai.arena.assist.data.SettingsRepository
import ai.arena.assist.service.AssistantService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!SettingsRepository(context).load().startOnBoot) return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AssistantService::class.java).setAction(AssistantService.ACTION_BOOT)
            )
        }
    }
}
