package app.immcloudscale.data

import app.immcloudscale.data.pairing.MembersClient
import app.immcloudscale.ui.account.GoogleSteps
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleClientTest {
    private val server = MockWebServer().apply { start() }
    private val settings get() = AppSettings(pairedServer = server.url("/").toString().trimEnd('/'), pairedDeviceId = "dev_a", deviceToken = "tok")
    private val client = MembersClient(OkHttpClient()) { settings }

    @After fun stop() = server.shutdown()

    @Test fun readsTheStatus() = runTest {
        server.enqueue(MockResponse().setBody("""{"keySet":true,"toolsReady":false,"toolsBuilding":true,"accounts":[{"id":"g1","email":"a@gmail.com","person":"camille",
            "folder":"Google Drive (a@gmail.com)","synced":"2026-11-26T08:00:00Z","files":12,"bytes":345,"syncing":false}],
            "imports":[{"person":"camille","export":"takeout-x","source":"a@gmail.com","at":"2026-11-26T09:00:00Z","imported":7,"duplicates":2,"errors":0}],
            "importing":false,"takeoutFolder":"Google Takeout"}"""))
        val st = client.google()
        assertTrue(st.keySet && st.toolsBuilding)
        assertEquals("a@gmail.com", st.accounts.single().email)
        assertEquals(7, st.imports.single().imported)
        val req = server.takeRequest()
        assertEquals("/api/v1/me/google", req.path)
        assertEquals("Device dev_a:tok", req.getHeader("Authorization"))
    }

    @Test fun connectsAndFinishes() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"https://accounts.google.com/o/oauth2/v2/auth?x=1"}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"g1","email":"a@gmail.com","person":"nina","folder":"Google Drive (a@gmail.com)"}"""))
        assertTrue(client.connectGoogle("nina").startsWith("https://accounts.google.com/"))
        val first = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("nina", first["for"]!!.jsonPrimitive.content)
        assertEquals("a@gmail.com", client.finishGoogle("  http://127.0.0.1:53682/?state=s&code=c  ").email)
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("http://127.0.0.1:53682/?state=s&code=c", second["url"]!!.jsonPrimitive.content)
    }

    @Test fun showsTheServersExplanationWhenSomethingIsWrong() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"that sign-in has expired or belongs to someone else; start again"}"""))
        val e = runCatching { client.finishGoogle("http://127.0.0.1:53682/?state=s&code=c") }.exceptionOrNull()
        assertTrue(e?.message!!.contains("expired"))
    }

    @Test fun remindersRunOnlyUntilGooglePhotosBackupIsOff() {
        assertFalse(GoogleReminderWorker.due(AppSettings()))
        assertTrue(GoogleReminderWorker.due(AppSettings(googleDone = setOf(GoogleSteps.STARTED))))
        assertFalse(GoogleReminderWorker.due(AppSettings(googleDone = setOf(GoogleSteps.STARTED, GoogleSteps.PHOTOS_BACKUP))))
        assertFalse(GoogleReminderWorker.due(AppSettings(googleDone = setOf(GoogleSteps.STARTED, GoogleSteps.NO_REMINDERS))))
    }
}
