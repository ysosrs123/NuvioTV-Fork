package com.nuvio.tv.data.floppy

import java.io.IOException
import kotlinx.serialization.json.JsonObject

class FloppyApiClient(
    private val http: FloppyHttpEngine,
    private val auth: FloppyAuthRepository,
    private val store: FloppyAuthStore
) {
    suspend fun scrobble(scope: FloppyAuthScope, body: JsonObject) {
        send(scope, FloppyHttpMethod.POST, "/api/v1/scrobble/", body.toString())
    }

    /** False when the server is too old to know `unwatch`; it has changed nothing. */
    suspend fun unwatch(scope: FloppyAuthScope, body: JsonObject): Boolean = try {
        scrobble(scope, body)
        true
    } catch (error: FloppyApiException) {
        if (error.status != 400 || error.detail?.startsWith(UNKNOWN_ACTION_DETAIL) != true) throw error
        false
    }

    /** Empty when Floppy isn't tracking the film. */
    suspend fun filmPlays(scope: FloppyAuthScope, tmdb: Long): List<FloppyPlay> = try {
        parseFloppyPlays(send(scope, FloppyHttpMethod.GET, filmHistoryPath(tmdb), null))
    } catch (error: FloppyApiException) {
        if (error.status != 404) throw error
        emptyList()
    }

    suspend fun deleteFilmPlay(scope: FloppyAuthScope, tmdb: Long, playId: Long) {
        try {
            send(scope, FloppyHttpMethod.DELETE, "${filmHistoryPath(tmdb)}$playId/", null)
        } catch (error: FloppyApiException) {
            if (error.status != 404) throw error
        }
    }

    private fun filmHistoryPath(tmdb: Long) = "/api/v1/media/movie/tmdb/$tmdb/history/"

    private suspend fun send(scope: FloppyAuthScope, method: FloppyHttpMethod, path: String, json: String?): String {
        var authorization = auth.authorization(scope)
        repeat(2) { attempt ->
            val credentials = authorization.credentials
            val response = execute(FloppyHttpRequest(credentials.serverUrl, method, path, credentials.accessToken, json = json))
            store.checkScope(scope)
            if (response.status in 200..299) return response.body
            if (response.status != 401 && response.status != 403) {
                throw FloppyApiException(response.status, detail = floppyErrorDetail(response.body))
            }
            if (attempt == 1 || !credentialIsDead(credentials)) throw FloppyApiException(response.status, "rejected")
            authorization = auth.authorization(scope, rejectedAccessToken = credentials.accessToken)
        }
        throw FloppyApiException(0, "rejected")
    }

    /**
     * Floppy answers 403 both for a dead credential and for a missing permission; a low-permission call tells them
     * apart. A proxy in front of the server can refuse that too, so a 403 counts only with Floppy's own JSON
     * refusal. Older servers answer a dead credential with an empty 401; a proxy's own sign-in page does not count.
     */
    private suspend fun credentialIsDead(credentials: FloppyCredentials): Boolean {
        val probe = execute(
            FloppyHttpRequest(
                credentials.serverUrl,
                FloppyHttpMethod.GET,
                FloppyAuthRepository.CONNECTION_CHECK_PATH,
                credentials.accessToken
            )
        )
        if (probe.status != 401 && probe.status != 403) return false
        return floppyErrorDetail(probe.body) != null || (probe.status == 401 && probe.body.isBlank())
    }

    private suspend fun execute(request: FloppyHttpRequest): FloppyHttpResponse = try {
        http.execute(request)
    } catch (_: IOException) {
        throw FloppyApiException(0, "transport_failure")
    }

    private companion object {
        const val UNKNOWN_ACTION_DETAIL = "'action' must be one of"
    }
}
