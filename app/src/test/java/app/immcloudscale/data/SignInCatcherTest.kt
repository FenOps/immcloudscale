package app.immcloudscale.data

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class SignInCatcherTest {
    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        return c.inputStream.bufferedReader().readText().also { c.disconnect() }
    }

    @Test fun handsBackTheAddressGoogleEndsOn() = runBlocking {
        val catcher = SignInCatcher(port = 53781)
        val caught = async { catcher.await() }
        delay(300)
        assertTrue(get("http://127.0.0.1:53781/favicon.ico").contains("Waiting")) // not the sign-in: keeps waiting
        val page = get("http://127.0.0.1:53781/?state=s1&code=4/0Ab&scope=email")
        assertTrue(page.contains("All set"))
        assertEquals("http://127.0.0.1:53781/?state=s1&code=4/0Ab&scope=email", caught.await())
    }

    @Test fun stoppingEndsTheWait() = runBlocking {
        val catcher = SignInCatcher(port = 53782)
        val caught = async { catcher.await() }
        delay(300)
        catcher.stop()
        assertNull(caught.await())
    }
}
