package io.github.sunflower2333.dsh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCapabilityManagerTest {
    @Test fun statusContainsOnlyPublicCapabilityFields() {
        val manager = manager()
        val status = manager.status()
        val fields = mutableSetOf<String>()
        status.keys().forEach { fields += it }
        assertEquals(
            setOf("adbConfigured", "adbEnabled", "adbConnected", "adbTarget", "rootAvailable", "rootEnabled"),
            fields,
        )
        assertFalse(status.has("token"))
        assertFalse(status.has("path"))
    }

    @Test fun adbRejectsUnsafeSerialAndArgumentsBeforeStartingProcess() {
        val started = AtomicReference<List<String>?>(null)
        val manager = manager { command, _ ->
            started.set(command)
            FakeProcess(exitCode = 0)
        }
        manager.setAdbEnabled(true)
        manager.setAdbConnected(true)

        assertEquals("INVALID_SERIAL", manager.executeAdb("session", "usb;rm", listOf("shell")).errorCode())
        assertEquals("INVALID_ARGV", manager.executeAdb("session", null, listOf("shell", "echo 'oops'")).errorCode())
        assertEquals(null, started.get())
    }

    @Test fun rootUsesOnlySuDashCAndRejectsShellQuotingBreakout() {
        val command = AtomicReference<List<String>?>(null)
        val su = executableFile()
        val manager = manager(rootCommand = su.absolutePath) { argv, _ ->
            command.set(argv)
            FakeProcess(exitCode = 0)
        }
        assertTrue(manager.setRootEnabled(true).getBoolean("rootEnabled"))
        val result = manager.executeRoot("session", listOf("id", "-u"))
        assertTrue(result.getBoolean("ok"))
        assertEquals(listOf(su.absolutePath, "-c", "'id' '-u'"), command.get())
        assertEquals("INVALID_ARGV", manager.executeRoot("session", listOf("id' ; touch /tmp/pwn")).errorCode())
    }

    @Test fun timeoutForceKillsChildAndReturnsStableError() {
        val child = FakeProcess(exitCode = 0, remainsAlive = true)
        val manager = manager { _, _ -> child }
        manager.setAdbEnabled(true)
        manager.setAdbConnected(true)
        val result = manager.executeAdb("session", null, listOf("shell"), timeoutMs = 100)
        assertEquals("TIMEOUT", result.errorCode())
        assertTrue(child.destroyed)
    }

    @Test fun importedAdbIsPrivateExecutable() {
        val directory = createTempDir(prefix = "capability-test")
        try {
            val manager = AndroidCapabilityManager(storageDirectory = directory)
            manager.importAdb(ByteArrayInputStream("adb".toByteArray()))
            val adb = File(directory, "tools/adb")
            assertTrue(adb.isFile)
            assertTrue(adb.canExecute())
            assertEquals(448, adb.modeBits())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun manager(
        rootCommand: String? = null,
        factory: AndroidCapabilityManager.ProcessFactory = AndroidCapabilityManager.ProcessFactory { _, _ -> FakeProcess(0) },
    ) = AndroidCapabilityManager(
        processFactory = factory,
        adbExecutable = executableFile(),
        rootCommand = rootCommand,
    )

    private fun manager(
        rootCommand: String? = null,
        factory: (List<String>, Map<String, String>) -> Process,
    ) = manager(rootCommand, AndroidCapabilityManager.ProcessFactory(factory))

    private fun executableFile(): File = createTempFile(prefix = "adb", suffix = ".bin").also {
        it.setExecutable(true)
        it.deleteOnExit()
    }

    private fun JSONObject.errorCode(): String? = optJSONObject("error")?.optString("code", null)

    private fun File.modeBits(): Int = (canonicalFile.let { it }
        .runCatching { java.nio.file.Files.getPosixFilePermissions(toPath()) }
        .getOrNull()
        ?.fold(0) { total, permission -> total + when (permission.name) {
            "OWNER_READ" -> 256
            "OWNER_WRITE" -> 128
            "OWNER_EXECUTE" -> 64
            else -> 0
        } } ?: 0)

    private class FakeProcess(
        private val exitCode: Int,
        private val remainsAlive: Boolean = false,
        stdout: ByteArray = ByteArray(0),
        stderr: ByteArray = ByteArray(0),
    ) : Process() {
        private val out = ByteArrayOutputStream()
        private var alive = remainsAlive
        var destroyed = false
        private val stdoutStream = ByteArrayInputStream(stdout)
        private val stderrStream = ByteArrayInputStream(stderr)
        override fun getOutputStream(): OutputStream = out
        override fun getInputStream(): InputStream = stdoutStream
        override fun getErrorStream(): InputStream = stderrStream
        override fun waitFor(): Int { alive = false; return exitCode }
        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean {
            if (!alive) return true
            return false
        }
        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException()
            return exitCode
        }
        override fun destroy() { alive = false }
        override fun destroyForcibly(): Process { destroyed = true; alive = false; return this }
        override fun isAlive(): Boolean = alive
    }
}
