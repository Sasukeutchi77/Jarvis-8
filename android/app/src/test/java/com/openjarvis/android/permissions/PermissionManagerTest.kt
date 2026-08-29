package com.openjarvis.android.permissions

import android.Manifest
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionManagerTest {

    @Test
    fun testRequiredCorePermissionsAreMinimal() {
        // Essential core permissions must only contain RECORD_AUDIO and optionally POST_NOTIFICATIONS (API 33+)
        val corePerms = PermissionManager.REQUIRED_CORE_PERMISSIONS.toList()
        assertTrue("Core permissions must include RECORD_AUDIO", corePerms.contains(Manifest.permission.RECORD_AUDIO))
        assertFalse("Core permissions must NOT include CAMERA at first launch", corePerms.contains(Manifest.permission.CAMERA))
        assertFalse("Core permissions must NOT include SEND_SMS at first launch", corePerms.contains(Manifest.permission.SEND_SMS))
        assertFalse("Core permissions must NOT include READ_CONTACTS at first launch", corePerms.contains(Manifest.permission.READ_CONTACTS))
        assertFalse("Core permissions must NOT include CALL_PHONE at first launch", corePerms.contains(Manifest.permission.CALL_PHONE))
        assertTrue("Core permissions list must be minimal (<= 2)", corePerms.size <= 2)
    }

    @Test
    fun testAllRuntimePermissionsCompleteness() {
        val runtimePerms = PermissionManager.ALL_RUNTIME_PERMISSIONS.toList()
        assertTrue(runtimePerms.contains(Manifest.permission.RECORD_AUDIO))
        assertTrue(runtimePerms.contains(Manifest.permission.CAMERA))
        assertTrue(runtimePerms.contains(Manifest.permission.READ_CONTACTS))
        assertTrue(runtimePerms.contains(Manifest.permission.WRITE_CONTACTS))
        assertTrue(runtimePerms.contains(Manifest.permission.SEND_SMS))
        assertTrue(runtimePerms.contains(Manifest.permission.READ_SMS))
        assertTrue(runtimePerms.contains(Manifest.permission.RECEIVE_SMS))
        assertTrue(runtimePerms.contains(Manifest.permission.CALL_PHONE))
        assertTrue(runtimePerms.contains(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    @Test
    fun testPermissionExplanationsAreClearAndFrench() {
        val audioExplanation = PermissionManager.getExplanationForPermission(Manifest.permission.RECORD_AUDIO)
        assertTrue(audioExplanation.contains("Cette autorisation est nécessaire"))
        assertTrue(audioExplanation.contains("Hey JARVIS") || audioExplanation.contains("vocale"))

        val smsExplanation = PermissionManager.getExplanationForPermission(Manifest.permission.SEND_SMS)
        assertTrue(smsExplanation.contains("Cette autorisation est nécessaire"))
        assertTrue(smsExplanation.contains("SMS"))

        val overlayExplanation = PermissionManager.getExplanationForPermission(Manifest.permission.SYSTEM_ALERT_WINDOW)
        assertTrue(overlayExplanation.contains("Cette autorisation est nécessaire"))
        assertTrue(overlayExplanation.contains("Hologramme"))

        val accessibilityExplanation = PermissionManager.getExplanationForPermission(Manifest.permission.BIND_ACCESSIBILITY_SERVICE)
        assertTrue(accessibilityExplanation.contains("Cette autorisation est nécessaire"))
        assertTrue(accessibilityExplanation.contains("écran") || accessibilityExplanation.contains("système"))

        val contactsExplanation = PermissionManager.getExplanationForPermission(Manifest.permission.READ_CONTACTS)
        assertTrue(contactsExplanation.contains("Cette autorisation est nécessaire"))
        assertTrue(contactsExplanation.contains("destinataires") || contactsExplanation.contains("appeler"))
    }

    @Test
    fun testPermissionStateEnumValues() {
        val states = PermissionState.values()
        assertTrue(states.contains(PermissionState.GRANTED))
        assertTrue(states.contains(PermissionState.DENIED))
        assertTrue(states.contains(PermissionState.PERMANENTLY_DENIED))
        assertTrue(states.contains(PermissionState.NOT_SUPPORTED))
        assertTrue(states.contains(PermissionState.REQUIRES_SETTINGS))
    }
}
