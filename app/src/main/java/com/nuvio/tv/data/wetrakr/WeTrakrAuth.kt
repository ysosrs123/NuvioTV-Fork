package com.nuvio.tv.data.wetrakr

import android.content.Context
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class WeTrakrAuthError {
    MISSING_CLIENT_ID,
    INVALID_CLIENT,
    INVALID_RESPONSE,
    TOO_MANY_CODES,
    CODE_EXPIRED,
    ACCESS_DENIED,
    AUTHORIZATION_REVOKED,
    APP_SUSPENDED
}

data class WeTrakrAuthScope(val profileId: Int, val generation: Long)

@Serializable
data class WeTrakrDeviceSession(
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresAtEpochMs: Long,
    val intervalSeconds: Int,
    val nextPollAtEpochMs: Long
) {
    override fun toString(): String = "WeTrakrDeviceSession(expiresAtEpochMs=$expiresAtEpochMs)"
}

@Serializable
data class WeTrakrUser(
    val id: Long,
    val username: String? = null,
    val displayName: String? = null,
    val plan: String? = null
) {
    val label: String?
        get() = displayName?.takeIf(String::isNotBlank) ?: username?.takeIf(String::isNotBlank)
}

@Serializable
data class WeTrakrTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long
) {
    override fun toString(): String = "WeTrakrTokens(expiresAtEpochMs=$expiresAtEpochMs)"
}

class WeTrakrAuthorization(val scope: WeTrakrAuthScope, val tokens: WeTrakrTokens) {
    override fun toString(): String = "WeTrakrAuthorization(scope=$scope)"
}

data class WeTrakrAuthState(
    val scope: WeTrakrAuthScope = WeTrakrAuthScope(1, 0),
    val isAuthenticated: Boolean = false,
    val user: WeTrakrUser? = null,
    val session: WeTrakrDeviceSession? = null,
    val error: WeTrakrAuthError? = null
)

sealed interface WeTrakrDevicePollResult {
    data object Pending : WeTrakrDevicePollResult
    data object Authorized : WeTrakrDevicePollResult
    data object Expired : WeTrakrDevicePollResult
    data object Denied : WeTrakrDevicePollResult
}

class WeTrakrAuthException(val error: WeTrakrAuthError) : Exception(error.name)

class WeTrakrAccountChangedException(message: String = "WeTrakr account changed") : IllegalStateException(message)

@Serializable
internal data class WeTrakrStoredAuth(
    val tokens: WeTrakrTokens? = null,
    val user: WeTrakrUser? = null,
    val session: WeTrakrDeviceSession? = null,
    val deviceCode: String? = null
) {
    override fun toString(): String = "WeTrakrStoredAuth()"
}

interface WeTrakrAuthPersistence {
    fun read(profileId: Int): String?
    fun write(profileId: Int, value: String?)
    fun clear()
}

@Singleton
class AndroidWeTrakrAuthPersistence @Inject constructor(
    @ApplicationContext context: Context
) : WeTrakrAuthPersistence {
    private val preferences = ProtectedProfilePreferences(context, "wetrakr_auth", "com.nuvio.tv.wetrakr.credentials.v1")

    override fun read(profileId: Int): String? = preferences.read(profileId)
    override fun write(profileId: Int, value: String?) = preferences.write(profileId, value)
    override fun clear() = preferences.clear()
}

class WeTrakrAuthStore(
    private val persistence: WeTrakrAuthPersistence,
    initialProfileId: Int = 1
) : ProfileScopedCredentialStore {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var currentScope = WeTrakrAuthScope(initialProfileId, 0)
    private var stored = load(initialProfileId)
    private val mutableState = MutableStateFlow(stateFor())

    val state: StateFlow<WeTrakrAuthState> = mutableState.asStateFlow()

    fun scope(): WeTrakrAuthScope = synchronized(lock) { currentScope }

    fun isCurrent(scope: WeTrakrAuthScope): Boolean = synchronized(lock) { currentScope == scope }

    fun checkScope(scope: WeTrakrAuthScope) {
        if (!isCurrent(scope)) throw WeTrakrAccountChangedException()
    }

    fun authorization(): WeTrakrAuthorization? = synchronized(lock) {
        stored.tokens?.let { WeTrakrAuthorization(currentScope, it) }
    }

    fun deviceCode(scope: WeTrakrAuthScope): String? = synchronized(lock) {
        stored.deviceCode.takeIf { currentScope == scope }
    }

    fun selectProfile(profileId: Int) = synchronized(lock) {
        if (currentScope.profileId == profileId) return@synchronized
        stored = load(profileId)
        currentScope = WeTrakrAuthScope(profileId, currentScope.generation + 1)
        publish()
    }

    fun saveSession(session: WeTrakrDeviceSession, deviceCode: String, scope: WeTrakrAuthScope): Boolean =
        mutate(scope, advanceGeneration = true) { it.copy(session = session, deviceCode = deviceCode) }

    fun updateSession(session: WeTrakrDeviceSession, scope: WeTrakrAuthScope): Boolean =
        mutate(scope) { it.copy(session = session) }

    fun cancelSession(error: WeTrakrAuthError? = null, scope: WeTrakrAuthScope = scope()): Boolean =
        mutate(scope, advanceGeneration = true, error = error) { it.copy(session = null, deviceCode = null) }

    fun authorize(tokens: WeTrakrTokens, scope: WeTrakrAuthScope): Boolean =
        mutate(scope, advanceGeneration = true) { WeTrakrStoredAuth(tokens = tokens) }

    fun refreshTokens(tokens: WeTrakrTokens, expected: WeTrakrAuthorization): Boolean = synchronized(lock) {
        if (stored.tokens?.accessToken != expected.tokens.accessToken) return@synchronized false
        mutate(expected.scope) { it.copy(tokens = tokens) }
    }

    fun saveUser(user: WeTrakrUser, scope: WeTrakrAuthScope): Boolean = mutate(scope) { it.copy(user = user) }

    fun clearAuth(
        scope: WeTrakrAuthScope = scope(),
        error: WeTrakrAuthError? = null,
        expectedAccessToken: String? = null
    ): Boolean = synchronized(lock) {
        if (expectedAccessToken != null && stored.tokens?.accessToken != expectedAccessToken) return@synchronized false
        mutate(scope, advanceGeneration = true, error = error) { WeTrakrStoredAuth() }
    }

    override fun removeProfile(profileId: Int) = synchronized(lock) {
        persistence.write(profileId, null)
        if (currentScope.profileId == profileId) {
            currentScope = currentScope.copy(generation = currentScope.generation + 1)
            stored = WeTrakrStoredAuth()
            publish()
        }
    }

    override fun clearAllProfiles() = synchronized(lock) {
        persistence.clear()
        currentScope = currentScope.copy(generation = currentScope.generation + 1)
        stored = WeTrakrStoredAuth()
        publish()
    }

    private fun mutate(
        scope: WeTrakrAuthScope,
        advanceGeneration: Boolean = false,
        error: WeTrakrAuthError? = null,
        transform: (WeTrakrStoredAuth) -> WeTrakrStoredAuth
    ): Boolean = synchronized(lock) {
        if (currentScope != scope) return@synchronized false
        val value = transform(stored)
        persistence.write(scope.profileId, json.encodeToString(WeTrakrStoredAuth.serializer(), value))
        stored = value
        if (advanceGeneration) currentScope = currentScope.copy(generation = currentScope.generation + 1)
        publish(error)
        true
    }

    private fun load(profileId: Int): WeTrakrStoredAuth = persistence.read(profileId)?.let { value ->
        runCatching { json.decodeFromString(WeTrakrStoredAuth.serializer(), value) }.getOrNull()
    }?.takeIf { value ->
        value.tokens == null || (value.tokens.accessToken.isNotBlank() && value.tokens.refreshToken.isNotBlank())
    } ?: WeTrakrStoredAuth()

    private fun publish(error: WeTrakrAuthError? = null) {
        mutableState.value = stateFor(error)
    }

    private fun stateFor(error: WeTrakrAuthError? = null) = WeTrakrAuthState(
        scope = currentScope,
        isAuthenticated = stored.tokens != null,
        user = stored.user,
        session = stored.session,
        error = error
    )
}

@Serializable
internal data class WeTrakrDeviceResponse(
    @SerialName("device_code") val deviceCode: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("verification_url") val verificationUrl: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val interval: Int? = null
)

@Serializable
internal data class WeTrakrTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("new_refresh_token") val newRefreshToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null
)

class WeTrakrAuthRepository(
    private val http: WeTrakrHttpClient,
    private val configuration: WeTrakrConfiguration,
    private val store: WeTrakrAuthStore,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    private val deviceMutex = Mutex()
    private val refreshMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    val state = store.state

    fun hasRequiredCredentials(): Boolean = configuration.clientId.isNotBlank()

    suspend fun startDeviceAuthorization(scope: WeTrakrAuthScope = store.scope()): WeTrakrDeviceSession = deviceMutex.withLock {
        store.checkScope(scope)
        if (!hasRequiredCredentials()) throw WeTrakrAuthException(WeTrakrAuthError.MISSING_CLIENT_ID)
        check(!state.value.isAuthenticated)
        val response = post("/oauth/device/code", mapOf("client_id" to configuration.clientId), scope)
        when (response.status) {
            in 200..299 -> Unit
            401, 403 -> throw WeTrakrAuthException(WeTrakrAuthError.INVALID_CLIENT)
            423 -> throw WeTrakrAuthException(WeTrakrAuthError.APP_SUSPENDED)
            429 -> throw WeTrakrAuthException(WeTrakrAuthError.TOO_MANY_CODES)
            else -> throw WeTrakrApiException(response.status, response.errorCode())
        }
        val payload = decode(WeTrakrDeviceResponse.serializer(), response.body)
        val deviceCode = payload.deviceCode?.takeIf(String::isNotBlank) ?: invalidResponse()
        val userCode = payload.userCode?.takeIf { it.isNotBlank() && it.length <= 32 } ?: invalidResponse()
        val uri = payload.verificationUrl?.takeIf(::isWeTrakrPage) ?: DEFAULT_VERIFICATION_URL
        val interval = (payload.interval ?: 5).takeIf { it in 1..3_600 } ?: invalidResponse()
        val now = nowEpochMs()
        val session = WeTrakrDeviceSession(
            userCode = userCode,
            verificationUri = uri,
            verificationUriComplete = "$DEFAULT_VERIFICATION_URL?code=$userCode",
            expiresAtEpochMs = expiresAt(payload.expiresIn ?: DEFAULT_CODE_SECONDS, now),
            intervalSeconds = interval,
            nextPollAtEpochMs = now + interval * 1_000L
        )
        if (!store.saveSession(session, deviceCode, scope)) scopeChanged()
        session
    }

    suspend fun pollDeviceAuthorization(scope: WeTrakrAuthScope = store.scope()): WeTrakrDevicePollResult = deviceMutex.withLock {
        store.checkScope(scope)
        val session = state.value.session ?: scopeChanged()
        val now = nowEpochMs()
        if (now >= session.expiresAtEpochMs) {
            store.cancelSession(WeTrakrAuthError.CODE_EXPIRED, scope)
            return@withLock WeTrakrDevicePollResult.Expired
        }
        if (now < session.nextPollAtEpochMs) return@withLock WeTrakrDevicePollResult.Pending
        val deviceCode = store.deviceCode(scope) ?: scopeChanged()
        if (!store.updateSession(session.copy(nextPollAtEpochMs = now + session.intervalSeconds * 1_000L), scope)) {
            scopeChanged()
        }
        val response = post(
            "/oauth/device/token",
            mapOf("code" to deviceCode, "client_id" to configuration.clientId),
            scope
        )
        when (response.status) {
            in 200..299 -> {
                if (!store.authorize(tokensFrom(response), scope)) scopeChanged()
                WeTrakrDevicePollResult.Authorized
            }
            400 -> {
                val code = response.errorCode()
                if (code == null || code.equals("authorization_pending", ignoreCase = true)) {
                    WeTrakrDevicePollResult.Pending
                } else {
                    throw WeTrakrApiException(400, code)
                }
            }
            429 -> {
                val interval = (session.intervalSeconds + 5).coerceAtMost(3_600)
                store.updateSession(
                    session.copy(intervalSeconds = interval, nextPollAtEpochMs = nowEpochMs() + interval * 1_000L),
                    scope
                )
                WeTrakrDevicePollResult.Pending
            }
            418 -> {
                store.cancelSession(WeTrakrAuthError.ACCESS_DENIED, scope)
                WeTrakrDevicePollResult.Denied
            }
            404, 409, 410 -> {
                store.cancelSession(WeTrakrAuthError.CODE_EXPIRED, scope)
                WeTrakrDevicePollResult.Expired
            }
            423 -> {
                store.cancelSession(WeTrakrAuthError.APP_SUSPENDED, scope)
                throw WeTrakrAuthException(WeTrakrAuthError.APP_SUSPENDED)
            }
            else -> throw WeTrakrApiException(response.status, response.errorCode())
        }
    }

    fun cancelDeviceAuthorization() {
        store.cancelSession()
    }

    suspend fun authorization(
        scope: WeTrakrAuthScope,
        rejectedAccessToken: String? = null
    ): WeTrakrAuthorization = refreshMutex.withLock {
        store.checkScope(scope)
        val current = store.authorization() ?: throw WeTrakrAuthException(WeTrakrAuthError.AUTHORIZATION_REVOKED)
        if (rejectedAccessToken != null && current.tokens.accessToken != rejectedAccessToken) return@withLock current
        if (rejectedAccessToken == null && current.tokens.expiresAtEpochMs - nowEpochMs() > REFRESH_MARGIN_MS) {
            return@withLock current
        }
        val response = post("/oauth/token/refresh", mapOf("refresh_token" to current.tokens.refreshToken), scope)
        if (response.status !in 200..299) {
            if (response.status in setOf(400, 401)) {
                store.clearAuth(scope, WeTrakrAuthError.AUTHORIZATION_REVOKED, current.tokens.accessToken)
                throw WeTrakrAuthException(WeTrakrAuthError.AUTHORIZATION_REVOKED)
            }
            throw WeTrakrApiException(response.status, response.errorCode())
        }
        val tokens = tokensFrom(response, current.tokens.refreshToken)
        if (!store.refreshTokens(tokens, current)) scopeChanged()
        WeTrakrAuthorization(scope, tokens)
    }

    suspend fun disconnect(scope: WeTrakrAuthScope = store.scope()): Boolean {
        store.checkScope(scope)
        val current = store.authorization()
        if (current != null && current.scope != scope) scopeChanged()
        if (!store.clearAuth(scope)) scopeChanged()
        if (current == null) return true
        return try {
            http.execute(
                WeTrakrHttpRequest(
                    method = WeTrakrHttpMethod.POST,
                    path = "/oauth/logout",
                    body = buildJsonObject { put("refresh_token", current.tokens.refreshToken) }.toString(),
                    accessToken = current.tokens.accessToken,
                    limitKey = "oauth:${scope.profileId}"
                )
            ).status in 200..299
        } catch (error: CancellationException) {
            throw error
        } catch (_: WeTrakrApiException) {
            false
        }
    }

    private suspend fun post(path: String, fields: Map<String, String>, scope: WeTrakrAuthScope) =
        http.execute(
            WeTrakrHttpRequest(
                method = WeTrakrHttpMethod.POST,
                path = path,
                body = buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }.toString(),
                limitKey = "oauth:${scope.profileId}",
                idempotencyKey = null,
                handlesRateLimit = true
            ),
            checkScope = { store.checkScope(scope) }
        )

    private fun tokensFrom(response: WeTrakrHttpResponse, previousRefreshToken: String? = null): WeTrakrTokens {
        val payload = decode(WeTrakrTokenResponse.serializer(), response.body)
        val accessToken = payload.accessToken?.takeIf(String::isNotBlank) ?: invalidResponse()
        val refreshToken = payload.newRefreshToken?.takeIf(String::isNotBlank)
            ?: payload.refreshToken?.takeIf(String::isNotBlank)
            ?: previousRefreshToken ?: invalidResponse()
        if (payload.tokenType != null && !payload.tokenType.equals("Bearer", ignoreCase = true)) invalidResponse()
        return WeTrakrTokens(accessToken, refreshToken, expiresAt(payload.expiresIn ?: DEFAULT_TOKEN_SECONDS, nowEpochMs()))
    }

    private fun expiresAt(seconds: Long, now: Long): Long = seconds
        .takeIf { it > 0L && it <= (Long.MAX_VALUE - now) / 1_000L }
        ?.let { now + it * 1_000L } ?: invalidResponse()

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, body: String): T =
        runCatching { json.decodeFromString(serializer, body) }.getOrElse { invalidResponse() }

    private fun invalidResponse(): Nothing = throw WeTrakrAuthException(WeTrakrAuthError.INVALID_RESPONSE)
    private fun scopeChanged(): Nothing = throw WeTrakrAccountChangedException()

    companion object {
        const val DEFAULT_VERIFICATION_URL = "https://wetrakr.com/activate"
        private const val DEFAULT_CODE_SECONDS = 600L
        private const val DEFAULT_TOKEN_SECONDS = 7L * 24 * 60 * 60
        private const val REFRESH_MARGIN_MS = 10L * 60 * 1_000

        internal fun isWeTrakrPage(value: String): Boolean = runCatching {
            val url = java.net.URI(value)
            url.scheme == "https" && (url.host == "wetrakr.com" || url.host == "www.wetrakr.com") && url.userInfo == null
        }.getOrDefault(false)
    }
}
