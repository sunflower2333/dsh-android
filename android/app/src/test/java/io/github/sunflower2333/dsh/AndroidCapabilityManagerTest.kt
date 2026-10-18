package io.github.sunflower2333.dsh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

    @Test fun rootDelegatesToConfiguredRootBackend() {
        val command = AtomicReference<Pair<String, Long>?>(null)
        val runner = RootShellRunner { text, timeoutMs, _ ->
            command.set(text to timeoutMs)
            RootShellResult.Completed(0, "uid=0(root)", "", false)
        }
        val manager = manager(
            rootCommand = executableFile().absolutePath,
            rootShellRunner = runner,
        )

        assertTrue(manager.setRootEnabled(true).getBoolean("rootEnabled"))
        val result = manager.executeRoot("session", listOf("id", "-u"), timeoutMs = 900)

        assertTrue(result.getBoolean("ok"))
        assertEquals("'id' '-u'", command.get()?.first)
        assertEquals(900L, command.get()?.second)
        assertEquals("uid=0(root)", result.getJSONObject("value").getString("stdout"))
    }

    @Test fun timeoutForceKillsChildAndReturnsStableError() {
        val child = FakeProcess(exitCode = 0, remainsAlive = true)
        val manager = manager { _, _ -> child }
        manager.setAdbEnabled(true)
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
            assertFalse(manager.status().getBoolean("adbConnected"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun enablingAdbDoesNotClaimATransportConnection() {
        val manager = manager()
        val status = manager.setAdbEnabled(true)
        assertTrue(status.getBoolean("adbEnabled"))
        assertFalse(status.getBoolean("adbConnected"))
    }

    @Test fun firstAuthorizedDeviceCommandCanRunWithoutAPreviousConnectionFlag() {
        val command = AtomicReference<List<String>?>(null)
        val executable = executableFile()
        val manager = AndroidCapabilityManager(adbExecutable = executable,
            processFactory = AndroidCapabilityManager.ProcessFactory { argv, _ ->
                command.set(argv)
                FakeProcess(0)
            })
        manager.setAdbEnabled(true)
        assertTrue(manager.executeAdb("session", "phone", listOf("shell", "id")).getBoolean("ok"))
        assertEquals(listOf(executable.absolutePath, "-s", "phone", "shell", "id"), command.get())
        assertTrue(manager.status().getBoolean("adbConnected"))
    }

    @Test fun hostCommandsAndAmbiguousGlobalFlagsDoNotProveDeviceConnection() {
        for (argv in listOf(listOf("version"), listOf("devices"), listOf("connect", "localhost:5555"),
                listOf("pair", "localhost:5555"), listOf("start-server"), listOf("forward", "--list"),
                listOf("-H", "localhost", "shell", "id"))) {
            val manager = manager()
            manager.setAdbEnabled(true)
            assertTrue(manager.executeAdb("session", null, argv).getBoolean("ok"))
            assertFalse(argv.toString(), manager.status().getBoolean("adbConnected"))
        }
    }

    @Test fun getStateRequiresAnActualDeviceResponse() {
        for (response in listOf("device\n", "offline\n", "unauthorized\n", "")) {
            val manager = manager { _, _ -> FakeProcess(0, stdout = response.toByteArray()) }
            manager.setAdbEnabled(true)
            manager.executeAdb("session", null, listOf("get-state"))
            assertEquals(response, response.trim() == "device", manager.status().getBoolean("adbConnected"))
        }
    }

    @Test fun forwardListDoesNotProveTheSelectedOrConfiguredDeviceIsConnected() {
        for (serial in listOf(null, "explicit-phone")) {
            val manager = manager { _, _ -> FakeProcess(0, stdout = "other-phone tcp:1234 tcp:5678\n".toByteArray()) }
            manager.setAdbEnabled(true)
            manager.setAdbTarget("configured-phone")
            manager.executeAdb("session", serial, listOf("forward", "--list"))
            assertFalse(manager.status().getBoolean("adbConnected"))
        }
    }

    @Test fun forwardListPreservesAnAlreadyVerifiedConnection() {
        val manager = manager()
        manager.setAdbEnabled(true)
        manager.executeAdb("session", null, listOf("shell", "id"))
        manager.executeAdb("session", null, listOf("forward", "--list"))
        assertTrue(manager.status().getBoolean("adbConnected"))
    }

    @Test fun failedDeviceCommandClearsPreviouslyVerifiedConnection() {
        var exit = 0
        val manager = manager { _, _ -> FakeProcess(exit) }
        manager.setAdbEnabled(true)
        manager.executeAdb("session", null, listOf("shell", "id"))
        assertTrue(manager.status().getBoolean("adbConnected"))
        exit = 1
        manager.executeAdb("session", null, listOf("shell", "id"))
        assertFalse(manager.status().getBoolean("adbConnected"))
    }

    @Test fun disconnectAndKillServerClearPreviouslyVerifiedConnection() {
        for (command in listOf("disconnect", "kill-server")) {
            val manager = manager()
            manager.setAdbEnabled(true)
            manager.executeAdb("session", null, listOf("shell", "id"))
            assertTrue(manager.status().getBoolean("adbConnected"))
            manager.executeAdb("session", null, listOf(command))
            assertFalse(command, manager.status().getBoolean("adbConnected"))
        }
    }

    @Test fun targetChangeAndReenableInvalidatePreviouslyVerifiedConnection() {
        val manager = manager()
        manager.setAdbEnabled(true)
        manager.executeAdb("session", null, listOf("shell", "id"))
        manager.setAdbTarget("other-phone")
        assertFalse(manager.status().getBoolean("adbConnected"))
        manager.executeAdb("session", null, listOf("shell", "id"))
        assertTrue(manager.status().getBoolean("adbConnected"))
        manager.setAdbEnabled(true)
        assertFalse(manager.status().getBoolean("adbConnected"))
    }

    @Test fun completedCommandForAnOldTargetCannotRevalidateTheNewTarget() {
        lateinit var manager: AndroidCapabilityManager
        manager = manager { _, _ ->
            manager.setAdbTarget("other-phone")
            FakeProcess(0)
        }
        manager.setAdbEnabled(true)
        assertTrue(manager.executeAdb("session", null, listOf("shell", "id")).getBoolean("ok"))
        assertFalse(manager.status().getBoolean("adbConnected"))
    }

    @Test fun capabilityChangeDuringCommandPreparationPreventsTheOldProcessFromStarting() {
        for (change in listOf("disable", "target", "import", "cancel")) {
            var beforeRead: (() -> Unit)? = null
            val executable = object : File(executableFile().absolutePath) {
                override fun canRead(): Boolean {
                    val callback = beforeRead
                    beforeRead = null
                    callback?.invoke()
                    return super.canRead()
                }
            }
            val started = AtomicReference<List<String>?>(null)
            val directory = kotlin.io.path.createTempDirectory("adb-prepare-test").toFile()
            try {
                val manager = AndroidCapabilityManager(adbExecutable = executable, storageDirectory = directory,
                    processFactory = AndroidCapabilityManager.ProcessFactory { argv, _ ->
                        started.set(argv)
                        FakeProcess(0)
                    })
                manager.setAdbEnabled(true)
                beforeRead = {
                    when (change) {
                        "disable" -> manager.setAdbEnabled(false)
                        "target" -> manager.setAdbTarget("other-phone")
                        "cancel" -> manager.cancelAll()
                        else -> manager.importAdb(ByteArrayInputStream("replacement".toByteArray()))
                    }
                }
                val result = manager.executeAdb("session", null, listOf("shell", "id"))
                assertEquals(change, null, started.get())
                assertEquals(if (change == "disable") "ADB_DISABLED" else "ADB_DISCONNECTED", result.errorCode())
                assertFalse(manager.status().getBoolean("adbConnected"))
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test fun adbEnvironmentProvidesPrivateWritableHomeAndTempWithoutBridgeSecrets() {
        val directory = kotlin.io.path.createTempDirectory("adb-env-test").toFile()
        try {
            val home = File(directory, "home")
            val temp = File(directory, "tmp")
            val source = mapOf("HOME" to "/old-home", "TMPDIR" to "/old-tmp", "PATH" to "/system/bin",
                "DSH_ANDROID" to "1", "DSH_ANDROID_MOBILE_SOCKET" to "socket-secret",
                "DSH_ANDROID_MOBILE_TOKEN" to "bridge-secret", "DSH_API_BEARER" to "other-secret")
            val environment = AndroidCapabilityManager.adbEnvironment(source, home, temp)!!
            assertTrue(home.isDirectory && home.canWrite())
            assertTrue(temp.isDirectory && temp.canWrite())
            assertEquals(home.canonicalPath, environment["HOME"])
            assertEquals(temp.canonicalPath, environment["TMPDIR"])
            assertEquals("/system/bin", environment["PATH"])
            assertEquals("1", environment["DSH_ANDROID"])
            assertFalse(environment.containsKey("DSH_ANDROID_MOBILE_SOCKET"))
            assertFalse(environment.containsKey("DSH_ANDROID_MOBILE_TOKEN"))
            assertFalse(environment.containsKey("DSH_API_BEARER"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun adbEnvironmentRejectsAnUnusableDirectory() {
        val directory = kotlin.io.path.createTempDirectory("adb-env-test").toFile()
        try {
            val home = File(directory, "home").also { it.writeText("not a directory") }
            assertEquals(null, AndroidCapabilityManager.adbEnvironment(emptyMap(), home, File(directory, "tmp")))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun jvmAdbEnvironmentPreservesInjectedHomeAndStillFiltersBridgeSecrets() {
        val environment = AndroidCapabilityManager.adbEnvironment(
            mapOf("HOME" to "/injected-home", "DSH_ANDROID_MOBILE_TOKEN" to "secret"), null, null)!!
        assertEquals("/injected-home", environment["HOME"])
        assertFalse(environment.containsKey("DSH_ANDROID_MOBILE_TOKEN"))
    }

    @Test fun cancellationRejectsAnOperationWidePreparedTokenBeforeProcessStart() {
        val started = AtomicReference<List<String>?>(null)
        val manager = manager { argv, _ -> started.set(argv); FakeProcess(0) }
        manager.setAdbEnabled(true)
        val prepared = manager.prepareAdbRequest()
        manager.cancelAll()
        val result = manager.executeAdb("session", null, listOf("shell", "id"), preparedRequest = prepared)
        assertEquals(null, started.get())
        assertEquals("ADB_DISCONNECTED", result.errorCode())
        assertTrue(manager.executeAdb("session", null, listOf("shell", "id")).getBoolean("ok"))
    }

    @Test fun aPreparedAutomaticTargetCannotFollowANewConfiguredTarget() {
        val started = AtomicReference<List<String>?>(null)
        val manager = manager { argv, _ -> started.set(argv); FakeProcess(0) }
        manager.setAdbEnabled(true)
        val prepared = manager.prepareAdbRequest()
        assertEquals(null, prepared.configuredTarget)
        manager.setAdbTarget("new-phone")
        manager.executeAdb("session", null, listOf("shell", "id"), preparedRequest = prepared)
        assertEquals(null, started.get())
        assertEquals(null, prepared.configuredTarget)
        assertEquals("new-phone", manager.prepareAdbRequest().configuredTarget)
    }

    @Test fun onePreparedOperationCannotStartItsReadStageAfterCancellation() {
        val starts = AtomicInteger()
        val manager = manager { _, _ -> starts.incrementAndGet(); FakeProcess(0) }
        manager.setAdbEnabled(true)
        val prepared = manager.prepareAdbRequest()
        assertTrue(manager.executeAdb("session", null, listOf("shell", "uiautomator", "dump"),
            preparedRequest = prepared).getBoolean("ok"))
        manager.cancelAll()
        val read = manager.executeAdb("session", null, listOf("exec-out", "cat", "/data/local/tmp/probe.xml"),
            preparedRequest = prepared)
        assertEquals("ADB_DISCONNECTED", read.errorCode())
        assertEquals(1, starts.get())
    }

    @Test fun completedCancellationKillsAChildWhoseStartWasAlreadyBeingRegistered() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelRequested = CountDownLatch(1)
        val starts = AtomicInteger()
        val child = FakeProcess(0, remainsAlive = true)
        val manager = manager { _, _ ->
            starts.incrementAndGet()
            entered.countDown()
            assertTrue(release.await(3, TimeUnit.SECONDS))
            child
        }
        manager.setAdbEnabled(true)
        val prepared = manager.prepareAdbRequest()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val execution = executor.submit<JSONObject> {
                manager.executeAdb("session", null, listOf("shell", "id"), preparedRequest = prepared)
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val cancellation = executor.submit { cancelRequested.countDown(); manager.cancelAll() }
            assertTrue(cancelRequested.await(1, TimeUnit.SECONDS))
            release.countDown()
            cancellation.get(3, TimeUnit.SECONDS)
            assertTrue(child.destroyed)
            execution.get(3, TimeUnit.SECONDS)
            val late = manager.executeAdb("session", null, listOf("shell", "id"), preparedRequest = prepared)
            assertEquals("ADB_DISCONNECTED", late.errorCode())
            assertEquals(1, starts.get())
        } finally { release.countDown(); manager.cancelAll(); executor.shutdownNow() }
    }

    @Test fun androidArm64ClientUsesSystemLinkerRatherThanExecutingPrivateFile() {
        val elf = androidElf(elfClass = 2, machine = 183)
        assertEquals(listOf("/system/bin/linker64", elf.absolutePath),
            AndroidCapabilityManager.androidAdbCommandPrefix(elf, listOf("arm64-v8a")))
    }

    @Test fun supported32BitClientUses32BitLinker() {
        val elf = androidElf(elfClass = 1, machine = 40)
        assertEquals(listOf("/system/bin/linker", elf.absolutePath),
            AndroidCapabilityManager.androidAdbCommandPrefix(elf, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(null, AndroidCapabilityManager.androidAdbCommandPrefix(elf, listOf("arm64-v8a")))
    }

    @Test fun x86ClientsRequireMatchingDeviceAbi() {
        val x86 = androidElf(1, 3)
        val x64 = androidElf(2, 62)
        assertEquals(listOf("/system/bin/linker", x86.absolutePath),
            AndroidCapabilityManager.androidAdbCommandPrefix(x86, listOf("x86")))
        assertEquals(listOf("/system/bin/linker64", x64.absolutePath),
            AndroidCapabilityManager.androidAdbCommandPrefix(x64, listOf("x86_64")))
        assertEquals(null, AndroidCapabilityManager.androidAdbCommandPrefix(x86, listOf("arm64-v8a")))
        assertEquals(null, AndroidCapabilityManager.androidAdbCommandPrefix(x64, listOf("arm64-v8a")))
    }

    @Test fun androidClientRejectsScriptsDesktopInterpretersAndTruncatedElf() {
        val script = executableFile().also { it.writeText("#!/system/bin/sh\necho adb\n") }
        assertEquals(null, AndroidCapabilityManager.androidAdbCommandPrefix(script, listOf("arm64-v8a")))
        val desktop = androidElf(2, 183, interpreter = "/lib/ld-linux-aarch64.so.1")
        assertEquals(null, AndroidCapabilityManager.androidAdbCommandPrefix(desktop, listOf("arm64-v8a")))
        val truncated = androidElf(2, 183).also { it.writeBytes(it.readBytes().copyOf(32)) }
        assertEquals(null, AndroidCapabilityManager.androidAdbCommandPrefix(truncated, listOf("arm64-v8a")))
    }

    /** ELF metadata fixture with one Android PT_INTERP segment; never executed. */
    private fun androidElf(elfClass: Int, machine: Int,
                           interpreter: String = if (elfClass == 2) "/system/bin/linker64" else "/system/bin/linker"): File {
        val is64 = elfClass == 2
        val headerSize = if (is64) 64 else 52
        val programHeaderSize = if (is64) 56 else 32
        val interpreterBytes = (interpreter + "\u0000").toByteArray()
        val interpreterOffset = headerSize + programHeaderSize
        val bytes = ByteBuffer.allocate(interpreterOffset + interpreterBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), elfClass.toByte(), 1, 1))
        bytes.putShort(16, 3).putShort(18, machine.toShort()).putInt(20, 1)
        if (is64) {
            bytes.putLong(32, headerSize.toLong()).putShort(52, headerSize.toShort())
                .putShort(54, programHeaderSize.toShort()).putShort(56, 1)
            bytes.putInt(headerSize, 3).putLong(headerSize + 8, interpreterOffset.toLong())
                .putLong(headerSize + 32, interpreterBytes.size.toLong())
        } else {
            bytes.putInt(28, headerSize).putShort(40, headerSize.toShort())
                .putShort(42, programHeaderSize.toShort()).putShort(44, 1)
            bytes.putInt(headerSize, 3).putInt(headerSize + 4, interpreterOffset)
                .putInt(headerSize + 16, interpreterBytes.size)
        }
        bytes.position(interpreterOffset)
        bytes.put(interpreterBytes)
        return executableFile().also { it.writeBytes(bytes.array()) }
    }

    private fun manager(
        rootCommand: String? = null,
        rootShellRunner: RootShellRunner? = null,
        factory: AndroidCapabilityManager.ProcessFactory = AndroidCapabilityManager.ProcessFactory { _, _ -> FakeProcess(0) },
    ) = AndroidCapabilityManager(
        processFactory = factory,
        adbExecutable = executableFile(),
        rootCommand = rootCommand,
        rootShellRunner = rootShellRunner,
    )

    private fun manager(
        rootCommand: String? = null,
        rootShellRunner: RootShellRunner? = null,
        factory: (List<String>, Map<String, String>) -> Process,
    ) = manager(rootCommand, rootShellRunner, AndroidCapabilityManager.ProcessFactory(factory))

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
        @Volatile private var alive = remainsAlive
        @Volatile var destroyed = false
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
