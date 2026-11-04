package dev.omershevach.streamlet.engine

import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A tiny HTTP/1.1 server on 127.0.0.1 that serves the current [Stream] with Range support,
 * which is what lets the player seek. One request per connection; players open a new one per seek.
 */
internal class StreamServer {
    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "stream-http").apply { isDaemon = true } }

    val port: Int get() = socket?.localPort ?: -1

    @Synchronized
    fun start() {
        if (socket != null) return
        val s = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        socket = s
        thread(isDaemon = true, name = "stream-accept") {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (_: IOException) { break }
                pool.execute { serve(client) }
            }
        }
    }

    @Synchronized
    fun stop() {
        socket?.close()
        socket = null
    }

    private fun serve(client: Socket): Unit = client.use {
        val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = reader.readLine()?.split(' ') ?: return
        var rangeHeader: String? = null
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            if (line.startsWith("range:", ignoreCase = true)) rangeHeader = line.substringAfter(':').trim()
        }
        val method = request.getOrNull(0)
        val id = request.getOrNull(1)?.split('/')?.getOrNull(1)?.toIntOrNull()
        val out = client.getOutputStream().buffered(64 * 1024)

        val stream = TorrentEngine.streamFor(id)
        if (stream == null || (method != "GET" && method != "HEAD")) {
            out.head(if (stream == null) "404 Not Found" else "405 Method Not Allowed", "Content-Length" to "0")
            out.flush()
            return
        }

        val size = stream.size
        val range = ByteRange.parse(rangeHeader, size)
        if (range == null) {
            out.head("416 Range Not Satisfiable", "Content-Range" to "bytes */$size", "Content-Length" to "0")
            out.flush()
            return
        }

        val headers = mutableListOf(
            "Content-Type" to mimeType(stream.name),
            "Accept-Ranges" to "bytes",
            "Content-Length" to range.length.toString(),
        )
        if (range.partial) headers += "Content-Range" to "bytes ${range.start}-${range.end}/$size"
        out.head(if (range.partial) "206 Partial Content" else "200 OK", *headers.toTypedArray())
        if (method == "HEAD") {
            out.flush()
            return
        }

        var file: RandomAccessFile? = null
        try {
            val buffer = ByteArray(64 * 1024)
            var pos = range.start
            while (pos <= range.end) {
                val readable = stream.awaitReadable(pos)
                if (readable < 0) break
                val f = file ?: RandomAccessFile(stream.file, "r").also { file = it }
                val n = minOf(readable, range.end - pos + 1, buffer.size.toLong()).toInt()
                f.seek(pos)
                f.readFully(buffer, 0, n)
                out.write(buffer, 0, n)
                pos += n
            }
            out.flush()
        } catch (_: IOException) {
            // The player hung up, usually because it seeked. Nothing to do.
        } finally {
            file?.close()
        }
    }

    private fun OutputStream.head(status: String, vararg headers: Pair<String, String>) {
        val text = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        write(text.toByteArray(Charsets.ISO_8859_1))
    }

    private fun mimeType(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "mkv" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts", "m2ts" -> "video/mp2t"
        "wmv" -> "video/x-ms-wmv"
        "mpg", "mpeg" -> "video/mpeg"
        else -> "application/octet-stream"
    }
}
