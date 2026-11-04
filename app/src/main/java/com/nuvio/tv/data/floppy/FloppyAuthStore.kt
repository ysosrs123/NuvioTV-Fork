package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface FloppyAuthPersistence {
    fun read(profileId: Int): String?
    fun write(profileId: Int, value: String?)
    fun clear()
}

class FloppyAuthStore(
    private val persistence: FloppyAuthPersistence,
    initialProfileId: Int = 1
) : ProfileScopedCredentialStore {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var currentScope = FloppyAuthScope(initialProfileId, 0)
    private var stored = load(initialProfileId)
    private val mutableState = MutableStateFlow(stateFor())

    val state: StateFlow<FloppyAuthState> = mutableState.asStateFlow()

    fun scope(): FloppyAuthScope = synchronized(lock) { currentScope }

    fun checkScope(scope: FloppyAuthScope) {
        if (synchronized(lock) { currentScope != scope }) throw FloppyAccountChangedException()
    }

    fun authorization(): FloppyAuthorization? = synchronized(lock) {
        stored.credentials?.let { FloppyAuthorization(currentScope, it) }
    }

    fun deviceCode(scope: FloppyAuthScope): String? = synchronized(lock) {
        stored.deviceCode.takeIf { currentScope == scope }
    }

    fun selectProfile(profileId: Int) = synchronized(lock) {
        if (currentScope.profileId == profileId) return@synchronized
        stored = load(profileId)
        currentScope = FloppyAuthScope(profileId, currentScope.generation + 1)
        publish()
    }

    fun saveSession(session: FloppyDeviceSession, deviceCode: String, scope: FloppyAuthScope): Boolean =
        mutate(scope, advanceGeneration = true) {
            it.copy(session = session, deviceCode = deviceCode, lastServerUrl = session.serverUrl)
        }

    fun updateSession(session: FloppyDeviceSession, scope: FloppyAuthScope): Boolean =
        mutate(scope) { it.copy(session = session) }

    fun cancelSession(error: FloppyAuthError? = null, scope: FloppyAuthScope = scope()): Boolean =
        mutate(scope, advanceGeneration = true, error = error) {
            it.copy(session = null, deviceCode = null)
        }

    fun authorize(credentials: FloppyCredentials, scope: FloppyAuthScope): Boolean =
        mutate(scope, advanceGeneration = true) {
            FloppyStoredAuth(credentials = credentials, lastServerUrl = credentials.serverUrl)
        }

    fun refreshCredentials(credentials: FloppyCredentials, expected: FloppyAuthorization): Boolean =
        synchronized(lock) {
            if (stored.credentials?.accessToken != expected.credentials.accessToken) return@synchronized false
            mutate(expected.scope) { it.copy(credentials = credentials) }
        }

    fun clearAuth(
        scope: FloppyAuthScope = scope(),
        error: FloppyAuthError? = null,
        expectedAccessToken: String? = null
    ): Boolean = synchronized(lock) {
        if (expectedAccessToken != null && stored.credentials?.accessToken != expectedAccessToken) {
            return@synchronized false
        }
        mutate(scope, advanceGeneration = true, error = error) { FloppyStoredAuth(lastServerUrl = it.lastServerUrl) }
    }

    override fun removeProfile(profileId: Int) = synchronized(lock) {
        persistence.write(profileId, null)
        if (currentScope.profileId == profileId) {
            currentScope = currentScope.copy(generation = currentScope.generation + 1)
            stored = FloppyStoredAuth()
            publish()
        }
    }

    override fun clearAllProfiles() = synchronized(lock) {
        persistence.clear()
        currentScope = currentScope.copy(generation = currentScope.generation + 1)
        stored = FloppyStoredAuth()
        publish()
    }

    private fun mutate(
        scope: FloppyAuthScope,
        advanceGeneration: Boolean = false,
        error: FloppyAuthError? = null,
        transform: (FloppyStoredAuth) -> FloppyStoredAuth
    ): Boolean = synchronized(lock) {
        if (currentScope != scope) return@synchronized false
        val value = transform(stored)
        persistence.write(scope.profileId, json.encodeToString(value))
        stored = value
        if (advanceGeneration) currentScope = currentScope.copy(generation = currentScope.generation + 1)
        publish(error)
        true
    }

    private fun load(profileId: Int): FloppyStoredAuth = persistence.read(profileId)?.let { value ->
        runCatching { json.decodeFromString<FloppyStoredAuth>(value) }.getOrNull()
    }?.takeIf { value ->
        value.credentials == null ||
            (value.credentials.accessToken.isNotBlank() && value.credentials.serverUrl.isNotBlank())
    } ?: FloppyStoredAuth()

    private fun publish(error: FloppyAuthError? = null) {
        mutableState.value = stateFor(error)
    }

    private fun stateFor(error: FloppyAuthError? = null) = FloppyAuthState(
        scope = currentScope,
        isAuthenticated = stored.credentials != null,
        serverUrl = stored.credentials?.serverUrl,
        lastServerUrl = stored.lastServerUrl,
        session = stored.session,
        error = error
    )
}
