package com.nuvio.tv.core.network

import android.content.Context
import com.nuvio.tv.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response

/**
 * Upper bound for addon-facing JSON requests (manifest / meta / stream lists).
 * Without this, the only backstop is the shared OkHttp 60 s read timeout, so a
 * single dead addon can hold a coroutine - and its UI chip - for a full minute.
 * A Stremio JSON endpoint should answer in low single-digit seconds; 20 s is
 * generous headroom.
 */
const val ADDON_REQUEST_TIMEOUT_MS = 20_000L

/** [safeApiCall] with a hard per-request deadline, for addon-facing endpoints. */
suspend fun <T> safeAddonApiCall(
    context: Context,
    timeoutMs: Long = ADDON_REQUEST_TIMEOUT_MS,
    apiCall: suspend () -> Response<T>
): NetworkResult<T> {
    return withTimeoutOrNull(timeoutMs) { safeApiCall(context, apiCall) }
        ?: NetworkResult.Error(context.getString(R.string.network_error_addon_timeout))
}

suspend fun <T> safeApiCall(
    context: Context,
    apiCall: suspend () -> Response<T>
): NetworkResult<T> {
    return try {
        val response = apiCall()
        if (response.isSuccessful) {
            response.body()?.let {
                NetworkResult.Success(it)
            } ?: NetworkResult.Error(context.getString(R.string.network_error_empty_response_body))
        } else {
            NetworkResult.Error(response.message(), response.code())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        NetworkResult.Error(e.message ?: context.getString(R.string.network_error_unknown))
    }
}
