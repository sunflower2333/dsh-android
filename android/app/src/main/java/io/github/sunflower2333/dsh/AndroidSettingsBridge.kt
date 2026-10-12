package io.github.sunflower2333.dsh

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.JsonReader
import android.util.JsonToken
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader

/** Private, current-document settings capability. It exposes no mobile tool-session token. */
internal class AndroidSettingsBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val trustedPage: () -> Boolean,
    private val requestAdbFile: () -> Boolean = { false },
) {
    private val lease = AndroidSettingsGestureLease()
    private var ports: Array<WebMessagePort>? = null
    private var foreground = false
    private var permissionRequestPending = false
    private var adbImportPending = false
    private val seenIds = LinkedHashSet<String>()
    private var tapDown: Pair<Float, Float>? = null
    private var tapStartedAt = 0L
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop

    fun install(url: String) {
        invalidate()
        if (!trustedPage() || activity.isFinishing || activity.isDestroyed) return
        val generation = lease.generation
        val channel = webView.createWebMessageChannel()
        ports = channel
        channel[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
            override fun onMessage(port: WebMessagePort?, message: WebMessage?) {
                if (port !== channel[0] || !lease.accepts(generation) || !trustedPage() ||
                    activity.isFinishing || activity.isDestroyed) return
                val command = runCatching {
                    val received = requireNotNull(message)
                    val data = requireNotNull(received.data)
                    require(data.length <= AndroidSettingsPolicy.MAX_MESSAGE_LENGTH && received.ports.isNullOrEmpty())
                    parse(data)
                }.getOrNull()
                if (command == null) { reply(null, false, "invalid_request"); return }
                if (!seenIds.add(command.id)) { reply(command.id, false, "invalid_request"); return }
                // Bounded memory; a discarded ID cannot carry a native tap capability.
                if (seenIds.size > 256) seenIds.remove(seenIds.first())
                if (AndroidSettingsPolicy.needsUserGesture(command.type) && !consumeRecentUserGesture()) {
                    reply(command.id, false, "user_gesture_required"); return
                }
                handle(command)
            }
        })
        val parsed = Uri.parse(url)
        val origin = Uri.parse("${parsed.scheme}://${parsed.host}:${parsed.port}")
        webView.postWebMessage(WebMessage(PORT_HELLO, arrayOf(channel[1])), origin)
    }

    fun invalidate() {
        lease.invalidate()
        tapDown = null
        seenIds.clear()
        adbImportPending = false
        ports?.forEach { runCatching { it.close() } }
        ports = null
    }

    fun onResume() {
        foreground = true
        lease.setForeground(true)
        publishStatus()
    }

    /** The existing authenticated document may reconnect without a WebView navigation. */
    fun onReady() { publishStatus() }

    fun onPause() {
        foreground = false
        lease.setForeground(false)
        tapDown = null
    }

    /** Relay real WebView input before its JS click handler; never consume the event. */
    fun onWebViewTouch(event: MotionEvent) {
        val unobscured = event.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or
            MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) == 0
        val trusted = foreground && activity.hasWindowFocus() && trustedPage() && unobscured &&
            event.pointerCount == 1 && event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
        if (!trusted) { tapDown = null; lease.clearTap(); return }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lease.clearTap()
                tapDown = event.x to event.y
                tapStartedAt = event.eventTime
            }
            MotionEvent.ACTION_MOVE -> {
                val down = tapDown
                if (down != null && (kotlin.math.abs(event.x - down.first) > touchSlop ||
                        kotlin.math.abs(event.y - down.second) > touchSlop)) tapDown = null
            }
            MotionEvent.ACTION_UP -> {
                val down = tapDown
                tapDown = null
                if (down != null && event.eventTime >= tapStartedAt && event.eventTime - tapStartedAt <= 1_800 &&
                    kotlin.math.abs(event.x - down.first) <= touchSlop && kotlin.math.abs(event.y - down.second) <= touchSlop) {
                    lease.recordTrustedTap(SystemClock.uptimeMillis())
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { tapDown = null; lease.clearTap() }
        }
    }

    /** Also usable by MainActivity's native workspace picker route. */
    fun consumeRecentUserGesture(): Boolean {
        if (!foreground || !activity.hasWindowFocus() || !trustedPage()) { lease.clearTap(); return false }
        return lease.consume(SystemClock.uptimeMillis())
    }

    fun onRequestPermissionsResult(requestCode: Int): Boolean {
        if (requestCode != NOTIFICATIONS_REQUEST) return false
        permissionRequestPending = false
        publishStatus()
        return true
    }

    /** Completes the user initiated ADB executable picker without retaining a
     * provider grant. Import is copied into app private storage by the
     * capability manager, and status is pushed only after the copy finishes. */
    fun onAdbFileResult(resultCode: Int, data: Intent?): Boolean {
        if (!adbImportPending) return false
        adbImportPending = false
        val uri = data?.data?.takeIf { it.scheme == "content" }
        if (resultCode != Activity.RESULT_OK || uri == null || !trustedPage()) {
            reply(null, false, if (resultCode == Activity.RESULT_CANCELED) "import_cancelled" else "import_failed")
            return true
        }
        Thread({
            val result = runCatching { MobileUseController.capabilities(activity).importAdb(uri) }
                .getOrElse { JSONObject().put("ok", false).put("error", JSONObject().put("code", "IMPORT_FAILED")) }
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed && trustedPage()) {
                    if (result.optBoolean("ok")) publishStatus()
                    else reply(null, false, "import_failed")
                }
            }
        }, "dsh-adb-import").apply { isDaemon = true }.start()
        return true
    }

    private fun parse(data: String): AndroidSettingsCommand {
        val entries = ArrayList<Pair<String, Any?>>(3)
        JsonReader(StringReader(data)).use { reader ->
            reader.isLenient = false
            require(reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject()
            while (reader.hasNext()) {
                require(entries.size < 3)
                val name = reader.nextName()
                require(name.length <= 32)
                val value = when (reader.peek()) {
                    JsonToken.STRING -> reader.nextString()
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    else -> throw IllegalArgumentException("Invalid settings request")
                }
                entries += name to value
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT)
        }
        return AndroidSettingsPolicy.parse(entries)
    }

    private fun handle(command: AndroidSettingsCommand) {
        val error = try {
            when (command.type) {
                "status" -> null
                "sync-language" -> {
                    if (DshUiLanguage.sync(activity, requireNotNull(command.locale))) {
                        DshService.refreshUiLanguage()
                    }
                    null
                }
                "pause-control" -> { MobileUseController.pause("user_paused"); null }
                "allow-control" -> {
                    val before = snapshot().getJSONObject("mobile")
                    if (!before.getBoolean("canAllow")) "control_unavailable"
                    else {
                        MobileUseController.resumeFromUser(activity)
                        if (snapshot().getJSONObject("mobile").getBoolean("active")) null else "control_unavailable"
                    }
                }
                "set-feedback" -> { MobileFeedbackSettings.setFromUser(activity, requireNotNull(command.enabled)); null }
                "open-adb-file" -> {
                    if (adbImportPending) "unavailable"
                    else if (requestAdbFile()) { adbImportPending = true; null } else "settings_unavailable"
                }
                "set-adb-enabled" -> {
                    val result = MobileUseController.capabilities(activity).setAdbEnabled(requireNotNull(command.enabled))
                    if (result.optBoolean("ok", true)) null else capabilityError(result)
                }
                "set-root-enabled" -> {
                    val result = MobileUseController.capabilities(activity).setRootEnabled(requireNotNull(command.enabled))
                    if (result.optBoolean("ok", true)) null else capabilityError(result)
                }
                "set-adb-target" -> {
                    val result = MobileUseController.capabilities(activity).setAdbTarget(command.serial)
                    if (result.optBoolean("ok", true)) null else capabilityError(result)
                }
                "open-accessibility" -> openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                "open-battery" -> openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                "open-notifications" -> configureNotifications()
                else -> "invalid_request"
            }
        } catch (_: SecurityException) { "settings_unavailable" }
        catch (_: Exception) { "unavailable" }
        reply(command.id, error == null, error)
    }

    private fun configureNotifications(): String? {
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (permissionRequestPending) return "permission_request_pending"
            val preferences = activity.getSharedPreferences("dsh-notifications", Context.MODE_PRIVATE)
            if (!preferences.getBoolean("requested", false) || activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                permissionRequestPending = true
                preferences.edit().putBoolean("requested", true).apply()
                try { activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATIONS_REQUEST) }
                catch (error: Exception) { permissionRequestPending = false; throw error }
                return null
            }
        }
        return openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
    }

    private fun openSettings(intent: Intent): String? = try {
        activity.startActivity(intent)
        null
    } catch (_: ActivityNotFoundException) { "settings_unavailable" }

    private fun snapshot(): JSONObject {
        val native = MobileUseController.status(activity)
        val capabilities = MobileUseController.capabilities(activity).status()
        val runtime = DshService.runtimeTaskStatus
        val enabled = native.getBoolean("enabled")
        val connected = native.getBoolean("connected")
        val active = native.getBoolean("active")
        val reason = AndroidSettingsPolicy.safeReason(native.optString("reason"))
        val manager = activity.getSystemService(NotificationManager::class.java)
        val permissionGranted = AndroidSettingsPolicy.notificationPermissionGranted(Build.VERSION.SDK_INT,
            Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        val disabledChannel = listOf(RuntimeTaskNotifications.ATTENTION_CHANNEL, RuntimeTaskNotifications.COMPLETION_CHANNEL,
            RuntimeTaskNotifications.NOTICE_CHANNEL, RuntimeTaskNotifications.RUNNING_CHANNEL)
            .any { manager.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE }
        val sessions = JSONArray()
        for (session in runtime.sessions.take(64)) {
            // These typed values passed HostEventProtocol's bounded metric schema.
            // Copy only UI fields, never question/reply tickets or controller tokens.
            sessions.put(JSONObject().put("sessionId", session.sessionId)
                .put("name", session.name ?: JSONObject.NULL).put("state", session.state)
                .put("turns", session.turns ?: JSONObject.NULL).put("steps", session.steps ?: JSONObject.NULL)
                .put("inputTokens", session.inputTokens ?: JSONObject.NULL)
                .put("outputTokens", session.outputTokens ?: JSONObject.NULL)
                .put("totalTokens", session.totalTokens ?: JSONObject.NULL)
                .put("cachedInputTokens", session.cachedInputTokens ?: JSONObject.NULL)
                .put("cacheWriteTokens", session.cacheWriteTokens ?: JSONObject.NULL)
                .put("sessionTokens", session.sessionTokens ?: JSONObject.NULL)
                .put("tokensPerSecond", session.tokensPerSecond ?: JSONObject.NULL)
                .put("contextUsed", session.contextUsed ?: JSONObject.NULL)
                .put("contextCapacity", session.contextCapacity ?: JSONObject.NULL))
        }
        return JSONObject()
            .put("mobile", JSONObject().put("enabled", enabled).put("connected", connected).put("active", active)
                .put("reason", reason).put("currentPackage", AndroidSettingsPolicy.safePackage(native.optString("currentPackage")) ?: JSONObject.NULL)
                .put("feedback", MobileFeedbackSettings.enabled(activity))
                .put("canAllow", AndroidSettingsPolicy.canAllow(enabled, connected, active, reason, runtime.hostRunning)))
            .put("capabilities", JSONObject()
                .put("adbConfigured", capabilities.optBoolean("adbConfigured"))
                .put("adbEnabled", capabilities.optBoolean("adbEnabled"))
                .put("adbConnected", capabilities.optBoolean("adbConnected"))
                .put("adbTarget", capabilities.opt("adbTarget"))
                .put("rootAvailable", capabilities.optBoolean("rootAvailable"))
                .put("rootEnabled", capabilities.optBoolean("rootEnabled")))
            .put("runtime", JSONObject().put("hostRunning", runtime.hostRunning).put("connected", runtime.connected)
                .put("running", runtime.running.coerceAtLeast(0)).put("waiting", runtime.waiting.coerceAtLeast(0))
                .put("sessions", sessions).put("sessionsComplete", runtime.sessionsComplete && runtime.sessions.size <= 64))
            .put("notifications", JSONObject().put("enabled", permissionGranted && manager.areNotificationsEnabled())
                .put("permissionRequired", Build.VERSION.SDK_INT >= 33).put("permissionGranted", permissionGranted)
                .put("channelsDisabled", disabledChannel))
            .put("battery", JSONObject().put("unrestricted", activity.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(activity.packageName)))
    }

    private fun publishStatus() { if (foreground && trustedPage()) reply(null, true) }

    private fun capabilityError(result: JSONObject): String = when {
        result.optJSONObject("error")?.optString("code") == "ROOT_UNAVAILABLE" -> "root_unavailable"
        result.optJSONObject("error")?.optString("code") == "ADB_UNAVAILABLE" -> "adb_unavailable"
        result.optJSONObject("error")?.optString("code") == "INVALID_SERIAL" -> "invalid_target"
        else -> "unavailable"
    }

    private fun reply(id: String?, ok: Boolean, error: String? = null) {
        if (ports == null || !trustedPage() || activity.isDestroyed) return
        val reply = JSONObject().put("id", id ?: JSONObject.NULL).put("ok", ok)
        if (error != null) reply.put("error", error)
        runCatching { snapshot() }.onSuccess { reply.put("status", it) }
        runCatching { ports?.get(0)?.postMessage(WebMessage(reply.toString())) }
    }

    companion object {
        const val PORT_HELLO = "dsh.android.settings.port.v1"
        const val NOTIFICATIONS_REQUEST = 47
    }
}
