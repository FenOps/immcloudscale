package app.immcloudscale.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException

/**
 * Catches the end of Google's sign-in on the phone itself. Google sends the browser to http://127.0.0.1:53682/?code=…,
 * which is this phone; listening there hands the address straight back to the app instead of the person copying it.
 * Only connections from the phone itself can reach it.
 */
class SignInCatcher(private val port: Int = 53682) {
    @Volatile private var socket: ServerSocket? = null

    /** Waits for the browser and returns the address it opened, or null when stopped or the port is taken. */
    suspend fun await(): String? = withContext(Dispatchers.IO) {
        val server = runCatching { ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")) }.getOrNull() ?: return@withContext null
        socket = server
        try {
            while (true) {
                val conn = server.accept()
                conn.use { c ->
                    c.soTimeout = 5_000
                    val line = c.getInputStream().bufferedReader().readLine().orEmpty() // GET /?state=…&code=… HTTP/1.1
                    val path = line.split(' ').getOrNull(1).orEmpty()
                    val done = path.startsWith("/?") && ("code=" in path || "error=" in path)
                    val html = if (done) PAGE_DONE else PAGE_WAIT
                    val body = html.toByteArray()
                    c.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                    if (done) return@withContext "http://127.0.0.1:$port$path"
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        } catch (_: SocketException) {
            null // stopped
        } finally {
            runCatching { server.close() }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
    }

    private companion object {
        const val STYLE = "<meta name=viewport content='width=device-width'><style>body{font-family:sans-serif;margin:40px 24px;font-size:18px}</style>"
        const val PAGE_DONE = "<!doctype html>$STYLE<h2>All set</h2><p>Go back to ImmCloudScale to finish.</p>"
        const val PAGE_WAIT = "<!doctype html>$STYLE<p>Waiting for Google's sign-in…</p>"
    }
}
