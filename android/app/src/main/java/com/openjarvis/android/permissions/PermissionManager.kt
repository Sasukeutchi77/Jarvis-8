package com.openjarvis.android.permissions

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.openjarvis.android.device.model.CapabilityState
import com.openjarvis.android.device.model.DeviceCapabilityInfo
import com.openjarvis.android.logging.JarvisLogger
import com.openjarvis.android.services.JarvisAccessibilityService
import com.openjarvis.android.services.JarvisDeviceAdminReceiver
import com.openjarvis.android.services.JarvisNotificationListenerService

/**
 * Granular Android Permission state.
 */
enum class PermissionState {
    /** Permission is granted and fully operational */
    GRANTED,
    /** Permission is not granted, but user can be prompted via runtime dialog */
    DENIED,
    /** User previously denied and selected 'Don't ask again' (requires navigating to App Settings) */
    PERMANENTLY_DENIED,
    /** The permission or underlying hardware feature is not supported on this Android OS version or device */
    NOT_SUPPORTED,
    /** Special system access requiring explicit user authorization in dedicated Android Settings screen */
    REQUIRES_SETTINGS
}

/**
 * Classification of Android permissions for audit and security transparency.
 */
enum class PermissionCategory {
    CORE_VOICE_HARDWARE,
    PRIVACY_COMMUNICATIONS,
    SYSTEM_SPECIAL_ACCESS,
    DEVICE_SUPERVISION
}

enum class AndroidPermissionKind {
    RUNTIME_DANGEROUS,
    SPECIAL_SETTINGS_ACCESS,
    SYSTEM_SERVICE_BINDING,
    DEVICE_ADMIN_POLICY
}

/**
 * Full audit record for an Android permission.
 */
data class AndroidPermissionAudit(
    val id: String,
    val name: String,
    val category: PermissionCategory,
    val kind: AndroidPermissionKind,
    val declaredManifest: Boolean,
    val targetApiMin: Int,
    val isGranted: Boolean,
    val state: PermissionState,
    val whyNeeded: String,
    val officialIntentAction: String?,
    val settingsResolutionPath: String
)

/**
 * Snapshot of all critical permission flags.
 */
data class PermissionStatus(
    val hasAudioPermission: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val hasNotificationListenerPermission: Boolean = false,
    val hasOverlayPermission: Boolean = false,
    val hasCameraPermission: Boolean = false,
    val hasCalendarPermission: Boolean = false,
    val hasContactsPermission: Boolean = false,
    val hasSmsPermission: Boolean = false,
    val hasPhonePermission: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val hasBluetoothPermission: Boolean = false,
    val hasStoragePermission: Boolean = false,
    val hasAccessibilityPermission: Boolean = false,
    val hasScreenCapturePermission: Boolean = false,
    val hasDeviceAdminPermission: Boolean = false,
    val hasUsageStatsPermission: Boolean = false,
    val hasBatteryOptimizationIgnored: Boolean = false
)

/**
 * Centralized Manager for checking, requesting, and auditing Android permissions and capabilities.
 * Adheres strictly to official Android platform specifications (API 24 to 35).
 */
object PermissionManager {

    /**
     * Minimal essential permissions requested at launch:
     * - RECORD_AUDIO: Voice activation & wake word
     * - POST_NOTIFICATIONS (API 33+): Foreground service notification
     */
    val REQUIRED_CORE_PERMISSIONS = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    /**
     * All runtime permissions declared across JARVIS capabilities.
     * Note: Requested progressively / just-in-time when the corresponding feature is used.
     */
    val ALL_RUNTIME_PERMISSIONS = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.WRITE_CONTACTS)
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
    }.toTypedArray()

    // =========================================================================
    // PERMISSION STATE DETECTION
    // =========================================================================

    /**
     * Detects the granular state of a runtime permission.
     * Accurately differentiates between GRANTED, DENIED, PERMANENTLY_DENIED and NOT_SUPPORTED.
     */
    fun getPermissionState(activity: Activity?, context: Context, permission: String): PermissionState {
        // Version-specific checks
        if (permission == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return PermissionState.GRANTED
        }

        if ((permission == Manifest.permission.BLUETOOTH_CONNECT || permission == Manifest.permission.BLUETOOTH_SCAN) &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        ) {
            return if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED) {
                PermissionState.GRANTED
            } else {
                PermissionState.DENIED
            }
        }

        if (permission.startsWith("android.permission.READ_MEDIA_") && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                PermissionState.GRANTED
            } else {
                PermissionState.DENIED
            }
        }

        val isGranted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (isGranted) {
            return PermissionState.GRANTED
        }

        if (activity != null) {
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            return if (shouldShowRationale) {
                PermissionState.DENIED
            } else {
                // If denied and rationale returns false, it could be first time or permanently denied.
                // In production, tracking first prompt via SharedPreferences confirms PERMANENTLY_DENIED.
                val prefs = context.getSharedPreferences("jarvis_permissions", Context.MODE_PRIVATE)
                val wasRequestedBefore = prefs.getBoolean("perm_req_$permission", false)
                if (wasRequestedBefore) {
                    PermissionState.PERMANENTLY_DENIED
                } else {
                    PermissionState.DENIED
                }
            }
        }

        return PermissionState.DENIED
    }

    /**
     * Mark a permission as previously requested to track permanent denial accurately.
     */
    fun markPermissionRequested(context: Context, permission: String) {
        context.getSharedPreferences("jarvis_permissions", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("perm_req_$permission", true)
            .apply()
    }

    /**
     * Inspect overall permission status snapshot.
     */
    fun checkStatus(context: Context): PermissionStatus {
        val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        
        val hasNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.areNotificationsEnabled() ?: true
        }

        val hasNotifListener = isNotificationListenerEnabled(context)
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }

        val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasCalendar = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val hasContacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val hasSms = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
                     ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        
        val hasPhone = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                          ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        val hasBluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }

        val hasStorage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager() || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        val hasAccessibility = isAccessibilityServiceEnabled(context)
        val hasDeviceAdmin = isDeviceAdminActive(context)
        val hasUsage = hasUsageStatsPermission(context)
        val hasBatteryIgnored = isIgnoringBatteryOptimizations(context)

        return PermissionStatus(
            hasAudioPermission = hasAudio,
            hasNotificationPermission = hasNotification,
            hasNotificationListenerPermission = hasNotifListener,
            hasOverlayPermission = hasOverlay,
            hasCameraPermission = hasCamera,
            hasCalendarPermission = hasCalendar,
            hasContactsPermission = hasContacts,
            hasSmsPermission = hasSms,
            hasPhonePermission = hasPhone,
            hasLocationPermission = hasLocation,
            hasBluetoothPermission = hasBluetooth,
            hasStoragePermission = hasStorage,
            hasAccessibilityPermission = hasAccessibility,
            hasScreenCapturePermission = true, // Prompted on-demand via MediaProjection intent
            hasDeviceAdminPermission = hasDeviceAdmin,
            hasUsageStatsPermission = hasUsage,
            hasBatteryOptimizationIgnored = hasBatteryIgnored
        )
    }

    // =========================================================================
    // SPECIAL PERMISSION & SERVICE STATUS
    // =========================================================================

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val packageName = context.packageName
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        if (JarvisAccessibilityService.instance != null) return true
        val expectedComponentName = "${context.packageName}/com.openjarvis.android.services.JarvisAccessibilityService"
        val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices != null && enabledServices.contains(expectedComponentName)
    }

    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        val adminComponent = ComponentName(context, JarvisDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(adminComponent)
    }

    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun canWriteSystemSettings(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else {
            true
        }
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }

    // =========================================================================
    // CAPABILITY MAPPING FOR AI REASONING & INTROSPECTION
    // =========================================================================

    /**
     * Produces a clean map of high-level capabilities for JARVIS AI system prompt & decision making.
     * Example: { "MICROPHONE": "AVAILABLE", "ACCESSIBILITY": "ENABLED", "OVERLAY": "ENABLED", "NOTIFICATIONS": "GRANTED", "LOCATION": "DENIED" }
     */
    fun getAvailableCapabilitiesSummary(context: Context): Map<String, String> {
        val status = checkStatus(context)
        val pm = context.packageManager

        val hasTelephony = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val hasFlash = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        val hasBluetoothHardware = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        val hasCameraHardware = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

        return mapOf(
            "MICROPHONE" to if (status.hasAudioPermission) "AVAILABLE" else "PERMISSION_MISSING",
            "NOTIFICATIONS" to if (status.hasNotificationPermission) "GRANTED" else "DENIED",
            "ACCESSIBILITY" to if (status.hasAccessibilityPermission) "ENABLED" else "DISABLED",
            "OVERLAY" to if (status.hasOverlayPermission) "ENABLED" else "DISABLED",
            "NOTIFICATION_LISTENER" to if (status.hasNotificationListenerPermission) "ENABLED" else "DISABLED",
            "CONTACTS" to if (status.hasContactsPermission) "GRANTED" else "DENIED",
            "SMS" to if (!hasTelephony) "NOT_SUPPORTED" else if (status.hasSmsPermission) "GRANTED" else "DENIED",
            "PHONE_CALLS" to if (!hasTelephony) "NOT_SUPPORTED" else if (status.hasPhonePermission) "GRANTED" else "DENIED",
            "LOCATION" to if (status.hasLocationPermission) "GRANTED" else "DENIED",
            "BLUETOOTH" to if (!hasBluetoothHardware) "NOT_SUPPORTED" else if (status.hasBluetoothPermission) "GRANTED" else "DENIED",
            "CAMERA" to if (!hasCameraHardware) "NOT_SUPPORTED" else if (status.hasCameraPermission) "GRANTED" else "DENIED",
            "STORAGE" to if (status.hasStoragePermission) "GRANTED" else "DENIED",
            "FLASHLIGHT" to if (hasFlash) "AVAILABLE" else "NOT_SUPPORTED",
            "DEVICE_ADMIN" to if (status.hasDeviceAdminPermission) "ACTIVE" else "INACTIVE",
            "BATTERY_OPTIMIZATION" to if (status.hasBatteryOptimizationIgnored) "IGNORED" else "OPTIMIZED"
        )
    }

    /**
     * Inspect all individual device capability models for settings UI & diagnostics.
     */
    fun auditCapabilities(context: Context): List<DeviceCapabilityInfo> {
        val status = checkStatus(context)
        val pm = context.packageManager

        val hasFlash = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        val hasCamera = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        val hasTelephony = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val hasBluetooth = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        val hasWifi = pm.hasSystemFeature(PackageManager.FEATURE_WIFI)

        return listOf(
            DeviceCapabilityInfo(
                key = "microphone",
                name = "Microphone & Écoute Vocale",
                description = "Reconnaissance vocale temps réel et détection du mot de réveil « Hey JARVIS ».",
                state = if (status.hasAudioPermission) CapabilityState.AVAILABLE else CapabilityState.PERMISSION_MISSING,
                minApiLevel = 1,
                requiredPermission = Manifest.permission.RECORD_AUDIO,
                settingsAction = null
            ),
            DeviceCapabilityInfo(
                key = "notifications",
                name = "Notifications Système",
                description = "Maintien du service d'arrière-plan et affichage des alertes de rappel.",
                state = if (status.hasNotificationPermission) CapabilityState.AVAILABLE else CapabilityState.PERMISSION_MISSING,
                minApiLevel = 33,
                requiredPermission = Manifest.permission.POST_NOTIFICATIONS,
                settingsAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Settings.ACTION_APP_NOTIFICATION_SETTINGS else null
            ),
            DeviceCapabilityInfo(
                key = "overlay",
                name = "Affichage Flottant Overlay",
                description = "HUD holographique interactif au-dessus des applications.",
                state = if (status.hasOverlayPermission) CapabilityState.AVAILABLE else CapabilityState.PERMISSION_MISSING,
                minApiLevel = 23,
                requiredPermission = Manifest.permission.SYSTEM_ALERT_WINDOW,
                settingsAction = Settings.ACTION_MANAGE_OVERLAY_PERMISSION
            ),
            DeviceCapabilityInfo(
                key = "accessibility",
                name = "Service d'Accessibilité",
                description = "Actions système globales (Accueil, Récents, Retour) et vision contextuelle de l'écran.",
                state = if (status.hasAccessibilityPermission) CapabilityState.AVAILABLE else CapabilityState.SYSTEM_RESTRICTED,
                minApiLevel = 14,
                requiredPermission = Manifest.permission.BIND_ACCESSIBILITY_SERVICE,
                settingsAction = Settings.ACTION_ACCESSIBILITY_SETTINGS
            ),
            DeviceCapabilityInfo(
                key = "notification_listener",
                name = "Écoute des Notifications (Messagerie)",
                description = "NotificationListenerService pour lecture vocale et réponses WhatsApp / SMS.",
                state = if (status.hasNotificationListenerPermission) CapabilityState.AVAILABLE else CapabilityState.SYSTEM_RESTRICTED,
                minApiLevel = 18,
                requiredPermission = Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE,
                settingsAction = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            ),
            DeviceCapabilityInfo(
                key = "flashlight",
                name = "Lampe Torche / Flash LED",
                description = "Activation et extinction physique du flash caméra.",
                state = if (!hasFlash) CapabilityState.HARDWARE_UNAVAILABLE else CapabilityState.AVAILABLE,
                minApiLevel = 23,
                requiredPermission = Manifest.permission.FLASHLIGHT,
                settingsAction = null
            ),
            DeviceCapabilityInfo(
                key = "camera",
                name = "Capteur Caméra & OCR",
                description = "Vision multimodale, lecture de texte et analyse d'images.",
                state = if (!hasCamera) CapabilityState.HARDWARE_UNAVAILABLE 
                        else if (status.hasCameraPermission) CapabilityState.AVAILABLE 
                        else CapabilityState.PERMISSION_MISSING,
                minApiLevel = 1,
                requiredPermission = Manifest.permission.CAMERA,
                settingsAction = null
            ),
            DeviceCapabilityInfo(
                key = "contacts",
                name = "Répertoire Contacts",
                description = "Résolution vocale de contacts pour appels et messages.",
                state = if (status.hasContactsPermission) CapabilityState.AVAILABLE else CapabilityState.PERMISSION_MISSING,
                minApiLevel = 1,
                requiredPermission = Manifest.permission.READ_CONTACTS,
                settingsAction = null
            ),
            DeviceCapabilityInfo(
                key = "phone",
                name = "Appels Téléphoniques",
                description = "Composition et initialisation d'appels voix.",
                state = if (!hasTelephony) CapabilityState.HARDWARE_UNAVAILABLE
                        else if (status.hasPhonePermission) CapabilityState.AVAILABLE
                        else CapabilityState.PERMISSION_MISSING,
                minApiLevel = 1,
                requiredPermission = Manifest.permission.CALL_PHONE,
                settingsAction = null
            ),
            DeviceCapabilityInfo(
                key = "sms",
                name = "Messagerie SMS",
                description = "Envoi et rédaction assistée de SMS.",
                state = if (!hasTelephony) CapabilityState.HARDWARE_UNAVAILABLE
                        else if (status.hasSmsPermission) CapabilityState.AVAILABLE
                        else CapabilityState.PERMISSION_MISSING,
                minApiLevel = 1,
                requiredPermission = Manifest.permission.SEND_SMS,
                settingsAction = null
            ),
            DeviceCapabilityInfo(
                key = "location",
                name = "Localisation GPS & Météo",
                description = "Météo locale certifiée temps réel et points d'intérêt.",
                state = if (status.hasLocationPermission) CapabilityState.AVAILABLE else CapabilityState.PERMISSION_MISSING,
                minApiLevel = 1,
                requiredPermission = Manifest.permission.ACCESS_FINE_LOCATION,
                settingsAction = null
            ),
            DeviceCapabilityInfo(
                key = "bluetooth",
                name = "Contrôle Bluetooth",
                description = "État et configuration des périphériques Bluetooth.",
                state = if (!hasBluetooth) CapabilityState.HARDWARE_UNAVAILABLE
                        else if (status.hasBluetoothPermission) CapabilityState.AVAILABLE
                        else CapabilityState.PERMISSION_MISSING,
                minApiLevel = 1,
                requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH,
                settingsAction = Settings.ACTION_BLUETOOTH_SETTINGS
            ),
            DeviceCapabilityInfo(
                key = "wifi",
                name = "Contrôle Wi-Fi",
                description = "État et ouverture des réglages Wi-Fi.",
                state = if (!hasWifi) CapabilityState.HARDWARE_UNAVAILABLE else CapabilityState.AVAILABLE,
                minApiLevel = 1,
                requiredPermission = Manifest.permission.ACCESS_WIFI_STATE,
                settingsAction = Settings.ACTION_WIFI_SETTINGS
            ),
            DeviceCapabilityInfo(
                key = "brightness_write",
                name = "Modification Luminosité",
                description = "Écriture directe du paramètre de luminosité système.",
                state = if (canWriteSystemSettings(context)) CapabilityState.AVAILABLE else CapabilityState.SYSTEM_RESTRICTED,
                minApiLevel = 23,
                requiredPermission = Manifest.permission.WRITE_SETTINGS,
                settingsAction = Settings.ACTION_MANAGE_WRITE_SETTINGS
            ),
            DeviceCapabilityInfo(
                key = "device_admin",
                name = "Administration Appareil",
                description = "Verrouillage immédiat et politique de sécurité avancée.",
                state = if (status.hasDeviceAdminPermission) CapabilityState.AVAILABLE else CapabilityState.SYSTEM_RESTRICTED,
                minApiLevel = 8,
                requiredPermission = Manifest.permission.BIND_DEVICE_ADMIN,
                settingsAction = Settings.ACTION_SECURITY_SETTINGS
            )
        )
    }

    // =========================================================================
    // EXPLANATION AND GUIDANCE (USER INTENT & PRIVACY)
    // =========================================================================

    /**
     * Provides clear, transparent explanation for why a permission is requested.
     */
    fun getExplanationForPermission(permission: String): String {
        return when (permission) {
            Manifest.permission.RECORD_AUDIO -> 
                "Cette autorisation est nécessaire pour utiliser les commandes vocales et le mot de réveil « Hey JARVIS »."
            Manifest.permission.POST_NOTIFICATIONS -> 
                "Cette autorisation est nécessaire pour afficher la notification persistante de veille et vos rappels."
            Manifest.permission.SYSTEM_ALERT_WINDOW, "OVERLAY" -> 
                "Cette autorisation est nécessaire pour projeter l'Hologramme JARVIS au-dessus de vos applications."
            Manifest.permission.BIND_ACCESSIBILITY_SERVICE, "ACCESSIBILITY" -> 
                "Cette autorisation est nécessaire pour permettre à JARVIS de lire l'écran et exécuter les actions système demandées."
            Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE, "NOTIFICATION_LISTENER" -> 
                "Cette autorisation est nécessaire pour que JARVIS puisse lire vocalement vos messages WhatsApp, Telegram et SMS reçus."
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS -> 
                "Cette autorisation est nécessaire pour identifier les destinataires lorsque vous demandez d'appeler ou d'envoyer un message."
            Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS -> 
                "Cette autorisation est nécessaire pour envoyer vos SMS après confirmation vocale et consulter vos messages."
            Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE -> 
                "Cette autorisation est nécessaire pour passer des appels téléphoniques directs sur votre commande vocale."
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION -> 
                "Cette autorisation est nécessaire pour vous fournir la météo locale et les informations géographiques pertinentes."
            Manifest.permission.CAMERA -> 
                "Cette autorisation est nécessaire pour analyser des documents, photographies ou objets via la vision JARVIS."
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH -> 
                "Cette autorisation est nécessaire pour gérer vos casques audio et accessoires Bluetooth."
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_EXTERNAL_STORAGE -> 
                "Cette autorisation est nécessaire pour lire les fichiers et documents que vous souhaitez analyser."
            Manifest.permission.WRITE_SETTINGS -> 
                "Cette autorisation est nécessaire pour ajuster la luminosité de l'écran."
            Manifest.permission.PACKAGE_USAGE_STATS -> 
                "Cette autorisation est nécessaire pour analyser le temps d'écran et les statistiques d'utilisation."
            else -> 
                "Cette autorisation est nécessaire pour utiliser cette fonctionnalité."
        }
    }

    // =========================================================================
    // INTENT CREATION & SETTINGS NAVIGATION
    // =========================================================================

    /**
     * Creates the exact official Android Intent to navigate the user to the correct Settings screen.
     */
    fun createSettingsIntent(context: Context, permissionOrKey: String): Intent {
        val packageName = context.packageName
        return when (permissionOrKey) {
            Manifest.permission.SYSTEM_ALERT_WINDOW, "overlay", "OVERLAY" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                } else {
                    createAppSettingsIntent(packageName)
                }
            }
            Manifest.permission.BIND_ACCESSIBILITY_SERVICE, "accessibility", "ACCESSIBILITY" -> {
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
            Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE, "notification_listener", "NOTIFICATION_LISTENER" -> {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
            Manifest.permission.WRITE_SETTINGS, "brightness_write" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                } else {
                    createAppSettingsIntent(packageName)
                }
            }
            Manifest.permission.POST_NOTIFICATIONS, "notifications" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                } else {
                    createAppSettingsIntent(packageName)
                }
            }
            Manifest.permission.PACKAGE_USAGE_STATS, "usage_stats" -> {
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            }
            Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_CONNECT, "bluetooth" -> {
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            }
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, "location" -> {
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            }
            "wifi" -> {
                Intent(Settings.ACTION_WIFI_SETTINGS)
            }
            "battery_optimization" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                } else {
                    createAppSettingsIntent(packageName)
                }
            }
            "device_admin" -> {
                Intent(Settings.ACTION_SECURITY_SETTINGS)
            }
            else -> {
                createAppSettingsIntent(packageName)
            }
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun createAppSettingsIntent(packageName: String): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Safely opens the relevant settings page with automatic fallback to App Settings.
     */
    fun openSettingsSafely(context: Context, permissionOrKey: String): Boolean {
        return try {
            val intent = createSettingsIntent(context, permissionOrKey)
            context.startActivity(intent)
            JarvisLogger.i("PermissionManager", "Opened settings for $permissionOrKey")
            true
        } catch (e: Exception) {
            JarvisLogger.w("PermissionManager", "Could not open specific settings for $permissionOrKey: ${e.message}. Falling back to App Details.")
            try {
                val fallbackIntent = createAppSettingsIntent(context.packageName)
                context.startActivity(fallbackIntent)
                true
            } catch (fallbackEx: Exception) {
                JarvisLogger.e("PermissionManager", "Failed to open fallback app settings", fallbackEx)
                false
            }
        }
    }
}
