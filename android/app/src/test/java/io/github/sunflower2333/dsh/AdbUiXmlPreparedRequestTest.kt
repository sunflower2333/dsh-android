package io.github.sunflower2333.dsh

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Real manager/capture integration with synthetic child processes, not Android acceptance. */
class AdbUiXmlPreparedRequestTest {
    private val xml = "<hierarchy><node text=\"Synthetic screen\" password=\"false\"/></hierarchy>"

    @Test fun theFirstCaptureDoesNotRequireAnExistingTransportConnectedFlag() {
        Fixture().use { fixture ->
            assertFalse(fixture.manager.status().getBoolean("adbConnected"))
            val result = fixture.capture()
            assertTrue(result.getBoolean("ok"))
            assertEquals(xml, result.getJSONObject("value").getString("stdout"))
        }
    }

    @Test fun targetRestoredAfterReadCannotReauthorizeAnOldHierarchy() {
        Fixture().use { fixture ->
            val result = fixture.capture { manager ->
                manager.setAdbTarget("other-phone")
                manager.setAdbTarget("phone")
            }
            assertRejected(result, "ADB_DISCONNECTED")
            assertEquals(3, fixture.commands.size)
        }
    }

    @Test fun disablingThenReenablingAdbCannotReauthorizeAnOldHierarchy() {
        Fixture().use { fixture ->
            val result = fixture.capture { manager ->
                manager.setAdbEnabled(false)
                manager.setAdbEnabled(true)
            }
            assertRejected(result, "ADB_DISCONNECTED")
            assertEquals(3, fixture.commands.size)
        }
    }

    @Test fun importingAClientAfterReadCannotReauthorizeAnOldHierarchy() {
        Fixture().use { fixture ->
            val result = fixture.capture { manager ->
                manager.importAdb(ByteArrayInputStream("replacement-synthetic-client".toByteArray()))
            }
            assertRejected(result, "ADB_DISCONNECTED")
            assertEquals(3, fixture.commands.size)
        }
    }

    @Test fun disablingAdbAfterReadPreventsReturningItsEarlierContents() {
        Fixture().use { fixture ->
            val result = fixture.capture { it.setAdbEnabled(false) }
            assertRejected(result, "ADB_DISABLED")
            assertEquals(3, fixture.commands.size)
        }
    }

    @Test fun anIntentionalDisconnectCanFinishWithoutXmlEpochRequirements() {
        Fixture().use { fixture ->
            val prepared = fixture.manager.prepareAdbRequest()
            assertTrue(fixture.manager.executeAdb(
                "session", "phone", listOf("disconnect"), 1_000, preparedRequest = prepared,
            ).getBoolean("ok"))
            assertNotEquals(prepared, fixture.manager.prepareAdbRequest())
            assertNull(AdbRequestAuthorization.errorCode(fixture.manager, prepared, requireCurrentRequest = false))
            assertEquals("ADB_DISCONNECTED", AdbRequestAuthorization.errorCode(fixture.manager, prepared, requireCurrentRequest = true))
        }
    }

    private fun assertRejected(result: JSONObject, code: String) {
        assertFalse(result.getBoolean("ok"))
        assertEquals(code, result.getJSONObject("error").getString("code"))
        assertFalse(result.has("value"))
    }

    private inner class Fixture : AutoCloseable {
        private val directory = Files.createTempDirectory("dsh-ui-prepared-").toFile()
        private val executable = File(directory, "adb.bin").also {
            it.writeText("synthetic-client-never-executed")
            check(it.setExecutable(true))
        }
        val commands = mutableListOf<List<String>>()
        val manager = AndroidCapabilityManager(
            adbExecutable = executable,
            storageDirectory = directory,
            processFactory = AndroidCapabilityManager.ProcessFactory { argv, _ ->
                commands += argv
                CompletedProcess(when {
                    "app_process" in argv -> xml
                    else -> ""
                })
            },
        ).also {
            it.setAdbEnabled(true)
            it.setAdbTarget("phone")
        }

        fun capture(afterRead: (AndroidCapabilityManager) -> Unit = {}): JSONObject {
            val prepared = manager.prepareAdbRequest()
            return AdbUiXmlCapture(
                execute = { argv, timeout ->
                    val result = manager.executeAdb("session", "phone", argv, timeout, preparedRequest = prepared)
                    if ("app_process" in argv) afterRead(manager)
                    result
                },
                helperFile = { File(directory, "helper.jar") },
                authorize = {
                    AdbRequestAuthorization.errorCode(manager, prepared, requireCurrentRequest = true)?.let { code ->
                        JSONObject().put("ok", false).put("error", JSONObject().put("code", code))
                    }
                },
            ).capture()
        }

        override fun close() {
            manager.cancelAll()
            directory.deleteRecursively()
        }
    }

    private class CompletedProcess(stdout: String) : Process() {
        private val input = ByteArrayInputStream(stdout.toByteArray())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun isAlive(): Boolean = false
        override fun destroy() {}
        override fun destroyForcibly(): Process = this
    }
}
