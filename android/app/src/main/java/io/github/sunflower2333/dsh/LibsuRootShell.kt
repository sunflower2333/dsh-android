package io.github.sunflower2333.dsh

import android.content.Context
import com.topjohnwu.superuser.Shell
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
    fun execute(commandText: String, timeoutMs: Long): RootShellResult

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

    override fun execute(commandText: String, timeoutMs: Long): RootShellResult {
        val future = workers.submit<RootShellResult> {
            var shell: Shell? = null
            try {
                val startupSeconds = ((timeoutMs + 999L) / 1_000L).coerceAtLeast(1L)
                val current = Shell.Builder.create()
                    .setContext(context)
                    .setTimeout(startupSeconds)
                    .build()
                if (Thread.currentThread().isInterrupted) {
                    runCatching { current.close() }
                    return@submit RootShellResult.Failed("TIMEOUT")
                }
                shell = current
                activeShells += current
                if (!current.isRoot) return@submit RootShellResult.Failed("ROOT_UNAVAILABLE")

                val stdout = BoundedLines()
                val stderr = BoundedLines()
                val result = current.newJob()
                    .add(commandText)
                    .to(stdout, stderr)
                    .exec()
                if (stdout.truncated || stderr.truncated) {
                    return@submit RootShellResult.Failed("OUTPUT_LIMIT")
                }
                RootShellResult.Completed(
                    exitCode = result.code,
                    stdout = stdout.text(),
                    stderr = stderr.text(),
                    truncated = false,
                )
            } catch (_: Throwable) {
                RootShellResult.Failed("ROOT_UNAVAILABLE")
            } finally {
                shell?.let {
                    activeShells.remove(it)
                    runCatching { it.close() }
                }
            }
        }

        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            closeActiveShells()
            RootShellResult.Failed("TIMEOUT")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            closeActiveShells()
            RootShellResult.Failed("TIMEOUT")
        } catch (_: Throwable) {
            RootShellResult.Failed("ROOT_UNAVAILABLE")
        }
    }

    override fun cancelAll() {
        closeActiveShells()
    }

    private fun closeActiveShells() {
        activeShells.toList().forEach { shell ->
            runCatching { shell.close() }
            activeShells.remove(shell)
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
