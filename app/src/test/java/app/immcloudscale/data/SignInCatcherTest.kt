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

    @Test fun aSilentOrBrokenConnectionDoesntStopTheWait() = runBlocking {
        val catcher = SignInCatcher(port = 53783)
        val caught = async { catcher.await() }
        delay(300)
        java.net.Socket("127.0.0.1", 53783).use { delay(5_500) } // says nothing until the catcher gives up on it
        java.net.Socket("127.0.0.1", 53783).apply { setSoLinger(true, 0) }.close() // reset straight away
        delay(200)
        assertTrue(get("http://127.0.0.1:53783/?state=s&code=c").contains("All set"))
        assertEquals("http://127.0.0.1:53783/?state=s&code=c", caught.await())
    }

    @Test fun stoppingBeforeItStartsLeavesThePortFree() = runBlocking {
        val catcher = SignInCatcher(port = 53784)
        catcher.stop()
        val again = SignInCatcher(port = 53784)
        val caught = async { again.await() }
        delay(300)
        get("http://127.0.0.1:53784/?state=s&code=c")
        assertEquals("http://127.0.0.1:53784/?state=s&code=c", caught.await())
    }
}
