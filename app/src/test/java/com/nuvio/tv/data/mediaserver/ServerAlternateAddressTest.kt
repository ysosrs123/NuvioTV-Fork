package com.nuvio.tv.data.mediaserver

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAlternateAddressTest {
    private val home = "https://fake.example"
    private val away = "https://away.example"
    private val provider = FakeServerProvider()
    private val server = fakeServerRepository(provider)
    private val repository = server.first
    private val connection = server.second

    private suspend fun withAlternate() {
        provider.locations[away] = ServerLocation(away, "server-1")
        assertEquals(AlternateAddressResult.SAVED, repository.setAlternateAddress(connection.id, away))
    }

    private suspend fun libraries() = repository.call(connection.id) { p, session -> p.libraries(session) }

    @Test
    fun movesToTheSecondAddressWhenTheFirstCannotBeReached() = runTest {
        withAlternate()
        provider.downAddresses += home

        assertEquals(2, libraries().size)
        assertEquals(listOf(home, away), provider.libraryCallAddresses)
        assertEquals(away, repository.session(connection.id)?.address)
        assertEquals(away, repository.connection(connection.id)?.lastGoodAddress)
        assertNull(repository.uiState.value.failures[connection.id])

        libraries()
        assertEquals(away, provider.libraryCallAddresses.last())
    }

    @Test
    fun aSecondAddressShortensTheWaitForADeadFirstAddress() = runTest {
        libraries()
        withAlternate()
        provider.downAddresses += home
        libraries()

        assertEquals(listOf(null, 4_000L, 4_000L), provider.libraryConnectLimits)
    }

    @Test
    fun neverSendsTheTokenToAnotherServer() = runTest {
        withAlternate()
        provider.locations[away] = ServerLocation(away, "someone-else")
        provider.downAddresses += home

        val error = runCatching { libraries() }.exceptionOrNull() as ServerException
        assertEquals(ServerFailure.UNREACHABLE, error.failure)
        assertEquals(listOf(home), provider.libraryCallAddresses)
        assertEquals(ServerFailure.UNREACHABLE, repository.uiState.value.failures[connection.id])
    }

    @Test
    fun aServerErrorDoesNotSwitchAddresses() = runTest {
        withAlternate()
        provider.failingWithStatus += home

        runCatching { libraries() }
        assertEquals(listOf(home), provider.libraryCallAddresses)
    }

    @Test
    fun bothAddressesDownTriesEachOnce() = runTest {
        withAlternate()
        provider.downAddresses += listOf(home, away)

        val error = runCatching { libraries() }.exceptionOrNull() as ServerException
        assertEquals(ServerFailure.UNREACHABLE, error.failure)
        assertEquals(listOf(home), provider.libraryCallAddresses)
    }

    @Test
    fun aNetworkChangeTriesTheFirstAddressFirstAgain() = runTest {
        withAlternate()
        provider.downAddresses += home
        libraries()
        provider.downAddresses.clear()

        repository.onNetworkChanged()

        assertEquals(home, repository.session(connection.id)?.address)
    }

    private var now = 0L

    private fun timedRepository(persistence: MemoryServerPersistence, timedProvider: FakeServerProvider) = ServerRepository(
        persistence, listOf(timedProvider), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        clock = { now }
    )

    @Test
    fun theSecondAddressDoesNotStickAcrossARestart() = runTest {
        val persistence = MemoryServerPersistence()
        val timedProvider = FakeServerProvider()
        val repo = timedRepository(persistence, timedProvider)
        repo.store(connection.copy(alternateAddress = away), token = "token")
        timedProvider.locations[away] = ServerLocation(away, "server-1")
        timedProvider.downAddresses += home
        repo.call(connection.id) { p, session -> p.libraries(session) }
        assertEquals(away, repo.session(connection.id)?.address)
        timedProvider.downAddresses.clear()

        val restarted = timedRepository(persistence, timedProvider)

        assertEquals(away, restarted.connection(connection.id)?.lastGoodAddress)
        assertEquals(home, restarted.session(connection.id)?.address)
    }

    @Test
    fun theFirstAddressIsTriedAgainAfterAWhileOnTheSecond() = runTest {
        val timedProvider = FakeServerProvider()
        val repo = timedRepository(MemoryServerPersistence(), timedProvider)
        repo.store(connection.copy(alternateAddress = away), token = "token")
        timedProvider.locations[away] = ServerLocation(away, "server-1")
        timedProvider.downAddresses += home
        suspend fun call() = repo.call(connection.id) { p, session -> p.libraries(session) }
        call()

        now += 9 * 60_000L
        call()
        assertEquals(listOf(home, away, away), timedProvider.libraryCallAddresses)

        now += 60_000L
        call()
        call()
        assertEquals(listOf(home, away, away, home, away, away), timedProvider.libraryCallAddresses)
        assertEquals(away, repo.session(connection.id)?.address)

        now += 10 * 60_000L
        timedProvider.downAddresses.clear()
        call()
        assertEquals(home, timedProvider.libraryCallAddresses.last())
        assertEquals(home, repo.session(connection.id)?.address)
    }

    @Test
    fun aCallCaughtByASettingsChangeFailsInsteadOfBeingCancelled() = runTest {
        val error = runCatching {
            repository.call(connection.id) { _, _ -> repository.setCatalogMetadata(connection.id, false) }
        }.exceptionOrNull() as ServerException

        assertEquals(ServerFailure.INCOMPLETE, error.failure)
        assertNull(repository.uiState.value.failures[connection.id])
    }

    @Test
    fun theCallThatLoadsTheProfileIsNotThrownAway() = runTest {
        val persistence = MemoryServerPersistence()
        val timedProvider = FakeServerProvider()
        timedRepository(persistence, timedProvider).store(connection, token = "token")

        val fresh = timedRepository(persistence, timedProvider)

        assertEquals(2, fresh.call(connection.id) { p, session -> p.libraries(session) }.size)
    }

    @Test
    fun neverMovesTheSignInToAPlainAddressOffTheCurrentNetwork() = runTest {
        val lan = "http://192.168.1.10:8096"
        val strictProvider = FakeServerProvider()
        val repo = ServerRepository(
            MemoryServerPersistence(), listOf(strictProvider), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            isOnCurrentNetwork = { false }
        )
        val conn = connection.copy(address = home, alternateAddress = lan)
        repo.store(conn, token = "token")
        strictProvider.locations[lan] = ServerLocation(lan, "server-1")
        strictProvider.downAddresses += home

        runCatching { repo.call(conn.id) { p, session -> p.libraries(session) } }
        assertEquals(listOf(home), strictProvider.libraryCallAddresses)

        val lanFirst = conn.copy(id = "c2", credentialRef = "k2", address = lan, alternateAddress = home)
        repo.store(lanFirst, token = "token")
        assertEquals(home, repo.session("c2")?.address)
        repo.onNetworkChanged()
        assertEquals(home, repo.session("c2")?.address)
    }

    @Test
    fun checksTheSecondAddressBeforeSavingIt() = runTest {
        provider.locations["https://other.example"] = ServerLocation("https://other.example", "server-2")
        provider.locations[home] = ServerLocation(home, "server-1")
        provider.locations["https://away.example/base"] = ServerLocation("https://away.example/base", "server-1")

        assertEquals(AlternateAddressResult.OTHER_SERVER, repository.setAlternateAddress(connection.id, "https://other.example"))
        assertEquals(AlternateAddressResult.UNREACHABLE, repository.setAlternateAddress(connection.id, "https://nowhere.example"))
        assertEquals(AlternateAddressResult.SAME_ADDRESS, repository.setAlternateAddress(connection.id, home))
        assertNull(repository.connection(connection.id)?.alternateAddress)

        assertEquals(AlternateAddressResult.SAVED, repository.setAlternateAddress(connection.id, "https://away.example/base"))
        assertEquals("https://away.example/base", repository.connection(connection.id)?.alternateAddress)

        assertEquals(AlternateAddressResult.CLEARED, repository.setAlternateAddress(connection.id, " "))
        assertNull(repository.connection(connection.id)?.alternateAddress)
    }

    @Test
    fun theSecondAddressStaysOnTheDeviceAcrossAccountSync() = runTest {
        withAlternate()
        repository.setSyncEnabled(true)
        val snapshot = repository.syncSnapshot(repository.currentProfileId)!!
        assertTrue(snapshot.servers.isNotEmpty())

        assertTrue(repository.applySync(snapshot, snapshot.servers, snapshot.servers.map { it.key }.toSet()))

        assertEquals(away, repository.connection(connection.id)?.alternateAddress)
    }
}
