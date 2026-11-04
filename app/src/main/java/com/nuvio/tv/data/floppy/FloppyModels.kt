package com.nuvio.tv.data.floppy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class FloppyAuthError {
    INVALID_ADDRESS,
    UNREACHABLE,
    NOT_FLOPPY,
    NO_CODE_SIGN_IN,
    TOKEN_REJECTED,
    INVALID_RESPONSE,
    CODE_EXPIRED,
    ACCESS_DENIED,
    AUTHORIZATION_REVOKED
}

data class FloppyAuthScope(val profileId: Int, val generation: Long)

@Serializable
data class FloppyDeviceSession(
    val serverUrl: String,
    val clientId: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresAtEpochMs: Long,
    val intervalSeconds: Int,
    val nextPollAtEpochMs: Long
) {
    override fun toString(): String = "FloppyDeviceSession(expiresAtEpochMs=$expiresAtEpochMs)"
}

/** An app token has no refresh token and no expiry; a code sign-in has both. */
@Serializable
data class FloppyCredentials(
    val serverUrl: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val clientId: String? = null,
    val expiresAtEpochMs: Long? = null
) {
    val refreshes: Boolean get() = refreshToken != null && clientId != null

    override fun toString(): String = "FloppyCredentials(serverUrl=$serverUrl, refreshes=$refreshes)"
}

class FloppyAuthorization(val scope: FloppyAuthScope, val credentials: FloppyCredentials) {
    override fun toString(): String = "FloppyAuthorization(scope=$scope)"
}

data class FloppyAuthState(
    val scope: FloppyAuthScope = FloppyAuthScope(1, 0),
    val isAuthenticated: Boolean = false,
    val serverUrl: String? = null,
    val lastServerUrl: String? = null,
    val session: FloppyDeviceSession? = null,
    val error: FloppyAuthError? = null
)

sealed interface FloppyDevicePollResult {
    data object Pending : FloppyDevicePollResult
    data object Authorized : FloppyDevicePollResult
    data object Expired : FloppyDevicePollResult
    data object Denied : FloppyDevicePollResult
}

class FloppyAuthException(val error: FloppyAuthError) : Exception(error.name)

class FloppyAccountChangedException : Exception("Floppy account changed")

class FloppyApiException(val status: Int, val code: String? = null) : Exception("Floppy HTTP $status ${code.orEmpty()}")

@Serializable
internal data class FloppyStoredAuth(
    val credentials: FloppyCredentials? = null,
    val lastServerUrl: String? = null,
    val session: FloppyDeviceSession? = null,
    val deviceCode: String? = null
) {
    override fun toString(): String = "FloppyStoredAuth()"
}

@Serializable
internal data class FloppyClientResponse(
    @SerialName("client_id") val clientId: String? = null
)

@Serializable
internal data class FloppyDeviceResponse(
    @SerialName("device_code") val deviceCode: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val interval: Int = 5
)

@Serializable
internal data class FloppyTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null
)

@Serializable
internal data class FloppyErrorResponse(
    val error: String? = null,
    val code: String? = null
)
