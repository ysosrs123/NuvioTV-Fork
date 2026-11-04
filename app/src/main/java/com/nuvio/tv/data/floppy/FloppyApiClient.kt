package com.nuvio.tv.data.floppy

import java.io.IOException
import kotlinx.serialization.json.JsonObject

class FloppyApiClient(
    private val http: FloppyHttpEngine,
    private val auth: FloppyAuthRepository,
    private val store: FloppyAuthStore
) {
    suspend fun scrobble(scope: FloppyAuthScope, body: JsonObject) {
        post(scope, "/api/v1/scrobble/", body.toString())
    }

    private suspend fun post(scope: FloppyAuthScope, path: String, json: String) {
        var authorization = auth.authorization(scope)
        repeat(2) { attempt ->
            val credentials = authorization.credentials
            val response = execute(FloppyHttpRequest(credentials.serverUrl, FloppyHttpMethod.POST, path, credentials.accessToken, json = json))
            store.checkScope(scope)
            if (response.status in 200..299) return
            if (response.status != 401 && response.status != 403) throw FloppyApiException(response.status)
            if (attempt == 1 || !credentialIsDead(credentials)) throw FloppyApiException(response.status, "rejected")
            authorization = auth.authorization(scope, rejectedAccessToken = credentials.accessToken)
        }
    }

    /** Floppy answers 403 both for a dead credential and for a missing permission; a low-permission call tells them apart. */
    private suspend fun credentialIsDead(credentials: FloppyCredentials): Boolean {
        val probe = execute(
            FloppyHttpRequest(
                credentials.serverUrl,
                FloppyHttpMethod.GET,
                FloppyAuthRepository.CONNECTION_CHECK_PATH,
                credentials.accessToken
            )
        )
        return probe.status == 401 || probe.status == 403
    }

    private suspend fun execute(request: FloppyHttpRequest): FloppyHttpResponse = try {
        http.execute(request)
    } catch (_: IOException) {
        throw FloppyApiException(0, "transport_failure")
    }
}
