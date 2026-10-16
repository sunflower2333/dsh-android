package io.github.sunflower2333.dsh

import android.content.Context
import android.net.Uri
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the two optional native capabilities used by mobile tools.  This class
 * deliberately has no knowledge of the mobile bridge or a shell session: the
 * bridge supplies a session id and remains responsible for authorisation.
 */
class AndroidCapabilityManager internal constructor(
    context: Context? = null,
    private val processFactory: ProcessFactory = DefaultProcessFactory,
    private val adbExecutable: File? = null,
    private val rootCommand: String? = null,
    private val storageDirectory: File? = null,
    private val clock: Clock = MonotonicClock,
    rootShellRunner: RootShellRunner? = null,
) {
    fun interface ProcessFactory {
        fun start(command: List<String>, environment: Map<String, String>): Process
    }

    fun interface Clock { fun nowMs(): Long }

    object MonotonicClock : Clock {
        // System.nanoTime is monotonic and available in plain JVM tests as
        // well as Android; no Android clock shim is needed here.
        override fun nowMs(): Long = System.nanoTime() / 1_000_000L
    }

    object DefaultProcessFactory : ProcessFactory {
        override fun start(command: List<String>, environment: Map<String, String>): Process {
            val builder = ProcessBuilder(command)
            builder.environment().clear()
            builder.environment().putAll(environment)
            return builder.start()
        }
    }

    companion object {
        const val MIN_TIMEOUT_MS = 100L
        const val MAX_TIMEOUT_MS = 15_000L
        const val MAX_ARGV = 32
        const val MAX_ARG_LENGTH = 4_096
        internal const val MAX_OUTPUT_BYTES = 64 * 1024
        private const val ADB = "adb"
        private const val ROOT = "root"

        private val serialPattern = Regex("[A-Za-z0-9._:-]{1,128}")
        // Root arguments are sent through a shell command line (libsu on
        // Android, or `su -c` in the injected compatibility backend), so
        // reject characters that could terminate or extend a quoted argument.
        // A single quote is especially important because it cannot be escaped
        // inside a POSIX single-quoted string without ending that string.
        private val forbiddenShellCharacters = Regex("[;&|$`()<>\\'\\\\\\u0000-\\r\\n]")
        private val dshSecretName = Regex("(?i)^DSH_.*(TOKEN|BEARER|CREDENTIAL|SECRET).*$")
    }

    private val appContext = context?.applicationContext ?: context
    private val rootBackend: RootShellRunner? = rootShellRunner ?:
        if (appContext != null && rootCommand == null) LibsuRootShellRunner(appContext) else null
    private val root = storageDirectory ?: appContext?.filesDir ?:
        File(System.getProperty("java.io.tmpdir") ?: ".", "dsh-capabilities")
    private val adbFile: File get() = adbExecutable ?: File(root, "tools/adb")
    private val prefs by lazy {
        appContext?.getSharedPreferences("android_capabilities", Context.MODE_PRIVATE)
    }
    private val active = ConcurrentHashMap.newKeySet<Process>()
    private val activeLock = Any()
    private val adbEnabledState = AtomicBoolean(
        prefs?.getBoolean("adb_enabled", false) == true && adbFile.isFile
    )
    // A stale preference must not re-enable root after su is removed or the
    // device changes. Root remains an explicit opt-in on every process start.
    private val rootEnabledState = AtomicBoolean(prefs?.getBoolean("root_enabled", false) == true && resolveRoot() != null)
    @Volatile private var adbConnectedState = adbEnabledState.get() && adbFile.isFile
    @Volatile private var targetState: String? = prefs?.getString("adb_target", null)
    @Volatile private var rootAvailableState: Boolean = resolveRoot() != null

    /** A status object contains only the public, non-secret capability fields. */
    fun status(): JSONObject = JSONObject()
        .put("adbConfigured", adbFile.isFile && adbFile.canRead())
        .put("adbEnabled", adbEnabledState.get())
        .put("adbConnected", adbConnectedState && adbEnabledState.get() && adbFile.isFile)
        .put("adbTarget", targetState ?: JSONObject.NULL)
        .put("rootAvailable", rootAvailableState)
        .put("rootEnabled", rootEnabledState.get())

    fun setAdbEnabled(enabled: Boolean): JSONObject {
        if (enabled && !(adbFile.isFile && adbFile.canExecute())) return error("ADB_UNAVAILABLE")
        adbEnabledState.set(enabled)
        if (!enabled) {
            adbConnectedState = false
            cancelCapability(ADB)
        } else adbConnectedState = adbFile.isFile
        prefs?.edit()?.putBoolean("adb_enabled", enabled)?.apply()
        return status()
    }

    fun setRootEnabled(enabled: Boolean): JSONObject {
        if (enabled && resolveRoot() == null) return error("ROOT_UNAVAILABLE")
        rootAvailableState = resolveRoot() != null
        rootEnabledState.set(enabled)
        if (!enabled) cancelCapability(ROOT)
        prefs?.edit()?.putBoolean("root_enabled", enabled)?.apply()
        return status()
    }

    fun setAdbConnected(connected: Boolean) {
        adbConnectedState = connected && adbFile.isFile
    }

    fun setAdbTarget(serial: String?): JSONObject {
        if (serial != null && !isSafeSerial(serial)) return error("INVALID_SERIAL")
        targetState = serial
        prefs?.edit()?.let { editor ->
            if (serial == null) editor.remove("adb_target") else editor.putString("adb_target", serial)
            editor.apply()
        }
        return status()
    }

    /** Copies a user-selected executable into app-private storage with mode 0700. */
    fun importAdb(uri: Uri): JSONObject {
        val resolver = appContext?.contentResolver ?: return error("IMPORT_UNAVAILABLE")
        return try {
            resolver.openInputStream(uri)?.use { importAdb(it) } ?: error("IMPORT_FAILED")
        } catch (_: Throwable) {
            error("IMPORT_FAILED")
        }
    }

    /** InputStream variant keeps import tests independent of a ContentResolver. */
    fun importAdb(input: InputStream): JSONObject {
        return try {
            val directory = File(root, "tools")
            if (!directory.exists() && !directory.mkdirs()) return error("IMPORT_FAILED")
            val temporary = File(directory, ".adb-${System.nanoTime()}.tmp")
            input.use { source -> temporary.outputStream().use { destination -> source.copyTo(destination) } }
            setPrivateExecutable(temporary)
            if (!temporary.renameTo(adbFile)) {
                temporary.copyTo(adbFile, overwrite = true)
                temporary.delete()
                setPrivateExecutable(adbFile)
            }
            adbConnectedState = false
            status()
        } catch (_: Throwable) {
            error("IMPORT_FAILED")
        }
    }

    fun executeAdb(sessionId: String, serial: String?, argv: List<String>, timeoutMs: Long = 5_000L): JSONObject {
        if (!isSession(sessionId)) return error("INVALID_SESSION")
        if (!adbEnabledState.get()) return error("ADB_DISABLED")
        if (!(adbFile.isFile && adbFile.canExecute())) return error("ADB_UNAVAILABLE")
        if (!adbConnectedState) return error("ADB_DISCONNECTED")
        val selectedSerial = serial ?: targetState
        if (serial != null && !isSafeSerial(serial)) return error("INVALID_SERIAL")
        if (selectedSerial != null && !isSafeSerial(selectedSerial)) return error("INVALID_SERIAL")
        val argsError = validateArgv(argv)
        if (argsError != null) return error(argsError)
        if (!validTimeout(timeoutMs)) return error("INVALID_TIMEOUT")
        val command = buildList {
            add(adbFile.absolutePath)
            if (selectedSerial != null) { add("-s"); add(selectedSerial) }
            addAll(argv)
        }
        return execute(ADB, command, sessionId, timeoutMs)
    }

    fun executeRoot(sessionId: String, argv: List<String>, timeoutMs: Long = 5_000L): JSONObject {
        if (!isSession(sessionId)) return error("INVALID_SESSION")
        if (!rootEnabledState.get()) return error("ROOT_DISABLED")
        val su = resolveRoot() ?: return error("ROOT_UNAVAILABLE")
        val argsError = validateArgv(argv)
        if (argsError != null) return error(argsError)
        if (!validTimeout(timeoutMs)) return error("INVALID_TIMEOUT")
        // The root backend receives one command string. Every argument has
        // already been checked for shell metacharacters; quote spaces for the
        // child shell.
        val commandText = argv.joinToString(" ") { "'$it'" }
        rootBackend?.let { backend ->
            // Capture a backend cancellation generation after checking the
            // opt-in state. The second state check closes the window where a
            // revoke can happen while the generation is being read; the
            // backend performs the final atomic generation check before it
            // creates a libsu shell.
            if (!rootEnabledState.get()) return error("ROOT_DISABLED")
            val generation = backend.currentCancellationGeneration()
            if (!rootEnabledState.get()) return error("ROOT_DISABLED")
            return rootResult(backend.execute(commandText, timeoutMs, generation))
        }
        return execute(ROOT, listOf(su, "-c", commandText), sessionId, timeoutMs)
    }

    fun cancelAll() {
        rootBackend?.cancelAll()
        synchronized(activeLock) {
            active.toList().forEach { process ->
                runCatching { process.destroyForcibly() }
                active.remove(process)
            }
        }
    }

    private fun execute(capability: String, command: List<String>, sessionId: String, timeoutMs: Long): JSONObject {
        val process = try {
            synchronized(activeLock) {
                val started = processFactory.start(command, cleanEnvironment(System.getenv()))
                // Register before any capability-revocation action can run,
                // so a process started concurrently with disable is always
                // cancelled.
                active.add(started)
                started
            }
        } catch (_: Throwable) {
            return error("PROCESS_START_FAILED")
        }
        val stdout = Capture()
        val stderr = Capture()
        val outputThreads = listOf(
            Thread { stdout.read(process.inputStream) },
            Thread { stderr.read(process.errorStream) },
        )
        outputThreads.forEach { it.start() }
        val deadline = clock.nowMs() + timeoutMs
        var timedOut = false
        var overflow = false
        try {
            while (process.isAlive) {
                if (stdout.overflow.get() || stderr.overflow.get()) { overflow = true; break }
                if (clock.nowMs() >= deadline) { timedOut = true; break }
                Thread.sleep(10)
            }
            if (timedOut || overflow) process.destroyForcibly()
            if (!process.isAlive) runCatching { process.waitFor(100, TimeUnit.MILLISECONDS) }
            outputThreads.forEach { runCatching { it.join(250) } }
            if (timedOut) return error("TIMEOUT")
            if (overflow) return error("OUTPUT_LIMIT")
            val exit = runCatching { process.exitValue() }.getOrDefault(-1)
            val result = JSONObject()
                .put("exitCode", exit)
                .put("stdout", stdout.text())
                .put("stderr", stderr.text())
                .put("truncated", stdout.truncated || stderr.truncated)
            return if (exit == 0) JSONObject().put("ok", true).put("value", result)
            else JSONObject().put("ok", false).put("error", JSONObject().put("code", "PROCESS_EXIT")
                .put("exitCode", exit).put("truncated", stdout.truncated || stderr.truncated))
        } finally {
            synchronized(activeLock) { active.remove(process) }
            outputThreads.forEach { thread -> if (thread.isAlive) thread.interrupt() }
        }
    }

    private fun cancelCapability(capability: String) {
        // Processes are intentionally not tagged in the public API.  Revoking
        // either capability therefore cancels all child work, which is fail
        // closed and also handles a process between command construction and
        // registration.
        cancelAll()
    }

    private fun rootResult(result: RootShellResult): JSONObject = when (result) {
        is RootShellResult.Failed -> error(result.code)
        is RootShellResult.Completed -> {
            val value = JSONObject()
                .put("exitCode", result.exitCode)
                .put("stdout", result.stdout)
                .put("stderr", result.stderr)
                .put("truncated", result.truncated)
            if (result.exitCode == 0) JSONObject().put("ok", true).put("value", value)
            else JSONObject().put("ok", false).put("error", JSONObject()
                .put("code", "PROCESS_EXIT")
                .put("exitCode", result.exitCode)
                .put("truncated", result.truncated))
        }
    }

    private fun resolveRoot(): String? {
        rootCommand?.let { configured ->
            return configured.takeIf { value -> value.isNotBlank() && File(value).canExecute() }
        }
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
        return (listOf("/system/bin/su", "/system/xbin/su") +
            path.filter { it.isNotBlank() }.map { File(it, "su").absolutePath })
            .firstOrNull { File(it).canExecute() }
    }

    private fun cleanEnvironment(source: Map<String, String>): Map<String, String> = source.filterKeys { key ->
        !dshSecretName.matches(key) && key != "DSH_ANDROID_MOBILE_SOCKET" && key != "DSH_ANDROID_MOBILE_TOKEN"
    }

    private fun setPrivateExecutable(file: File) {
        runCatching { Os.chmod(file.absolutePath, 448) }
        file.setReadable(true, true)
        file.setWritable(true, true)
        file.setExecutable(true, true)
    }

    private fun isSession(value: String): Boolean = value.isNotBlank() && value.length <= 256 &&
        !forbiddenShellCharacters.containsMatchIn(value)

    private fun validateArgv(argv: List<String>): String? {
        if (argv.isEmpty() || argv.size > MAX_ARGV) return "INVALID_ARGV"
        if (argv.any { it.isEmpty() || it.length > MAX_ARG_LENGTH || forbiddenShellCharacters.containsMatchIn(it) }) {
            return "INVALID_ARGV"
        }
        return null
    }

    private fun isSafeSerial(serial: String): Boolean = serialPattern.matches(serial)
    private fun validTimeout(timeoutMs: Long): Boolean = timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS
    private fun error(code: String): JSONObject = JSONObject().put("ok", false)
        .put("error", JSONObject().put("code", code))

    private class Capture {
        val overflow = AtomicBoolean(false)
        var truncated = false
        private val output = ByteArrayOutputStream()
        fun read(input: InputStream) {
            input.use { stream ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = try { stream.read(buffer) } catch (_: Throwable) { break }
                    if (count <= 0) break
                    val remaining = MAX_OUTPUT_BYTES - output.size()
                    if (count > remaining) {
                        if (remaining > 0) output.write(buffer, 0, remaining)
                        truncated = true
                        overflow.set(true)
                        break
                    }
                    output.write(buffer, 0, count)
                }
            }
        }
        fun text(): String = output.toString(Charsets.UTF_8.name())
    }

}
