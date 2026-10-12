package io.github.sunflower2333.dsh

internal data class AndroidSettingsCommand(val id: String, val type: String, val enabled: Boolean? = null,
    val locale: String? = null, val serial: String? = null)

/** A small protocol shared by the real DSH settings page and its private native port. */
internal object AndroidSettingsPolicy {
    const val MAX_MESSAGE_LENGTH = 256
    private val idPattern = Regex("[A-Za-z0-9._:-]{1,64}")
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val types = setOf("status", "open-accessibility", "allow-control", "pause-control",
        "set-feedback", "open-notifications", "open-battery", "sync-language", "open-adb-file",
        "set-adb-enabled", "set-root-enabled", "set-adb-target")
    private val serialPattern = Regex("[A-Za-z0-9._:-]{0,128}")
    private val reasons = setOf("disabled", "disconnected", "device_locked", "user_paused", "user_grant",
        "host_started", "host_stopped", "service_disconnected", "service_interrupted", "unavailable")

    // Keep entries until validation: JSONObject/Map would silently discard duplicate keys.
    fun parse(entries: List<Pair<String, Any?>>): AndroidSettingsCommand {
        require(entries.size in 2..3)
        require(entries.map { it.first }.toSet().size == entries.size)
        val fields = entries.toMap()
        require(fields.keys.all { it in setOf("id", "type", "enabled", "locale", "serial") })
        val id = fields["id"] as? String ?: throw IllegalArgumentException("Invalid settings request")
        val type = fields["type"] as? String ?: throw IllegalArgumentException("Invalid settings request")
        require(idPattern.matches(id) && type in types)
        if (type == "sync-language") {
            require(fields.keys == setOf("id", "type", "locale"))
            val locale = fields["locale"] as? String ?: throw IllegalArgumentException("Invalid settings request")
            require(DshUiLanguagePolicy.isSupported(locale))
            return AndroidSettingsCommand(id, type, locale = locale)
        }
        if (type == "set-adb-target") {
            require(fields.keys == setOf("id", "type", "serial"))
            val serial = fields["serial"] as? String ?: throw IllegalArgumentException("Invalid settings request")
            require(serialPattern.matches(serial))
            return AndroidSettingsCommand(id, type, serial = serial.ifEmpty { null })
        }
        val enabled = if (type == "set-feedback" || type == "set-adb-enabled" || type == "set-root-enabled") {
            require(fields.keys == setOf("id", "type", "enabled"))
            fields["enabled"] as? Boolean ?: throw IllegalArgumentException("Invalid settings request")
        } else {
            require(fields.keys == setOf("id", "type"))
            null
        }
        return AndroidSettingsCommand(id, type, enabled)
    }

    fun needsUserGesture(type: String): Boolean = type != "status" && type != "pause-control" && type != "sync-language"
    fun safeReason(value: String): String = value.takeIf { it in reasons } ?: "unavailable"
    fun safePackage(value: String?): String? = value?.takeIf { it.length <= 255 && packagePattern.matches(it) }
    fun canAllow(enabled: Boolean, connected: Boolean, active: Boolean, reason: String, hostRunning: Boolean): Boolean =
        enabled && connected && !active && hostRunning && reason in setOf("user_paused", "user_grant",
            "host_started", "service_disconnected", "service_interrupted")

    /** Android 13 permission is read, never inferred from request launch or its callback. */
    fun notificationPermissionGranted(sdk: Int, permissionGranted: Boolean): Boolean = sdk < 33 || permissionGranted
}

/** Native-only, one-use tap capability; page lifecycle and backgrounding revoke it. */
internal class AndroidSettingsGestureLease(private val lifetimeMs: Long = 1_800) {
    var generation: Long = 0
        private set
    private var foreground = false
    private var issuedAt: Long? = null

    fun invalidate() { generation++; issuedAt = null }
    fun setForeground(value: Boolean) { foreground = value; issuedAt = null }
    fun recordTrustedTap(now: Long) { issuedAt = now.takeIf { foreground && it >= 0 } }
    fun clearTap() { issuedAt = null }
    fun consume(now: Long, portGeneration: Long = generation): Boolean {
        val issued = issuedAt
        issuedAt = null
        return foreground && issued != null && portGeneration == generation && now >= issued && now - issued <= lifetimeMs
    }
    fun accepts(portGeneration: Long): Boolean = portGeneration == generation
}
