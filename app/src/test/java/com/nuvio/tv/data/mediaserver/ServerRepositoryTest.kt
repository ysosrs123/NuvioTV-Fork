package com.nuvio.tv.data.mediaserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRepositoryTest {
    private val persistence = MemoryServerPersistence()
    private val repository = ServerRepository(
        persistence = persistence,
        providers = emptyList(),
        scope = CoroutineScope(Dispatchers.Unconfined)
    )

    private fun connection(id: String, ref: String) = ServerConnection(
        id = id,
        providerId = "jellyfin",
        name = "Home",
        address = "http://192.168.1.10:8096",
        remoteServerId = "s1",
        remoteUserId = "u1",
        userName = "viewer",
        credentialRef = ref
    )

    @Test
    fun aCodeSignInForAKnownServerUserReplacesItsToken() = runTest {
        val provider = FakeServerProvider()
        val (repository, connection) = fakeServerRepository(provider)
        val ticket = ServerQuickConnect(
            address = "https://fake.example",
            serverName = "Box",
            serverId = "server-1",
            code = "123456",
            secret = "secret",
            expiresAtMs = Long.MAX_VALUE
        )

        val signedIn = repository.connectWithQuickConnect(provider, ticket)

        assertEquals(connection.id, signedIn.id)
        assertEquals(1, repository.uiState.value.connections.size)
        assertEquals("code-token", repository.session(connection.id)?.token)
    }

    @Test
    fun streamTestsGetTheSignInOnlyForLinksOnAConnectedServer() = runTest {
        val (repository, connection) = fakeServerRepository()

        assertEquals(mapOf("X-Test-Token" to "token"), repository.authHeadersFor("https://FAKE.example:443/Videos/42/stream?static=true"))
        assertEquals(emptyMap<String, String>(), repository.authHeadersFor("http://fake.example/Videos/42/stream"))
        assertEquals(emptyMap<String, String>(), repository.authHeadersFor("https://fake.example.evil.test/Videos/42/stream"))
        assertEquals(emptyMap<String, String>(), repository.authHeadersFor("not a link"))
        assertEquals(emptyMap<String, String>(), repository.authHeadersFor(null))

        repository.setEnabled(connection.id, false)
        assertEquals(emptyMap<String, String>(), repository.authHeadersFor("https://fake.example/Videos/42/stream"))
    }

    @Test
    fun newServersUseAddonMetadataAndASignInAgainKeepsTheChoice() = runTest {
        val provider = FakeServerProvider()
        val fresh = ServerRepository(MemoryServerPersistence(), listOf(provider), CoroutineScope(Dispatchers.Unconfined))

        val added = fresh.connect(provider, "https://fake.example", "viewer", "pw")
        assertTrue(added.useCatalogMetadata)
        assertTrue(fresh.connection(added.id)!!.useCatalogMetadata)

        val (existing, connection) = fakeServerRepository(provider)
        assertFalse(connection.useCatalogMetadata)
        val signedInAgain = existing.connect(provider, "https://fake.example", "viewer", "pw")
        assertEquals(connection.id, signedInAgain.id)
        assertFalse(existing.connection(connection.id)!!.useCatalogMetadata)
    }

    @Test
    fun anAcceptedCertificateIsStoredWithTheServerAndKeptOffTheAccount() = runTest {
        val provider = FakeServerProvider()
        val (repository, connection) = fakeServerRepository(provider)
        val fingerprint = "ab".repeat(32)

        repository.connect(provider, "https://fake.example", "viewer", "pw", acceptedCertificate = "fake.example" to fingerprint)

        assertEquals(mapOf("fake.example" to fingerprint), repository.connection(connection.id)?.tlsPins)
        assertTrue(com.nuvio.tv.core.network.ServerTrust.isServerHost("fake.example"))
        repository.setSyncEnabled(true)
        val snapshot = repository.syncSnapshot(repository.currentProfileId)!!
        assertTrue(repository.applySync(snapshot, snapshot.servers, snapshot.servers.map { it.key }.toSet()))
        assertEquals(mapOf("fake.example" to fingerprint), repository.connection(connection.id)?.tlsPins)

        repository.forgetCertificates(connection.id)
        assertTrue(repository.connection(connection.id)?.tlsPins.orEmpty().isEmpty())
    }

    @Test
    fun siloServersStayOnTheDeviceThroughAccountSync() = runTest {
        val jellyfin = FakeServerProvider()
        val (repository, connection) = fakeServerRepository(jellyfin)
        val silo = connection.copy(id = "csilo", providerId = "silo", remoteServerId = "silo-1", credentialRef = "ksilo")
        repository.store(silo, "silo-token")
        repository.setSyncEnabled(true)

        val snapshot = repository.syncSnapshot(repository.currentProfileId)!!
        assertEquals(listOf(connection.id), snapshot.servers.map { it.id })

        assertTrue(repository.applySync(snapshot, emptyList(), emptySet()))
        assertEquals(listOf("csilo"), repository.uiState.value.connections.map { it.id })
        assertEquals("silo-token", repository.session("csilo")?.token)
    }

    @Test
    fun keepsConnectionsAndTokensPerProfile() {
        repository.store(connection("c1", "k1"), "token-one")
        assertEquals("token-one", repository.session("c1")?.token)

        repository.selectProfile(2)
        assertNull(repository.connection("c1"))
        repository.store(connection("c2", "k2"), "token-two")

        repository.selectProfile(1)
        assertEquals(listOf("c1"), repository.uiState.value.connections.map { it.id })
        assertEquals("token-one", repository.session("c1")?.token)
        assertFalse(persistence.values.getValue(1).contains("token-two"))
    }

    @Test
    fun removingAConnectionDropsItsToken() {
        repository.store(connection("c1", "k1"), "token-one")
        repository.remove("c1")

        assertNull(repository.session("c1"))
        assertFalse(persistence.values.getValue(1).contains("token-one"))
    }

    @Test
    fun replacingACredentialKeepsOnlyTheNewToken() {
        repository.store(connection("c1", "k1"), "old")
        repository.store(connection("c1", "k2"), "new", replacedCredential = "k1")

        assertEquals("new", repository.session("c1")?.token)
        assertFalse(persistence.values.getValue(1).contains("old"))
    }

    @Test
    fun profileRemovalAndAccountResetClearStoredServers() {
        repository.store(connection("c1", "k1"), "token-one")
        repository.selectProfile(2)
        repository.store(connection("c2", "k2"), "token-two")

        repository.removeProfile(1)
        assertFalse(persistence.values.containsKey(1))
        assertTrue(persistence.values.containsKey(2))

        repository.clearAllProfiles()
        assertTrue(persistence.values.isEmpty())
        assertTrue(repository.uiState.value.connections.isEmpty())
    }

    @Test
    fun accountSyncIsOffByDefaultAndRememberedPerProfile() {
        repository.store(connection("c1", "k1"), "token-one")
        assertFalse(repository.uiState.value.syncEnabled)
        assertNull(repository.syncSnapshot(1))

        repository.setSyncEnabled(true)
        assertTrue(repository.uiState.value.syncEnabled)
        assertEquals(listOf("token-one"), repository.syncSnapshot(1)?.servers?.map { it.token })

        repository.selectProfile(2)
        assertFalse(repository.uiState.value.syncEnabled)
        assertNull(repository.syncSnapshot(2))

        val reloaded = ServerRepository(persistence, emptyList(), CoroutineScope(Dispatchers.Unconfined))
        reloaded.ensureLoaded()
        assertTrue(reloaded.uiState.value.syncEnabled)

        reloaded.clearAllProfiles()
        reloaded.ensureLoaded()
        assertFalse(reloaded.uiState.value.syncEnabled)
    }

    @Test
    fun syncedListIsNotAppliedWhileSyncIsOff() {
        repository.store(connection("c1", "k1"), "token-one")
        repository.setSyncEnabled(true)
        val snapshot = repository.syncSnapshot(1)!!
        repository.setSyncEnabled(false)

        assertFalse(repository.applySync(snapshot, emptyList(), emptySet()))
        assertEquals(listOf("c1"), repository.uiState.value.connections.map { it.id })
        assertEquals("token-one", repository.session("c1")?.token)
    }

    @Test
    fun continueWatchingImportIsOnByDefaultAndStaysOnTheDevice() {
        repository.store(connection("c1", "k1"), "token-one")
        assertTrue(repository.connection("c1")!!.importContinueWatching)

        repository.setImportContinueWatching("c1", false)
        repository.setSyncEnabled(true)
        val snapshot = repository.syncSnapshot(1)!!

        assertTrue(repository.applySync(snapshot, snapshot.servers.map { it.copy(name = "Renamed") }, snapshot.servers.map { it.key }.toSet()))
        val synced = repository.connection("c1")!!
        assertEquals("Renamed", synced.name)
        assertFalse(synced.importContinueWatching)

        val reloaded = ServerRepository(persistence, emptyList(), CoroutineScope(Dispatchers.Unconfined))
        assertFalse(reloaded.connection("c1")!!.importContinueWatching)
    }

    @Test
    fun disabledServersHaveNoSession() {
        repository.store(connection("c1", "k1"), "token-one")
        repository.setEnabled("c1", false)

        assertNull(repository.session("c1"))
        assertTrue(repository.enabledConnections().isEmpty())
    }

    @Test
    fun callsReportMissingAndDisabledServers() = runTest {
        repository.store(connection("c1", "k1"), "token-one")
        repository.setEnabled("c1", false)

        val missing = runCatching { repository.call("nope") { _, _ -> 1 } }.exceptionOrNull() as ServerException
        val disabled = runCatching { repository.call("c1") { _, _ -> 1 } }.exceptionOrNull() as ServerException

        assertEquals(ServerFailure.NOT_FOUND, missing.failure)
        assertEquals(ServerFailure.UNREACHABLE, disabled.failure)
    }
}
