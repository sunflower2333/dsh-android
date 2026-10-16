package io.github.sunflower2333.dsh

import android.content.Context
import com.topjohnwu.superuser.Shell
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal sealed interface RootShellResult {
    data class Completed(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val truncated: Boolean,
    ) : RootShellResult

    data class Failed(val code: String) : RootShellResult
}

internal fun interface RootShellRunner {
    fun execute(commandText: String, timeoutMs: Long, cancellationGeneration: Long): RootShellResult

    fun currentCancellationGeneration(): Long = 0L

    fun cancelAll() = Unit
}

/**
 * Runs one isolated root shell through libsu. libsu delegates the actual
 * authorization and shell creation to the installed root implementation,
 * including Magisk and KernelSU.
 */
internal class LibsuRootShellRunner(
    private val context: Context,
) : RootShellRunner {
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "dsh-root-shell").apply { isDaemon = true }
    }
    private val activeShells = ConcurrentHashMap.newKeySet<Shell>()
    private val activeWorkers = ConcurrentHashMap.newKeySet<Future<*>>()
    private val activeCancellations = ConcurrentHashMap.newKeySet<AtomicBoolean>()
    private val cancellationLock = Any()
    private val cancellationGeneration = AtomicLong(0L)

    override fun currentCancellationGeneration(): Long = cancellationGeneration.get()

    override fun execute(
        commandText: String,
        timeoutMs: Long,
        expectedGeneration: Long,
    ): RootShellResult {
        val future: Future<RootShellResult>
        val cancellation = AtomicBoolean(false)
        synchronized(cancellationLock) {
            if (!isCurrent(expectedGeneration, cancellation)) return RootShellResult.Failed("ROOT_DISABLED")
            future = try {
                workers.submit<RootShellResult> {
                    runCommand(commandText, timeoutMs, expectedGeneration, cancellation)
                }
            } catch (_: Throwable) {
                return RootShellResult.Failed("ROOT_UNAVAILABLE")
            }
            // Register the worker while holding the same lock used to advance
            // the generation. This closes the submit/cancel window.
            activeWorkers += future
            activeCancellations += cancellation
        }

        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            cancellation.set(true)
            future.cancel(true)
            closeActiveShells()
            RootShellResult.Failed("TIMEOUT")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            cancellation.set(true)
            future.cancel(true)
            closeActiveShells()
            RootShellResult.Failed("TIMEOUT")
        } catch (_: CancellationException) {
            if (!isCurrent(expectedGeneration, cancellation)) RootShellResult.Failed("ROOT_DISABLED")
            else RootShellResult.Failed("ROOT_UNAVAILABLE")
        } catch (_: Throwable) {
            RootShellResult.Failed("ROOT_UNAVAILABLE")
        } finally {
            activeWorkers.remove(future)
            activeCancellations.remove(cancellation)
        }
    }

    override fun cancelAll() {
        val (workersToCancel, cancellationsToApply) = synchronized(cancellationLock) {
            cancellationGeneration.incrementAndGet()
            activeWorkers.toList() to activeCancellations.toList()
        }
        cancellationsToApply.forEach { it.set(true) }
        workersToCancel.forEach { it.cancel(true) }
        closeActiveShells()
    }

    private fun isCurrent(expectedGeneration: Long, cancellation: AtomicBoolean): Boolean =
        !cancellation.get() && cancellationGeneration.get() == expectedGeneration

    private fun runCommand(
        commandText: String,
        timeoutMs: Long,
        expectedGeneration: Long,
        cancellation: AtomicBoolean,
    ): RootShellResult {
        if (!isCurrent(expectedGeneration, cancellation) || Thread.currentThread().isInterrupted) {
            return RootShellResult.Failed("ROOT_DISABLED")
        }
        var shell: Shell? = null
        try {
            val startupSeconds = ((timeoutMs + 999L) / 1_000L).coerceAtLeast(1L)
            val current = Shell.Builder.create()
                .setContext(context)
                .setTimeout(startupSeconds)
                .build()
            // Revocation can happen while libsu is resolving the root shell.
            // Never register or execute a shell from an obsolete generation.
            if (!isCurrent(expectedGeneration, cancellation) || Thread.currentThread().isInterrupted) {
                runCatching { current.close() }
                return RootShellResult.Failed("ROOT_DISABLED")
            }
            shell = current
            activeShells += current
            if (!isCurrent(expectedGeneration, cancellation) || Thread.currentThread().isInterrupted) {
                return RootShellResult.Failed("ROOT_DISABLED")
            }
            if (!current.isRoot) return RootShellResult.Failed("ROOT_UNAVAILABLE")

            val stdout = BoundedLines()
            val stderr = BoundedLines()
            if (!isCurrent(expectedGeneration, cancellation) || Thread.currentThread().isInterrupted) {
                return RootShellResult.Failed("ROOT_DISABLED")
            }
            val result = current.newJob()
                .add(commandText)
                .to(stdout, stderr)
                .exec()
            if (!isCurrent(expectedGeneration, cancellation)) return RootShellResult.Failed("ROOT_DISABLED")
            if (stdout.truncated || stderr.truncated) {
                return RootShellResult.Failed("OUTPUT_LIMIT")
            }
            return RootShellResult.Completed(
                exitCode = result.code,
                stdout = stdout.text(),
                stderr = stderr.text(),
                truncated = false,
            )
        } catch (_: Throwable) {
            return if (!isCurrent(expectedGeneration, cancellation)) RootShellResult.Failed("ROOT_DISABLED")
            else RootShellResult.Failed("ROOT_UNAVAILABLE")
        } finally {
            shell?.let {
                activeShells.remove(it)
                runCatching { it.close() }
            }
        }
    }

    private fun closeActiveShells() {
        activeShells.toList().forEach { shell ->
            runCatching { shell.close() }
        }
    }

    private class BoundedLines : java.util.AbstractList<String>() {
        private val values = ArrayList<String>()
        private var bytes = 0
        var truncated = false
            private set

        override val size: Int get() = values.size

        override fun get(index: Int): String = values[index]

        override fun add(element: String): Boolean {
            val extra = element.toByteArray(StandardCharsets.UTF_8).size + if (values.isEmpty()) 0 else 1
            if (bytes + extra > AndroidCapabilityManager.MAX_OUTPUT_BYTES) {
                truncated = true
                return true
            }
            values += element
            bytes += extra
            return true
        }

        fun text(): String = values.joinToString("\n")
    }
}
