package com.nuvio.tv.data.floppy

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class FloppyAuthRepository(
    private val http: FloppyHttpEngine,
    private val store: FloppyAuthStore,
    private val clientName: String,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    private val deviceMutex = Mutex()
    private val refreshMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    val state = store.state

    suspend fun startDeviceAuthorization(
        address: String,
        scope: FloppyAuthScope = store.scope()
    ): FloppyDeviceSession = deviceMutex.withLock {
        store.checkScope(scope)
        val serverUrl = normalizeFloppyServerUrl(address) ?: fail(FloppyAuthError.INVALID_ADDRESS)
        var clientId = register(serverUrl)
        var response = deviceAuthorization(serverUrl, clientId)
        if (response.status == 400 && response.errorCode() == "invalid_client") {
            clientId = register(serverUrl)
            response = deviceAuthorization(serverUrl, clientId)
        }
        if (response.status == 404) fail(FloppyAuthError.NO_CODE_SIGN_IN)
        if (response.status !in 200..299) throw FloppyApiException(response.status, response.errorCode())
        val payload = decode<FloppyDeviceResponse>(response.body)
        val deviceCode = payload.deviceCode?.takeIf(String::isNotBlank) ?: invalidResponse()
        val userCode = payload.userCode?.takeIf(String::isNotBlank) ?: invalidResponse()
        val uri = verificationUrl(payload.verificationUri) ?: floppyUrl(serverUrl, "/oauth/device").toString()
        val complete = verificationUrl(payload.verificationUriComplete)
            ?: uri.toHttpUrlOrNull()?.newBuilder()?.addQueryParameter("user_code", userCode)?.build()?.toString()
            ?: invalidResponse()
        val interval = payload.interval.takeIf { it in 1..3_600 } ?: invalidResponse()
        val now = nowEpochMs()
        val session = FloppyDeviceSession(
            serverUrl = serverUrl,
            clientId = clientId,
            userCode = userCode,
            verificationUri = uri,
            verificationUriComplete = complete,
            expiresAtEpochMs = expiresAt(payload.expiresIn, now),
            intervalSeconds = interval,
            nextPollAtEpochMs = now + interval * 1_000L
        )
        if (!store.saveSession(session, deviceCode, scope)) scopeChanged()
        session
    }

    suspend fun pollDeviceAuthorization(scope: FloppyAuthScope = store.scope()): FloppyDevicePollResult =
        deviceMutex.withLock {
            store.checkScope(scope)
            val session = state.value.session ?: scopeChanged()
            val now = nowEpochMs()
            if (now >= session.expiresAtEpochMs) {
                store.cancelSession(FloppyAuthError.CODE_EXPIRED, scope)
                return@withLock FloppyDevicePollResult.Expired
            }
            if (now < session.nextPollAtEpochMs) return@withLock FloppyDevicePollResult.Pending
            val deviceCode = store.deviceCode(scope) ?: scopeChanged()
            if (!store.updateSession(session.copy(nextPollAtEpochMs = now + session.intervalSeconds * 1_000L), scope)) {
                scopeChanged()
            }
            val response = send(
                FloppyHttpRequest(
                    serverUrl = session.serverUrl,
                    method = FloppyHttpMethod.POST,
                    path = "/oauth/token",
                    form = mapOf(
                        "client_id" to session.clientId,
                        "grant_type" to DEVICE_CODE_GRANT,
                        "device_code" to deviceCode
                    )
                )
            )
            store.checkScope(scope)
            if (response.status in 200..299) {
                val credentials = credentialsFrom(response, session.serverUrl, session.clientId)
                if (!store.authorize(credentials, scope)) scopeChanged()
                return@withLock FloppyDevicePollResult.Authorized
            }
            when (response.errorCode()) {
                "authorization_pending" -> FloppyDevicePollResult.Pending
                "slow_down" -> {
                    val interval = (session.intervalSeconds + 5).coerceAtMost(3_600)
                    store.updateSession(
                        session.copy(intervalSeconds = interval, nextPollAtEpochMs = nowEpochMs() + interval * 1_000L),
                        scope
                    )
                    FloppyDevicePollResult.Pending
                }
                "access_denied" -> {
                    store.cancelSession(FloppyAuthError.ACCESS_DENIED, scope)
                    FloppyDevicePollResult.Denied
                }
                "expired_token", "invalid_grant", "invalid_client" -> {
                    store.cancelSession(FloppyAuthError.CODE_EXPIRED, scope)
                    FloppyDevicePollResult.Expired
                }
                else -> throw FloppyApiException(response.status, response.errorCode())
            }
        }

    fun cancelDeviceAuthorization() {
        store.cancelSession()
    }

    suspend fun connectWithToken(
        address: String,
        token: String,
        scope: FloppyAuthScope = store.scope()
    ) = deviceMutex.withLock {
        store.checkScope(scope)
        val serverUrl = normalizeFloppyServerUrl(address) ?: fail(FloppyAuthError.INVALID_ADDRESS)
        val accessToken = token.trim().takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
            ?: fail(FloppyAuthError.TOKEN_REJECTED)
        val response = send(
            FloppyHttpRequest(serverUrl, FloppyHttpMethod.GET, CONNECTION_CHECK_PATH, accessToken = accessToken)
        )
        when {
            response.status == 401 || response.status == 403 -> fail(FloppyAuthError.TOKEN_REJECTED)
            response.status !in 200..299 || !response.isJsonCollection() -> fail(FloppyAuthError.NOT_FLOPPY)
        }
        if (!store.authorize(FloppyCredentials(serverUrl, accessToken), scope)) scopeChanged()
    }

    suspend fun authorization(
        scope: FloppyAuthScope = store.scope(),
        rejectedAccessToken: String? = null
    ): FloppyAuthorization = refreshMutex.withLock {
        store.checkScope(scope)
        val current = store.authorization() ?: fail(FloppyAuthError.AUTHORIZATION_REVOKED)
        val credentials = current.credentials
        if (rejectedAccessToken != null && credentials.accessToken != rejectedAccessToken) return@withLock current
        if (!credentials.refreshes) {
            if (rejectedAccessToken != null) {
                store.clearAuth(scope, FloppyAuthError.AUTHORIZATION_REVOKED, credentials.accessToken)
                fail(FloppyAuthError.AUTHORIZATION_REVOKED)
            }
            return@withLock current
        }
        val expiresAt = credentials.expiresAtEpochMs ?: 0L
        if (rejectedAccessToken == null && expiresAt - nowEpochMs() > REFRESH_MARGIN_MS) return@withLock current
        val response = send(
            FloppyHttpRequest(
                serverUrl = credentials.serverUrl,
                method = FloppyHttpMethod.POST,
                path = "/oauth/token",
                form = mapOf(
                    "client_id" to requireNotNull(credentials.clientId),
                    "grant_type" to "refresh_token",
                    "refresh_token" to requireNotNull(credentials.refreshToken)
                )
            )
        )
        if (response.status !in 200..299) {
            if (response.errorCode() in setOf("invalid_grant", "invalid_client", "invalid_token")) {
                store.clearAuth(scope, FloppyAuthError.AUTHORIZATION_REVOKED, credentials.accessToken)
                fail(FloppyAuthError.AUTHORIZATION_REVOKED)
            }
            throw FloppyApiException(response.status, response.errorCode())
        }
        val refreshed = credentialsFrom(response, credentials.serverUrl, credentials.clientId, credentials.refreshToken)
        if (!store.refreshCredentials(refreshed, current)) scopeChanged()
        FloppyAuthorization(scope, refreshed)
    }

    suspend fun disconnect(scope: FloppyAuthScope = store.scope()): Boolean {
        store.checkScope(scope)
        val current = store.authorization()
        if (!store.clearAuth(scope)) scopeChanged()
        val credentials = current?.credentials ?: return true
        if (!credentials.refreshes) return true
        return try {
            send(
                FloppyHttpRequest(
                    serverUrl = credentials.serverUrl,
                    method = FloppyHttpMethod.POST,
                    path = "/oauth/revoke",
                    form = mapOf(
                        "client_id" to requireNotNull(credentials.clientId),
                        "token" to requireNotNull(credentials.refreshToken)
                    )
                )
            ).status in 200..299
        } catch (error: CancellationException) {
            throw error
        } catch (_: FloppyAuthException) {
            false
        }
    }

    private suspend fun register(serverUrl: String): String {
        val response = send(
            FloppyHttpRequest(
                serverUrl = serverUrl,
                method = FloppyHttpMethod.POST,
                path = "/oauth/register",
                form = mapOf("client_name" to clientName.take(60))
            )
        )
        if (response.status == 404) {
            val probe = send(FloppyHttpRequest(serverUrl, FloppyHttpMethod.GET, CONNECTION_CHECK_PATH))
            if (probe.status == 401 || probe.status == 403) fail(FloppyAuthError.NO_CODE_SIGN_IN)
            fail(FloppyAuthError.NOT_FLOPPY)
        }
        if (response.status !in 200..299) fail(FloppyAuthError.NOT_FLOPPY)
        val payload = runCatching { json.decodeFromString<FloppyClientResponse>(response.body) }.getOrNull()
        return payload?.clientId?.takeIf(String::isNotBlank) ?: fail(FloppyAuthError.NOT_FLOPPY)
    }

    private suspend fun deviceAuthorization(serverUrl: String, clientId: String) = send(
        FloppyHttpRequest(
            serverUrl = serverUrl,
            method = FloppyHttpMethod.POST,
            path = "/oauth/device/authorization",
            form = mapOf("client_id" to clientId)
        )
    )

    private suspend fun send(request: FloppyHttpRequest): FloppyHttpResponse = try {
        http.execute(request)
    } catch (_: IOException) {
        fail(FloppyAuthError.UNREACHABLE)
    }

    private fun credentialsFrom(
        response: FloppyHttpResponse,
        serverUrl: String,
        clientId: String?,
        previousRefreshToken: String? = null
    ): FloppyCredentials {
        val payload = decode<FloppyTokenResponse>(response.body)
        val accessToken = payload.accessToken?.takeIf(String::isNotBlank) ?: invalidResponse()
        val refreshToken = payload.refreshToken?.takeIf(String::isNotBlank) ?: previousRefreshToken ?: invalidResponse()
        if (!payload.tokenType.equals("Bearer", ignoreCase = true)) invalidResponse()
        return FloppyCredentials(
            serverUrl = serverUrl,
            accessToken = accessToken,
            refreshToken = refreshToken,
            clientId = clientId ?: invalidResponse(),
            expiresAtEpochMs = expiresAt(payload.expiresIn ?: DEFAULT_TOKEN_SECONDS, nowEpochMs())
        )
    }

    private fun verificationUrl(value: String?): String? = value?.toHttpUrlOrNull()
        ?.takeIf { it.username.isEmpty() && it.password.isEmpty() }
        ?.toString()

    private fun FloppyHttpResponse.errorCode(): String? =
        runCatching { json.decodeFromString<FloppyErrorResponse>(body) }.getOrNull()?.let { it.error ?: it.code }

    private fun FloppyHttpResponse.isJsonCollection(): Boolean = runCatching {
        val element = Json.parseToJsonElement(body)
        element is JsonObject || element is JsonArray
    }.getOrDefault(false)

    private fun expiresAt(seconds: Long?, now: Long): Long = seconds
        ?.takeIf { it > 0L && it <= (Long.MAX_VALUE - now) / 1_000L }
        ?.let { now + it * 1_000L } ?: invalidResponse()

    private inline fun <reified T> decode(body: String): T =
        runCatching { json.decodeFromString<T>(body) }.getOrElse { invalidResponse() }

    private fun fail(error: FloppyAuthError): Nothing = throw FloppyAuthException(error)
    private fun invalidResponse(): Nothing = fail(FloppyAuthError.INVALID_RESPONSE)
    private fun scopeChanged(): Nothing = throw FloppyAccountChangedException()

    companion object {
        const val CONNECTION_CHECK_PATH = "/api/v1/sync/connections/"
        private const val DEFAULT_TOKEN_SECONDS = 3_600L
        private const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        private const val REFRESH_MARGIN_MS = 60_000L
    }
}
