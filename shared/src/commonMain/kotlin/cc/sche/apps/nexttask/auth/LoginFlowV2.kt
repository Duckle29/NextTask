package cc.sche.apps.nexttask.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the app needs to send the user to the browser and then collect the result. */
data class LoginSession(
    /** Open this URL in the user's browser. */
    val loginUrl: String,
    internal val pollEndpoint: String,
    internal val pollToken: String,
)

class LoginFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Nextcloud Login Flow v2: the app asks the server for a login URL, the user approves in a
 * browser, and the app polls until the server hands back an app password.
 * See https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html#login-flow-v2
 */
class LoginFlowV2(
    private val client: HttpClient,
    /** Shown to the user in Nextcloud's list of connected devices. */
    private val userAgent: String,
) {

    suspend fun start(serverUrl: String): LoginSession {
        val response = client.post(serverUrlWithPath(serverUrl, "index.php", "login", "v2")) {
            header(HttpHeaders.UserAgent, userAgent)
        }
        response.requireSuccess("Starting login")
        val body = decode<StartResponse>(response)
        return LoginSession(body.login, body.poll.endpoint, body.poll.token)
    }

    /** Checks once whether the user has approved the login. Returns null while still pending. */
    suspend fun poll(session: LoginSession): Credentials? {
        val response = client.submitForm(
            url = session.pollEndpoint,
            formParameters = parameters { append("token", session.pollToken) },
        ) {
            header(HttpHeaders.UserAgent, userAgent)
        }
        if (response.status == HttpStatusCode.NotFound) return null
        response.requireSuccess("Polling login")
        val body = decode<PollResponse>(response)
        return Credentials(body.server.trimEnd('/'), body.loginName, body.appPassword)
    }

    /**
     * Polls until approved. The server invalidates the session after 20 minutes.
     *
     * Network errors (the app is usually in the background while the user logs in, which makes
     * lookups and connections fail intermittently) are retried; server answers that are not
     * "still pending" abort the login.
     */
    suspend fun awaitCredentials(
        session: LoginSession,
        interval: Duration = 2.seconds,
        timeout: Duration = 20.minutes,
    ): Credentials {
        var lastError: Exception? = null
        repeat(maxOf(1, (timeout / interval).toInt())) {
            try {
                poll(session)?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LoginFailedException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
            delay(interval)
        }
        throw LoginFailedException("Login was not approved in time", lastError)
    }

    private suspend fun HttpResponse.requireSuccess(action: String) {
        if (!status.isSuccess()) throw LoginFailedException("$action failed: HTTP ${status.value}")
    }

    private suspend inline fun <reified T> decode(response: HttpResponse): T = try {
        json.decodeFromString<T>(response.bodyAsText())
    } catch (e: kotlinx.serialization.SerializationException) {
        throw LoginFailedException("Unexpected response from server", e)
    }

    @Serializable
    private class StartResponse(val poll: Poll, val login: String) {
        @Serializable
        class Poll(val token: String, val endpoint: String)
    }

    @Serializable
    private class PollResponse(val server: String, val loginName: String, val appPassword: String)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
