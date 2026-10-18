package io.github.sunflower2333.dsh

import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Runs the packaged shell helper without suppressing other Accessibility services. */
internal class AdbUiXmlCapture(
    private val execute: (List<String>, Long) -> JSONObject,
    private val authorize: () -> JSONObject?,
    private val helperFile: () -> File,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    fun capture(): JSONObject {
        // Both this monotonic clock and the Node bridge timer run inside
        // Android. Leave headroom beneath the bridge's 15-second deadline.
        val deadline = nowMs() + TOTAL_TIMEOUT_MS
        val path = "/data/local/tmp/dsh-ui-${UUID.randomUUID()}.jar"
        var pushAttempted = false
        val result = try {
            readHierarchy(path, deadline) { pushAttempted = true }
        } finally {
            // Only remove this invocation's generated file while the same
            // grant is live. Cleanup failure never replaces the main result.
            if (pushAttempted) runCatching {
                if (authorize() == null) {
                    val remaining = deadline - nowMs()
                    if (remaining >= MIN_TIMEOUT_MS) {
                        execute(listOf("shell", "rm", "-f", path), minOf(CLEANUP_TIMEOUT_MS, remaining))
                    }
                }
            }
        }
        // Cleanup itself can overlap a pause/lock; never return its earlier
        // screen contents after that grant has been revoked.
        return if (result.optBoolean("ok")) authorize() ?: result else result
    }

    private fun readHierarchy(path: String, deadline: Long, onPush: () -> Unit): JSONObject {
        authorize()?.let { return it }
        val local = try { helperFile() } catch (_: Exception) { return error("PROCESS_START_FAILED") }
        authorize()?.let { return it }
        val pushTimeout = workTimeout(deadline, PUSH_TIMEOUT_MS) ?: return timeout()
        onPush()
        stageError(execute(listOf("push", local.absolutePath, path), pushTimeout))?.let { return it }
        authorize()?.let { return it }
        val chmodTimeout = workTimeout(deadline, CHMOD_TIMEOUT_MS) ?: return timeout()
        stageError(execute(listOf("shell", "chmod", "0444", path), chmodTimeout))?.let { return it }
        authorize()?.let { return it }
        val captureTimeout = workTimeout(deadline, CAPTURE_TIMEOUT_MS) ?: return timeout()
        // Shell v2 preserves remote exit status and separate stderr. The raw
        // exec-out service returns local success even when the helper fails.
        val captured = execute(listOf("shell", "-T", "-n", "env", "CLASSPATH=$path", "app_process",
            "/system/bin", "io.github.sunflower2333.dsh.uixml.UiXmlMain"), captureTimeout)
        return stageError(captured, helperCapture = true) ?: authorize() ?: captured
    }

    private fun stageError(result: JSONObject, helperCapture: Boolean = false): JSONObject? {
        if (!result.optBoolean("ok")) {
            val failure = result.optJSONObject("error")
            if (helperCapture && failure?.optString("code") == "PROCESS_EXIT" &&
                failure.optInt("exitCode", -1) == 124) return timeout()
            return result
        }
        val value = result.optJSONObject("value")
        val exit = value?.optInt("exitCode", -1) ?: -1
        if (helperCapture && exit == 124) return timeout()
        if (exit != 0) return error("PROCESS_EXIT").also { it.getJSONObject("error").put("exitCode", exit) }
        if (value?.optBoolean("truncated") == true) return error("OUTPUT_LIMIT")
        return null
    }

    private fun workTimeout(deadline: Long, maximum: Long): Long? {
        val remaining = deadline - nowMs() - CLEANUP_TIMEOUT_MS
        return minOf(remaining, maximum).takeIf { it >= MIN_TIMEOUT_MS }
    }

    private fun error(code: String): JSONObject = JSONObject().put("ok", false)
        .put("error", JSONObject().put("code", code))
    private fun timeout(): JSONObject = error("TIMEOUT")

    private companion object {
        const val TOTAL_TIMEOUT_MS = 12_000L
        const val PUSH_TIMEOUT_MS = 2_000L
        const val CHMOD_TIMEOUT_MS = 1_000L
        const val CAPTURE_TIMEOUT_MS = 9_500L
        const val CLEANUP_TIMEOUT_MS = 500L
        const val MIN_TIMEOUT_MS = 100L
    }
}

/** Checks opt-in and configuration epochs without requiring prior connection proof. */
internal object AdbRequestAuthorization {
    fun errorCode(
        manager: AndroidCapabilityManager,
        prepared: AndroidCapabilityManager.PreparedAdbRequest,
        requireCurrentRequest: Boolean,
    ): String? {
        if (!manager.status().optBoolean("adbEnabled")) return "ADB_DISABLED"
        val current = manager.prepareAdbRequest()
        if (current.configuredTarget != prepared.configuredTarget) return "ADB_DISCONNECTED"
        // UI capture has no intentional lifecycle command. A state restored
        // after disable/reimport/target changes cannot revive its old XML.
        // Ordinary shell disconnect/kill-server may intentionally change the
        // epoch, so their caller retains the narrower grant/opt-in checks.
        if (requireCurrentRequest && current != prepared) return "ADB_DISCONNECTED"
        return null
    }
}
