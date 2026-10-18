package io.github.sunflower2333.dsh

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Manager/backend lifecycle checks; these never create a real Android root shell. */
class RootRequestLifecycleTest {
    @Test fun cancellationWhileCapturingBackendGenerationCannotStartFreshRootWork() {
        val backend = RecordingBackend()
        withManager(backend) { manager ->
            backend.beforeGenerationRead = { manager.cancelAll() }
            val result = manager.executeRoot("previous-control-grant", listOf("id"))
            assertFalse("A completed pause must reject this older request", result.getBoolean("ok"))
            assertEquals("ROOT_DISABLED", result.errorCode())
            assertEquals("No new root command may be submitted after cancellation", 0, backend.executions)
        }
    }

    @Test fun successfulBackendOutputIsNotReturnedAfterCancellation() {
        val backend = RecordingBackend()
        withManager(backend) { manager ->
            backend.afterExecution = { manager.cancelAll() }
            val result = manager.executeRoot("previous-control-grant", listOf("id"))
            assertFalse("Revoked successful output must be discarded", result.getBoolean("ok"))
            assertEquals("ROOT_DISABLED", result.errorCode())
            assertFalse(result.has("value"))
            assertEquals(1, backend.executions)
        }
    }

    @Test fun requestPreparedUnderAnOldControlGrantCannotSubmitAfterPauseCompletes() {
        val backend = RecordingBackend()
        withManager(backend) { manager ->
            val prepared = manager.prepareRootRequest()
            manager.cancelAll()
            assertRevoked(manager.executeRoot("previous-control-grant", listOf("id"), preparedRequest = prepared))
            assertEquals(0, backend.executions)
            assertTrue(manager.executeRoot("fresh-control-grant", listOf("id")).getBoolean("ok"))
            assertEquals(1, backend.executions)
        }
    }

    @Test fun disableThenReenableCannotReviveThePreparedBackendRequest() {
        val backend = RecordingBackend()
        withManager(backend) { manager ->
            val prepared = manager.prepareRootRequest()
            manager.setRootEnabled(false)
            manager.setRootEnabled(true)
            assertRevoked(manager.executeRoot("previous-control-grant", listOf("id"), preparedRequest = prepared))
            assertEquals(0, backend.executions)
        }
    }

    @Test fun backendReceivesTheGenerationCapturedWithTheOriginalGrant() {
        val backend = RecordingBackend()
        withManager(backend) { manager ->
            val prepared = manager.prepareRootRequest()
            // The backend may independently invalidate its active workers.
            // The manager must pass the old value rather than adopt this one.
            backend.cancelAll()
            assertRevoked(manager.executeRoot("previous-control-grant", listOf("id"), preparedRequest = prepared))
            assertEquals(0, backend.executions)
        }
    }

    @Test fun processFallbackCannotStartAfterPreparedGrantWasPaused() {
        val starts = AtomicInteger()
        withManager(factory = AndroidCapabilityManager.ProcessFactory { _, _ ->
            starts.incrementAndGet()
            Child()
        }) { manager ->
            val prepared = manager.prepareRootRequest()
            manager.cancelAll()
            assertRevoked(manager.executeRoot("previous-control-grant", listOf("id"), preparedRequest = prepared))
            assertEquals(0, starts.get())
        }
    }

    @Test fun processFallbackCannotReviveAfterDisableThenReenable() {
        val starts = AtomicInteger()
        withManager(factory = AndroidCapabilityManager.ProcessFactory { _, _ ->
            starts.incrementAndGet()
            Child()
        }) { manager ->
            val prepared = manager.prepareRootRequest()
            manager.setRootEnabled(false)
            manager.setRootEnabled(true)
            assertRevoked(manager.executeRoot("previous-control-grant", listOf("id"), preparedRequest = prepared))
            assertEquals(0, starts.get())
        }
    }

    @Test fun processFallbackRechecksCancellationAtActualProcessStart() {
        val starts = AtomicInteger()
        withManager(factory = AndroidCapabilityManager.ProcessFactory { _, _ ->
            starts.incrementAndGet()
            Child()
        }) { manager ->
            val prepared = manager.prepareRootRequest()
            val arguments = object : AbstractList<String>() {
                override val size = 1
                override fun get(index: Int): String {
                    // A completed pause after the manager's first state check
                    // but before it reaches process creation.
                    manager.cancelAll()
                    return "id"
                }
            }
            assertRevoked(manager.executeRoot("previous-control-grant", arguments, preparedRequest = prepared))
            assertEquals(0, starts.get())
        }
    }

    @Test fun completedPauseKillsAProcessWhoseStartWasAlreadyBeingRegistered() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancellationRequested = CountDownLatch(1)
        val child = Child(alive = true)
        withManager(factory = AndroidCapabilityManager.ProcessFactory { _, _ ->
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            child
        }) { manager ->
            val prepared = manager.prepareRootRequest()
            val executor = Executors.newFixedThreadPool(2)
            try {
                val work = executor.submit<JSONObject> {
                    manager.executeRoot("previous-control-grant", listOf("id"), preparedRequest = prepared)
                }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                val cancellation = executor.submit {
                    cancellationRequested.countDown()
                    manager.cancelAll()
                }
                assertTrue(cancellationRequested.await(1, TimeUnit.SECONDS))
                release.countDown()
                cancellation.get(3, TimeUnit.SECONDS)
                assertTrue("No registered root process survives a completed pause", child.destroyed)
                assertRevoked(work.get(3, TimeUnit.SECONDS))
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    private fun assertRevoked(result: JSONObject) {
        assertFalse(result.getBoolean("ok"))
        assertEquals("ROOT_DISABLED", result.errorCode())
        assertFalse(result.has("value"))
    }

    private fun withManager(
        backend: RootShellRunner? = null,
        factory: AndroidCapabilityManager.ProcessFactory = AndroidCapabilityManager.DefaultProcessFactory,
        block: (AndroidCapabilityManager) -> Unit,
    ) {
        val executable = File.createTempFile("dsh-root-lifecycle-", ".bin")
        try {
            check(executable.setExecutable(true))
            val manager = AndroidCapabilityManager(rootCommand = executable.absolutePath, rootShellRunner = backend,
                processFactory = factory)
            assertTrue(manager.setRootEnabled(true).getBoolean("rootEnabled"))
            try { block(manager) } finally { manager.cancelAll() }
        } finally { executable.delete() }
    }

    private class RecordingBackend : RootShellRunner {
        private val generation = AtomicLong()
        var executions = 0
        var beforeGenerationRead: () -> Unit = {}
        var afterExecution: () -> Unit = {}
        override fun currentCancellationGeneration(): Long {
            val callback = beforeGenerationRead
            beforeGenerationRead = {}
            callback()
            return generation.get()
        }
        override fun cancelAll() { generation.incrementAndGet() }
        override fun execute(commandText: String, timeoutMs: Long, cancellationGeneration: Long): RootShellResult {
            if (cancellationGeneration != generation.get()) return RootShellResult.Failed("ROOT_DISABLED")
            executions++
            afterExecution()
            return RootShellResult.Completed(0, "uid=0(root)\n", "", false)
        }
    }

    private class Child(@Volatile private var alive: Boolean = false) : Process() {
        @Volatile var destroyed = false
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream("uid=0(root)\n".toByteArray())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int { alive = false; return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive
        override fun exitValue(): Int { check(!alive); return 0 }
        override fun isAlive(): Boolean = alive
        override fun destroy() { alive = false; destroyed = true }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    private fun JSONObject.errorCode(): String? = optJSONObject("error")?.optString("code", null)
}
