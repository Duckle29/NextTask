package cc.sche.apps.nexttask.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

/**
 * Invalidates the app password of [credentials] on the server, which also removes the app from the
 * user's list of connected devices. Throws [IllegalStateException] if the server refuses.
 * See https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html
 */
suspend fun revokeAppPassword(client: HttpClient, credentials: Credentials) {
    val response = client.delete(serverUrlWithPath(credentials.serverUrl, "ocs", "v2.php", "core", "apppassword")) {
        header(HttpHeaders.Authorization, credentials.basicAuthHeader)
        header("OCS-APIRequest", "true")
    }
    check(response.status.isSuccess()) { "Revoking the app password failed: HTTP ${response.status.value}" }
}
