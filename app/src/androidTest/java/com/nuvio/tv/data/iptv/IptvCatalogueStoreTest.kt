package com.nuvio.tv.data.iptv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.database.sqlite.SQLiteFullException
import com.nuvio.tv.core.iptv.ChannelCandidate
import com.nuvio.tv.core.iptv.GuideKey
import com.nuvio.tv.core.iptv.RefreshDecision
import java.io.IOException
import java.io.InterruptedIOException
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IptvCatalogueStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val suffix = UUID.randomUUID().toString()
    private val name = "iptv-test-$suffix.db"
    private val alias = "nuvio.iptv.tests.$suffix"
    private lateinit var realSecrets: AndroidIptvSecretBox
    private lateinit var secrets: FailingBox
    private lateinit var store: IptvCatalogueStore
    private val connection = IptvSourceConnection("https://fixture.invalid/list?token=UNIQUE_SOURCE_SECRET", "test-user", "UNIQUE_PASSWORD")

    @Before fun start() {
        realSecrets = AndroidIptvSecretBox(alias)
        secrets = FailingBox(realSecrets)
        store = IptvCatalogueStore(context, name, secrets)
    }
    @After fun cleanup() {
        store.close()
        context.deleteDatabase(name)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
    private fun source(profile: Int = 1) = store.createSource(profile, "Fixture", IptvSourceKind.M3U, "account-shared", connection).ref
    private fun row(id: Int, name: String = "Channel $id") = IptvCatalogueRecord(ChannelCandidate(name, "https://fixture.invalid/live/$id?token=UNIQUE_MEDIA_SECRET", providerId = id.toString(), guideId = "guide-$id"), mapOf("catchup-source" to "https://fixture.invalid/archive?secret=UNIQUE_ARCHIVE_SECRET"))
    private fun publish(ref: IptvSourceRef, rows: List<IptvCatalogueRecord> = listOf(row(1)), etag: String = "v1") =
        store.commitCatalogue(ref, store.beginRefresh(ref), rows, true, IptvCacheValidators(etag))

    @Test fun credentialsCatalogueAndOverlaysSurviveCloseAndReopenWithoutPlaintextSecrets() {
        val ref = source()
        assertEquals(RefreshDecision.PUBLISH, publish(ref))
        val original = store.snapshot(ref).channels.single().channel
        val overlay = IptvChannelOverlay("My station", 3, true, GuideKey("feed-2", "manual"))
        store.setOverlay(ref, original.id, overlay)
        store.close()
        store = IptvCatalogueStore(context, name, AndroidIptvSecretBox(alias))
        assertEquals(connection, store.connection(ref))
        val reopened = store.snapshot(ref)
        assertEquals(original, reopened.channels.single().channel)
        assertEquals(overlay, reopened.channels.single().overlay)
        assertEquals("v1", reopened.validators!!.etag)
        assertTrue(reopened.source.playbackEligible)
        store.close()
        val databaseBytes = context.getDatabasePath(name).readBytes().toString(Charsets.ISO_8859_1)
        listOf("UNIQUE_SOURCE_SECRET", "UNIQUE_PASSWORD", "UNIQUE_MEDIA_SECRET", "UNIQUE_ARCHIVE_SECRET").forEach { assertFalse(databaseBytes.contains(it)) }
    }

    @Test fun sourceAndProfileIdentityCannotCrossBindFavouritesOrRefreshes() {
        val first = source(1); val second = source(2)
        publish(first); publish(second)
        val firstId = store.snapshot(first).channels.single().channel.id
        assertNotEquals(firstId, store.snapshot(second).channels.single().channel.id)
        store.setOverlay(first, firstId, IptvChannelOverlay(favouriteRank = 0))
        assertNull(store.snapshot(second).channels.single().overlay.favouriteRank)
        assertThrows(IllegalArgumentException::class.java) { store.snapshot(IptvSourceRef(2, first.sourceId)) }
        assertThrows(IllegalArgumentException::class.java) { store.setOverlay(second, firstId, IptvChannelOverlay(hidden = true)) }
        assertEquals(1, store.sources(1).size)
    }

    @Test fun newestRequestWinsAndAReplayedTicketCannotOverwriteIt() {
        val ref = source(); publish(ref)
        val older = store.beginRefresh(ref); val newest = store.beginRefresh(ref)
        assertEquals(RefreshDecision.STALE, store.commitCatalogue(ref, older, listOf(row(9)), true))
        assertEquals(RefreshDecision.PUBLISH, store.commitCatalogue(ref, newest, listOf(row(2)), true))
        assertEquals(RefreshDecision.STALE, store.commitCatalogue(ref, newest, listOf(row(3)), true))
        assertEquals("2", store.snapshot(ref).channels.single { it.channel.available }.channel.data.providerId)
    }

    @Test fun generationFenceIsSharedAcrossSeparateDatabaseHandles() {
        val ref = source(); publish(ref)
        val stale = store.beginRefresh(ref)
        IptvCatalogueStore(context, name, AndroidIptvSecretBox(alias)).use { second ->
            val newest = second.beginRefresh(ref)
            assertEquals(RefreshDecision.STALE, store.commitCatalogue(ref, stale, listOf(row(8)), true))
            assertEquals(RefreshDecision.PUBLISH, second.commitCatalogue(ref, newest, listOf(row(9)), true))
            assertEquals("9", store.snapshot(ref).channels.single { it.channel.available }.channel.data.providerId)
        }
    }

    @Test fun editingCredentialsInvalidatesAnInFlightImportAndConditionalCache() {
        val ref = source(); publish(ref)
        val request = store.prepareRefresh(ref)
        assertEquals(connection, request.connection)
        assertEquals("v1", request.validators!!.etag)
        store.editSource(ref, "Edited", IptvSourceKind.M3U, "account-shared", connection.copy(password = "new"))
        assertEquals(RefreshDecision.STALE, store.commitCatalogue(ref, request.ticket, listOf(row(2)), true))
        assertFalse(store.acceptNotModified(ref, request.ticket))
        val old = store.snapshot(ref)
        assertFalse(old.source.playbackEligible)
        assertEquals(1, old.channels.size)
        assertNull(old.validators)
        assertNull(store.prepareRefresh(ref).validators)
    }

    @Test fun renamingASourceDoesNotDisablePlaybackOrInvalidateARefresh() {
        val ref = source(); publish(ref)
        val request = store.prepareRefresh(ref)
        val edited = store.editSource(ref, "New display name", IptvSourceKind.M3U, "account-shared", connection)
        assertEquals(request.ticket.configurationVersion, edited.configurationVersion)
        assertTrue(edited.playbackEligible)
        assertTrue(store.acceptNotModified(ref, request.ticket))
        assertEquals("v1", store.snapshot(ref).validators!!.etag)
        assertEquals("New display name", store.snapshot(ref).source.label)
    }

    @Test fun partialConflictingAndEmptyImportsPreserveLastGoodValidatorsAndRows() {
        val ref = source(); publish(ref)
        val before = store.snapshot(ref)
        assertEquals(RefreshDecision.INVALID, store.commitCatalogue(ref, store.beginRefresh(ref), listOf(row(2)), false, IptvCacheValidators("bad")))
        assertEquals(RefreshDecision.INVALID, store.commitCatalogue(ref, store.beginRefresh(ref), listOf(row(1), row(1, "conflict")), true))
        assertEquals(RefreshDecision.EMPTY_REQUIRES_REVIEW, store.commitCatalogue(ref, store.beginRefresh(ref), emptyList(), true))
        val after = store.snapshot(ref)
        assertEquals(before.channels, after.channels)
        assertEquals(before.validators, after.validators)
        assertEquals(before.source.activeGeneration, after.source.activeGeneration)
    }

    @Test fun reviewedRemovalKeepsTombstoneAndOverlayThenReappearanceRestoresIdentity() {
        val ref = source(); publish(ref, (1..4).map { row(it) })
        val saved = store.snapshot(ref).channels.single { it.channel.data.providerId == "2" }.channel.id
        store.setOverlay(ref, saved, IptvChannelOverlay(favouriteRank = 2))
        val ticket = store.beginRefresh(ref)
        assertEquals(RefreshDecision.SHRINK_REQUIRES_REVIEW, store.commitCatalogue(ref, ticket, listOf(row(1)), true))
        assertEquals(RefreshDecision.PUBLISH, store.commitCatalogue(ref, ticket, listOf(row(1)), true, acceptedLargeChange = true))
        assertFalse(store.snapshot(ref).channels.single { it.channel.id == saved }.channel.available)
        assertEquals(RefreshDecision.PUBLISH, publish(ref, listOf(row(1), row(2, "Renamed"))))
        val restored = store.snapshot(ref).channels.single { it.channel.id == saved }
        assertTrue(restored.channel.available)
        assertEquals(2, restored.overlay.favouriteRank)
        assertEquals("Renamed", restored.channel.data.name)
    }

    @Test fun protectedStorageFailureAfterSomeRowsRollsBackWholeGeneration() {
        val ref = source(); publish(ref)
        val before = store.snapshot(ref)
        val ticket = store.beginRefresh(ref)
        secrets.sealsBeforeFailure = 1
        assertThrows(IOException::class.java) { store.commitCatalogue(ref, ticket, listOf(row(2), row(3)), true, IptvCacheValidators("bad")) }
        secrets.sealsBeforeFailure = null
        store.close(); store = IptvCatalogueStore(context, name, secrets)
        assertEquals(before.channels, store.snapshot(ref).channels)
        assertEquals(before.validators, store.snapshot(ref).validators)
        assertEquals(RefreshDecision.PUBLISH, store.commitCatalogue(ref, ticket, listOf(row(2), row(3)), true))
    }

    @Test fun cancellationDuringRowInsertionRollsBackWithoutChangingValidators() {
        val ref = source(); publish(ref)
        val before = store.snapshot(ref)
        val ticket = store.beginRefresh(ref)
        var checks = 0
        assertThrows(InterruptedIOException::class.java) {
            store.commitCatalogue(ref, ticket, listOf(row(2), row(3)), true, IptvCacheValidators("bad")) {
                if (++checks == 4) throw InterruptedIOException("fixture cancellation")
            }
        }
        assertEquals(before.channels, store.snapshot(ref).channels)
        assertEquals(before.validators, store.snapshot(ref).validators)
    }

    @Test fun realSqliteCapacityFailureKeepsLastGoodGenerationAndAllowsRetry() {
        val ref = source(); publish(ref)
        val before = store.snapshot(ref)
        val ticket = store.beginRefresh(ref)
        store.close()
        store = IptvCatalogueStore(context, name, secrets, maxDatabaseBytes = 128L * 1024)
        val oversized = (1..100).map { row(it, "Channel $it " + "x".repeat(3000)) }
        assertThrows(SQLiteFullException::class.java) { store.commitCatalogue(ref, ticket, oversized, true, IptvCacheValidators("bad")) }
        assertEquals(before.channels, store.snapshot(ref).channels)
        assertEquals(before.validators, store.snapshot(ref).validators)
        store.close()
        store = IptvCatalogueStore(context, name, secrets)
        assertEquals(RefreshDecision.PUBLISH, store.commitCatalogue(ref, ticket, oversized, true))
        assertEquals(100, store.snapshot(ref).channels.count { it.channel.available })
    }

    @Test fun authenticatedEncryptionRejectsWrongProfileAndTamperingWithoutErasingData() {
        val blob = realSecrets.seal("profile:one", "SECRET")
        assertEquals("SECRET", realSecrets.open("profile:one", blob))
        assertThrows(IOException::class.java) { realSecrets.open("profile:two", blob) }
        assertThrows(IOException::class.java) { realSecrets.open("profile:one", blob.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }) }
        assertEquals("SECRET", realSecrets.open("profile:one", blob))
    }

    @Test fun notModifiedNeedsLiveCacheAndLatestConfigurationTicket() {
        val ref = source()
        assertFalse(store.acceptNotModified(ref, store.beginRefresh(ref)))
        publish(ref)
        val ticket = store.beginRefresh(ref)
        assertTrue(store.acceptNotModified(ref, ticket))
        store.beginRefresh(ref)
        assertFalse(store.acceptNotModified(ref, ticket))
    }

    private class FailingBox(private val delegate: IptvSecretBox) : IptvSecretBox {
        var sealsBeforeFailure: Int? = null
        override fun seal(context: String, plaintext: String): ByteArray {
            sealsBeforeFailure?.let { if (it == 0) throw IOException("simulated protected write failure"); sealsBeforeFailure = it - 1 }
            return delegate.seal(context, plaintext)
        }
        override fun open(context: String, ciphertext: ByteArray) = delegate.open(context, ciphertext)
    }
}
