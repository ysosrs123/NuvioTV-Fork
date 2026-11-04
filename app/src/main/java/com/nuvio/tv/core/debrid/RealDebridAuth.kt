package com.nuvio.tv.core.debrid

import android.content.Context
import com.nuvio.tv.data.remote.api.RealDebridApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The stored Real-Debrid credential is either a private API token or, after the device sign-in, the client id,
 * client secret and refresh token joined by '|'. Access tokens from the sign-in expire and are kept only on
 * this device.
 */
internal data class RealDebridDeviceCredential(
    val clientId: String,
    val clientSecret: String,
    val refreshToken: String
) {
    fun encode(): String = listOf(PREFIX, clientId, clientSecret, refreshToken).joinToString("|")

    companion object {
        private const val PREFIX = "rd-device"

        fun parse(stored: String): RealDebridDeviceCredential? {
            val parts = stored.trim().split('|')
            if (parts.size != 4 || parts[0] != PREFIX) return null
            if (parts.drop(1).any { it.isBlank() }) return null
            return RealDebridDeviceCredential(parts[1], parts[2], parts[3])
        }
    }
}

@Singleton
class RealDebridAuth @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: RealDebridApi
) {
    private val prefs by lazy { context.getSharedPreferences("real_debrid_tokens", Context.MODE_PRIVATE) }
    private val mutex = Mutex()

    /** "Bearer ..." for [stored], refreshing the access token when it is close to expiry. Null when signed out. */
    suspend fun authorization(stored: String, forceRefresh: Boolean = false): String? {
        val value = stored.trim().takeIf { it.isNotBlank() } ?: return null
        val device = RealDebridDeviceCredential.parse(value) ?: return "Bearer $value"
        val slot = slotFor(value)
        return mutex.withLock {
            val cached = if (forceRefresh) null else cachedToken(slot)
            val token = cached ?: refresh(device, slot)
            token?.let { "Bearer $it" }
        }
    }

    /** Runs [block] with a valid authorization, refreshing once and retrying when the token was refused. */
    suspend fun <T> call(stored: String, block: suspend (String) -> Response<T>): Response<T>? {
        val first = authorization(stored) ?: return null
        val response = block(first)
        if (response.code() != 401 || RealDebridDeviceCredential.parse(stored) == null) return response
        response.errorBody()?.close()
        val refreshed = authorization(stored, forceRefresh = true) ?: return response
        return block(refreshed)
    }

    fun remember(stored: String, accessToken: String, expiresInSeconds: Long?) {
        store(slotFor(stored), accessToken, expiresInSeconds)
    }

    private fun cachedToken(slot: String): String? {
        val token = prefs.getString("$slot.token", null)?.takeIf { it.isNotBlank() } ?: return null
        val expiresAt = prefs.getLong("$slot.expires", 0L)
        return token.takeIf { System.currentTimeMillis() < expiresAt - EXPIRY_MARGIN_MS }
    }

    /** Uses the newest refresh token this device was given, falling back to the signed-in one. */
    private suspend fun refresh(device: RealDebridDeviceCredential, slot: String): String? {
        val latest = prefs.getString("$slot.refresh", null)?.takeIf { it.isNotBlank() && it != device.refreshToken }
        latest?.let { code -> refreshWith(device, slot, code)?.let { return it } }
        return refreshWith(device, slot, device.refreshToken)
    }

    private suspend fun refreshWith(device: RealDebridDeviceCredential, slot: String, code: String): String? =
        try {
            val response = api.token(
                clientId = device.clientId,
                clientSecret = device.clientSecret,
                code = code
            )
            val body = response.body()?.takeIf { response.isSuccessful }
            response.errorBody()?.close()
            val token = body?.accessToken?.takeIf { it.isNotBlank() }
            if (token != null) {
                store(slot, token, body.expiresIn)
                body.refreshToken?.takeIf { it.isNotBlank() }?.let { prefs.edit().putString("$slot.refresh", it).apply() }
            }
            token
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }

    private fun store(slot: String, token: String, expiresInSeconds: Long?) {
        val lifetimeMs = (expiresInSeconds ?: DEFAULT_LIFETIME_S).coerceAtLeast(60L) * 1000L
        prefs.edit()
            .putString("$slot.token", token)
            .putLong("$slot.expires", System.currentTimeMillis() + lifetimeMs)
            .apply()
    }

    private fun slotFor(stored: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(stored.trim().toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val EXPIRY_MARGIN_MS = 120_000L
        const val DEFAULT_LIFETIME_S = 3_600L
    }
}
