package io.github.sunflower2333.dsh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.ProtocolFamily
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.attribute.PosixFilePermissions
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedAdbServerTest {
    private val prefix = listOf("/system/bin/linker64", "/private/tools/adb")
    private val environment = mapOf("HOME" to "/private/adb-home", "TMPDIR" to "/private/cache")
    private fun deadline() = AndroidCapabilityManager.MonotonicClock.nowMs() + 3_000L

    @Test fun nonPrivateEndpointNeverStartsTheForegroundServer() {
        for (endpoint in listOf("tcp:19001", "localabstract:dsh-adb", "localfilesystem:relative", "localfilesystem:/tmp/a\u0000b")) {
            val starts = AtomicInteger()
            val server = ManagedAdbServer(endpointFactory = { endpoint }, readiness = { _, _ -> true })
            assertEquals(endpoint, ManagedAdbServer.Result.Failed("ADB_STORAGE_UNAVAILABLE"),
                server.ensureReady(1, prefix, environment, deadline()) { _, _ ->
                    starts.incrementAndGet()
                    ManagedAdbServer.StartResult.Started(Child())
                })
            assertEquals(0, starts.get())
        }
    }

    @Test fun filesystemPathLengthIsMeasuredInUtf8AndFailsClosedBeforeStart() {
        for (path in listOf("/" + "a".repeat(107), "/" + "é".repeat(54))) {
            val server = ManagedAdbServer(endpointFactory = { "localfilesystem:$path" }, readiness = { _, _ -> true })
            assertEquals(ManagedAdbServer.Result.Failed("ADB_STORAGE_UNAVAILABLE"),
                server.ensureReady(1, prefix, environment, deadline()) { _, _ -> error("invalid endpoint must not start") })
        }
    }

    @Test fun endpointPermissionFailureFailsClosedBeforeStart() {
        val server = ManagedAdbServer(endpointFactory = { throw java.io.IOException("chmod denied") })
        assertEquals(ManagedAdbServer.Result.Failed("ADB_STORAGE_UNAVAILABLE"),
            server.ensureReady(1, prefix, environment, deadline()) { _, _ -> error("private storage failed") })
    }

    @Test fun normalStopRemovesOnlyTheOwnedFilesystemPath() {
        val directory = Files.createTempDirectory("dsh-adb-").toFile()
        val path = File(directory, "owned")
        val other = File(directory, "other").also { it.writeText("keep") }
        val server = ManagedAdbServer(endpointFactory = { "localfilesystem:${path.absolutePath}" }, readiness = { _, _ -> true })
        try {
            assertTrue(server.ensureReady(1, prefix, environment, deadline()) { _, _ ->
                path.writeText("bound socket")
                ManagedAdbServer.StartResult.Started(Child())
            } is ManagedAdbServer.Result.Ready)
            server.invalidateBefore(2)
            assertFalse(path.exists())
            assertTrue(other.exists())
        } finally { server.invalidateBefore(3); directory.deleteRecursively() }
    }

    @Test fun aLateCancelledChildCleansItsPathWithoutRemovingTheReplacement() {
        val directory = Files.createTempDirectory("dsh-adb-").toFile()
        val oldPath = File(directory, "old")
        val newPath = File(directory, "replacement")
        val endpoints = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val child = Child()
        val server = ManagedAdbServer(endpointFactory = {
            "localfilesystem:${if (endpoints.getAndIncrement() == 0) oldPath.absolutePath else newPath.absolutePath}"
        }, readiness = { _, _ -> true })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val old = executor.submit<ManagedAdbServer.Result> {
                server.ensureReady(1, prefix, environment, deadline()) { _, _ ->
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                    oldPath.writeText("late bind")
                    ManagedAdbServer.StartResult.Started(child)
                }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            server.invalidateBefore(2)
            assertTrue(server.ensureReady(2, prefix, environment, deadline()) { _, _ ->
                newPath.writeText("new bind")
                ManagedAdbServer.StartResult.Started(Child())
            } is ManagedAdbServer.Result.Ready)
            release.countDown()
            assertEquals(ManagedAdbServer.Result.Failed("CANCELLED"), old.get(2, TimeUnit.SECONDS))
            assertTrue(child.destroyed)
            assertFalse(oldPath.exists())
            assertTrue(newPath.exists())
        } finally { release.countDown(); server.invalidateBefore(3); executor.shutdownNow(); directory.deleteRecursively() }
    }

    @Test fun foregroundServerUsesTheOwnedSocketAndIsReusedByClients() {
        val starts = AtomicInteger()
        val command = AtomicReference<List<String>>()
        val child = Child()
        val server = ManagedAdbServer(endpointFactory = { "localfilesystem:/private/adb/s-1" }, readiness = { _, _ -> true })
        val start = { argv: List<String>, env: Map<String, String> ->
            starts.incrementAndGet()
            command.set(argv)
            assertEquals("localfilesystem:/private/adb/s-1", env["ADB_SERVER_SOCKET"])
            ManagedAdbServer.StartResult.Started(child)
        }
        try {
            val first = server.ensureReady(1, prefix, environment, deadline(), start)
            val second = server.ensureReady(1, prefix, environment, deadline(), start)
            assertTrue(first is ManagedAdbServer.Result.Ready)
            assertEquals(first, second)
            assertEquals(environment + mapOf("ADB_SERVER_SOCKET" to "localfilesystem:/private/adb/s-1",
                "ADB_LOCAL_TRANSPORT_MAX_PORT" to "0", "ADB_MDNS_AUTO_CONNECT" to ""),
                (first as ManagedAdbServer.Result.Ready).environment)
            assertEquals(prefix + listOf("-L", "localfilesystem:/private/adb/s-1", "server", "nodaemon"), command.get())
            assertEquals(1, starts.get())
        } finally { server.invalidateBefore(2) }
        assertTrue(child.destroyed)
    }

    @Test fun newerGenerationStopsTheOldServerAndDoesNotStopItsReplacement() {
        val children = mutableListOf<Child>()
        val endpoints = AtomicInteger(1)
        val server = ManagedAdbServer(endpointFactory = { "localfilesystem:/private/adb/s-${endpoints.incrementAndGet()}" }, readiness = { _, _ -> true })
        val start = { _: List<String>, _: Map<String, String> ->
            Child().also { children += it }.let { ManagedAdbServer.StartResult.Started(it) }
        }
        try {
            assertTrue(server.ensureReady(1, prefix, environment, deadline(), start) is ManagedAdbServer.Result.Ready)
            assertTrue(server.ensureReady(2, prefix, environment, deadline(), start) is ManagedAdbServer.Result.Ready)
            assertEquals(ManagedAdbServer.Result.Failed("CANCELLED"),
                server.ensureReady(1, prefix, environment, deadline(), start))
            server.invalidateBefore(2)
            assertTrue(children[0].destroyed)
            assertFalse(children[1].destroyed)
        } finally { server.invalidateBefore(3) }
    }

    @Test fun invalidationDoesNotWaitForAnInProgressStartAndDisposesItsLateChild() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val child = Child()
        val server = ManagedAdbServer(endpointFactory = { "localfilesystem:/private/adb/s-1" }, readiness = { _, _ -> true })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<ManagedAdbServer.Result> {
                server.ensureReady(1, prefix, environment, deadline()) { _, _ ->
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                    ManagedAdbServer.StartResult.Started(child)
                }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            server.invalidateBefore(2) // Must return while the starter is still blocked.
            release.countDown()
            assertEquals(ManagedAdbServer.Result.Failed("CANCELLED"), result.get(2, TimeUnit.SECONDS))
            assertTrue(child.destroyed)
        } finally { release.countDown(); server.invalidateBefore(3); executor.shutdownNow() }
    }

    @Test fun startupSharesItsSingleProcessWithConcurrentCallers() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val starts = AtomicInteger()
        val server = ManagedAdbServer(endpointFactory = { "localfilesystem:/private/adb/s-1" }, readiness = { _, _ -> true })
        val executor = Executors.newFixedThreadPool(2)
        val start = { _: List<String>, _: Map<String, String> ->
            starts.incrementAndGet(); entered.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
            ManagedAdbServer.StartResult.Started(Child())
        }
        try {
            val first = executor.submit<ManagedAdbServer.Result> { server.ensureReady(1, prefix, environment, deadline(), start) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val second = executor.submit<ManagedAdbServer.Result> { server.ensureReady(1, prefix, environment, deadline(), start) }
            release.countDown()
            assertTrue(first.get(2, TimeUnit.SECONDS) is ManagedAdbServer.Result.Ready)
            assertEquals(first.get(), second.get(2, TimeUnit.SECONDS))
            assertEquals(1, starts.get())
        } finally { release.countDown(); server.invalidateBefore(2); executor.shutdownNow() }
    }

    @Test fun deniedStartPreservesTheAuthorizationError() {
        val server = ManagedAdbServer(endpointFactory = { "localfilesystem:/private/adb/s-1" }, readiness = { _, _ -> error("must not probe") })
        assertEquals(ManagedAdbServer.Result.Failed("ADB_DISABLED"),
            server.ensureReady(1, prefix, environment, deadline()) { _, _ -> ManagedAdbServer.StartResult.Denied("ADB_DISABLED") })
    }

    @Test fun startupTimeoutKillsTheOwnedChildWithinTheCallerBudget() {
        val now = AtomicLong()
        val child = Child()
        val server = ManagedAdbServer(clock = AndroidCapabilityManager.Clock(now::get), endpointFactory = { "localfilesystem:/private/adb/s-1" },
            readiness = { _, _ -> now.set(100); false })
        assertEquals(ManagedAdbServer.Result.Failed("TIMEOUT"), server.ensureReady(1, prefix, environment, 100) { _, _ ->
            ManagedAdbServer.StartResult.Started(child)
        })
        assertTrue(child.destroyed)
    }

    @Test fun expiredDeadlineNeverStartsAProcess() {
        val server = ManagedAdbServer(endpointFactory = { error("expired operation must not allocate") }, clock = AndroidCapabilityManager.Clock { 100 })
        assertEquals(ManagedAdbServer.Result.Failed("TIMEOUT"), server.ensureReady(1, prefix, environment, 100) { _, _ ->
            error("expired operation must not start")
        })
    }

    @Test fun listeningSocketDoesNotOverrideADeadOwnedProcess() {
        val child = Child().also { it.destroy() }
        val server = ManagedAdbServer(endpointFactory = { "localfilesystem:/private/adb/s-1" }, readiness = { _, _ -> true })
        assertEquals(ManagedAdbServer.Result.Failed("ADB_SERVER_START_FAILED"),
            server.ensureReady(1, prefix, environment, deadline()) { _, _ -> ManagedAdbServer.StartResult.Started(child) })
    }

    @Test fun serverOutputContinuesDrainingWithConstantMemory() {
        val eof = CountDownLatch(1)
        val stream = object : ByteArrayInputStream(ByteArray(100_000)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also {
                if (it == -1) eof.countDown()
            }
        }
        val child = Child(stream)
        val server = ManagedAdbServer(endpointFactory = { "localfilesystem:/private/adb/s-1" }, readiness = { _, _ -> true })
        try {
            assertTrue(server.ensureReady(1, prefix, environment, deadline()) { _, _ ->
                ManagedAdbServer.StartResult.Started(child)
            } is ManagedAdbServer.Result.Ready)
            assertTrue(eof.await(1, TimeUnit.SECONDS))
        } finally { server.invalidateBefore(2) }
    }

    @Test fun managedClientsCannotOverrideTheOwnedEndpointOrStartAnUnmanagedServer() {
        for (argv in listOf(listOf("-H", "remote", "devices"), listOf("-P5037", "devices"),
                listOf("-L", "tcp:5037", "server"), listOf("-a", "server", "nodaemon"),
                listOf("server", "nodaemon"), listOf("fork-server", "server"),
                listOf("nodaemon", "-L", "tcp:5037", "kill-server"),
                listOf("nodaemon", "-L", "tcp:5037", "server"))) {
            assertFalse(argv.toString(), ManagedAdbServer.allowedClientArguments(argv))
        }
        assertTrue(ManagedAdbServer.allowedClientArguments(listOf("shell", "echo", "-H")))
        assertTrue(ManagedAdbServer.allowedClientArguments(listOf("version")))
    }

    @Test fun privateEndpointUsesAnOwnerOnlyDirectoryAndUniqueShortFilesystemPaths() {
        val files = Files.createTempDirectory("dsh-adb-").toFile()
        val ownerOnly = PosixFilePermissions.fromString("rwx------")
        val secured = mutableListOf<File>()
        try {
            val create = {
                ManagedAdbServer.privateEndpoint(files) { directory ->
                    secured += directory
                    Files.setPosixFilePermissions(directory.toPath(), ownerOnly)
                }
            }
            val first = create()
            val second = create()
            assertTrue(first.startsWith("localfilesystem:${files.canonicalPath}/adb/s-"))
            assertFalse(first == second)
            assertTrue(first.removePrefix("localfilesystem:").toByteArray(Charsets.UTF_8).size <= 107)
            assertEquals(listOf(File(files, "adb"), File(files, "adb")), secured)
            assertEquals(ownerOnly, Files.getPosixFilePermissions(File(files, "adb").toPath()))
        } finally { files.deleteRecursively() }
    }

    @Test fun deniedPrivateDirectoryPermissionsDoNotReturnAnEndpoint() {
        val files = Files.createTempDirectory("dsh-adb-").toFile()
        try {
            val server = ManagedAdbServer(endpointFactory = {
                ManagedAdbServer.privateEndpoint(files) { throw java.io.IOException("chmod denied") }
            })
            assertEquals(ManagedAdbServer.Result.Failed("ADB_STORAGE_UNAVAILABLE"),
                server.ensureReady(1, prefix, environment, deadline()) { _, _ -> error("permission failure must not start") })
        } finally { files.deleteRecursively() }
    }

    @Test fun privateEndpointCannotShortenAnOverlongParentIntoAnotherNamespace() {
        val files = Files.createTempDirectory("dsh-adb-").toFile()
        val longParent = File(files, "é".repeat(54)).also { it.mkdir() }
        try {
            val server = ManagedAdbServer(endpointFactory = {
                ManagedAdbServer.privateEndpoint(longParent) { }
            })
            assertEquals(ManagedAdbServer.Result.Failed("ADB_STORAGE_UNAVAILABLE"),
                server.ensureReady(1, prefix, environment, deadline()) { _, _ -> error("long private path must not start") })
        } finally { files.deleteRecursively() }
    }

    @Test fun readinessChecksTheActualAdbProtocolOverAFilesystemSocket() {
        for ((reply, expected) in listOf("OKAY00040029" to true, "HTTP/1.1 200" to false, "" to false)) {
            withUnixListener { path, listener ->
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val request = executor.submit<String> {
                        listener.accept().use { peer ->
                            val bytes = ByteBuffer.allocate(16)
                            while (bytes.hasRemaining()) check(peer.read(bytes) > 0)
                            peer.write(ByteBuffer.wrap(reply.toByteArray()))
                            String(bytes.array(), Charsets.US_ASCII)
                        }
                    }
                    assertEquals(reply, expected, ManagedAdbServer.probe("localfilesystem:$path", deadline(), ::UnixProbeConnection))
                    assertEquals("000chost:version", request.get(1, TimeUnit.SECONDS))
                } finally { executor.shutdownNow() }
            }
        }
    }

    @Test fun readinessRejectsTcpAndAbstractSocketsWithoutConnecting() {
        for (endpoint in listOf("tcp:19001", "localabstract:dsh", "localfilesystem:relative")) {
            assertFalse(ManagedAdbServer.probe(endpoint, deadline()) { error("untrusted socket namespace") })
        }
    }

    @Test fun anUnresponsiveUnixPeerCannotExtendTheReadinessDeadline() {
        withUnixListener { path, listener ->
            val executor = Executors.newSingleThreadExecutor()
            val release = CountDownLatch(1)
            try {
                val peer = executor.submit {
                    listener.accept().use { release.await(2, TimeUnit.SECONDS) }
                }
                val before = AndroidCapabilityManager.MonotonicClock.nowMs()
                assertFalse(ManagedAdbServer.probe("localfilesystem:$path", before + 75, ::UnixProbeConnection))
                assertTrue(AndroidCapabilityManager.MonotonicClock.nowMs() - before < 500)
                release.countDown()
                peer.get(1, TimeUnit.SECONDS)
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }

    private fun withUnixListener(test: (String, ServerSocketChannel) -> Unit) {
        val directory = Files.createTempDirectory("dsh-adb-").toFile()
        val path = File(directory, "socket")
        try {
            openUnixListener().use { listener ->
                listener.bind(unixAddress(path.absolutePath))
                test(path.absolutePath, listener)
            }
        } finally { directory.deleteRecursively() }
    }

    companion object {
        // These host-JDK APIs are absent from the Android bootclasspath used to
        // compile JVM tests; reflection keeps real filesystem socket coverage.
        private fun unixAddress(path: String): SocketAddress = Class.forName("java.net.UnixDomainSocketAddress")
            .getMethod("of", String::class.java).invoke(null, path) as SocketAddress
        private fun openUnixListener(): ServerSocketChannel = ServerSocketChannel::class.java
            .getMethod("open", ProtocolFamily::class.java).invoke(null, StandardProtocolFamily.valueOf("UNIX")) as ServerSocketChannel
        private fun openUnixChannel(): SocketChannel = SocketChannel::class.java
            .getMethod("open", ProtocolFamily::class.java).invoke(null, StandardProtocolFamily.valueOf("UNIX")) as SocketChannel
    }

    private class UnixProbeConnection : ManagedAdbServer.Companion.ProbeConnection {
        private val channel = openUnixChannel().also { it.configureBlocking(false) }
        private val selector = Selector.open()
        override fun connect(path: String) { check(channel.connect(unixAddress(path))) }
        override fun write(bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) check(channel.write(buffer) > 0)
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            channel.register(selector, SelectionKey.OP_READ)
            if (selector.select(timeoutMs.toLong()) == 0) return -1
            selector.selectedKeys().clear()
            return channel.read(ByteBuffer.wrap(bytes, offset, length))
        }
        override fun close() { channel.close(); selector.close() }
    }

    private class Child(private val stdout: InputStream = ByteArrayInputStream(ByteArray(0))) : Process() {
        @Volatile var destroyed = false
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun isAlive(): Boolean = !destroyed
        override fun waitFor(): Int { destroyed = true; return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = destroyed
        override fun exitValue(): Int { if (!destroyed) throw IllegalThreadStateException(); return 0 }
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroyed = true; return this }
    }
}
