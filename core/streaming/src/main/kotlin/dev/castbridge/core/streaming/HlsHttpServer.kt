package dev.castbridge.core.streaming

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small HTTP server for one live HLS session.
 *
 * It binds to the phone's Wi-Fi address only, serves only the tokenised paths, and supports HEAD and
 * byte ranges because some TV players probe segments before playing them.
 */
class HlsHttpServer(
    private val store: HlsMediaStore,
    private val token: String,
    private val log: (String) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private val basePath = "/s/$token/"

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var executor: ExecutorService? = null

    /** Binds the server and returns the actual port. Port 0 asks the OS for a free port. */
    fun start(bindAddress: InetAddress, port: Int = 0): Int {
        check(!running.get()) { "The HLS server is already running." }

        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(bindAddress, port), BACKLOG)
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }

        val pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "HlsHttp").apply { isDaemon = true }
        }

        serverSocket = socket
        executor = pool
        running.set(true)
        acceptThread = Thread({ acceptLoop(socket, pool) }, "HlsAccept").apply {
            isDaemon = true
            start()
        }

        log("HLS server listening on ${bindAddress.hostAddress}:${socket.localPort}")
        return socket.localPort
    }

    fun stop() {
        if (!running.getAndSet(false)) return

        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        executor?.shutdownNow()
        executor = null
        log("HLS server stopped")
    }

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (error: SocketException) {
                if (running.get()) log("HTTP accept failed: ${error.message}")
                break
            } catch (error: IOException) {
                if (running.get()) log("HTTP accept failed: ${error.message}")
                break
            }

            try {
                pool.execute { handle(client) }
            } catch (_: RejectedExecutionException) {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use {
                it.soTimeout = SOCKET_TIMEOUT_MS
                val input = BufferedInputStream(it.getInputStream())
                val output = BufferedOutputStream(it.getOutputStream())

                val request = readRequest(input)
                if (request == null) {
                    writeResponse(400, emptyMap(), EMPTY, output, head = false)
                } else {
                    log("HTTP ${request.method} ${request.path} from ${it.inetAddress?.hostAddress}")
                    route(request, output)
                }
                output.flush()
            }
        } catch (_: IOException) {
            // A TV or browser closed the connection early; this is normal.
        } catch (error: Exception) {
            log("HTTP request failed: ${error.message}")
        }
    }

    private fun route(request: HttpRequest, output: OutputStream) {
        val head = request.method == "HEAD"

        if (request.method == "OPTIONS") {
            writeResponse(204, emptyMap(), EMPTY, output, head = false)
            return
        }
        if (request.method != "GET" && !head) {
            writeResponse(405, mapOf("Allow" to "GET, HEAD, OPTIONS"), EMPTY, output, head = false)
            return
        }
        if (!request.path.startsWith(basePath)) {
            writeResponse(404, emptyMap(), EMPTY, output, head)
            return
        }

        val resource = request.path.removePrefix(basePath)
        when {
            resource == HlsMediaStore.PLAYLIST_NAME -> {
                val body = store.playlist().toByteArray(Charsets.UTF_8)
                writeResponse(
                    status = 200,
                    headers = mapOf(
                        "Content-Type" to PLAYLIST_CONTENT_TYPE,
                        "Cache-Control" to "no-cache, no-store, must-revalidate",
                    ),
                    body = body,
                    output = output,
                    head = head,
                )
            }

            resource.startsWith("segment") && resource.endsWith(".ts") -> {
                val segment = store.segment(resource)
                if (segment == null) {
                    writeResponse(404, emptyMap(), EMPTY, output, head)
                } else {
                    serveBytes(segment.data, SEGMENT_CONTENT_TYPE, request, output, head)
                }
            }

            else -> writeResponse(404, emptyMap(), EMPTY, output, head)
        }
    }

    private fun serveBytes(
        data: ByteArray,
        contentType: String,
        request: HttpRequest,
        output: OutputStream,
        head: Boolean,
    ) {
        val headers = mutableMapOf(
            "Content-Type" to contentType,
            "Cache-Control" to "public, max-age=60",
            "Accept-Ranges" to "bytes",
        )

        when (val range = parseRange(request.headers["range"], data.size)) {
            null -> writeResponse(200, headers, data, output, head)

            ByteRange.Unsatisfiable -> {
                headers["Content-Range"] = "bytes */${data.size}"
                writeResponse(416, headers, EMPTY, output, head = false)
            }

            is ByteRange.Satisfiable -> {
                val slice = data.copyOfRange(range.start, range.endExclusive)
                headers["Content-Range"] =
                    "bytes ${range.start}-${range.endExclusive - 1}/${data.size}"
                writeResponse(206, headers, slice, output, head)
            }
        }
    }

    private fun readRequest(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null

        val method = parts[0].uppercase(Locale.ROOT)
        val path = parts[1].substringBefore('?')
        val headers = mutableMapOf<String, String>()

        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] =
                    line.substring(colon + 1).trim()
            }
        }

        return HttpRequest(method, path, headers)
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream(64)
        while (true) {
            val byte = input.read()
            if (byte == -1) {
                return if (line.size() == 0) null else line.toString(Charsets.US_ASCII.name())
            }
            if (byte == '\n'.code) {
                val bytes = line.toByteArray()
                val length = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                    bytes.size - 1
                } else {
                    bytes.size
                }
                return String(bytes, 0, length, Charsets.US_ASCII)
            }
            line.write(byte)
            if (line.size() > MAX_HEADER_LINE_BYTES) return null
        }
    }

    private fun parseRange(header: String?, size: Int): ByteRange? {
        if (header == null || !header.startsWith("bytes=", ignoreCase = true)) return null
        if (size <= 0) return ByteRange.Unsatisfiable

        val spec = header.substringAfter('=').trim()
        if (spec.contains(',')) return null // Multiple ranges are not needed for HLS.
        val dash = spec.indexOf('-')
        if (dash < 0) return null

        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()

        return if (startText.isEmpty()) {
            val suffixLength = endText.toIntOrNull() ?: return null
            if (suffixLength <= 0) {
                ByteRange.Unsatisfiable
            } else {
                ByteRange.Satisfiable(maxOf(0, size - suffixLength), size)
            }
        } else {
            val start = startText.toIntOrNull() ?: return null
            if (start >= size) {
                ByteRange.Unsatisfiable
            } else {
                val end = endText.toIntOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
                if (end < start) {
                    ByteRange.Unsatisfiable
                } else {
                    ByteRange.Satisfiable(start, end + 1)
                }
            }
        }
    }

    private fun writeResponse(
        status: Int,
        headers: Map<String, String>,
        body: ByteArray,
        output: OutputStream,
        head: Boolean,
    ) {
        val builder = StringBuilder(256)
        builder.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        log("HTTP response $status (${body.size} bytes)")
        headers.forEach { (name, value) ->
            builder.append(name).append(": ").append(value).append("\r\n")
        }
        if (headers.keys.none { it.equals("Content-Length", ignoreCase = true) }) {
            builder.append("Content-Length: ").append(body.size).append("\r\n")
        }
        builder.append("Access-Control-Allow-Origin: *\r\n")
        builder.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        builder.append("Access-Control-Allow-Headers: Range\r\n")
        builder.append("Access-Control-Expose-Headers: Content-Length, Content-Range\r\n")
        builder.append("Connection: close\r\n\r\n")

        output.write(builder.toString().toByteArray(Charsets.US_ASCII))
        if (!head && body.isNotEmpty()) output.write(body)
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        206 -> "Partial Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        else -> "Error"
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
    )

    private sealed interface ByteRange {
        data class Satisfiable(val start: Int, val endExclusive: Int) : ByteRange

        data object Unsatisfiable : ByteRange
    }

    private companion object {
        const val BACKLOG = 16
        const val SOCKET_TIMEOUT_MS = 10_000
        const val MAX_HEADER_LINE_BYTES = 8 * 1024
        const val PLAYLIST_CONTENT_TYPE = "application/vnd.apple.mpegurl"
        const val SEGMENT_CONTENT_TYPE = "video/mp2t"
        val EMPTY = ByteArray(0)
    }
}
