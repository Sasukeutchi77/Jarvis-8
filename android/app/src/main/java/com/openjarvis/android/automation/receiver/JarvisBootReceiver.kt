package com.openjarvis.android.automation.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.openjarvis.android.JarvisApplication
import com.openjarvis.android.logging.JarvisLogger
import com.openjarvis.android.services.VoiceAssistantForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reschedules all enabled alarms and background routines when the device boots or app updates.
 * Restores the VoiceAssistantForegroundService if wake-word detection was active before reboot.
 */
class JarvisBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        JarvisLogger.i("JarvisBootReceiver", "Device boot or package update detected (action: $action). Re-arming automations & services...")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = JarvisApplication.instance
                app.automationManager.rescheduleAllActiveAutomations()
                JarvisLogger.i("JarvisBootReceiver", "All active automations successfully rescheduled after boot.")

                // Restore Foreground Voice Service if wake-word was enabled
                val config = app.configManager.config.value
                val hasAudioPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (config.wakeWordEnabled && config.backgroundListening && hasAudioPermission) {
                    JarvisLogger.i("JarvisBootReceiver", "SERVICE_RESTARTED: Auto-restoring VoiceAssistantForegroundService after device reboot.")
                    VoiceAssistantForegroundService.start(context)
                } else {
                    JarvisLogger.i("JarvisBootReceiver", "VoiceAssistantForegroundService not restored (enabled=${config.wakeWordEnabled}, permission=$hasAudioPermission).")
                }
            } catch (e: Exception) {
                JarvisLogger.e("JarvisBootReceiver", "Failed to restore automations/services on boot: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
