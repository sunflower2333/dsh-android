package io.github.sunflower2333.dsh

import org.junit.Assert.*
import org.junit.Test

class AndroidSettingsPolicyTest {
    private fun fields(type: String) = listOf("id" to "settings.42", "type" to type)
    private fun rejected(entries: List<Pair<String, Any?>>) {
        try { AndroidSettingsPolicy.parse(entries); fail("Invalid settings request was accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun commandsCannotSmuggleControlTokensOrCoerceFeedbackConsent() {
        val operations = listOf("status", "open-accessibility", "allow-control", "pause-control", "open-notifications", "open-battery", "open-adb-file")
        for (type in operations) {
            assertEquals(type, AndroidSettingsPolicy.parse(fields(type)).type)
            rejected(fields(type) + ("enabled" to true))
            rejected(fields(type) + ("sessionId" to "private-mobile-token"))
        }
        assertEquals(false, AndroidSettingsPolicy.parse(fields("set-feedback") + ("enabled" to false)).enabled)
        for (value in listOf(null, "true", 1, emptyList<Any>())) rejected(fields("set-feedback") + ("enabled" to value))
        rejected(fields("set-feedback"))
        rejected(fields("grant"))
        assertEquals(false, AndroidSettingsPolicy.parse(fields("set-adb-enabled") + ("enabled" to false)).enabled)
        assertEquals(true, AndroidSettingsPolicy.parse(fields("set-root-enabled") + ("enabled" to true)).enabled)
        assertNull(AndroidSettingsPolicy.parse(fields("set-adb-target") + ("serial" to "")).serial)
        assertEquals("emulator-5554", AndroidSettingsPolicy.parse(fields("set-adb-target") + ("serial" to "emulator-5554")).serial)
        for (serial in listOf("bad serial", "x".repeat(129), "serial\n")) {
            rejected(fields("set-adb-target") + ("serial" to serial))
        }
    }

    @Test fun duplicateFieldsRejectBeforeTheyCanReplaceActionOrIdentity() {
        rejected(fields("status") + ("type" to "allow-control"))
        rejected(fields("allow-control") + ("id" to "replacement"))
        rejected(listOf("id" to "settings.42", "id" to "settings.42", "type" to "status"))
        for (id in listOf("", "x".repeat(65), "secret/token", "中文", "line\nbreak")) rejected(listOf("id" to id, "type" to "status"))
    }

    @Test fun revokedOldPortAndBackgroundTapCannotAuthorizeGrant() {
        val lease = AndroidSettingsGestureLease()
        lease.recordTrustedTap(100)
        assertFalse(lease.consume(101))
        lease.setForeground(true)
        val oldPort = lease.generation
        lease.recordTrustedTap(200)
        lease.invalidate()
        assertFalse(lease.accepts(oldPort))
        assertFalse(lease.consume(201, oldPort))
        lease.recordTrustedTap(300)
        lease.setForeground(false)
        assertFalse(lease.consume(301))
        lease.setForeground(true)
        assertFalse(lease.consume(302))
    }

    @Test fun nativeTapIsSingleUseBoundedAndCannotBeMadeFreshByClockRollback() {
        val lease = AndroidSettingsGestureLease(1_800)
        lease.setForeground(true)
        lease.recordTrustedTap(1_000)
        assertTrue(lease.consume(2_800))
        assertFalse(lease.consume(2_800))
        lease.recordTrustedTap(3_000)
        assertFalse(lease.consume(4_801))
        lease.recordTrustedTap(5_000)
        assertFalse(lease.consume(4_999))
        assertFalse(lease.consume(5_001))
        lease.recordTrustedTap(6_000)
        lease.clearTap()
        assertFalse(lease.consume(6_001))
        assertTrue(AndroidSettingsPolicy.needsUserGesture("allow-control"))
        assertTrue(AndroidSettingsPolicy.needsUserGesture("set-feedback"))
        assertFalse(AndroidSettingsPolicy.needsUserGesture("pause-control"))
    }

    @Test fun pausedControllerCanOnlyBeEnabledWhenSystemAndHostAreReady() {
        assertTrue(AndroidSettingsPolicy.canAllow(true, true, false, "user_paused", true))
        assertFalse(AndroidSettingsPolicy.canAllow(false, true, false, "disabled", true))
        assertFalse(AndroidSettingsPolicy.canAllow(true, false, false, "disconnected", true))
        assertFalse(AndroidSettingsPolicy.canAllow(true, true, false, "device_locked", true))
        assertFalse(AndroidSettingsPolicy.canAllow(true, true, false, "host_stopped", false))
        assertFalse(AndroidSettingsPolicy.canAllow(true, true, false, "host_stopped", true))
        assertFalse(AndroidSettingsPolicy.canAllow(true, true, false, "unavailable", true))
        assertFalse(AndroidSettingsPolicy.canAllow(true, true, false, "unexpected", true))
        assertFalse(AndroidSettingsPolicy.canAllow(true, true, true, "user_grant", true))
    }

    @Test fun deniedNotificationPermissionDoesNotBecomeGrantedFromOpeningSettings() {
        assertTrue(AndroidSettingsPolicy.notificationPermissionGranted(30, false))
        assertTrue(AndroidSettingsPolicy.notificationPermissionGranted(32, false))
        assertFalse(AndroidSettingsPolicy.notificationPermissionGranted(33, false))
        assertFalse(AndroidSettingsPolicy.notificationPermissionGranted(35, false))
        assertTrue(AndroidSettingsPolicy.notificationPermissionGranted(35, true))
    }

    @Test fun statusCannotExposeUnexpectedReasonTextOrPackagePayloads() {
        assertEquals("user_paused", AndroidSettingsPolicy.safeReason("user_paused"))
        assertEquals("unavailable", AndroidSettingsPolicy.safeReason("sensitive untrusted detail"))
        assertEquals("com.android.settings", AndroidSettingsPolicy.safePackage("com.android.settings"))
        for (value in listOf(null, "null", "settings", "com.android/secret", "com.android\nsettings", "a." + "x".repeat(255))) {
            assertNull(AndroidSettingsPolicy.safePackage(value))
        }
    }
}
