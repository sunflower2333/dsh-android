package io.github.sunflower2333.dsh

import android.content.Context
import android.net.Uri
import android.os.Build
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    @ConsistentCopyVisibility
    data class PreparedAdbRequest internal constructor(
        internal val generation: Long,
        val configuredTarget: String?,
        internal val serverGeneration: Long,
    )

    @ConsistentCopyVisibility
    data class PreparedRootRequest internal constructor(
        internal val generation: Long,
        internal val backendGeneration: Long,
    )

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

        private val deviceCommands = setOf(
            "shell", "exec-in", "exec-out", "push", "pull", "install", "install-multiple",
            "install-multi-package", "uninstall", "sync", "forward", "reverse", "jdwp", "logcat",
            "bugreport", "get-devpath", "get-serialno", "get-state",
        )
        private val hostCommands = setOf("version", "help", "devices", "connect", "pair", "start-server", "server")
        private val disconnectCommands = setOf("disconnect", "kill-server")

        internal fun adbEnvironment(source: Map<String, String>, home: File?, temp: File?): Map<String, String>? {
            val environment = cleanEnvironment(source).toMutableMap()
            if (home == null && temp == null) return environment
            if (home == null || temp == null) return null
            return runCatching {
                for (directory in listOf(home, temp)) {
                    if ((!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) || !directory.canWrite()) {
                        return null
                    }
                }
                environment["HOME"] = home.canonicalPath
                environment["TMPDIR"] = temp.canonicalPath
                environment
            }.getOrNull()
        }

        private fun cleanEnvironment(source: Map<String, String>): Map<String, String> = source.filterKeys { key ->
            !dshSecretName.matches(key) && key != "DSH_ANDROID_MOBILE_SOCKET" && key != "DSH_ANDROID_MOBILE_TOKEN"
        }

        /** Android blocks execve of writable app data; use its linker for an Android PIE. */
        internal fun androidAdbCommandPrefix(executable: File, supportedAbis: List<String>): List<String>? {
            return runCatching {
                RandomAccessFile(executable, "r").use { input ->
                    if (input.length() < 64) return null
                    val header = ByteArray(64).also(input::readFully)
                    if (!header.copyOfRange(0, 4).contentEquals(byteArrayOf(127, 69, 76, 70)) ||
                        header[5] != 1.toByte() || header[6] != 1.toByte()) return null
                    val is64 = when (header[4].toInt()) { 1 -> false; 2 -> true; else -> return null }
                    val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    if (fields.getShort(16).toInt() != 3) return null // ET_DYN / PIE only
                    val abi = when (is64 to fields.getShort(18).toInt()) {
                        true to 183 -> "arm64-v8a"
                        false to 40 -> "armeabi-v7a"
                        true to 62 -> "x86_64"
                        false to 3 -> "x86"
                        else -> return null
                    }
                    if (abi !in supportedAbis) return null
                    val linker = if (is64) "/system/bin/linker64" else "/system/bin/linker"
                    val offset = if (is64) fields.getLong(32) else fields.getInt(28).toLong() and 0xffffffffL
                    val entrySize = fields.getShort(if (is64) 54 else 42).toInt() and 0xffff
                    val count = fields.getShort(if (is64) 56 else 44).toInt() and 0xffff
                    if (entrySize != (if (is64) 56 else 32) || count !in 1..256 ||
                        offset < (if (is64) 64 else 52) || offset > input.length() - count.toLong() * entrySize) return null
                    val program = ByteArray(entrySize)
                    repeat(count) { index ->
                        input.seek(offset + index.toLong() * entrySize)
                        input.readFully(program)
                        val segment = ByteBuffer.wrap(program).order(ByteOrder.LITTLE_ENDIAN)
                        if (segment.getInt(0) == 3) { // PT_INTERP must name Android's matching linker.
                            val pathOffset = if (is64) segment.getLong(8) else segment.getInt(4).toLong() and 0xffffffffL
                            val pathSize = if (is64) segment.getLong(32) else segment.getInt(16).toLong() and 0xffffffffL
                            val expected = (linker + "\u0000").toByteArray(Charsets.US_ASCII)
                            if (pathSize != expected.size.toLong() || pathOffset < 0 ||
                                pathOffset > input.length() - pathSize) return null
                            val actual = ByteArray(expected.size)
                            input.seek(pathOffset)
                            input.readFully(actual)
                            return if (actual.contentEquals(expected)) listOf(linker, executable.absolutePath) else null
                        }
                    }
                    null
                }
            }.getOrNull()
        }
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
    private val adbServerProcesses = ConcurrentHashMap<Process, Long>()
    private val activeLock = Any()
    private val managedAdbServer = appContext?.let {
        ManagedAdbServer(endpointFactory = { ManagedAdbServer.privateEndpoint(it.filesDir) }, clock = clock, onStopped = { process ->
            synchronized(activeLock) { active.remove(process); adbServerProcesses.remove(process) }
        })
    }
    private val adbEnabledState = AtomicBoolean(
        prefs?.getBoolean("adb_enabled", false) == true && adbFile.isFile
    )
    // A stale preference must not re-enable root after su is removed or the
    // device changes. Root remains an explicit opt-in on every process start.
    private val rootEnabledState = AtomicBoolean(prefs?.getBoolean("root_enabled", false) == true && resolveRoot() != null)
    // A stored opt-in or executable does not prove a live ADB transport.
    @Volatile private var adbConnectedState = false
    private var adbConnectionGeneration = 0L // guarded by activeLock
    private var adbServerGeneration = 0L // guarded by activeLock
    private var rootRequestGeneration = 0L // guarded by activeLock
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
        if (enabled && adbCommandPrefix() == null) return error("ADB_UNAVAILABLE")
        synchronized(activeLock) {
            adbEnabledState.set(enabled)
            invalidateAdbConnection()
        }
        if (!enabled) {
            cancelCapability(ADB)
        }
        prefs?.edit()?.putBoolean("adb_enabled", enabled)?.apply()
        return status()
    }

    fun setRootEnabled(enabled: Boolean): JSONObject {
        if (enabled && resolveRoot() == null) return error("ROOT_UNAVAILABLE")
        rootAvailableState = resolveRoot() != null
        synchronized(activeLock) { rootEnabledState.set(enabled) }
        if (!enabled) cancelCapability(ROOT)
        prefs?.edit()?.putBoolean("root_enabled", enabled)?.apply()
        return status()
    }

    fun setAdbTarget(serial: String?): JSONObject {
        if (serial != null && !isSafeSerial(serial)) return error("INVALID_SERIAL")
        synchronized(activeLock) {
            targetState = serial
            invalidateAdbConnection()
        }
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
        var invalidatedServerGeneration: Long? = null
        return try {
            val directory = File(root, "tools")
            if (!directory.exists() && !directory.mkdirs()) return error("IMPORT_FAILED")
            val temporary = File(directory, ".adb-${System.nanoTime()}.tmp")
            input.use { source -> temporary.outputStream().use { destination -> source.copyTo(destination) } }
            if (appContext != null && androidAdbCommandPrefix(temporary, Build.SUPPORTED_ABIS.toList()) == null) {
                temporary.delete()
                return error("IMPORT_FAILED")
            }
            setPrivateExecutable(temporary)
            synchronized(activeLock) {
                invalidateAdbConnection()
                adbServerGeneration++
                invalidatedServerGeneration = adbServerGeneration
                if (!temporary.renameTo(adbFile)) {
                    temporary.copyTo(adbFile, overwrite = true)
                    temporary.delete()
                    setPrivateExecutable(adbFile)
                }
            }
            status()
        } catch (_: Throwable) {
            error("IMPORT_FAILED")
        } finally {
            invalidatedServerGeneration?.let(::disposeManagedServersBefore)
        }
    }

    fun prepareAdbRequest(): PreparedAdbRequest = synchronized(activeLock) {
        PreparedAdbRequest(adbConnectionGeneration, targetState, adbServerGeneration)
    }

    fun executeAdb(sessionId: String, serial: String?, argv: List<String>, timeoutMs: Long = 5_000L,
                   preparedRequest: PreparedAdbRequest? = null): JSONObject {
        if (!isSession(sessionId)) return error("INVALID_SESSION")
        val request = preparedRequest ?: prepareAdbRequest()
        synchronized(activeLock) {
            adbStartError(request.generation, request.configuredTarget, request.serverGeneration)?.let { return error(it) }
        }
        var connectionGeneration = request.generation
        val configuredTarget = request.configuredTarget
        val prefix = adbCommandPrefix() ?: return error("ADB_UNAVAILABLE")
        val selectedSerial = serial ?: configuredTarget
        if (serial != null && !isSafeSerial(serial)) return error("INVALID_SERIAL")
        if (selectedSerial != null && !isSafeSerial(selectedSerial)) return error("INVALID_SERIAL")
        val argsError = validateArgv(argv)
        if (argsError != null) return error(argsError)
        if (!validTimeout(timeoutMs)) return error("INVALID_TIMEOUT")
        val deadline = clock.nowMs() + timeoutMs
        var environment = adbEnvironment(System.getenv(), appContext?.let { File(root, "tools/adb-home") },
            appContext?.let { File(it.cacheDir, "dsh-adb") }) ?: return error("ADB_STORAGE_UNAVAILABLE")
        val server = managedAdbServer
        if (server != null) {
            if (!ManagedAdbServer.allowedClientArguments(argv)) return error("INVALID_ARGV")
            if (argv.first() == "kill-server") {
                val existing = server.readyEnvironment(request.serverGeneration)
                if (existing == null) {
                    stopManagedServer(request)?.let { return error(it) }
                    return JSONObject().put("ok", true).put("value", JSONObject()
                        .put("exitCode", 0).put("stdout", "Managed ADB server stopped.\n")
                        .put("stderr", "").put("truncated", false))
                }
                environment = existing
            } else if (argv.first() !in setOf("version", "help")) {
                when (val ready = server.ensureReady(request.serverGeneration, prefix, environment, deadline) { command, env ->
                    synchronized(activeLock) {
                        val denied = adbStartError(request.generation, configuredTarget, request.serverGeneration)
                            ?: if (clock.nowMs() >= deadline) "TIMEOUT" else null
                        if (denied != null) ManagedAdbServer.StartResult.Denied(denied)
                        else ManagedAdbServer.StartResult.Started(processFactory.start(command, env).also { process ->
                            active.add(process)
                            adbServerProcesses[process] = request.serverGeneration
                        })
                    }
                }) {
                    is ManagedAdbServer.Result.Failed -> return error(ready.code)
                    is ManagedAdbServer.Result.Ready -> environment = ready.environment
                }
            }
        }
        val command = buildList {
            addAll(prefix)
            if (selectedSerial != null) { add("-s"); add(selectedSerial) }
            addAll(argv)
        }
        val result = execute(ADB, command, sessionId, timeoutMs, environment, deadline) {
            // execute invokes this check while holding the same lock as opt-in,
            // target/import invalidation, process creation, and registration.
            val denied = adbStartError(connectionGeneration, configuredTarget, request.serverGeneration)
            when {
                denied != null -> denied
                clock.nowMs() >= deadline -> "TIMEOUT"
                else -> {
                    if (argv.first() in disconnectCommands) {
                        invalidateAdbConnection()
                        connectionGeneration = adbConnectionGeneration
                    }
                    null
                }
            }
        }
        if (server != null && argv.first() == "kill-server") {
            stopManagedServer(PreparedAdbRequest(connectionGeneration, configuredTarget, request.serverGeneration))
                ?.let { return error(it) }
        }
        synchronized(activeLock) {
            if (connectionGeneration == adbConnectionGeneration && adbEnabledState.get() && configuredTarget == targetState) {
                val verb = argv.first()
                // forward --list queries every host forwarding record, even with -s.
                val hostQuery = verb in hostCommands || (verb == "forward" && argv.getOrNull(1) == "--list")
                if (!hostQuery) {
                    adbConnectedState = verb in deviceCommands && result.optBoolean("ok") &&
                        (verb != "get-state" || result.optJSONObject("value")?.optString("stdout")?.trim() == "device")
                }
            }
        }
        return result
    }

    private fun adbCommandPrefix(): List<String>? {
        if (!(adbFile.isFile && adbFile.canRead())) return null
        // Plain JVM clients are injected by tests/embedders and may be scripts.
        return if (appContext == null) listOf(adbFile.absolutePath).takeIf { adbFile.canExecute() }
        else androidAdbCommandPrefix(adbFile, Build.SUPPORTED_ABIS.toList())
    }

    private fun invalidateAdbConnection() = synchronized(activeLock) {
        adbConnectionGeneration++
        adbConnectedState = false
    }

    /** Called only under activeLock; contains no controller callbacks or I/O. */
    private fun adbStartError(generation: Long, target: String?, serverGeneration: Long): String? = when {
        !adbEnabledState.get() -> "ADB_DISABLED"
        generation != adbConnectionGeneration || target != targetState || serverGeneration != adbServerGeneration -> "ADB_DISCONNECTED"
        else -> null
    }

    private fun stopManagedServer(request: PreparedAdbRequest): String? {
        val generation = synchronized(activeLock) {
            adbStartError(request.generation, request.configuredTarget, request.serverGeneration)?.let { return it }
            invalidateAdbConnection()
            ++adbServerGeneration
        }
        disposeManagedServersBefore(generation)
        return null
    }

    private fun disposeManagedServersBefore(generation: Long) {
        val children = synchronized(activeLock) {
            adbServerProcesses.filterValues { it < generation }.keys.toList().also { processes ->
                processes.forEach { active.remove(it); adbServerProcesses.remove(it) }
            }
        }
        children.forEach { runCatching { it.destroyForcibly() } }
        managedAdbServer?.invalidateBefore(generation)
    }

    fun prepareRootRequest(): PreparedRootRequest = synchronized(activeLock) {
        // Capture both epochs while the controller still owns the original
        // grant. A later call must never adopt a post-pause backend generation.
        PreparedRootRequest(rootRequestGeneration, rootBackend?.currentCancellationGeneration() ?: 0L)
    }

    internal fun rootRequestError(request: PreparedRootRequest): String? = synchronized(activeLock) {
        when {
            !rootEnabledState.get() || request.generation != rootRequestGeneration -> "ROOT_DISABLED"
            else -> null
        }
    }

    fun executeRoot(sessionId: String, argv: List<String>, timeoutMs: Long = 5_000L,
                    preparedRequest: PreparedRootRequest? = null): JSONObject {
        if (!isSession(sessionId)) return error("INVALID_SESSION")
        val request = preparedRequest ?: prepareRootRequest()
        rootRequestError(request)?.let { return error(it) }
        val su = resolveRoot() ?: return error("ROOT_UNAVAILABLE")
        val argsError = validateArgv(argv)
        if (argsError != null) return error(argsError)
        if (!validTimeout(timeoutMs)) return error("INVALID_TIMEOUT")
        // The root backend receives one command string. Every argument has
        // already been checked for shell metacharacters; quote spaces for the
        // child shell.
        val commandText = argv.joinToString(" ") { "'$it'" }
        val result = rootBackend?.let { backend ->
            rootRequestError(request)?.let { return error(it) }
            // The backend atomically checks this captured generation when it
            // registers its worker; never refresh it after cancellation.
            rootResult(backend.execute(commandText, timeoutMs, request.backendGeneration))
        } ?: execute(ROOT, listOf(su, "-c", commandText), sessionId, timeoutMs,
            startError = { rootRequestError(request) })
        // A completed command is still private until its original epoch is
        // checked. The controller additionally rechecks the native user grant.
        return if (result.optBoolean("ok")) rootRequestError(request)?.let(::error) ?: result else result
    }

    fun cancelAll() {
        rootBackend?.cancelAll()
        val serverGeneration = synchronized(activeLock) {
            rootRequestGeneration++
            invalidateAdbConnection()
            adbServerGeneration++
            active.toList().forEach { process ->
                runCatching { process.destroyForcibly() }
                active.remove(process)
            }
            adbServerGeneration
        }
        disposeManagedServersBefore(serverGeneration)
    }

    private fun execute(
        capability: String,
        command: List<String>,
        sessionId: String,
        timeoutMs: Long,
        environment: Map<String, String> = cleanEnvironment(System.getenv()),
        deadlineMs: Long? = null,
        startError: (() -> String?)? = null,
    ): JSONObject {
        val process = try {
            synchronized(activeLock) {
                startError?.invoke()?.let { return error(it) }
                val started = processFactory.start(command, environment)
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
        val deadline = deadlineMs ?: (clock.nowMs() + timeoutMs)
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
