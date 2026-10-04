package com.nuvio.tv.data.mediaserver

import android.util.Log
import com.nuvio.tv.core.network.ServerTrust
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.data.mediaserver.silo.SiloProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class AlternateAddressResult {
    SAVED,
    CLEARED,
    SAME_ADDRESS,
    OTHER_SERVER,
    UNREACHABLE,
    INVALID
}

data class ServersUiState(
    val connections: List<ServerConnection> = emptyList(),
    val failures: Map<String, ServerFailure> = emptyMap(),
    val revision: Int = 0,
    val syncEnabled: Boolean = false
) {
    val enabledConnections: List<ServerConnection>
        get() = connections.filter { it.enabled }
}

private fun originOf(url: String): String? {
    val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val port = if (uri.port != -1) uri.port else if (scheme == "https") 443 else 80
    return "$scheme://$host:$port"
}

class ServerRepository(
    private val persistence: ServerPersistence,
    val providers: List<ServerProvider>,
    private val scope: CoroutineScope,
    initialProfileId: Int = 1,
    private val isOnCurrentNetwork: (String) -> Boolean = { true },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) : ProfileScopedCredentialStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val mutableState = MutableStateFlow(ServersUiState())
    val uiState: StateFlow<ServersUiState> = mutableState.asStateFlow()

    @Volatile
    private var activeProfileId = initialProfileId

    @Volatile
    private var loadedProfileId: Int? = null

    @Volatile
    private var generation = 0L
    private var tokens = emptyMap<String, String>()
    private var pendingPush = false
    private var syncedKeys: Set<String>? = null
    private var syncEnabled = false
    private var localVersion = 0L
    private val activeAddresses = ConcurrentHashMap<String, AddressChoice>()
    private val trustLock = Any()
    private val mutableLocalChanges = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val localChanges: SharedFlow<Int> = mutableLocalChanges.asSharedFlow()

    val currentProfileId: Int
        get() = activeProfileId

    fun ensureLoaded() {
        val profileId = activeProfileId
        if (loadedProfileId != profileId) load(profileId)
    }

    fun selectProfile(profileId: Int) {
        activeProfileId = profileId
        ensureLoaded()
    }

    fun connection(connectionId: String): ServerConnection? {
        ensureLoaded()
        return mutableState.value.connections.firstOrNull { it.id == connectionId }
    }

    fun enabledConnections(): List<ServerConnection> {
        ensureLoaded()
        return mutableState.value.enabledConnections
    }

    fun provider(connection: ServerConnection): ServerProvider? = providers.firstOrNull { it.id == connection.providerId }

    /**
     * Sign-in headers for a link on one of the connected servers, for the stream tests in settings.
     * Saved diagnostics keep server links without their token, so the token is added here at test time only.
     */
    fun authHeadersFor(url: String?): Map<String, String> {
        val target = url?.let(::originOf) ?: return emptyMap()
        val connection = mutableState.value.connections.firstOrNull { connection ->
            connection.enabled && listOfNotNull(connection.address, connection.alternateAddress, connection.lastGoodAddress)
                .any { originOf(it) == target }
        } ?: return emptyMap()
        val session = session(connection.id) ?: return emptyMap()
        return provider(connection)?.authHeaders(session).orEmpty()
    }

    fun sourceLabel(connection: ServerConnection): String =
        "${provider(connection)?.displayName ?: connection.providerId} · ${connection.name}"

    fun session(connectionId: String): ServerSession? {
        val connection = connection(connectionId)?.takeIf { it.enabled } ?: return null
        val token = synchronized(lock) { tokens[connection.credentialRef] } ?: return null
        return ServerSession(connection, token, activeAddress(connection))
    }

    fun activeAddress(connection: ServerConnection): String {
        val known = listOfNotNull(connection.address, connection.alternateAddress)
        val chosen = activeAddresses[connection.id]?.address?.takeIf { it in known } ?: connection.address
        val other = known.firstOrNull { it != chosen } ?: return chosen
        return if (!isSafeToUse(chosen) && isSafeToUse(other)) other else chosen
    }

    /** After a network change the first address is tried first again, unless it would send the sign-in unencrypted. */
    fun onNetworkChanged() {
        mutableState.value.connections.forEach { connection ->
            if (isSafeToUse(connection.address)) chooseAddress(connection.id, connection.address)
        }
    }

    private fun chooseAddress(connectionId: String, address: String) {
        activeAddresses[connectionId] = AddressChoice(address, clock())
    }

    /** While the second address is in use, one call every so often tries the first address again. */
    private fun firstAddressRetry(session: ServerSession): ServerSession? {
        val connection = session.connection
        if (session.address == connection.address || !isSafeToUse(connection.address)) return null
        val choice = activeAddresses[connection.id] ?: return null
        if (clock() - choice.atMs < FIRST_ADDRESS_RETRY_MS) return null
        chooseAddress(connection.id, choice.address)
        return ServerSession(connection, session.token, connection.address)
    }

    /** An unencrypted address is only used while it is on the network the box is connected to. */
    private fun isSafeToUse(address: String): Boolean {
        val url = address.toHttpUrlOrNull() ?: return false
        return url.isHttps || isOnCurrentNetwork(url.host)
    }

    suspend fun <T> call(
        connectionId: String,
        block: suspend (ServerProvider, ServerSession) -> T
    ): T {
        val connection = connection(connectionId) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val startedGeneration = generation
        if (!connection.enabled) throw ServerException(ServerFailure.UNREACHABLE)
        val session = session(connectionId) ?: throw ServerException(ServerFailure.AUTH_REQUIRED)
        val provider = provider(session.connection) ?: throw ServerException(ServerFailure.UNSUPPORTED)
        val connectLimit = connectLimitFor(connection)
        val first = firstAddressRetry(session)
        suspend fun run(current: ServerSession): T = withContext(connectLimit) { block(provider, current) }.also {
            if (startedGeneration != generation) throw ServerException(ServerFailure.INCOMPLETE)
            setFailure(connectionId, null)
        }
        return try {
            try {
                if (first == null) run(session) else run(first).also { chooseAddress(connectionId, first.address) }
            } catch (error: ServerException) {
                val switched = if (error.network) switchAddress(provider, first ?: session) else null
                if (switched == null) throw error
                run(switched)
            }
        } catch (error: ServerException) {
            if (startedGeneration == generation &&
                (error.failure == ServerFailure.AUTH_REQUIRED || error.failure == ServerFailure.UNREACHABLE)
            ) {
                setFailure(connectionId, error.failure)
            }
            throw error
        }
    }

    /** With a second address set, a dead first address is given up on quickly so the switch happens sooner. */
    private fun connectLimitFor(connection: ServerConnection): CoroutineContext =
        if (connection.alternateAddress != null) ServerConnectTimeout(ALTERNATE_CONNECT_TIMEOUT_MS) else EmptyCoroutineContext

    private suspend fun switchAddress(provider: ServerProvider, session: ServerSession): ServerSession? {
        val connection = session.connection
        val alternate = connection.alternateAddress ?: return null
        val other = if (session.address == alternate) connection.address else alternate
        if (!isSafeToUse(other)) return null
        val location = withTimeoutOrNull(ADDRESS_PROBE_MS) { provider.locate(other) } ?: return null
        if (location.serverId != connection.remoteServerId) return null
        chooseAddress(connection.id, other)
        rememberGoodAddress(connection.id, other)
        return ServerSession(connection, session.token, other)
    }

    private fun rememberGoodAddress(connectionId: String, address: String) {
        val connections = mutableState.value.connections.map {
            if (it.id == connectionId) it.copy(lastGoodAddress = address) else it
        }
        persist(connections, synchronized(lock) { tokens })
        mutableState.update { it.copy(connections = connections) }
    }

    suspend fun setAlternateAddress(connectionId: String, input: String): AlternateAddressResult {
        val connection = connection(connectionId) ?: return AlternateAddressResult.INVALID
        if (input.isBlank()) {
            activeAddresses.remove(connectionId)
            updateConnection(connectionId) { it.copy(alternateAddress = null, lastGoodAddress = null) }
            return AlternateAddressResult.CLEARED
        }
        val provider = provider(connection) ?: return AlternateAddressResult.INVALID
        val location = withTimeoutOrNull(ADDRESS_PROBE_MS) { provider.locate(input) }
            ?: return AlternateAddressResult.UNREACHABLE
        if (location.serverId != connection.remoteServerId) return AlternateAddressResult.OTHER_SERVER
        if (location.address == connection.address) return AlternateAddressResult.SAME_ADDRESS
        updateConnection(connectionId) { it.copy(alternateAddress = location.address) }
        return AlternateAddressResult.SAVED
    }

    suspend fun connect(
        provider: ServerProvider,
        address: String,
        username: String,
        password: String,
        acceptedCertificate: Pair<String, String>? = null
    ): ServerConnection {
        acceptedCertificate?.let { (host, fingerprint) -> ServerTrust.allow(host, fingerprint) }
        return try {
            connect(provider, acceptedCertificate) { provider.signIn(address, username, password) }
        } finally {
            ServerTrust.clearPending()
        }
    }

    suspend fun connectWithQuickConnect(
        provider: ServerProvider,
        ticket: ServerQuickConnect,
        acceptedCertificate: Pair<String, String>? = null
    ): ServerConnection = try {
        connect(provider, acceptedCertificate) { provider.quickConnectSignIn(ticket) }
    } finally {
        ServerTrust.clearPending()
    }

    private suspend fun connect(
        provider: ServerProvider,
        acceptedCertificate: Pair<String, String>? = null,
        signInWith: suspend () -> ServerSignIn
    ): ServerConnection {
        ensureLoaded()
        val startedGeneration = generation
        val profileId = loadedProfileId ?: activeProfileId
        val signIn = signInWith()
        val existing = mutableState.value.connections.firstOrNull {
            it.providerId == provider.id &&
                it.remoteServerId == signIn.serverId &&
                it.remoteUserId == signIn.userId
        }
        val signedInOnAlternate = existing != null && existing.alternateAddress == signIn.address
        val draft = ServerConnection(
            id = existing?.id ?: newId("c"),
            providerId = provider.id,
            name = signIn.serverName,
            address = if (signedInOnAlternate) existing!!.address else signIn.address,
            remoteServerId = signIn.serverId,
            remoteUserId = signIn.userId,
            userName = signIn.userName,
            credentialRef = newId("k"),
            libraries = existing?.libraries.orEmpty(),
            enabled = true,
            useCatalogMetadata = existing?.useCatalogMetadata ?: true,
            importContinueWatching = existing?.importContinueWatching ?: true,
            alternateAddress = existing?.alternateAddress,
            tlsPins = existing?.tlsPins.orEmpty() + listOfNotNull(acceptedCertificate)
        )
        val libraries = provider.libraries(ServerSession(draft, signIn.token, signIn.address))
        if (startedGeneration != generation || profileId != loadedProfileId) {
            throw CancellationException("Server scope changed")
        }
        val connection = draft.copy(libraries = mergeLibraries(existing?.libraries.orEmpty(), libraries))
        store(connection, signIn.token, replacedCredential = existing?.credentialRef)
        return connection
    }

    internal fun store(connection: ServerConnection, token: String, replacedCredential: String? = null) {
        ensureLoaded()
        val updatedTokens = synchronized(lock) {
            (tokens - listOfNotNull(replacedCredential)) + (connection.credentialRef to token)
        }
        save(
            mutableState.value.connections.filterNot { it.id == connection.id } + connection,
            updatedTokens
        )
        setFailure(connection.id, null)
    }

    suspend fun refreshLibraries(connectionId: String) {
        val libraries = call(connectionId) { provider, session -> provider.libraries(session) }
        updateConnection(connectionId) { it.copy(libraries = mergeLibraries(it.libraries, libraries)) }
    }

    fun setLibrarySelected(connectionId: String, libraryId: String, selected: Boolean) {
        updateConnection(connectionId) { connection ->
            connection.copy(
                libraries = connection.libraries.map {
                    if (it.id == libraryId) it.copy(selected = selected) else it
                }
            )
        }
    }

    fun setCatalogMetadata(connectionId: String, enabled: Boolean) {
        updateConnection(connectionId) { it.copy(useCatalogMetadata = enabled) }
    }

    fun setImportContinueWatching(connectionId: String, enabled: Boolean) {
        updateConnection(connectionId) { it.copy(importContinueWatching = enabled) }
    }

    fun forgetCertificates(connectionId: String) {
        updateConnection(connectionId) { it.copy(tlsPins = emptyMap()) }
    }

    fun setEnabled(connectionId: String, enabled: Boolean) {
        updateConnection(connectionId) { it.copy(enabled = enabled) }
    }

    fun remove(connectionId: String) {
        val connection = connection(connectionId) ?: return
        activeAddresses.remove(connectionId)
        val session = session(connectionId)
        save(
            mutableState.value.connections.filterNot { it.id == connectionId },
            synchronized(lock) { tokens - connection.credentialRef }
        )
        setFailure(connectionId, null)
        if (session != null) {
            scope.launch {
                withContext(NonCancellable) {
                    withTimeoutOrNull(5_000L) {
                        runCatching { provider(connection)?.signOut(session) }
                    }
                }
            }
        }
    }

    fun setSyncEnabled(enabled: Boolean) {
        ensureLoaded()
        val profileId = loadedProfileId ?: return
        if (syncEnabled == enabled) return
        syncEnabled = enabled
        if (!enabled) syncedKeys = null
        persist(mutableState.value.connections, synchronized(lock) { tokens })
        mutableState.update { it.copy(syncEnabled = enabled) }
        if (enabled) mutableLocalChanges.tryEmit(profileId)
    }

    fun syncSnapshot(profileId: Int): ServerSyncSnapshot? {
        ensureLoaded()
        if (loadedProfileId != profileId || !syncEnabled) return null
        val currentTokens = synchronized(lock) { tokens }
        return ServerSyncSnapshot(
            profileId = profileId,
            version = localVersion,
            servers = mutableState.value.connections.filter { it.syncsToAccount }.mapNotNull { connection ->
                currentTokens[connection.credentialRef]?.let(connection::toSynced)
            },
            pendingPush = pendingPush,
            syncedKeys = syncedKeys
        )
    }

    fun applySync(snapshot: ServerSyncSnapshot, servers: List<SyncedServer>, keys: Set<String>): Boolean {
        if (!syncEnabled || loadedProfileId != snapshot.profileId || localVersion != snapshot.version) return false
        val current = mutableState.value.connections
        val currentByKey = current.associateBy { serverKey(it.providerId, it.remoteServerId, it.remoteUserId) }
        val currentTokens = synchronized(lock) { tokens }
        val usedIds = mutableSetOf<String>()
        val updatedTokens = mutableMapOf<String, String>()
        val connections = servers.map { server ->
            val local = currentByKey[server.key]
            val credentialRef = local?.credentialRef ?: newId("k")
            val id = local?.id ?: server.id.takeUnless { id -> current.any { it.id == id } || id in usedIds } ?: newId("c")
            usedIds += id
            updatedTokens[credentialRef] = server.token
            if (local != null && currentTokens[credentialRef] != server.token) setFailure(local.id, null)
            server.toConnection(id, credentialRef)
                .copy(
                    importContinueWatching = local?.importContinueWatching ?: true,
                    alternateAddress = local?.alternateAddress,
                    lastGoodAddress = local?.lastGoodAddress,
                    tlsPins = local?.tlsPins.orEmpty()
                )
        }
        val deviceOnly = current.filterNot { it.syncsToAccount || it.id in usedIds }
        deviceOnly.forEach { connection ->
            currentTokens[connection.credentialRef]?.let { updatedTokens[connection.credentialRef] = it }
        }
        val allConnections = connections + deviceOnly
        (current.map { it.id } - usedIds - deviceOnly.map { it.id }.toSet()).forEach { setFailure(it, null) }
        pendingPush = false
        syncedKeys = keys
        if (allConnections == current && updatedTokens == currentTokens) {
            persist(current, currentTokens)
        } else {
            save(allConnections, updatedTokens, local = false)
        }
        return true
    }

    fun markPushed(snapshot: ServerSyncSnapshot) {
        if (!syncEnabled || loadedProfileId != snapshot.profileId) return
        syncedKeys = snapshot.servers.mapTo(mutableSetOf()) { it.key }
        if (localVersion == snapshot.version) pendingPush = false
        persist(mutableState.value.connections, synchronized(lock) { tokens })
    }

    override fun removeProfile(profileId: Int) {
        runCatching { persistence.write(profileId, null) }
            .onFailure { Log.w(TAG, "Unable to remove server connections", it) }
        if (loadedProfileId == profileId) load(profileId)
    }

    override fun clearAllProfiles() {
        generation++
        synchronized(lock) { tokens = emptyMap() }
        loadedProfileId = null
        pendingPush = false
        syncedKeys = null
        syncEnabled = false
        runCatching { persistence.clear() }.onFailure { Log.w(TAG, "Unable to clear server storage", it) }
        mutableState.value = ServersUiState(revision = mutableState.value.revision + 1)
    }

    private fun load(profileId: Int) {
        generation++
        val stored = readStored(profileId)
        synchronized(lock) { tokens = stored.tokens }
        pendingPush = stored.pendingPush
        syncedKeys = stored.syncedKeys?.toSet()
        syncEnabled = stored.syncEnabled
        loadedProfileId = profileId
        mutableState.value = ServersUiState(
            connections = stored.connections,
            revision = mutableState.value.revision + 1,
            syncEnabled = stored.syncEnabled
        )
        publishTrust()
    }

    private fun publishTrust() = synchronized(trustLock) {
        val connections = mutableState.value.connections
        val pins = connections
            .flatMap { connection -> connection.tlsPins.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { it.value.toSet() }
        val hosts = connections
            .flatMap { listOfNotNull(it.address, it.alternateAddress) }
            .mapNotNull { it.toHttpUrlOrNull()?.host }
            .toSet()
        ServerTrust.update(pins, hosts)
    }

    private fun updateConnection(connectionId: String, transform: (ServerConnection) -> ServerConnection) {
        ensureLoaded()
        val connections = mutableState.value.connections.map { if (it.id == connectionId) transform(it) else it }
        save(connections, synchronized(lock) { tokens })
    }

    private fun save(connections: List<ServerConnection>, updatedTokens: Map<String, String>, local: Boolean = true) {
        val profileId = loadedProfileId ?: return
        val retained = updatedTokens.filterKeys { ref -> connections.any { it.credentialRef == ref } }
        if (local) {
            pendingPush = true
            localVersion++
        }
        persist(connections, retained)
        synchronized(lock) { tokens = retained }
        generation++
        mutableState.update { it.copy(connections = connections, revision = it.revision + 1) }
        publishTrust()
        if (local) mutableLocalChanges.tryEmit(profileId)
    }

    private fun persist(connections: List<ServerConnection>, currentTokens: Map<String, String>) {
        val profileId = loadedProfileId ?: return
        val stored = StoredServers(connections, currentTokens, pendingPush, syncedKeys?.toList(), syncEnabled)
        runCatching {
            persistence.write(profileId, json.encodeToString(StoredServers.serializer(), stored))
        }.onFailure { Log.w(TAG, "Unable to save server connections", it) }
    }

    private fun setFailure(connectionId: String, failure: ServerFailure?) {
        mutableState.update { state ->
            if (state.failures[connectionId] == failure) return@update state
            val failures = if (failure == null) state.failures - connectionId else state.failures + (connectionId to failure)
            state.copy(failures = failures)
        }
    }

    private fun readStored(profileId: Int): StoredServers =
        runCatching {
            persistence.read(profileId)?.let { json.decodeFromString(StoredServers.serializer(), it) }
        }.getOrNull() ?: StoredServers()

    private fun mergeLibraries(previous: List<ServerLibrary>, current: List<ServerLibrary>): List<ServerLibrary> {
        val selection = previous.associate { it.id to it.selected }
        return current.map { library -> library.copy(selected = selection[library.id] ?: true) }
    }

    private fun newId(prefix: String): String =
        prefix + (0 until 15).joinToString("") { Random.nextInt(16).toString(16) }

    private val ServerConnection.syncsToAccount: Boolean
        get() = providerId != SiloProvider.ID

    private class AddressChoice(val address: String, val atMs: Long)

    private companion object {
        const val TAG = "ServerRepository"
        const val ADDRESS_PROBE_MS = 5_000L
        const val ALTERNATE_CONNECT_TIMEOUT_MS = 4_000L
        const val FIRST_ADDRESS_RETRY_MS = 10 * 60_000L
    }
}
