package cc.sche.apps.nexttask.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class LoginFlowV2Test {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val startBody = """
        {"poll":{"token":"tok123","endpoint":"https://cloud.example.com/login/v2/poll"},
         "login":"https://cloud.example.com/login/v2/flow/abc"}
    """.trimIndent()
    private val pollBody = """
        {"server":"https://cloud.example.com/","loginName":"mikkel","appPassword":"secret-app-pw"}
    """.trimIndent()

    @Test
    fun startReturnsLoginUrlAndSendsUserAgent() = runTest {
        var userAgent: String? = null
        var path: String? = null
        val engine = MockEngine { request ->
            userAgent = request.headers[HttpHeaders.UserAgent]
            path = request.url.encodedPath
            assertEquals(HttpMethod.Post, request.method)
            respond(startBody, HttpStatusCode.OK, json)
        }

        val session = LoginFlowV2(HttpClient(engine), "NextTask").start("https://cloud.example.com")

        assertEquals("https://cloud.example.com/login/v2/flow/abc", session.loginUrl)
        assertEquals("NextTask", userAgent)
        assertEquals("/index.php/login/v2", path)
    }

    @Test
    fun pollReturnsNullWhilePendingThenCredentials() = runTest {
        var calls = 0
        val engine = MockEngine { request ->
            assertEquals("token=tok123", request.body.toByteArray().decodeToString())
            if (++calls < 3) respond("", HttpStatusCode.NotFound) else respond(pollBody, HttpStatusCode.OK, json)
        }
        val flow = LoginFlowV2(HttpClient(engine), "NextTask")
        val session = LoginSession("https://x/login", "https://cloud.example.com/login/v2/poll", "tok123")

        assertNull(flow.poll(session))
        val credentials = flow.awaitCredentials(session)

        assertEquals(Credentials("https://cloud.example.com", "mikkel", "secret-app-pw"), credentials)
        assertEquals(3, calls)
    }

    @Test
    fun startFailsOnHttpError() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
        assertFailsWith<LoginFailedException> {
            LoginFlowV2(HttpClient(engine), "NextTask").start("https://cloud.example.com")
        }
    }

    @Test
    fun startFailsOnGarbageResponse() = runTest {
        val engine = MockEngine { respond("<html>not nextcloud</html>", HttpStatusCode.OK) }
        assertFailsWith<LoginFailedException> {
            LoginFlowV2(HttpClient(engine), "NextTask").start("https://cloud.example.com")
        }
    }

    @Test
    fun normalizesServerUrls() {
        assertEquals("https://cloud.example.com", normalizeServerUrl(" cloud.example.com/ "))
        assertEquals("http://192.168.1.5:8080/nextcloud", normalizeServerUrl("http://192.168.1.5:8080/nextcloud/"))
        assertNull(normalizeServerUrl(""))
        assertNull(normalizeServerUrl("ftp://example.com"))
    }

    @Test
    fun awaitCredentialsSurvivesTransientNetworkErrors() = runTest {
        var calls = 0
        val engine = MockEngine {
            when (++calls) {
                1, 2 -> throw RuntimeException("Unable to resolve host")
                3 -> respond("", HttpStatusCode.NotFound)
                else -> respond(pollBody, HttpStatusCode.OK, json)
            }
        }
        val session = LoginSession("https://x/login", "https://cloud.example.com/login/v2/poll", "tok123")

        val credentials = LoginFlowV2(HttpClient(engine), "NextTask").awaitCredentials(session)

        assertEquals("mikkel", credentials.loginName)
        assertEquals(4, calls)
    }

    @Test
    fun awaitCredentialsAbortsOnServerError() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }
        val session = LoginSession("https://x/login", "https://cloud.example.com/login/v2/poll", "tok123")
        assertFailsWith<LoginFailedException> {
            LoginFlowV2(HttpClient(engine), "NextTask").awaitCredentials(session)
        }
    }

    @Test
    fun awaitCredentialsTimesOutKeepingLastNetworkError() = runTest {
        val engine = MockEngine { throw RuntimeException("Unable to resolve host") }
        val session = LoginSession("https://x/login", "https://cloud.example.com/login/v2/poll", "tok123")

        val failure = assertFailsWith<LoginFailedException> {
            LoginFlowV2(HttpClient(engine), "NextTask").awaitCredentials(session, timeout = 10.seconds)
        }

        assertEquals("Unable to resolve host", failure.cause?.message)
    }
}
