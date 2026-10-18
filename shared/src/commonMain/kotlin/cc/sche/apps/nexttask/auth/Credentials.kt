package cc.sche.apps.nexttask.auth

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.parseUrl
import io.ktor.http.takeFrom
import kotlin.io.encoding.Base64

/** Nextcloud account access: [serverUrl] is the base URL, [appPassword] a per-app token (never the real password). */
data class Credentials(
    val serverUrl: String,
    val loginName: String,
    val appPassword: String,
) {
    val basicAuthHeader: String
        get() = "Basic " + Base64.encode("$loginName:$appPassword".encodeToByteArray())
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

internal fun serverUrlWithPath(serverUrl: String, vararg segments: String): Url =
    URLBuilder().takeFrom(serverUrl).appendPathSegments(*segments).build()
