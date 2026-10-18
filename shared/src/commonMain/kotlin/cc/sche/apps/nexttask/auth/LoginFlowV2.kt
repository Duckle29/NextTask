package cc.sche.apps.nexttask.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.http.takeFrom
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
    /** The server address the user entered; everything the server reports back must stay on it. */
    internal val serverUrl: String,
    internal val pollEndpoint: String,
    internal val pollToken: String,
) {
    /** Keeps the poll token, which can be exchanged for an app password, out of logs and crash reports. */
    override fun toString(): String = "LoginSession(loginUrl=$loginUrl, serverUrl=$serverUrl, pollEndpoint=$pollEndpoint)"
}

class LoginFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The plain HTTP address the user entered redirects to HTTPS on the same host. Nothing was sent
 * beyond the request to start the login; ask the user, then start again at [httpsServerUrl].
 */
class RedirectsToHttpsException(val httpsServerUrl: String) :
    Exception("The server redirects to HTTPS: $httpsServerUrl")

/**
 * Nextcloud Login Flow v2: the app asks the server for a login URL, the user approves in a
 * browser, and the app polls until the server hands back an app password.
 *
 * Every URL the server reports must point at the server the user entered (see [isSameServer]). A
 * misconfigured reverse proxy (missing `overwriteprotocol`) otherwise makes Nextcloud report `http://`
 * URLs, which would send the poll token and the app password in plain text.
 *
 * See https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html#login-flow-v2
 */
class LoginFlowV2(
    private val client: HttpClient,
    /** Shown to the user in Nextcloud's list of connected devices. */
    private val userAgent: String,
) {

    /** Throws [RedirectsToHttpsException] if [serverUrl] is plain HTTP and the server redirects to HTTPS. */
    suspend fun start(serverUrl: String): LoginSession {
        val response = client.post(serverUrlWithPath(serverUrl, "index.php", "login", "v2")) {
            header(HttpHeaders.UserAgent, userAgent)
        }
        if (response.status.value in 300..399) throw redirectFailure(serverUrl, response)
        response.requireSuccess("Starting login")
        val body = decode<StartResponse>(response)
        requireSameServer(serverUrl, body.login)
        requireSameServer(serverUrl, body.poll.endpoint)
        return LoginSession(body.login, serverUrl, body.poll.endpoint, body.poll.token)
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
        val server = body.server.trimEnd('/')
        requireSameServer(session.serverUrl, server)
        return Credentials(server, body.loginName, body.appPassword)
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

    /** Redirects are never followed: an upgrade to HTTPS on the same host is offered, anything else reported. */
    private fun redirectFailure(serverUrl: String, response: HttpResponse): Exception {
        val location = response.headers[HttpHeaders.Location]
            ?: return LoginFailedException("Starting login failed: HTTP ${response.status.value}")
        val target = URLBuilder(response.request.url).takeFrom(location).build()
        val isUpgrade = Url(serverUrl).protocol == URLProtocol.HTTP && target.protocol == URLProtocol.HTTPS
        if (!isUpgrade || !isSameServer(serverUrl, target.toString())) {
            return LoginFailedException("Starting login failed: the server redirects to $target")
        }
        // Keep the path the user entered (e.g. `/nextcloud`); take the port the redirect names.
        val httpsServerUrl = URLBuilder().takeFrom(serverUrl).apply {
            protocol = URLProtocol.HTTPS
            port = target.specifiedPort
        }.buildString()
        return RedirectsToHttpsException(httpsServerUrl)
    }

    private fun requireSameServer(chosen: String, reported: String) {
        if (!isSameServer(chosen, reported)) {
            throw LoginFailedException(
                "The server reported the address $reported, which does not match $chosen. " +
                    "Check the server's overwrite.cli.url and overwriteprotocol settings.",
            )
        }
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
