package cc.sche.apps.nexttask.auth

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.parseUrl
import io.ktor.http.takeFrom
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * Nextcloud account access: [serverUrl] is the base URL, [appPassword] a per-app token (never the real password).
 *
 * The scheme of [serverUrl] is the one the user chose, so all traffic for this account uses it: an
 * account set up over HTTPS never falls back to plain HTTP.
 */
@Serializable
data class Credentials(
    val serverUrl: String,
    val loginName: String,
    val appPassword: String,
) {
    val basicAuthHeader: String
        get() = "Basic " + Base64.encode("$loginName:$appPassword".encodeToByteArray())

    /** Keeps the app password out of logs and crash reports. */
    override fun toString(): String = "Credentials(serverUrl=$serverUrl, loginName=$loginName, appPassword=***)"
}

/** Normalises user input like `cloud.example.com/` to `https://cloud.example.com`. Returns null if unusable. */
fun normalizeServerUrl(input: String): String? {
    val trimmed = input.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val url = parseUrl(withScheme) ?: return null
    if (url.protocol != URLProtocol.HTTPS && url.protocol != URLProtocol.HTTP) return null
    if (url.host.isEmpty()) return null
    return withScheme
}

/**
 * True if [reported], a URL the server handed back, points at the server the user chose in [chosen]:
 * the same host, and never plain HTTP where the user chose HTTPS. An upgrade from HTTP to HTTPS is allowed.
 */
internal fun isSameServer(chosen: String, reported: String): Boolean {
    val expected = parseUrl(chosen) ?: return false
    val actual = parseUrl(reported) ?: return false
    if (!actual.host.equals(expected.host, ignoreCase = true)) return false
    return when (actual.protocol) {
        expected.protocol -> actual.port == expected.port
        URLProtocol.HTTPS -> expected.protocol == URLProtocol.HTTP
        else -> false
    }
}

internal fun serverUrlWithPath(serverUrl: String, vararg segments: String): Url =
    URLBuilder().takeFrom(serverUrl).appendPathSegments(*segments).build()
