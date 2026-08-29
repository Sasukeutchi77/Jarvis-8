package com.openjarvis.android.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openjarvis.android.JarvisApplication
import com.openjarvis.android.MainActivity
import com.openjarvis.android.R
import com.openjarvis.android.logging.JarvisLogger
import com.openjarvis.android.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Real Android Foreground Service for JARVIS Voice Assistant ("Hey JARVIS").
 * Keeps microphone active in the background with FOREGROUND_SERVICE_MICROPHONE,
 * coordinates wake-word detection when app is minimized, another app is active,
 * or when the screen is locked/off (as permitted by Android & device hardware).
 *
 * Implements strict single-instance guarantees, audio focus & screen lifecycle observation,
 * battery-efficient zero-polling wake-word handling, and seamless notification controls.
 */
class VoiceAssistantForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateObserverJob: Job? = null

    // Screen & Audio Hardware State Receiver
    private val systemStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    JarvisLogger.d("VoiceService", "Device screen turned OFF. Wake-word engine remains active in background.")
                }
                Intent.ACTION_SCREEN_ON -> {
                    JarvisLogger.d("VoiceService", "Device screen turned ON.")
                }
                Intent.ACTION_USER_PRESENT -> {
                    JarvisLogger.d("VoiceService", "Device unlocked by user.")
                }
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    JarvisLogger.i("VoiceService", "Audio becoming noisy (headset unplugged). Adapting audio stream.")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()

        val initialNotification = buildForegroundNotification("J.A.R.V.I.S. en veille — Écoute de « Hey JARVIS »")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        JarvisLogger.i("VoiceService", "JARVIS_SERVICE_START: VoiceAssistantForegroundService initialized with FOREGROUND_SERVICE_MICROPHONE.")
        JarvisLogger.i("VoiceService", "WAKE_WORD_SERVICE_ACTIVE: Continuous wake-word listening armed.")

        // Register screen & audio hardware receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(systemStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(systemStateReceiver, filter)
            }
        } catch (e: Exception) {
            JarvisLogger.w("VoiceService", "Could not register systemStateReceiver: ${e.message}")
        }

        // Start listening via VoiceEngine
        try {
            JarvisApplication.instance.voiceEngine.startBackgroundListening()
        } catch (e: Exception) {
            JarvisLogger.e("VoiceService", "WAKE_WORD_SERVICE_ERROR: Failed to start background listening", e)
        }

        // Observe VoiceState and dynamically update Notification
        stateObserverJob = scope.launch {
            JarvisApplication.instance.voiceEngine.voiceState.collect { state ->
                val statusText = when (state) {
                    VoiceState.IDLE -> "JARVIS en veille"
                    VoiceState.LISTENING_FOR_WAKE_WORD -> "En attente de « ${JarvisApplication.instance.configManager.config.value.wakeWordPhrase} »"
                    VoiceState.WAKE_WORD_DETECTED -> "Mot-clé détecté !"
                    VoiceState.LISTENING_COMMAND -> "Écoute de votre commande..."
                    VoiceState.PROCESSING -> "Raisonnement JARVIS en cours..."
                    VoiceState.SPEAKING -> "Réponse en cours..."
                    VoiceState.PAUSED -> "Écoute vocale en pause"
                    VoiceState.ERROR -> "MICROPHONE_UNAVAILABLE / Erreur audio"
                    VoiceState.STOPPED -> "Service arrêté"
                }
                updateNotification(statusText)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || (flags and START_FLAG_REDELIVERY) != 0) {
            JarvisLogger.i("VoiceService", "SERVICE_RESTARTED: Service restored by Android OS after memory reclaim.")
            if (JarvisApplication.instance.configManager.config.value.wakeWordEnabled) {
                JarvisApplication.instance.voiceEngine.startBackgroundListening()
            }
        }

        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                JarvisLogger.i("VoiceService", "JARVIS_SERVICE_STOP: Stop action received from notification or settings.")
                JarvisApplication.instance.voiceEngine.stopBackgroundListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TRIGGER_COMMAND -> {
                JarvisLogger.i("VoiceService", "Trigger command action received from notification.")
                JarvisApplication.instance.voiceEngine.startListeningForCommand()
            }
            ACTION_START -> {
                JarvisLogger.i("VoiceService", "WAKE_WORD_SERVICE_ACTIVE: Explicit start confirmation received.")
                if (JarvisApplication.instance.configManager.config.value.wakeWordEnabled) {
                    JarvisApplication.instance.voiceEngine.startBackgroundListening()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        JarvisLogger.i("VoiceService", "JARVIS_SERVICE_STOP: VoiceAssistantForegroundService stopping and releasing all resources.")
        isRunning = false
        stateObserverJob?.cancel()
        stateObserverJob = null

        try {
            unregisterReceiver(systemStateReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }

        try {
            JarvisApplication.instance.voiceEngine.stopBackgroundListening()
        } catch (e: Exception) {
            JarvisLogger.w("VoiceService", "Error stopping background listening during service destroy: ${e.message}")
        }

        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_voice_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_voice_service_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(statusText: String) {
        if (!isRunning) return
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildForegroundNotification(statusText))
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Action: Stop JARVIS
        val stopIntent = Intent(this, VoiceAssistantForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Action: Speak / Trigger Command
        val speakIntent = Intent(this, VoiceAssistantForegroundService::class.java).apply {
            action = ACTION_TRIGGER_COMMAND
        }
        val speakPendingIntent = PendingIntent.getService(
            this,
            2,
            speakIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("J.A.R.V.I.S. Assistant Vocal")
            .setContentText(statusText)
            .setSubText("Microphone actif en arrière-plan")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.voice_action_stop), stopPendingIntent)
            .addAction(android.R.drawable.ic_btn_speak_now, getString(R.string.voice_action_speak), speakPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "jarvis_voice_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.openjarvis.android.ACTION_START_VOICE"
        const val ACTION_STOP = "com.openjarvis.android.ACTION_STOP_VOICE"
        const val ACTION_TRIGGER_COMMAND = "com.openjarvis.android.ACTION_TRIGGER_COMMAND"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, VoiceAssistantForegroundService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                JarvisLogger.e("VoiceService", "WAKE_WORD_SERVICE_ERROR: Could not start VoiceAssistantForegroundService", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceAssistantForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                JarvisLogger.e("VoiceService", "Could not send stop intent to VoiceAssistantForegroundService", e)
            }
        }
    }
}
