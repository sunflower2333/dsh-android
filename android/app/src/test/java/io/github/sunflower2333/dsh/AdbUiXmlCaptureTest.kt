package io.github.sunflower2333.dsh

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Command orchestration only; these fixtures do not claim Android capture evidence. */
class AdbUiXmlCaptureTest {
    private val xml = "<?xml version=\"1.0\"?><hierarchy><node text=\"Synthetic screen\" password=\"false\"/></hierarchy>"
    private data class Call(val argv: List<String>, val timeoutMs: Long, val startedAt: Long)

    private inner class Device {
        var now = 0L
        var authorization: JSONObject? = null
        val calls = mutableListOf<Call>()
        val files = mutableSetOf<String>()
        val responses = mutableMapOf<String, JSONObject>()
        val elapsed = mutableMapOf<String, Long>()
        var after: (String) -> Unit = {}
        var throwDuringCleanup = false
        var prepareHelper: () -> File = { File("/private/test-helper.jar") }

        fun execute(argv: List<String>, timeoutMs: Long): JSONObject {
            calls += Call(argv, timeoutMs, now)
            val stage = when {
                argv.first() == "push" -> "push"
                argv.take(2) == listOf("shell", "chmod") -> "chmod"
                "app_process" in argv -> "capture"
                argv.take(3) == listOf("shell", "rm", "-f") -> "cleanup"
                else -> "stock_or_unexpected"
            }
            if (stage == "push") files += argv.last()
            if (stage == "cleanup") {
                if (throwDuringCleanup) throw IllegalStateException("Synthetic cleanup failure")
                if (responses[stage] == null) files -= argv.last()
            }
            now += elapsed[stage] ?: 0L
            after(stage)
            return responses[stage] ?: success(if (stage == "capture") xml else "")
        }

        fun capture(): JSONObject = AdbUiXmlCapture(
            execute = ::execute, authorize = { authorization }, nowMs = { now },
            helperFile = { prepareHelper() },
        ).capture()
    }

    @Test fun pushesReadOnlyHelperAndExecutesRealShellEntryPointInsteadOfStockDump() {
        val device = Device()
        val result = device.capture()
        assertTrue(result.getBoolean("ok"))
        assertEquals("push", device.calls.first().argv.first())
        val remote = device.calls.first().argv.last()
        assertEquals(listOf("shell", "chmod", "0444", remote), device.calls[1].argv)
        assertEquals(listOf("shell", "-T", "-n", "env", "CLASSPATH=$remote", "app_process", "/system/bin",
            "io.github.sunflower2333.dsh.uixml.UiXmlMain"), device.calls[2].argv)
        assertEquals(xml, result.getJSONObject("value").getString("stdout"))
        assertFalse(device.calls.any { it.argv.contains("uiautomator") })
        assertTrue(device.files.isEmpty())
    }

    @Test fun everyInvocationOwnsAUniqueRemoteJarPath() {
        val device = Device()
        device.capture(); device.capture()
        val paths = device.calls.filter { it.argv.first() == "push" }.map { it.argv.last() }
        assertEquals(2, paths.toSet().size)
        assertTrue(paths.all { Regex("/data/local/tmp/dsh-ui-[0-9a-f-]{36}\\.jar").matches(it) })
        assertTrue(device.files.isEmpty())
    }

    @Test fun pushFailureStopsBeforeChmodAndCleansItsPartialUpload() {
        val expected = failure("PROCESS_EXIT", 1)
        val device = Device().apply { responses["push"] = expected }
        assertSame(expected, device.capture())
        assertEquals(listOf("push", "shell"), device.calls.map { it.argv.first() })
        assertTrue(device.files.isEmpty())
    }

    @Test fun chmodFailureStopsBeforeAppProcessAndPreservesTheFailure() {
        val expected = failure("TIMEOUT")
        val device = Device().apply { responses["chmod"] = expected }
        assertSame(expected, device.capture())
        assertFalse(device.calls.any { "app_process" in it.argv })
        assertTrue(device.files.isEmpty())
    }

    @Test fun captureFailureSurvivesCleanupFailure() {
        val expected = failure("PROCESS_EXIT", 65)
        val device = Device().apply {
            responses["capture"] = expected; responses["cleanup"] = failure("TIMEOUT")
        }
        assertSame(expected, device.capture())
        assertEquals(4, device.calls.size)
    }

    @Test fun helperDeadlineExitMapsToTimeoutAndStillCleansItsJar() {
        val device = Device().apply { responses["capture"] = failure("PROCESS_EXIT", 124) }
        val result = device.capture()
        assertEquals("TIMEOUT", result.getJSONObject("error").getString("code"))
        assertTrue(device.files.isEmpty())
        assertEquals(4, device.calls.size)
    }

    @Test fun anOkEnvelopeWithNonzeroExitNeverStartsTheNextStage() {
        for (stage in listOf("push", "chmod", "capture")) {
            val device = Device().apply { responses[stage] = success("").also {
                it.getJSONObject("value").put("exitCode", 23)
            } }
            val result = device.capture()
            assertFalse("$stage exit must fail", result.getBoolean("ok"))
            assertEquals("PROCESS_EXIT", result.getJSONObject("error").getString("code"))
            if (stage != "capture") assertFalse(device.calls.any { "app_process" in it.argv })
        }
    }

    @Test fun revokedGrantAfterAnyWorkStagePreventsFurtherCommandsAndReturningXml() {
        for (stage in listOf("push", "chmod", "capture")) {
            val paused = failure("paused")
            val device = Device().apply { after = { if (it == stage) authorization = paused } }
            assertSame(paused, device.capture())
            assertFalse(device.calls.any { it.argv.take(3) == listOf("shell", "rm", "-f") })
            assertEquals(listOf("push", "chmod", "capture").indexOf(stage) + 1, device.calls.size)
        }
    }

    @Test fun invalidInitialGrantStartsNoAdbCommand() {
        val paused = failure("paused")
        val device = Device().apply { authorization = paused }
        assertSame(paused, device.capture())
        assertTrue(device.calls.isEmpty())
    }

    @Test fun helperAssetFailureStartsNoAdbCommand() {
        val device = Device().apply { prepareHelper = { throw java.io.IOException("fixture") } }
        assertEquals("PROCESS_START_FAILED", device.capture().getJSONObject("error").getString("code"))
        assertTrue(device.calls.isEmpty())
    }

    @Test fun grantOrBudgetExpiringDuringAssetPreparationPreventsPush() {
        val paused = failure("paused")
        val revoked = Device().apply { prepareHelper = { authorization = paused; File("/helper.jar") } }
        assertSame(paused, revoked.capture())
        assertTrue(revoked.calls.isEmpty())
        val expired = Device().apply { prepareHelper = { now = 12_001; File("/helper.jar") } }
        assertEquals("TIMEOUT", expired.capture().getJSONObject("error").getString("code"))
        assertTrue(expired.calls.isEmpty())
    }

    @Test fun truncatedStageOutputFailsClosedBeforeFurtherWork() {
        val device = Device().apply { responses["push"] = success("").also {
            it.getJSONObject("value").put("truncated", true)
        } }
        assertEquals("OUTPUT_LIMIT", device.capture().getJSONObject("error").getString("code"))
        assertFalse(device.calls.any { it.argv.contains("chmod") || "app_process" in it.argv })
    }

    @Test fun cleanupFailureDoesNotReplaceSuccessfullyCapturedXml() {
        val device = Device().apply { throwDuringCleanup = true }
        assertEquals(xml, device.capture().getJSONObject("value").getString("stdout"))
    }

    @Test fun aGrantRevokedDuringCleanupPreventsReturningEarlierXml() {
        val locked = failure("locked")
        val device = Device().apply { after = { if (it == "cleanup") authorization = locked } }
        assertSame(locked, device.capture())
    }

    @Test fun allCommandsShareOneTwelveSecondBudgetIncludingCleanup() {
        val device = Device().apply { elapsed["push"] = 1_000; elapsed["chmod"] = 500; elapsed["capture"] = 8_000 }
        assertTrue(device.capture().getBoolean("ok"))
        assertEquals(4, device.calls.size)
        assertTrue(device.calls.all { it.timeoutMs >= 100 && it.startedAt + it.timeoutMs <= 12_000 })
        assertTrue(device.calls.last().timeoutMs <= 500)
    }

    @Test fun expiredBudgetAfterPushPreventsChmodAndLateCleanup() {
        val device = Device().apply { elapsed["push"] = 12_001 }
        val result = device.capture()
        assertEquals("TIMEOUT", result.getJSONObject("error").getString("code"))
        assertEquals(1, device.calls.size)
    }

    private fun success(stdout: String): JSONObject = JSONObject().put("ok", true).put("value", JSONObject()
        .put("exitCode", 0).put("stdout", stdout).put("stderr", "").put("truncated", false))
    private fun failure(code: String, exitCode: Int? = null): JSONObject = JSONObject().put("ok", false)
        .put("error", JSONObject().put("code", code).also { if (exitCode != null) it.put("exitCode", exitCode) })
}
