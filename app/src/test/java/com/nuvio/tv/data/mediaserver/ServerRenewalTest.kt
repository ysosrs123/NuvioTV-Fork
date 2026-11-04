package com.nuvio.tv.data.mediaserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ServerRenewalTest {
    private class RenewingProvider : ServerProvider {
        override val id: String = "renewing"
        override val displayName: String = "Renewing"
        override val capabilities: Set<ServerCapability> = emptySet()
        override val minimumVersion: String = ""

        var current = "t1"
        var dueSoon = false
        var signedOut = false
        var unavailable = false
        var renewals = 0
        val seenTokens = mutableListOf<String>()

        override suspend fun libraries(session: ServerSession): List<ServerLibrary> {
            seenTokens += session.token
            if (session.token != current) throw ServerException(ServerFailure.AUTH_REQUIRED, code = "invalid_token")
            return listOf(ServerLibrary("1", "Films", ServerMediaKind.MOVIE))
        }

        override fun renewalDue(session: ServerSession): Boolean = dueSoon && session.token != current

        override suspend fun renewCredential(session: ServerSession, failure: ServerException?): String? {
            renewals++
            delay(10)
            if (signedOut) throw ServerException(ServerFailure.AUTH_REQUIRED, code = "session_expired")
            if (unavailable) throw ServerException(ServerFailure.UNREACHABLE)
            return current
        }

        override suspend fun libraryPage(
            session: ServerSession,
            library: ServerLibrary,
            start: Int,
            limit: Int,
            sort: ServerSort,
            genreId: String?,
            filter: ServerLibraryFilter
        ) =
            throw ServerException(ServerFailure.UNSUPPORTED)

        override suspend fun details(session: ServerSession, itemId: String) = throw ServerException(ServerFailure.UNSUPPORTED)

        override suspend fun candidates(session: ServerSession, itemId: String) = throw ServerException(ServerFailure.UNSUPPORTED)

        override suspend fun preparePlayback(session: ServerSession, request: ServerPlaybackRequest) =
            throw ServerException(ServerFailure.UNSUPPORTED)
    }

    private val persistence = MemoryServerPersistence()
    private val provider = RenewingProvider()
    private val repository = ServerRepository(persistence, listOf(provider), CoroutineScope(Dispatchers.Unconfined))
    private val connection = ServerConnection(
        id = "c1",
        providerId = provider.id,
        name = "Silo",
        address = "https://silo.example",
        remoteServerId = "srv",
        remoteUserId = "u1",
        userName = "laura",
        credentialRef = "k1"
    )

    private suspend fun libraries() = repository.call(connection.id) { p, s -> p.libraries(s) }

    @Test
    fun anExpiredTokenIsRenewedOnceAndTheCallRepeated() = runTest {
        repository.store(connection, "t1")
        provider.current = "t2"

        assertEquals(1, libraries().size)

        assertEquals(listOf("t1", "t2"), provider.seenTokens)
        assertEquals(1, provider.renewals)
        assertEquals("t2", repository.session(connection.id)?.token)
        assertTrue(persistence.values.values.single().contains("t2"))
    }

    @Test
    fun callsThatFailTogetherShareOneRenewal() = runTest {
        repository.store(connection, "t1")
        provider.current = "t2"

        List(4) { async { libraries() } }.awaitAll()

        assertEquals(1, provider.renewals)
        assertEquals("t2", repository.session(connection.id)?.token)
    }

    @Test
    fun aTokenAboutToRunOutIsRenewedBeforeTheCall() = runTest {
        repository.store(connection, "t1")
        provider.current = "t2"
        provider.dueSoon = true

        libraries()

        assertEquals(listOf("t2"), provider.seenTokens)
    }

    @Test
    fun aRefusedRenewalShowsSignInAgainAndKeepsTheOldToken() = runTest {
        repository.store(connection, "t1")
        provider.current = "t2"
        provider.signedOut = true

        try {
            libraries()
            fail("expected sign-in needed")
        } catch (error: ServerException) {
            assertEquals(ServerFailure.AUTH_REQUIRED, error.failure)
        }
        assertEquals(ServerFailure.AUTH_REQUIRED, repository.uiState.value.failures[connection.id])
        assertEquals("t1", repository.session(connection.id)?.token)
    }

    @Test
    fun anUnavailableServerDuringRenewalIsNotASignOut() = runTest {
        repository.store(connection, "t1")
        provider.current = "t2"
        provider.unavailable = true

        try {
            libraries()
            fail("expected unreachable")
        } catch (error: ServerException) {
            assertEquals(ServerFailure.UNREACHABLE, error.failure)
        }
        assertEquals("t1", repository.session(connection.id)?.token)
    }

    @Test
    fun serversThatCannotRenewFailAsBefore() = runTest {
        val plain = FakeServerProvider()
        val (fake, saved) = fakeServerRepository(plain)

        val renewed = plain.renewCredential(fake.session(saved.id)!!, ServerException(ServerFailure.AUTH_REQUIRED))

        assertNull(renewed)
    }

    @Test
    fun switchingASiloServerKeepsItsNameAndLibraryChoicesAndRemovesTheOldOne() = runTest {
        val old = connection.copy(
            id = "old",
            providerId = "fake",
            name = "Popcorn",
            displayName = null,
            credentialRef = "kold",
            libraries = listOf(
                ServerLibrary("jf-1", "Films", ServerMediaKind.MOVIE, selected = false),
                ServerLibrary("jf-2", "Shows", ServerMediaKind.SERIES, selected = true)
            ),
            useCatalogMetadata = false
        )
        repository.store(old, "old-token")

        val signedIn = repository.connectWith(provider, replacing = old.id) {
            ServerSignIn("https://silo.example", "Silo", "srv", "u1", "laura", "t1")
        }

        val connections = repository.uiState.value.connections
        assertEquals(listOf(signedIn.id), connections.map { it.id })
        assertEquals("Popcorn", signedIn.shownName)
        assertEquals(listOf("Films" to false), signedIn.libraries.map { it.name to it.selected })
        assertEquals(false, signedIn.useCatalogMetadata)
        assertEquals("t1", repository.session(signedIn.id)?.token)
    }
}
