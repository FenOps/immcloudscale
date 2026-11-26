package app.immcloudscale.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
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

    /** Waits for the browser and returns the address it opened, or null when stopped or the port is taken. */
    suspend fun await(): String? = withContext(Dispatchers.IO) {
        stopped = false
        val server = runCatching { ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")) }.getOrNull() ?: return@withContext null
        socket = server
        if (stopped) { // stopped while it was opening
            runCatching { server.close() }
            return@withContext null
        }
        try {
            while (!stopped) {
                val conn = try {
                    server.accept()
                } catch (_: IOException) {
                    return@withContext null // closed by stop()
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
                if (url != null) return@withContext url
            }
            null
        } finally {
            runCatching { server.close() }
        }
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
        fun firstLine(input: java.io.InputStream): String {
            val b = java.io.ByteArrayOutputStream()
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
