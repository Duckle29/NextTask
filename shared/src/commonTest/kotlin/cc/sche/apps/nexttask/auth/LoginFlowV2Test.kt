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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

    private fun session(serverUrl: String = "https://cloud.example.com") =
        LoginSession("https://cloud.example.com/login/v2/flow/abc", serverUrl, "https://cloud.example.com/login/v2/poll", "tok123")

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
        val session = session()

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
        val session = session()

        val credentials = LoginFlowV2(HttpClient(engine), "NextTask").awaitCredentials(session)

        assertEquals("mikkel", credentials.loginName)
        assertEquals(4, calls)
    }

    @Test
    fun awaitCredentialsAbortsOnServerError() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }
        val session = session()
        assertFailsWith<LoginFailedException> {
            LoginFlowV2(HttpClient(engine), "NextTask").awaitCredentials(session)
        }
    }

    @Test
    fun awaitCredentialsTimesOutKeepingLastNetworkError() = runTest {
        val engine = MockEngine { throw RuntimeException("Unable to resolve host") }
        val session = session()

        val failure = assertFailsWith<LoginFailedException> {
            LoginFlowV2(HttpClient(engine), "NextTask").awaitCredentials(session, timeout = 10.seconds)
        }

        assertEquals("Unable to resolve host", failure.cause?.message)
    }

    @Test
    fun startRejectsPollEndpointDowngradedToHttp() = runTest {
        val engine = MockEngine { respond(startBody.replace("https://cloud.example.com/login/v2/poll", "http://cloud.example.com/login/v2/poll"), HttpStatusCode.OK, json) }
        assertFailsWith<LoginFailedException> {
            LoginFlowV2(HttpClient(engine), "NextTask").start("https://cloud.example.com")
        }
    }

    @Test
    fun startRejectsLoginUrlOnAnotherHost() = runTest {
        val engine = MockEngine { respond(startBody.replace("https://cloud.example.com/login/v2/flow", "https://evil.example.net/login/v2/flow"), HttpStatusCode.OK, json) }
        assertFailsWith<LoginFailedException> {
            LoginFlowV2(HttpClient(engine), "NextTask").start("https://cloud.example.com")
        }
    }

    @Test
    fun pollRejectsServerDowngradedToHttp() = runTest {
        val engine = MockEngine { respond(pollBody.replace("https://", "http://"), HttpStatusCode.OK, json) }
        assertFailsWith<LoginFailedException> {
            LoginFlowV2(HttpClient(engine), "NextTask").awaitCredentials(session())
        }
    }

    @Test
    fun pollAcceptsServerUpgradedToHttps() = runTest {
        val engine = MockEngine { respond(pollBody, HttpStatusCode.OK, json) }
        val credentials = LoginFlowV2(HttpClient(engine), "NextTask").poll(session(serverUrl = "http://cloud.example.com"))
        assertEquals("https://cloud.example.com", credentials?.serverUrl)
    }

    @Test
    fun sameServerRequiresHostAndNoDowngrade() {
        assertTrue(isSameServer("https://cloud.example.com", "https://Cloud.Example.com/nextcloud"))
        assertTrue(isSameServer("http://cloud.example.com", "https://cloud.example.com"))
        assertTrue(isSameServer("http://192.168.1.5:8080", "http://192.168.1.5:8080/login"))
        assertFalse(isSameServer("https://cloud.example.com", "http://cloud.example.com"))
        assertFalse(isSameServer("https://cloud.example.com", "https://cloud.example.com.evil.net"))
        assertFalse(isSameServer("http://192.168.1.5:8080", "http://192.168.1.5:9090"))
        assertFalse(isSameServer("https://cloud.example.com", "intent://cloud.example.com"))
    }

    @Test
    fun secretsStayOutOfToString() {
        assertFalse("secret-app-pw" in Credentials("https://cloud.example.com", "mikkel", "secret-app-pw").toString())
        assertFalse("tok123" in session().toString())
    }

    @Test
    fun revokeAppPasswordSendsOcsDelete() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals("https://cloud.example.com/nextcloud/ocs/v2.php/core/apppassword", request.url.toString())
            assertEquals("true", request.headers["OCS-APIRequest"])
            assertEquals("Basic bWlra2VsOnB3", request.headers[HttpHeaders.Authorization])
            respond("", HttpStatusCode.OK)
        }
        revokeAppPassword(HttpClient(engine), Credentials("https://cloud.example.com/nextcloud", "mikkel", "pw"))
    }

    @Test
    fun revokeAppPasswordFailsOnHttpError() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Forbidden) }
        assertFailsWith<IllegalStateException> {
            revokeAppPassword(HttpClient(engine), Credentials("https://cloud.example.com", "mikkel", "pw"))
        }
    }

    @Test
    fun startOffersHttpsWhenHttpServerRedirectsToIt() = runTest {
        val engine = MockEngine {
            respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "https://cloud.example.com/index.php/login/v2"))
        }
        val failure = assertFailsWith<RedirectsToHttpsException> {
            LoginFlowV2(HttpClient(engine), "NextTask").start("http://cloud.example.com")
        }
        assertEquals("https://cloud.example.com", failure.httpsServerUrl)
    }

    @Test
    fun httpsOfferKeepsPathAndTakesRedirectPort() = runTest {
        val engine = MockEngine {
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cloud.example.com:8443/nextcloud/index.php/login/v2"))
        }
        val failure = assertFailsWith<RedirectsToHttpsException> {
            LoginFlowV2(HttpClient(engine), "NextTask").start("http://cloud.example.com:8080/nextcloud")
        }
        assertEquals("https://cloud.example.com:8443/nextcloud", failure.httpsServerUrl)
    }

    @Test
    fun startReportsRedirectToAnotherHost() = runTest {
        val engine = MockEngine {
            respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "https://evil.example.net/index.php/login/v2"))
        }
        val failure = assertFailsWith<LoginFailedException> {
            LoginFlowV2(HttpClient(engine), "NextTask").start("http://cloud.example.com")
        }
        assertTrue("evil.example.net" in failure.message.orEmpty())
    }

    @Test
    fun startReportsRedirectWhenAlreadyOnHttps() = runTest {
        val engine = MockEngine {
            respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "/index.php/login/v2/"))
        }
        assertFailsWith<LoginFailedException> {
            LoginFlowV2(HttpClient(engine), "NextTask").start("https://cloud.example.com")
        }
    }
}
