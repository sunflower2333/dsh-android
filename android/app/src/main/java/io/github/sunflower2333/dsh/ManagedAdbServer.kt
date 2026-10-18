package io.github.sunflower2333.dsh

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns an optional foreground ADB server; never changes device connection status. */
internal class ManagedAdbServer(
    private val endpointFactory: () -> String,
    private val clock: AndroidCapabilityManager.Clock = AndroidCapabilityManager.MonotonicClock,
    private val readiness: (String, Long) -> Boolean = ::probe,
    private val onStopped: (Process) -> Unit = {},
) {
    sealed interface Result {
        data class Ready(val environment: Map<String, String>) : Result
        data class Failed(val code: String) : Result
    }
    sealed interface StartResult {
        data class Started(val process: Process) : StartResult
        data class Denied(val code: String) : StartResult
    }

    private class Attempt(val generation: Long, val prefix: List<String>, val baseEnvironment: Map<String, String>) {
        val done = CountDownLatch(1)
        val disposed = AtomicBoolean(false)
        @Volatile var process: Process? = null
        @Volatile var socketPath: File? = null
        @Volatile var result: Result? = null
    }

    private val lock = Any()
    private var minimumGeneration = 0L
    private var current: Attempt? = null

    fun ensureReady(generation: Long, prefix: List<String>, environment: Map<String, String>, deadlineMs: Long,
                    start: (List<String>, Map<String, String>) -> StartResult): Result {
        if (clock.nowMs() >= deadlineMs) return Result.Failed("TIMEOUT")
        var previous: Attempt? = null
        val selected = synchronized(lock) {
            if (generation < minimumGeneration) return Result.Failed("CANCELLED")
            minimumGeneration = generation
            val existing = current
            if (existing != null && existing.generation == generation && existing.prefix == prefix &&
                existing.baseEnvironment == environment &&
                (existing.result == null || (existing.result is Result.Ready && existing.process?.isAlive == true))) {
                existing to false
            } else {
                previous = existing
                existing?.let(::cancelLocked)
                Attempt(generation, prefix.toList(), environment.toMap()).also { current = it } to true
            }
        }
        previous?.let(::dispose)
        val (attempt, owner) = selected
        if (owner) return startAndWait(attempt, deadlineMs, start)
        val remaining = deadlineMs - clock.nowMs()
        if (remaining <= 0) return Result.Failed("TIMEOUT")
        return try {
            if (attempt.done.await(remaining, TimeUnit.MILLISECONDS)) attempt.result ?: Result.Failed("CANCELLED")
            else Result.Failed("TIMEOUT")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.Failed("CANCELLED")
        }
    }

    /** Detach/wake under the monitor; process and stream teardown always happens outside it. */
    fun invalidateBefore(generation: Long) {
        val detached = synchronized(lock) {
            minimumGeneration = maxOf(minimumGeneration, generation)
            current?.takeIf { it.generation < generation }?.also {
                current = null
                cancelLocked(it)
            }
        }
        detached?.let(::dispose)
    }

    private fun startAndWait(attempt: Attempt, deadlineMs: Long,
                             start: (List<String>, Map<String, String>) -> StartResult): Result {
        try {
            // Private storage setup, caller start/authorization callback, and readiness I/O
            // must never execute while holding the helper monitor.
            val endpoint = try {
                endpointFactory().also {
                    attempt.socketPath = filesystemPath(it) ?: throw IOException("Invalid private ADB socket")
                }
            } catch (_: Exception) {
                return fail(attempt, "ADB_STORAGE_UNAVAILABLE")
            }
            if (!isCurrent(attempt)) {
                dispose(attempt)
                return attempt.result ?: Result.Failed("CANCELLED")
            }
            if (clock.nowMs() >= deadlineMs) return fail(attempt, "TIMEOUT")
            val environment = attempt.baseEnvironment + mapOf(
                "ADB_SERVER_SOCKET" to endpoint,
                "ADB_LOCAL_TRANSPORT_MAX_PORT" to "0",
                "ADB_MDNS_AUTO_CONNECT" to "",
            )
            val started = start(attempt.prefix + listOf("-L", endpoint, "server", "nodaemon"), environment)
            if (started is StartResult.Denied) return fail(attempt, started.code)
            val process = (started as StartResult.Started).process
            val published = synchronized(lock) {
                if (current === attempt && attempt.result == null) {
                    attempt.process = process
                    true
                } else false
            }
            if (!published) {
                stop(process, attempt.socketPath)
                return attempt.result ?: Result.Failed("CANCELLED")
            }
            drain(process.inputStream)
            drain(process.errorStream)
            while (isCurrent(attempt)) {
                if (!process.isAlive) return fail(attempt, "ADB_SERVER_START_FAILED")
                val remaining = deadlineMs - clock.nowMs()
                if (remaining <= 0) return fail(attempt, "TIMEOUT")
                if (readiness(endpoint, minOf(deadlineMs, clock.nowMs() + 100)) && process.isAlive) {
                    synchronized(lock) {
                        if (current === attempt && attempt.result == null) {
                            attempt.result = Result.Ready(environment)
                            attempt.done.countDown()
                            return attempt.result!!
                        }
                    }
                }
                val pause = minOf(25L, deadlineMs - clock.nowMs())
                if (pause > 0) Thread.sleep(pause)
            }
            return attempt.result ?: Result.Failed("CANCELLED")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return fail(attempt, "CANCELLED")
        } catch (_: Throwable) {
            return fail(attempt, "ADB_SERVER_START_FAILED")
        }
    }

    private fun isCurrent(attempt: Attempt): Boolean = synchronized(lock) {
        current === attempt && attempt.result == null
    }

    private fun fail(attempt: Attempt, code: String): Result {
        val result = synchronized(lock) {
            if (current === attempt) current = null
            if (attempt.result == null) attempt.result = Result.Failed(code)
            attempt.done.countDown()
            attempt.result!!
        }
        dispose(attempt)
        return result
    }

    private fun cancelLocked(attempt: Attempt) {
        attempt.result = Result.Failed("CANCELLED")
        attempt.done.countDown()
    }

    fun readyEnvironment(generation: Long): Map<String, String>? = synchronized(lock) {
        current?.takeIf { it.generation == generation && it.process?.isAlive == true }
            ?.result?.let { (it as? Result.Ready)?.environment }
    }

    private fun dispose(attempt: Attempt) {
        val child = attempt.process
        if (child != null && attempt.disposed.compareAndSet(false, true)) stop(child, attempt.socketPath)
        // Cancellation may precede a child's return from start(). Its late
        // return takes the stop path again, after the child could have bound.
        unlink(attempt.socketPath)
    }

    private fun stop(child: Process, socketPath: File?) {
        runCatching { child.destroyForcibly() }
        runCatching { child.inputStream.close() }
        runCatching { child.errorStream.close() }
        runCatching { child.outputStream.close() }
        runCatching { onStopped(child) }
        unlink(socketPath)
        if (child.isAlive) {
            // destroyForcibly is asynchronous. Remove a late bind only after
            // exit as well, always using this attempt's unique pathname.
            Thread({
                runCatching { child.waitFor() }
                unlink(socketPath)
            }, "dsh-adb-server-reap").also { it.isDaemon = true; it.start() }
        }
    }

    private fun unlink(path: File?) { path?.let { runCatching { it.delete() } } }

    private fun drain(input: InputStream) {
        // Consume all output with constant memory; never stop reading at an output
        // limit, which could block a long-lived server on a full pipe.
        Thread({ runCatching {
            input.use { stream ->
                val buffer = ByteArray(4096)
                while (stream.read(buffer) != -1) { }
            }
        } }, "dsh-adb-server-output").also { it.isDaemon = true; it.start() }
    }

    companion object {
        internal fun allowedClientArguments(argv: List<String>): Boolean {
            val verb = argv.firstOrNull() ?: return false
            // Transport selection belongs to the explicit serial argument. Global
            // flags can override the owned server socket or enter server mode.
            return !verb.startsWith("-") && verb !in setOf("server", "fork-server", "nodaemon") &&
                (verb !in setOf("start-server", "kill-server") || argv.size == 1)
        }

        private val random = SecureRandom()

        internal fun privateEndpoint(filesDirectory: File, secureDirectory: (File) -> Unit = ::secureSocketDirectory): String {
            val directory = File(filesDirectory.canonicalFile, "adb")
            if (!directory.isDirectory && !directory.mkdir() && !directory.isDirectory) {
                throw IOException("Private ADB directory unavailable")
            }
            secureDirectory(directory)
            val name = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val path = File(directory, "s-$name")
            val endpoint = "localfilesystem:${path.absolutePath}"
            if (filesystemPath(endpoint) == null || path.exists()) throw IOException("Private ADB socket unavailable")
            return endpoint
        }

        private fun secureSocketDirectory(directory: File) {
            if (!OsConstants.S_ISDIR(Os.lstat(directory.path).st_mode)) throw IOException("ADB directory is not private storage")
            Os.chmod(directory.path, 448) // 0700: host ADB clients do not authenticate.
            val stat = Os.lstat(directory.path)
            if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_mode and 511 != 448 || stat.st_uid != Os.getuid()) {
                throw IOException("Private ADB directory permissions unavailable")
            }
        }

        private fun filesystemPath(endpoint: String): File? {
            if (!endpoint.startsWith("localfilesystem:")) return null
            val path = endpoint.removePrefix("localfilesystem:")
            return File(path).takeIf { it.isAbsolute && '\u0000' !in path && path.toByteArray(Charsets.UTF_8).size <= 107 }
        }

        internal interface ProbeConnection : AutoCloseable {
            /** Must be nonblocking, including when the listener backlog is full. */
            fun connect(path: String)
            fun write(bytes: ByteArray)
            /** Waits at most timeoutMs, then performs a nonblocking read. */
            fun read(bytes: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
        }

        private class LocalProbeConnection : ProbeConnection {
            private val socket = LocalSocket()
            // Force descriptor creation before connect. On API 30 the timed
            // connect overload throws UnsupportedOperationException.
            private val input = socket.inputStream
            init {
                try {
                    val fd = socket.fileDescriptor
                    Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
                } catch (error: Exception) {
                    socket.close()
                    throw error
                }
            }
            override fun connect(path: String) {
                // A nonblocking AF_UNIX connect either succeeds immediately or
                // fails (e.g. EAGAIN for a full backlog); readiness retries it.
                socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            }
            override fun write(bytes: ByteArray) { socket.outputStream.write(bytes) }
            override fun read(bytes: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
                val descriptor = StructPollfd().apply { fd = socket.fileDescriptor; events = OsConstants.POLLIN.toShort() }
                if (Os.poll(arrayOf(descriptor), timeoutMs) <= 0) return -1
                return input.read(bytes, offset, length)
            }
            override fun close() { socket.close() }
        }

        internal fun probe(endpoint: String, deadlineMs: Long,
                           open: () -> ProbeConnection = ::LocalProbeConnection): Boolean = runCatching {
            val path = filesystemPath(endpoint) ?: return@runCatching false
            if (deadlineMs <= AndroidCapabilityManager.MonotonicClock.nowMs()) return@runCatching false
            open().use { socket ->
                socket.connect(path.path)
                if (deadlineMs <= AndroidCapabilityManager.MonotonicClock.nowMs()) return@runCatching false
                socket.write("000chost:version".toByteArray(Charsets.US_ASCII))
                fun readExact(size: Int): String? {
                    val bytes = ByteArray(size)
                    var offset = 0
                    while (offset < size) {
                        val budget = deadlineMs - AndroidCapabilityManager.MonotonicClock.nowMs()
                        if (budget <= 0) return null
                        val count = socket.read(bytes, offset, size - offset, minOf(100L, budget).toInt())
                        if (count <= 0) return null
                        offset += count
                    }
                    return String(bytes, Charsets.US_ASCII)
                }
                readExact(4) == "OKAY" && readExact(4) == "0004" &&
                    readExact(4)?.matches(Regex("[0-9a-fA-F]{4}")) == true
            }
        }.getOrDefault(false)
    }
}
