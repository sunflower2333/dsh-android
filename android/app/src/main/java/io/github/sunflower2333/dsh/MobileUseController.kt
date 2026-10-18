package io.github.sunflower2333.dsh

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject
import java.util.UUID

/** App-local consent. System accessibility permission alone never starts agent control. */
object MobileUseController {
    private val lock = Any()
    private var service: MobileAccessibilityService? = null
    private var session: String? = null
    private var generation = 0L
    private var reason = "user_paused"
    private var capabilityManager: AndroidCapabilityManager? = null
    @Volatile private var currentPackage: String? = null

    fun status(context: Context): JSONObject {
        val enabled = isEnabled(context)
        val locked = isDeviceLocked(context)
        if (locked) pause("device_locked")
        return synchronized(lock) {
            val connected = service != null
            JSONObject().put("enabled", enabled).put("connected", connected)
                .put("active", enabled && connected && !locked && session != null)
                .put("sessionId", session ?: JSONObject.NULL)
                .put("reason", when {
                    !enabled -> "disabled"
                    !connected -> "disconnected"
                    locked -> "device_locked"
                    reason == "device_locked" -> "user_paused"
                    else -> reason
                }).put("currentPackage", currentPackage ?: JSONObject.NULL)
        }
    }

    /** Called only after a genuine user gesture, never a tool RPC or unguarded WebView message. */
    fun resumeFromUser(context: Context): JSONObject {
        val (epoch, attached, manager) = synchronized(lock) {
            generation++
            session = null
            reason = "user_paused"
            service?.invalidateObservations()
            Triple(generation, service, capabilityManager)
        }
        // Revoke old work before publishing a new UUID, without holding the
        // grant lock while cancellation waits for child-process registration.
        manager?.cancelAll()
        synchronized(lock) {
            // A concurrent pause/detach wins over this pending user grant.
            if (generation == epoch && service === attached) {
                session = if (service != null && isEnabled(context) && !isDeviceLocked(context)) UUID.randomUUID().toString() else null
                reason = if (session == null) "user_paused" else "user_grant"
            }
        }
        return status(context)
    }

    fun pause(reason: String) {
        val manager = synchronized(lock) {
            generation++
            session = null
            this.reason = reason
            service?.invalidateObservations()
            capabilityManager
        }
        manager?.cancelAll()
    }

    internal fun attach(value: MobileAccessibilityService) {
        val manager = synchronized(lock) {
            generation++
            session = null
            service = value
            reason = "user_paused"
            capabilityManager
        }
        manager?.cancelAll()
    }

    internal fun detach(value: MobileAccessibilityService) {
        val manager = synchronized(lock) {
            if (service !== value) return
            generation++
            session = null
            service = null
            currentPackage = null
            reason = "service_disconnected"
            capabilityManager
        }
        manager?.cancelAll()
    }

    internal fun updatePackage(value: String?) { currentPackage = value }
    internal fun hideFeedback() { synchronized(lock) { service?.hideFeedback() } }

    /** Shared capability state for bridge requests and settings; never exposed to WebView code. */
    internal fun capabilities(context: Context): AndroidCapabilityManager = synchronized(lock) {
        capabilityManager ?: AndroidCapabilityManager(context.applicationContext).also { capabilityManager = it }
    }

    internal data class Grant(val sessionId: String, val generation: Long, val service: MobileAccessibilityService)

    internal fun valid(grant: Grant): Boolean = synchronized(lock) {
        service === grant.service && session == grant.sessionId && generation == grant.generation
    }

    internal fun execute(context: Context, command: MobileCommand, complete: (JSONObject) -> Unit) {
        if (command === MobileCommand.Status) {
            complete(success(status(context)))
            return
        }
        if (command === MobileCommand.AdbStatus) {
            complete(success(capabilities(context).status()))
            return
        }
        if (command === MobileCommand.Stop) {
            pause("user_paused")
            complete(success(status(context)))
            return
        }
        val requestedSession = when (command) {
            is MobileCommand.Observe -> command.sessionId
            is MobileCommand.Click -> command.sessionId
            is MobileCommand.Type -> command.sessionId
            is MobileCommand.Swipe -> command.sessionId
            is MobileCommand.Scroll -> command.sessionId
            is MobileCommand.Back -> command.sessionId
            is MobileCommand.ListApps -> command.sessionId
            is MobileCommand.OpenApp -> command.sessionId
            is MobileCommand.AdbShell -> command.sessionId
            is MobileCommand.RootShell -> command.sessionId
            is MobileCommand.UiXml -> command.sessionId
            else -> null
        }
        val grant = synchronized(lock) {
            val connected = service
            if (session != null && session == requestedSession && connected != null) Grant(session!!, generation, connected) else null
        }
        if (!isEnabled(context)) complete(failure("accessibility_disabled", "Enable DSH accessibility in Android settings"))
        else if (isDeviceLocked(context)) {
            pause("device_locked")
            complete(failure("locked", "Unlock the device before phone control"))
        }
        else if (grant == null) complete(failure("paused", "Phone control requires a current native user grant"))
        else when (command) {
            is MobileCommand.AdbShell -> {
                val manager = capabilities(context)
                val prepared = prepareAdb(grant, manager)
                if (prepared == null) {
                    complete(failure("paused", "Phone control requires a current native user grant"))
                    return
                }
                var result = manager.executeAdb(
                    grant.sessionId, command.serial, command.argv, command.timeoutMs, preparedRequest = prepared,
                )
                // Keep the requested target explicit in the response metadata. The
                // manager may select a configured default when serial is omitted;
                // never expose that value through an untrusted command error.
                if (result.optBoolean("ok")) {
                    result = authorizeAdb(context, grant, manager, prepared,
                        requireCurrentRequest = command.argv.firstOrNull() !in setOf("disconnect", "kill-server")) ?: result
                    result.optJSONObject("value")?.put("target", command.serial ?: prepared.configuredTarget ?: JSONObject.NULL)
                }
                complete(result)
            }
            is MobileCommand.RootShell -> {
                val manager = capabilities(context)
                val prepared = synchronized(lock) {
                    if (valid(grant)) manager.prepareRootRequest() else null
                }
                if (prepared == null) {
                    complete(failure("paused", "Phone control requires a current native user grant"))
                    return
                }
                var result = manager.executeRoot(
                    grant.sessionId, command.argv, command.timeoutMs, preparedRequest = prepared,
                )
                if (result.optBoolean("ok")) {
                    result = when {
                        !isEnabled(context) -> failure("accessibility_disabled", "Enable DSH accessibility in Android settings")
                        isDeviceLocked(context) -> {
                            pause("device_locked")
                            failure("locked", "Unlock the device before phone control")
                        }
                        !valid(grant) -> failure("paused", "Phone control requires a current native user grant")
                        else -> manager.rootRequestError(prepared)?.let {
                            failure(it, "Root capability changed during this request")
                        } ?: result
                    }
                }
                complete(result)
            }
            is MobileCommand.UiXml -> if (command.source == "adb_uiautomator") {
                val manager = capabilities(context)
                val prepared = prepareAdb(grant, manager)
                if (prepared == null) {
                    complete(failure("paused", "Phone control requires a current native user grant"))
                    return
                }
                // Keep every stage on the target selected at request start.
                // Null retains ADB's single-device selection (multiple devices
                // are rejected by ADB rather than guessed by this controller).
                val target = command.serial ?: prepared.configuredTarget
                val result = AdbUiXmlCapture(
                    execute = { argv, timeoutMs -> manager.executeAdb(
                        grant.sessionId, target, argv, timeoutMs, preparedRequest = prepared,
                    ) },
                    authorize = { authorizeAdb(context, grant, manager, prepared, requireCurrentRequest = true) },
                    helperFile = { AdbUiXmlHelperAsset(java.io.File(context.cacheDir, "dsh-ui-xml")) {
                        context.assets.open("ui-xml/dsh-ui-xml-helper.jar")
                    }.materialize() },
                ).capture()
                if (result.optBoolean("ok")) {
                    result.optJSONObject("value")?.put("source", command.source)
                        ?.put("target", target ?: JSONObject.NULL)
                }
                complete(result)
            } else {
                // Accessibility XML serialization is owned by the service (Task 3).
                // Keep the request on the service's serial queue so it shares the
                // current-window, grant, and lock checks with observations.
                grant.service.execute(command, grant, complete)
            }
            else -> grant.service.execute(command, grant, complete)
        }
    }

    private fun prepareAdb(grant: Grant, manager: AndroidCapabilityManager): AndroidCapabilityManager.PreparedAdbRequest? =
        synchronized(lock) { if (valid(grant)) manager.prepareAdbRequest() else null }

    private fun authorizeAdb(
        context: Context,
        grant: Grant,
        manager: AndroidCapabilityManager,
        prepared: AndroidCapabilityManager.PreparedAdbRequest,
        requireCurrentRequest: Boolean,
    ): JSONObject? {
        if (!isEnabled(context)) return failure("accessibility_disabled", "Enable DSH accessibility in Android settings")
        if (isDeviceLocked(context)) {
            pause("device_locked")
            return failure("locked", "Unlock the device before phone control")
        }
        if (!valid(grant)) return failure("paused", "Phone control requires a current native user grant")
        return AdbRequestAuthorization.errorCode(manager, prepared, requireCurrentRequest)?.let {
            failure(it, "ADB capability changed during this request")
        }
    }

    internal fun isDeviceLocked(context: Context): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked ||
            !(context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    private fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, MobileAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    internal fun success(value: JSONObject): JSONObject = JSONObject().put("ok", true).put("value", value)
    internal fun failure(code: String, message: String): JSONObject = JSONObject().put("ok", false)
        .put("error", JSONObject().put("code", code).put("message", message))
}
