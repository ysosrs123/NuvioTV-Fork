package com.nuvio.tv.data.wetrakr

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class WeTrakrApiClient(
    private val http: WeTrakrHttpClient,
    private val auth: WeTrakrAuthRepository,
    private val store: WeTrakrAuthStore
) {
    val quota: Flow<WeTrakrQuota?> = combine(http.quotas, store.state) { quotas, state ->
        quotas[limitKey(state.scope, state.user)]
    }

    suspend fun get(
        path: String,
        query: Map<String, String> = emptyMap(),
        scope: WeTrakrAuthScope = store.scope(),
        acceptedStatuses: Set<Int> = emptySet()
    ): WeTrakrHttpResponse = execute(WeTrakrHttpMethod.GET, path, query, null, scope, acceptedStatuses)

    suspend fun post(
        path: String,
        body: String,
        scope: WeTrakrAuthScope = store.scope(),
        query: Map<String, String> = emptyMap(),
        acceptedStatuses: Set<Int> = emptySet()
    ): WeTrakrHttpResponse = execute(WeTrakrHttpMethod.POST, path, query, body, scope, acceptedStatuses)

    suspend fun delete(
        path: String,
        body: String? = null,
        scope: WeTrakrAuthScope = store.scope(),
        acceptedStatuses: Set<Int> = emptySet()
    ): WeTrakrHttpResponse = execute(WeTrakrHttpMethod.DELETE, path, emptyMap(), body, scope, acceptedStatuses)

    suspend fun publicGet(
        path: String,
        query: Map<String, String> = emptyMap(),
        acceptedStatuses: Set<Int> = emptySet()
    ): WeTrakrHttpResponse {
        val response = http.execute(WeTrakrHttpRequest(WeTrakrHttpMethod.GET, path, query, limitKey = "app"))
        if (response.status !in 200..299 && response.status !in acceptedStatuses) {
            throw WeTrakrApiException(response.status, response.errorCode())
        }
        return response
    }

    fun hasQuotaToSpare(scope: WeTrakrAuthScope = store.scope()): Boolean =
        http.hasQuotaToSpare(limitKey(scope, store.state.value.user))

    suspend fun refreshUser(scope: WeTrakrAuthScope = store.scope()): WeTrakrUser {
        val previousKey = limitKey(scope, store.state.value.user)
        val user = decodeWeTrakrUser(get("/account/settings", scope = scope).body)
        if (!store.saveUser(user, scope)) store.checkScope(scope)
        http.moveLimit(previousKey, limitKey(scope, user))
        store.checkScope(scope)
        return user
    }

    private suspend fun execute(
        method: WeTrakrHttpMethod,
        path: String,
        query: Map<String, String>,
        body: String?,
        scope: WeTrakrAuthScope,
        acceptedStatuses: Set<Int>
    ): WeTrakrHttpResponse {
        var authorization = auth.authorization(scope)
        val request = { token: String ->
            WeTrakrHttpRequest(
                method = method,
                path = path,
                query = query,
                body = body,
                accessToken = token,
                limitKey = limitKey(scope, store.state.value.user)
            )
        }
        repeat(2) { attempt ->
            val response = http.execute(request(authorization.tokens.accessToken), checkScope = { store.checkScope(scope) })
            when {
                response.status == 401 && attempt == 0 ->
                    authorization = auth.authorization(scope, authorization.tokens.accessToken)
                response.status == 401 -> {
                    store.clearAuth(scope, WeTrakrAuthError.AUTHORIZATION_REVOKED, authorization.tokens.accessToken)
                    throw WeTrakrAuthException(WeTrakrAuthError.AUTHORIZATION_REVOKED)
                }
                response.status in 200..299 || response.status in acceptedStatuses -> return response
                else -> throw WeTrakrApiException(response.status, response.errorCode())
            }
        }
        throw WeTrakrAuthException(WeTrakrAuthError.AUTHORIZATION_REVOKED)
    }

    private fun limitKey(scope: WeTrakrAuthScope, user: WeTrakrUser?): String =
        user?.id?.let { "user:$it" } ?: "profile:${scope.profileId}:${scope.generation}"
}
