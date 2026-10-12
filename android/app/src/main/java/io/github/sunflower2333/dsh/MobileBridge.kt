package io.github.sunflower2333.dsh

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Same-UID abstract Unix socket. Only the native-launched Node receives its bearer. */
internal class MobileBridge(context: Context,
    private val hostEvents: ((HostEvent, (JSONObject) -> Unit) -> Unit)? = null,
    private val notices: ((NativeNotice, (JSONObject) -> Unit) -> Unit)? = null,
) : AutoCloseable {
    data class Credentials(val socketName: String, val token: String)
    private val app = context.applicationContext
    private val random = SecureRandom()
    private val closed = AtomicBoolean(false)
    private val clients = ConcurrentHashMap.newKeySet<LocalSocket>()
    private val workers = ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "dsh-mobile-request").apply { isDaemon = true } })
    private var server: LocalServerSocket? = null
    private var credentials: Credentials? = null

    @Synchronized fun start(): Credentials {
        check(!closed.get()) { "Mobile bridge is closed" }
        credentials?.let { return it }
        val value = Credentials("dsh-mobile-${randomHex(12)}", randomHex(32))
        val listener = LocalServerSocket(value.socketName)
        server = listener
        credentials = value
        MobileUseController.pause("host_started")
        Thread({
            while (!closed.get()) {
                val socket = try { listener.accept() } catch (_: IOException) { break }
                try {
                    if (closed.get() || socket.peerCredentials.uid != Process.myUid()) {
                        socket.close()
                        continue
                    }
                    socket.soTimeout = 5_000
                    clients.add(socket)
                    workers.execute { serve(socket, value.token) }
                } catch (_: Exception) {
                    clients.remove(socket)
                    runCatching { socket.close() }
                }
            }
        }, "dsh-mobile-accept").apply { isDaemon = true }.start()
        return value
    }

    private fun randomHex(count: Int): String {
        val bytes = ByteArray(count).also(random::nextBytes)
        val chars = "0123456789abcdef"
        return buildString(count * 2) {
            for (byte in bytes) {
                val value = byte.toInt() and 255
                append(chars[value ushr 4]); append(chars[value and 15])
            }
        }
    }

    private fun serve(socket: LocalSocket, token: String) {
        try {
            val input = BufferedInputStream(socket.inputStream)
            val request = readRequest(input, token)
            if (request.path == NativeNoticeProtocol.PATH) {
                val notice = try { NativeNoticeProtocol.parse(parseFields(request.body)) }
                    catch (_: IllegalArgumentException) { throw HttpFailure(400, "invalid_request", "Invalid native notice") }
                val sink = notices ?: throw HttpFailure(403, "forbidden", "Native notices unavailable")
                val completed = CountDownLatch(1)
                val value = AtomicReference<JSONObject>()
                sink(notice) { result -> if (value.compareAndSet(null, result)) completed.countDown() }
                if (!completed.await(2, TimeUnit.SECONDS)) throw HttpFailure(500, "timeout", "Native notice timed out")
                respond(socket, 200, MobileUseController.success(value.get()))
                return
            }
            if (request.path == HostEventProtocol.PATH) {
                val event = try { HostEventProtocol.parse(parseFields(request.body, structured = true)) }
                    catch (_: IllegalArgumentException) { throw HttpFailure(400, "invalid_request", "Invalid native host event") }
                val sink = hostEvents ?: throw HttpFailure(403, "forbidden", "Native host events unavailable")
                val completed = CountDownLatch(1)
                val accepted = AtomicReference<JSONObject>()
                sink(event) { value -> if (accepted.compareAndSet(null, value)) completed.countDown() }
                if (!completed.await(5, TimeUnit.SECONDS)) {
                    throw HttpFailure(500, "timeout", "Native host event timed out")
                }
                respond(socket, 200, JSONObject().put("ok", true)
                    .put("value", accepted.get() ?: JSONObject().put("accepted", false)))
                return
            }
            val command = MobileProtocol.parse(MobileProtocol.operation(request.path), parseFields(request.body))
            val response = AtomicReference<JSONObject>()
            val completed = CountDownLatch(1)
            MobileUseController.execute(app, command) { value ->
                if (response.compareAndSet(null, value)) completed.countDown()
            }
            if (!completed.await(20, TimeUnit.SECONDS)) {
                respond(socket, 200, MobileUseController.failure("timeout", "The native phone request timed out"))
            } else respond(socket, 200, response.get())
        } catch (error: MobileProtocolException) {
            runCatching { respond(socket, 400, MobileUseController.failure(error.code, error.message ?: "Invalid phone request")) }
        } catch (error: HttpFailure) {
            runCatching { respond(socket, error.status, MobileUseController.failure(error.code, error.safeMessage)) }
        } catch (_: Exception) {
            runCatching { respond(socket, 500, MobileUseController.failure("internal", "The native phone request failed")) }
        } finally {
            clients.remove(socket)
            runCatching { socket.close() }
        }
    }

    private data class Request(val path: String, val body: ByteArray)
    private class HttpFailure(val status: Int, val code: String, val safeMessage: String) : Exception()

    private fun readRequest(input: BufferedInputStream, token: String): Request {
        val headers = ByteArrayOutputStream()
        var matched = 0
        val terminator = byteArrayOf(13, 10, 13, 10)
        while (matched < terminator.size) {
            val value = input.read()
            if (value < 0 || headers.size() >= MAX_HEADERS) throw HttpFailure(400, "invalid_request", "Invalid HTTP headers")
            if (value > 127 || value == 0) throw HttpFailure(400, "invalid_request", "Invalid HTTP headers")
            headers.write(value)
            matched = if (value == terminator[matched].toInt()) matched + 1 else if (value == 13) 1 else 0
        }
        val lines = headers.toString(Charsets.US_ASCII.name()).removeSuffix("\r\n\r\n").split("\r\n")
        val first = lines.firstOrNull()?.split(' ') ?: emptyList()
        if (first.size != 3 || first[0] != "POST" || first[2] != "HTTP/1.1") {
            throw HttpFailure(405, "invalid_request", "Only HTTP POST is supported")
        }
        val values = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpFailure(400, "invalid_request", "Invalid HTTP headers")
            val name = line.substring(0, colon).lowercase(Locale.ROOT)
            if (!name.matches(Regex("[a-z0-9-]{1,64}")) || values.containsKey(name)) {
                throw HttpFailure(400, "invalid_request", "Duplicate or invalid HTTP headers")
            }
            values[name] = line.substring(colon + 1).trim()
        }
        if (values.containsKey("origin") || values.containsKey("sec-fetch-site") || values.containsKey("transfer-encoding")) {
            throw HttpFailure(403, "forbidden", "Browser requests are not supported by the native phone bridge")
        }
        val supplied = values["authorization"].orEmpty().toByteArray(Charsets.US_ASCII)
        val expected = "Bearer $token".toByteArray(Charsets.US_ASCII)
        if (!MessageDigest.isEqual(supplied, expected)) throw HttpFailure(401, "unauthorized", "Native phone authentication failed")
        if (values["content-type"]?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) != "application/json") {
            throw HttpFailure(400, "invalid_request", "Phone requests require JSON")
        }
        val length = values["content-length"]?.takeIf { it.matches(Regex("[0-9]{1,8}")) }?.toIntOrNull()
            ?: throw HttpFailure(400, "invalid_request", "A bounded Content-Length is required")
        if (length !in 2..MAX_BODY) throw HttpFailure(413, "invalid_request", "Phone request exceeds the body limit")
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count < 0) throw HttpFailure(400, "invalid_request", "Incomplete phone request")
            offset += count
        }
        return Request(first[1], body)
    }

    private fun parseFields(bytes: ByteArray, structured: Boolean = false): Map<String, Any?> {
        try {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                if (reader.peek() != JsonToken.BEGIN_OBJECT) throw IllegalArgumentException()
                fun value(depth: Int): Any? {
                    // sessions[] -> summary -> optional assistant activity.
                    // HostEventProtocol validates that sole nested structure;
                    // ordinary mobile/notice commands remain flat.
                    require(depth <= 3)
                    return when (reader.peek()) {
                        JsonToken.STRING -> reader.nextString()
                        JsonToken.BOOLEAN -> reader.nextBoolean()
                        JsonToken.NUMBER -> { val number = reader.nextString(); number.toLongOrNull() ?: number.toDoubleOrNull() ?: throw IllegalArgumentException() }
                        JsonToken.NULL -> { require(structured); reader.nextNull(); null }
                        JsonToken.BEGIN_ARRAY -> {
                            // Mobile capability commands use a top-level argv array;
                            // host events also use arrays. Nested arrays remain rejected.
                            require(depth == 0)
                            reader.beginArray(); val items = ArrayList<Any?>()
                            while (reader.hasNext()) { require(items.size < 64); items.add(value(depth + 1)) }
                            reader.endArray(); items
                        }
                        JsonToken.BEGIN_OBJECT -> {
                            require(structured && depth in 1..2)
                            reader.beginObject(); val fields = LinkedHashMap<String, Any?>()
                            while (reader.hasNext()) {
                                val key = reader.nextName(); require(key.length <= 64 && key !in fields && fields.size < 16)
                                fields[key] = value(depth + 1)
                            }
                            reader.endObject(); fields
                        }
                        else -> throw IllegalArgumentException()
                    }
                }
                reader.beginObject()
                val result = LinkedHashMap<String, Any?>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (name.length > 64 || result.containsKey(name) || result.size >= 16) throw IllegalArgumentException()
                    result[name] = value(0)
                }
                reader.endObject()
                if (reader.peek() != JsonToken.END_DOCUMENT) throw IllegalArgumentException()
                return result
            }
        } catch (_: Exception) {
            throw HttpFailure(400, "invalid_request", "Phone request must be one strict JSON object")
        }
    }

    private fun respond(socket: LocalSocket, status: Int, value: JSONObject) {
        var actualStatus = status
        var body = value.toString().toByteArray(Charsets.UTF_8)
        if (body.size > MAX_RESPONSE) {
            actualStatus = 500
            body = MobileUseController.failure("response_too_large", "Phone observation exceeds the response limit")
                .toString().toByteArray(Charsets.UTF_8)
        }
        val reason = when (actualStatus) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            405 -> "Method Not Allowed"; 413 -> "Payload Too Large"; else -> "Internal Server Error"
        }
        val output = socket.outputStream
        output.write(("HTTP/1.1 $actualStatus $reason\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (credentials != null) MobileUseController.pause("host_stopped")
        credentials = null
        runCatching { server?.close() }
        server = null
        for (client in clients) runCatching { client.close() }
        clients.clear()
        workers.shutdownNow()
    }

    companion object {
        private const val MAX_HEADERS = 16 * 1024
        private const val MAX_BODY = 32 * 1024
        private const val MAX_RESPONSE = 16 * 1024 * 1024
    }
}
