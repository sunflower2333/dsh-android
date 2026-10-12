package io.github.sunflower2333.dsh

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Android's actual accessibility channel. No ADB, shell input, or WebView injection. */
class MobileAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val feedback by lazy { MobileActionFeedback(this, main) }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { }
        override fun onDisplayRemoved(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) feedback.hide() }
        override fun onDisplayChanged(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) feedback.hide() }
    }
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    // A late platform callback must still close its HardwareBuffer after teardown.
    private val screenshotCallbacks = Executor { task ->
        try { screenshotExecutor.execute(task) } catch (_: RejectedExecutionException) { task.run() }
    }
    private val busy = AtomicBoolean(false)
    private val revision = AtomicLong()
    @Volatile private var observation: Observation? = null
    private var lastScreenshotAt = Long.MIN_VALUE
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) MobileUseController.pause("device_locked")
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, filter)
        screenReceiverRegistered = true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        MobileUseController.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val root = rootInActiveWindow
        val activeWindow = try {
            MobileUseController.updatePackage(root?.packageName?.toString())
            root?.windowId
        } finally { root?.recycle() }
        if (event.eventType in SCREEN_EVENTS && MobileObservationPolicy.changesActiveScreen(event.windowId,
                activeWindow, event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED)) revision.incrementAndGet()
        if (MobileUseController.isDeviceLocked(this)) MobileUseController.pause("device_locked")
    }

    override fun onInterrupt() {
        MobileUseController.pause("service_interrupted")
    }

    override fun onDestroy() {
        MobileUseController.detach(this)
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        invalidateObservations()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        feedback.close()
        screenshotExecutor.shutdown()
        super.onDestroy()
    }

    internal fun invalidateObservations() { observation = null; hideFeedback() }
    internal fun hideFeedback() { main.post { feedback.hide() } }

    override fun onUnbind(intent: Intent?): Boolean {
        MobileUseController.detach(this)
        invalidateObservations()
        return super.onUnbind(intent)
    }

    internal fun execute(command: MobileCommand, grant: MobileUseController.Grant, complete: (JSONObject) -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            complete(MobileUseController.failure("busy", "Another phone action is still running"))
            return
        }
        val finished = AtomicBoolean(false)
        val deadline = SystemClock.uptimeMillis() + 15_000
        fun requireCurrent() {
            if (finished.get() || SystemClock.uptimeMillis() >= deadline) {
                throw NativeFailure("timeout", "The native phone action timed out")
            }
            requireGrant(grant)
        }
        lateinit var timeout: Runnable
        fun finish(value: JSONObject) {
            if (!finished.compareAndSet(false, true)) return
            main.removeCallbacks(timeout)
            busy.set(false)
            complete(if (MobileUseController.valid(grant)) value else
                MobileUseController.failure("paused", "The phone control session ended"))
        }
        timeout = Runnable { finish(MobileUseController.failure("timeout", "The native phone action timed out")) }
        main.postDelayed(timeout, 15_000)
        main.post {
            try {
                requireCurrent()
                when (command) {
                    is MobileCommand.Observe -> {
                        observation = null
                        var retries = 0
                        lateinit var captureAfterFeedback: () -> Unit
                        fun observationResult(value: JSONObject) {
                            val delay = if (!value.optBoolean("ok")) MobileObservationPolicy.observationRetryDelay(
                                value.optJSONObject("error")?.optString("code"), command.screenshot, retries) else null
                            if (!finished.get() && delay != null) {
                                retries++
                                main.postDelayed({ captureAfterFeedback() }, delay)
                            } else finish(value)
                        }
                        captureAfterFeedback = {
                            try {
                                requireCurrent()
                                val delay = feedback.captureDelayMs()
                                if (delay > 0) main.postDelayed({ captureAfterFeedback() }, delay)
                                else observe(command, grant, ::observationResult, ::requireCurrent) { finished.get() }
                            } catch (error: NativeFailure) {
                                observationResult(MobileUseController.failure(error.code, error.message ?: "Observation unavailable"))
                            } catch (_: Exception) { finish(MobileUseController.failure("internal", "The phone observation failed")) }
                        }
                        captureAfterFeedback()
                    }
                    is MobileCommand.Click -> {
                        val snapshot = requireObservation(command.observationId, grant,
                            strictRevision = command.nodeId == null)
                        if (command.nodeId != null) {
                            var outline: Rect? = null
                            val accepted = withTarget(snapshot, command.nodeId) { node ->
                                val rect = Rect().also(node::getBoundsInScreen)
                                requireVisible(node, rect)
                                if (node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                                    throw NativeFailure("not_clickable", "This node has no click action; choose its observed clickable parent")
                                }
                                outline = Rect(rect)
                                requireCurrent()
                                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            }
                            observation = null
                            if (!accepted) throw NativeFailure("action_failed", "Android rejected the node click")
                            outline?.let { feedback.gesture(it.exactCenterX().toDouble(), it.exactCenterY().toDouble(),
                                it.exactCenterX().toDouble(), it.exactCenterY().toDouble(), 80,
                                snapshot.display.width, snapshot.display.height) }
                            finish(performed("click", command.observationId))
                        } else {
                            val x = requireNotNull(command.x)
                            val y = requireNotNull(command.y)
                            MobileProtocol.requireCoordinates(x, y, snapshot.display.width, snapshot.display.height)
                            requireCurrent()
                            gesture(x, y, x, y, 80, command.observationId, "click", ::finish)
                        }
                    }
                    is MobileCommand.Type -> {
                        val snapshot = requireObservation(command.observationId, grant, strictRevision = false)
                        var outline: Rect? = null
                        val accepted = withTarget(snapshot, command.nodeId) { node ->
                            if (node.isPassword) throw NativeFailure("password_field", "Phone control cannot fill password fields")
                            if (!node.isEnabled) throw NativeFailure("not_enabled", "This node is disabled")
                            if (!node.isEditable || node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
                                throw NativeFailure("not_editable", "This node does not support Android text replacement")
                            }
                            val rect = Rect().also(node::getBoundsInScreen)
                            requireVisible(node, rect)
                            outline = Rect(rect)
                            val args = Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, command.text)
                            }
                            requireCurrent()
                            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        }
                        observation = null
                        if (!accepted) throw NativeFailure("action_failed", "Android rejected text replacement")
                        outline?.let { feedback.type(it, snapshot.display.width, snapshot.display.height) }
                        finish(performed("type", command.observationId))
                    }
                    is MobileCommand.Swipe -> {
                        val snapshot = requireObservation(command.observationId, grant)
                        MobileProtocol.requireCoordinates(command.fromX, command.fromY, snapshot.display.width, snapshot.display.height)
                        MobileProtocol.requireCoordinates(command.toX, command.toY, snapshot.display.width, snapshot.display.height)
                        requireCurrent()
                        gesture(command.fromX, command.fromY, command.toX, command.toY, command.durationMs,
                            command.observationId, "swipe", ::finish)
                    }
                    is MobileCommand.Scroll -> {
                        val snapshot = requireObservation(command.observationId, grant, strictRevision = false)
                        var outline: Rect? = null
                        val action = if (command.direction == "forward") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        val accepted = withTarget(snapshot, command.nodeId) { node ->
                            val rect = Rect().also(node::getBoundsInScreen)
                            requireVisible(node, rect)
                            if (!node.isScrollable || node.actionList.none { it.id == action }) {
                                throw NativeFailure("not_scrollable", "This node cannot scroll in the requested direction")
                            }
                            outline = Rect(rect)
                            requireCurrent()
                            node.performAction(action)
                        }
                        observation = null
                        if (!accepted) throw NativeFailure("action_failed", "Android rejected the node scroll")
                        outline?.let { feedback.type(it, snapshot.display.width, snapshot.display.height) }
                        finish(performed("scroll", command.observationId))
                    }
                    is MobileCommand.Back -> {
                        requireObservation(command.observationId, grant, strictRevision = false)
                        observation = null
                        requireCurrent()
                        if (!performGlobalAction(GLOBAL_ACTION_BACK)) throw NativeFailure("action_failed", "Android rejected Back")
                        finish(performed("back", command.observationId))
                    }
                    is MobileCommand.ListApps -> {
                        val value = MobileLauncherApps(this).list(grant.sessionId)
                        requireCurrent()
                        finish(MobileUseController.success(value))
                    }
                    is MobileCommand.OpenApp -> {
                        // Launching a validated app has no screen-coordinate or node dependency.
                        // Streaming chat events or a slow model must not block it with a stale frame.
                        val intent = MobileLauncherApps(this).intent(command.packageName)
                            ?: throw NativeFailure("app_not_available", "This package has no available launcher activity")
                        requireCurrent()
                        observation = null
                        feedback.hide()
                        try { startActivity(intent) }
                        catch (_: ActivityNotFoundException) { throw NativeFailure("app_not_available", "The launcher activity is unavailable") }
                        catch (_: SecurityException) { throw NativeFailure("app_not_available", "Android does not allow this launcher activity") }
                        finish(MobileUseController.success(JSONObject().put("performed", true).put("action", "open_app")
                            .put("sessionId", grant.sessionId).put("observationId", command.observationId)
                            .put("packageName", command.packageName).put("foregroundOnly", true)))
                    }
                    is MobileCommand.UiXml -> if (command.source == "accessibility_xml") {
                        val root = rootInActiveWindow ?: throw NativeFailure("window_unavailable", "The active accessibility window is unavailable")
                        val windowId = root.windowId
                        val packageName = root.packageName?.toString()
                        val result = MobileUiXmlSerializer.serialize(adaptNode(root))
                        finish(MobileUseController.success(JSONObject().put("sessionId", grant.sessionId)
                            .put("observationId", UUID.randomUUID().toString()).put("windowId", windowId)
                            .put("packageName", packageName ?: JSONObject.NULL)
                            .put("source", command.source).put("xml", result.xml)
                            .put("truncated", result.truncated).put("nodes", result.nodes)))
                    } else throw NativeFailure("invalid_request", "Unsupported UI XML source")
                    else -> throw NativeFailure("invalid_request", "Unsupported native phone action")
                }
            } catch (error: MobileProtocolException) {
                finish(MobileUseController.failure(error.code, error.message ?: "Invalid phone request"))
            } catch (error: NativeFailure) {
                finish(MobileUseController.failure(error.code, error.message ?: "Phone action unavailable"))
            } catch (_: Exception) {
                finish(MobileUseController.failure("internal", "The native phone action failed"))
            }
        }
    }

    private fun adaptNode(value: AccessibilityNodeInfo): MobileUiXmlSerializer.Node = object : MobileUiXmlSerializer.Node {
        override val className get() = value.className?.toString(); override val viewId get() = value.viewIdResourceName
        override val packageName get() = value.packageName?.toString()
        override val bounds get() = Rect().also(value::getBoundsInScreen).let { "${it.left},${it.top},${it.right},${it.bottom}" }
        override val enabled get() = value.isEnabled; override val visible get() = value.isVisibleToUser
        override val clickable get() = value.isClickable; override val editable get() = value.isEditable
        override val scrollable get() = value.isScrollable; override val password get() = value.isPassword
        override val text get() = value.text?.toString(); override val description get() = value.contentDescription?.toString()
        override fun childCount() = value.childCount
        override fun childAt(index: Int) = value.getChild(index)?.let(::adaptNode)
        override fun release() = value.recycle()
    }

    private fun requireGrant(grant: MobileUseController.Grant) {
        if (!MobileUseController.valid(grant)) throw NativeFailure("paused", "The phone control session ended")
        if (MobileUseController.isDeviceLocked(this)) {
            MobileUseController.pause("device_locked")
            throw NativeFailure("locked", "Unlock the device before phone control")
        }
    }

    private fun observe(command: MobileCommand.Observe, grant: MobileUseController.Grant,
        finish: (JSONObject) -> Unit, requireCurrent: () -> Unit, cancelled: () -> Boolean) {
        val screen = displayState()
        val root = rootInActiveWindow ?: throw NativeFailure("no_window", "Android has no accessible active window")
        val nodes = JSONArray()
        val targets = LinkedHashMap<String, Target>()
        val retainedPaths = HashMap<List<Int>, String>()
        val sampled = SystemClock.uptimeMillis()
        val capturedRevision = revision.get()
        val windowId = root.windowId
        val packageName = root.packageName?.toString().orEmpty()
        fun retain(node: AccessibilityNodeInfo, path: List<Int>): Boolean {
            val rect = Rect().also(node::getBoundsInScreen)
            // WebView can clip an offscreen node into an inverted Rect. Preserve
            // real geometry: omit that target, but still inspect its children.
            if (!MobileObservationTree.acceptsBounds(rect.left, rect.top, rect.right, rect.bottom)) return false
            val id = "n${targets.size}"
            var parentId: String? = null
            var parentPath = path
            while (parentPath.isNotEmpty() && parentId == null) {
                parentPath = parentPath.dropLast(1)
                parentId = retainedPaths[parentPath]
            }
            val target = Target(path, node.className?.toString(), node.viewIdResourceName,
                node.packageName?.toString(), Rect(rect), if (node.isPassword) null else node.text?.toString()?.take(MAX_TEXT),
                if (node.isPassword) null else node.contentDescription?.toString()?.take(MAX_TEXT),
                node.isPassword, node.isEnabled, node.isClickable, node.isEditable, node.isScrollable)
            targets[id] = target
            retainedPaths[path] = id
            val actions = node.actionList.mapNotNull { action -> when (action.id) {
                AccessibilityNodeInfo.ACTION_CLICK -> "click"
                AccessibilityNodeInfo.ACTION_SET_TEXT -> if (node.isPassword) null else "set_text"
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scroll_forward"
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scroll_backward"
                AccessibilityNodeInfo.ACTION_FOCUS -> "focus"
                else -> null
            } }.distinct()
            val row = JSONObject().put("id", id)
                .put("className", target.className?.take(256).orEmpty())
                .put("viewId", target.viewId?.take(256).orEmpty())
                .put("packageName", target.packageName?.take(256).orEmpty())
                .put("bounds", bounds(rect)).put("clickable", node.isClickable)
                .put("editable", node.isEditable).put("password", node.isPassword)
                .put("scrollable", node.isScrollable).put("parentId", parentId ?: JSONObject.NULL)
                .put("actions", JSONArray(actions))
                .put("enabled", node.isEnabled)
                .put("visible", node.isVisibleToUser)
            if (!node.isPassword) {
                node.text?.toString()?.take(MAX_TEXT)?.let { row.put("text", it) }
                node.contentDescription?.toString()?.take(MAX_TEXT)?.let { row.put("description", it) }
            }
            nodes.put(row)
            return true
        }
        val truncated = try {
            MobileObservationTree.scan(root, MAX_NODES, MAX_DEPTH,
                childCount = { it.childCount }, childAt = { node, index -> node.getChild(index) },
                release = { it.recycle() }, visit = ::retain)
        } finally { root.recycle() }
        MobileUseController.updatePackage(packageName)
        val snapshot = Observation(UUID.randomUUID().toString(), grant.sessionId, sampled, capturedRevision,
            windowId, packageName, screen, targets)
        val value = JSONObject().put("sessionId", grant.sessionId).put("observationId", snapshot.id)
            .put("sampledAtMs", sampled).put("display", screen.json())
            .put("window", JSONObject().put("id", windowId).put("packageName", packageName))
            .put("nodes", nodes).put("truncated", truncated).put("screenshotRequested", command.screenshot)
            .put("screenshot", JSONObject.NULL)
        fun publish() {
            if (cancelled()) return
            requireCurrent()
            if (!sameWindow(snapshot)) {
                throw NativeFailure("observation_window_changed", "The screen changed during observation; observe again")
            }
            // Background chat updates need not invalidate an unchanged node.
            // Coordinates still compare the captured content revision before acting.
            value.put("screenChangedDuringCapture", capturedRevision != revision.get())
            observation = snapshot.copy(issuedAt = SystemClock.uptimeMillis())
            finish(MobileUseController.success(value))
        }
        if (!command.screenshot) {
            publish()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (lastScreenshotAt != Long.MIN_VALUE && now - lastScreenshotAt < SCREENSHOT_INTERVAL_MS) {
            throw NativeFailure("rate_limited", "Android 11 screenshots require at least 1100 ms between captures")
        }
        lastScreenshotAt = now
        requireCurrent()
        takeScreenshot(Display.DEFAULT_DISPLAY, screenshotCallbacks, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                var wrapped: Bitmap? = null
                var software: Bitmap? = null
                try {
                    if (cancelled()) return
                    requireCurrent()
                    wrapped = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, result.colorSpace))
                    software = checkNotNull(wrapped.copy(Bitmap.Config.ARGB_8888, false))
                    val output = ByteArrayOutputStream()
                    check(software.compress(Bitmap.CompressFormat.PNG, 100, output))
                    if (output.size() > MAX_SCREENSHOT_BYTES) throw NativeFailure("screen_too_large", "The PNG exceeds the phone observation limit")
                    val shot = JSONObject().put("mimeType", "image/png")
                        .put("base64", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
                        .put("widthPx", software.width).put("heightPx", software.height)
                        .put("capturedAtMs", result.timestamp)
                    main.post {
                        try { value.put("screenshot", shot); publish() }
                        catch (error: NativeFailure) { finish(MobileUseController.failure(error.code, error.message ?: "Screenshot unavailable")) }
                        catch (_: Exception) { finish(MobileUseController.failure("internal", "The screenshot could not be returned")) }
                    }
                } catch (error: NativeFailure) {
                    finish(MobileUseController.failure(error.code, error.message ?: "Screenshot unavailable"))
                } catch (_: Exception) {
                    finish(MobileUseController.failure("screenshot_failed", "Android could not encode the screenshot"))
                } finally {
                    software?.recycle()
                    wrapped?.recycle()
                    buffer.close()
                }
            }

            override fun onFailure(errorCode: Int) {
                val (code, message) = when (errorCode) {
                    ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "accessibility_disabled" to "Android screenshot permission is unavailable"
                    ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "rate_limited" to "Android rejected a screenshot captured too soon"
                    ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "invalid_display" to "Android cannot capture this display"
                    // API 34+ emits 6; API 30 never reports a secure-window error.
                    6 -> "secure_window" to "Android protected this screen from capture"
                    else -> "screenshot_failed" to "Android could not capture the display"
                }
                finish(MobileUseController.failure(code, message))
            }
        })
    }

    private fun requireObservation(id: String, grant: MobileUseController.Grant, strictRevision: Boolean = true): Observation {
        requireGrant(grant)
        val snapshot = observation ?: throw NativeFailure("observation_missing", "No unused observation is available")
        val reason = MobileObservationPolicy.invalidReason(snapshot.id == id, snapshot.session == grant.sessionId,
            MobileObservationPolicy.fresh(snapshot.issuedAt, SystemClock.uptimeMillis()), sameWindow(snapshot),
            snapshot.revision == revision.get(), strictRevision)
        if (reason != null) throw NativeFailure(reason, "Observe the current screen before acting")
        return snapshot
    }

    private fun sameWindow(snapshot: Observation): Boolean {
        if (displayState() != snapshot.display) return false
        val root = rootInActiveWindow ?: return false
        return try { root.windowId == snapshot.window && root.packageName?.toString().orEmpty() == snapshot.packageName }
            finally { root.recycle() }
    }

    private fun <T> withTarget(snapshot: Observation, id: String, block: (AccessibilityNodeInfo) -> T): T {
        val expected = snapshot.targets[id] ?: throw NativeFailure("unknown_node", "The node is not part of this observation")
        var current = rootInActiveWindow ?: throw NativeFailure("observation_window_changed", "The observed window is gone")
        try {
            for (index in expected.path) {
                val child = current.getChild(index) ?: throw NativeFailure("observation_target_changed", "The observed node is gone")
                current.recycle()
                current = child
            }
            if (!current.refresh()) throw NativeFailure("observation_target_changed", "The observed node is gone")
            val bounds = Rect().also(current::getBoundsInScreen)
            if (current.windowId != snapshot.window || current.className?.toString() != expected.className ||
                current.viewIdResourceName != expected.viewId || current.packageName?.toString() != expected.packageName ||
                bounds != expected.bounds || current.isPassword != expected.password ||
                current.isEnabled != expected.enabled || current.isClickable != expected.clickable || current.isEditable != expected.editable ||
                current.isScrollable != expected.scrollable ||
                (!current.isPassword && (current.text?.toString()?.take(MAX_TEXT) != expected.text ||
                    current.contentDescription?.toString()?.take(MAX_TEXT) != expected.description))) {
                throw NativeFailure("observation_target_changed", "The observed node changed; observe again")
            }
            return block(current)
        } finally { current.recycle() }
    }

    private fun requireVisible(node: AccessibilityNodeInfo, rect: Rect) {
        if (!node.isVisibleToUser || rect.width() <= 0 || rect.height() <= 0) {
            throw NativeFailure("not_visible", "This node is not currently visible")
        }
        if (!node.isEnabled) throw NativeFailure("not_enabled", "This node is disabled")
    }

    private fun gesture(fromX: Double, fromY: Double, toX: Double, toY: Double, duration: Long,
        observationId: String, action: String, finish: (JSONObject) -> Unit) {
        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            if (fromX != toX || fromY != toY) lineTo(toX.toFloat(), toY.toFloat())
        }
        val description = GestureDescription.Builder().setDisplayId(Display.DEFAULT_DISPLAY)
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        observation = null
        val accepted = dispatchGesture(description, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                finish(performed(action, observationId))
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                feedback.hide()
                finish(MobileUseController.failure("action_cancelled", "Android cancelled the touch gesture"))
            }
        }, main)
        if (!accepted) throw NativeFailure("action_failed", "Android rejected the touch gesture")
        val screen = displayState()
        feedback.gesture(fromX, fromY, toX, toY, duration, screen.width, screen.height)
    }

    private fun performed(action: String, id: String): JSONObject = MobileUseController.success(
        JSONObject().put("performed", true).put("action", action).put("observationId", id))

    private fun displayState(): Screen {
        val display = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
            ?: throw NativeFailure("invalid_display", "Android has no default display")
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return Screen(metrics.widthPixels, metrics.heightPixels, display.rotation)
    }

    private data class Screen(val width: Int, val height: Int, val rotation: Int) {
        fun json() = JSONObject().put("widthPx", width).put("heightPx", height).put("rotation", rotation)
    }
    private data class Target(val path: List<Int>, val className: String?, val viewId: String?, val packageName: String?,
        val bounds: Rect, val text: String?, val description: String?, val password: Boolean,
        val enabled: Boolean, val clickable: Boolean, val editable: Boolean, val scrollable: Boolean)
    private data class Observation(val id: String, val session: String, val issuedAt: Long, val revision: Long,
        val window: Int, val packageName: String, val display: Screen, val targets: Map<String, Target>)
    private class NativeFailure(val code: String, message: String) : Exception(message)

    companion object {
        private const val MAX_NODES = 1000
        private const val MAX_DEPTH = 40
        private const val MAX_TEXT = 2048
        private const val MAX_SCREENSHOT_BYTES = 8 * 1024 * 1024
        private const val SCREENSHOT_INTERVAL_MS = 1100L
        private val SCREEN_EVENTS = setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED, AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_SELECTED)
        private fun bounds(rect: Rect) = JSONObject().put("left", rect.left).put("top", rect.top)
            .put("right", rect.right).put("bottom", rect.bottom)
    }
}
