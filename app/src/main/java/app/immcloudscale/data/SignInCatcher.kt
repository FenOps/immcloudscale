package app.immcloudscale.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Catches the end of Google's sign-in on the phone itself. Google sends the browser to http://127.0.0.1:53682/?code=…,
 * which is this phone; listening there hands the address straight back to the app instead of the person copying it.
 * Only connections from the phone itself can reach it, and a code is useless without the server's secrets.
 */
class SignInCatcher(private val port: Int = 53682) {
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var stopped = false

    /**
     * Waits for the browser and returns the address it opened, or null when stopped or the port is taken. Cancelling
     * the wait (a new attempt, the dialog closing) frees the port straight away.
     */
    suspend fun await(): String? = coroutineScope {
        if (stopped) return@coroutineScope null
        val server = withContext(Dispatchers.IO) {
            runCatching { ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")) }.getOrNull()
        } ?: return@coroutineScope null
        socket = server
        // accept() blocks a thread that cancelling can't interrupt; closing the socket does.
        val closer = launch { try { awaitCancellation() } finally { runCatching { server.close() } } }
        try {
            if (stopped) null else withContext(Dispatchers.IO) { acceptUntilDone(server) }
        } finally {
            closer.cancel()
            runCatching { server.close() }
        }
    }

    private fun acceptUntilDone(server: ServerSocket): String? {
        while (!stopped) {
            val conn = try {
                server.accept()
            } catch (_: IOException) {
                return null // closed: stopped or cancelled
            }
            // One misbehaving connection (silent, reset, endless) mustn't stop the wait or the app.
            val url = try {
                conn.use { c ->
                    c.soTimeout = 5_000
                    val input = c.getInputStream()
                    val path = requestPath(firstLine(input))
                    // The rest of the request (headers, up to a blank line), so the browser gets the answer cleanly.
                    var read = 0
                    while (read < MAX_REQUEST) {
                        val line = firstLine(input)
                        if (line.isEmpty()) break
                        read += line.length + 2
                    }
                    val done = path.startsWith("/?") && ("code=" in path || "error=" in path)
                    val body = (if (done) PAGE_DONE else PAGE_WAIT).toByteArray()
                    c.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                    if (done) "http://127.0.0.1:$port$path" else null
                }
            } catch (_: IOException) {
                null
            }
            if (url != null) return url
        }
        return null
    }

    fun stop() {
        stopped = true
        runCatching { socket?.close() }
    }

    private companion object {
        const val MAX_REQUEST = 8 * 1024
        const val STYLE = "<meta name=viewport content='width=device-width'><style>body{font-family:sans-serif;margin:40px 24px;font-size:18px}</style>"
        const val PAGE_DONE = "<!doctype html>$STYLE<h2>All set</h2><p>Go back to ImmCloudScale to finish.</p>"
        const val PAGE_WAIT = "<!doctype html>$STYLE<p>Waiting for Google's sign-in…</p>"

        /** Reads up to the end of the request's first line (a browser sends more, then waits for the answer). */
        fun firstLine(input: InputStream): String {
            val b = ByteArrayOutputStream()
            while (b.size() < MAX_REQUEST) {
                val c = input.read()
                if (c == -1 || c == '\n'.code) break
                b.write(c)
            }
            return b.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
        }

        /** The path of an HTTP request line ("GET /?state=…&code=… HTTP/1.1"). */
        fun requestPath(line: String): String = line.split(' ').getOrNull(1).orEmpty()
    }
}
