package com.nuvio.tv.data.iptv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.iptv.ChannelCandidate
import com.nuvio.tv.core.iptv.GuideKey
import com.nuvio.tv.core.iptv.GuideMatchReason
import com.nuvio.tv.core.iptv.RefreshDecision
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IptvBrowseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val suffix = UUID.randomUUID().toString()
    private val name = "browse-$suffix.db"; private val guideName = "browse-guide-$suffix.db"; private val alias = "browse.$suffix"
    private lateinit var catalogue: IptvCatalogueStore
    private lateinit var guides: IptvGuideStore
    private lateinit var secrets: CountingBox
    private val connection = IptvSourceConnection("https://fixture.invalid/list")
    @Before fun setup() {
        secrets = CountingBox(AndroidIptvSecretBox(alias))
        catalogue = IptvCatalogueStore(context, name, secrets)
        guides = IptvGuideStore(context, guideName, secrets)
    }
    @After fun cleanup() {
        catalogue.close(); guides.close(); context.deleteDatabase(name); context.deleteDatabase(guideName)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
    private fun source(profile: Int = 1) = catalogue.createSource(profile, "Fixture", IptvSourceKind.M3U, "shared", connection).ref
    private fun row(id: Int, label: String = "Channel ${id.toString().padStart(4, '0')}", guide: String = "one") =
        IptvCatalogueRecord(ChannelCandidate(label, "https://fixture.invalid/$id", providerId = id.toString(), guideId = guide))
    private fun publish(ref: IptvSourceRef, rows: List<IptvCatalogueRecord>) =
        catalogue.commitCatalogue(ref, catalogue.beginRefresh(ref), rows, true, acceptedLargeChange = true)
    private fun feed(profile: Int = 1, externalId: String = "one"): IptvGuideRef {
        val ref = guides.createFeed(profile, "Guide", "https://fixture.invalid/guide")
        val from = Instant.parse("2026-10-05T00:00:00Z").toEpochMilli()
        val xml = "<tv><channel id=\"$externalId\"><display-name>One</display-name></channel><programme channel=\"$externalId\" start=\"20261005000000 +0000\" stop=\"20261005010000 +0000\"><title>Fixture</title></programme></tv>"
        assertEquals(RefreshDecision.PUBLISH, guides.importGuide(guides.beginRefresh(ref), xml.byteInputStream(), IptvGuideWindow(from, from + 86400000)))
        return ref
    }
    @Test fun pagingDecryptsOnlyTheBoundedPageAndVisitsEachIdentityOnce() {
        val ref = source(); publish(ref, (1..401).map { row(it) })
        secrets.opens = 0
        val first = catalogue.page(ref, limit = 10)
        assertEquals(11, secrets.opens)
        assertEquals(10, first.items.size); assertNotNull(first.next)
        val ids = mutableListOf<String>(); var cursor: IptvBrowseCursor? = null
        do {
            val page = catalogue.page(ref, cursor = cursor, limit = 73)
            ids += page.items.map { it.channel.id }; cursor = page.next
        } while (cursor != null)
        assertEquals(401, ids.size); assertEquals(ids.size, ids.distinct().size)
    }
    @Test fun unicodeCustomNamesAndSearchMetacharactersAreLiteral() {
        val ref = source(); publish(ref, listOf(row(1, "НОВОСТИ"), row(2, "東京"), row(3, "ＡＢＣ"), row(4, "100%_News")))
        assertEquals("НОВОСТИ", catalogue.page(ref, IptvBrowseQuery("новости")).items.single().channel.data.name)
        assertEquals("東京", catalogue.page(ref, IptvBrowseQuery("東京")).items.single().channel.data.name)
        assertEquals("ＡＢＣ", catalogue.page(ref, IptvBrowseQuery("abc")).items.single().channel.data.name)
        assertEquals("100%_News", catalogue.page(ref, IptvBrowseQuery("%_")).items.single().channel.data.name)
        val id = catalogue.page(ref, IptvBrowseQuery("abc")).items.single().channel.id
        catalogue.setOverlay(ref, id, IptvChannelOverlay(customName = "MÜNCHEN"))
        assertTrue(catalogue.page(ref, IptvBrowseQuery("abc")).items.isEmpty())
        assertEquals(id, catalogue.page(ref, IptvBrowseQuery("münchen")).items.single().channel.id)
    }
    @Test fun favouritesHiddenAndUnavailableFiltersStayIndependent() {
        val ref = source(); publish(ref, listOf(row(1), row(2), row(3)))
        val items = catalogue.page(ref).items
        catalogue.setOverlay(ref, items[0].channel.id, IptvChannelOverlay(favouriteRank = 9))
        catalogue.setOverlay(ref, items[1].channel.id, IptvChannelOverlay(favouriteRank = 0, hidden = true))
        assertEquals(listOf(items[0].channel.id), catalogue.page(ref, IptvBrowseQuery(favouritesOnly = true)).items.map { it.channel.id })
        assertEquals(listOf(items[1].channel.id, items[0].channel.id), catalogue.page(ref, IptvBrowseQuery(favouritesOnly = true, includeHidden = true)).items.map { it.channel.id })
        publish(ref, listOf(row(3)))
        assertTrue(catalogue.page(ref, IptvBrowseQuery(favouritesOnly = true)).items.isEmpty())
        assertEquals(2, catalogue.page(ref, IptvBrowseQuery(favouritesOnly = true, includeHidden = true, includeUnavailable = true)).items.size)
    }
    @Test fun refreshOverlayAndQueryChangesInvalidateOldPagingCursors() {
        val ref = source(); publish(ref, listOf(row(1), row(2)))
        val first = catalogue.page(ref, limit = 1)
        assertThrows(IptvCatalogueChangedException::class.java) { catalogue.page(ref, IptvBrowseQuery("Channel"), first.next) }
        catalogue.setOverlay(ref, first.items.single().channel.id, IptvChannelOverlay(customName = "Z"))
        assertThrows(IptvCatalogueChangedException::class.java) { catalogue.page(ref, cursor = first.next) }
        val next = catalogue.page(ref, limit = 1)
        publish(ref, listOf(row(1), row(2), row(3)))
        assertThrows(IptvCatalogueChangedException::class.java) { catalogue.page(ref, cursor = next.next) }
    }
    @Test fun playbackLookupRechecksVisibilityAvailabilityAndEditedCredentials() {
        val ref = source(); publish(ref, listOf(row(1), row(2)))
        val id = catalogue.page(ref).items.first().channel.id
        assertNotNull(catalogue.playbackItem(ref, id))
        catalogue.setOverlay(ref, id, IptvChannelOverlay(hidden = true)); assertNull(catalogue.playbackItem(ref, id))
        catalogue.setOverlay(ref, id, IptvChannelOverlay()); publish(ref, listOf(row(2))); assertNull(catalogue.playbackItem(ref, id))
        val active = catalogue.page(ref).items.single().channel.id
        catalogue.editSource(ref, "Edited", IptvSourceKind.M3U, "shared", IptvSourceConnection("https://fixture.invalid/new"))
        assertNull(catalogue.playbackItem(ref, active)); assertFalse(catalogue.page(ref).source.playbackEligible)
    }
    @Test fun sourceProfileAndCursorCannotCrossBindChannels() {
        val one = source(); val two = source(2)
        publish(one, listOf(row(1), row(2))); publish(two, listOf(row(1)))
        val page = catalogue.page(one, limit = 1)
        assertThrows(IllegalArgumentException::class.java) { catalogue.page(IptvSourceRef(2, one.sourceId)) }
        assertThrows(IptvCatalogueChangedException::class.java) { catalogue.page(two, cursor = page.next) }
        assertNull(catalogue.playbackItem(two, page.items.single().channel.id))
    }
    @Test fun editedSourceHasNoComparableOldEndpointShrinkBaseline() {
        val ref = source(); publish(ref, (1..4).map { row(it) })
        catalogue.editSource(ref, "Different endpoint", IptvSourceKind.M3U, "shared", IptvSourceConnection("https://fixture.invalid/new"))
        assertEquals(RefreshDecision.PUBLISH, catalogue.commitCatalogue(ref, catalogue.beginRefresh(ref), listOf(row(9)), true))
        assertEquals("9", catalogue.page(ref).items.single().channel.data.providerId)
        assertNotNull(catalogue.playbackItem(ref, catalogue.page(ref).items.single().channel.id))
    }
    @Test fun unmatchedChannelsUseAUniqueNormalisedGuideName() = runBlocking {
        val ref = source()
        publish(ref, listOf(IptvCatalogueRecord(ChannelCandidate("UK: One HD", "https://fixture.invalid/1", providerId = "1")),
            IptvCatalogueRecord(ChannelCandidate("Two", "https://fixture.invalid/2", providerId = "2"))))
        val a = feed(externalId = "one.uk"); val repo = IptvBrowseRepository(catalogue, guides)
        repo.setGuideFeeds(ref, listOf(a))
        val rows = repo.page(ref).channels.associateBy { it.item.channel.data.name }
        assertEquals(GuideKey(a.feedId, "one.uk"), rows.getValue("UK: One HD").guide.key)
        assertEquals(GuideMatchReason.NAME, rows.getValue("UK: One HD").guide.reason)
        assertEquals(GuideMatchReason.NONE, rows.getValue("Two").guide.reason)
        assertEquals(listOf("one.uk"), guides.searchChannels(a, "on").map { it.externalId })
    }
    @Test fun linkedOrderDecidesBetweenFeedsUntilPriorityOrManualMappingIsExplicit() = runBlocking {
        val ref = source(); publish(ref, listOf(row(1)))
        val a = feed(); val b = feed(); val repo = IptvBrowseRepository(catalogue, guides)
        repo.setGuideFeeds(ref, listOf(a, b))
        assertEquals(a.feedId, repo.page(ref).channels.single().guide.key!!.feedId)
        assertEquals(GuideMatchReason.EXACT_ID, repo.page(ref).channels.single().guide.reason)
        repo.setGuideFeeds(ref, listOf(a, b), listOf(b))
        assertEquals(b.feedId, repo.page(ref).channels.single().guide.key!!.feedId)
        val id = catalogue.page(ref).items.single().channel.id
        catalogue.setOverlay(ref, id, IptvChannelOverlay(manualGuide = GuideKey(a.feedId, "one")))
        assertEquals(a.feedId, repo.page(ref).channels.single().guide.key!!.feedId)
        catalogue.setOverlay(ref, id, IptvChannelOverlay(manualGuide = GuideKey(a.feedId, "missing")))
        assertEquals(GuideMatchReason.MISSING_MANUAL_TARGET, repo.page(ref).channels.single().guide.reason)
    }
    @Test fun associationsSurviveReopenAndAnUnlinkedManualTargetStaysMissing() = runBlocking {
        val ref = source(); publish(ref, listOf(row(1), row(2)))
        val a = feed(); val b = feed()
        catalogue.setGuideFeeds(ref, listOf(a, b), listOf(b, a))
        catalogue.close(); catalogue = IptvCatalogueStore(context, name, secrets)
        assertEquals(IptvGuideAssociations(listOf(a.feedId, b.feedId), listOf(b.feedId, a.feedId)), catalogue.guideAssociations(ref))
        val page = catalogue.page(ref, limit = 1)
        catalogue.setGuideFeeds(ref, listOf(b))
        assertThrows(IptvCatalogueChangedException::class.java) { catalogue.page(ref, cursor = page.next) }
        catalogue.setOverlay(ref, page.items.single().channel.id, IptvChannelOverlay(manualGuide = GuideKey(a.feedId, "one")))
        val rows = IptvBrowseRepository(catalogue, guides).page(ref).channels
        assertEquals(GuideMatchReason.MISSING_MANUAL_TARGET, rows.single { it.item.channel.id == page.items.single().channel.id }.guide.reason)
    }
    @Test fun guideLookupsNeverUseForeignProfileFeedsOrMatchNames() = runBlocking {
        val ref = source(); publish(ref, listOf(row(1, "One", "unmatched")))
        val own = feed(); val other = feed(2)
        val repo = IptvBrowseRepository(catalogue, guides)
        assertThrows(IllegalArgumentException::class.java) { catalogue.setGuideFeeds(ref, listOf(other)) }
        assertTrue(guides.matchingIndexes(1, listOf(other.feedId), setOf("one")).single().channelIds.isEmpty())
        repo.setGuideFeeds(ref, listOf(own))
        assertEquals(GuideMatchReason.NONE, repo.page(ref).channels.single().guide.reason)
        assertEquals(listOf(own), guides.feeds(1).map { it.ref })
    }
    @Test fun browsingBoundsAreEnforcedBeforeReadingRows() {
        val ref = source()
        assertThrows(IllegalArgumentException::class.java) { catalogue.page(ref, limit = 201) }
        assertThrows(IllegalArgumentException::class.java) { guides.matchingIndexes(1, emptyList(), (1..401).map { it.toString() }.toSet()) }
        assertThrows(IllegalArgumentException::class.java) { catalogue.setGuideFeeds(ref, listOf(IptvGuideRef(1, "same"), IptvGuideRef(1, "same"))) }
    }
    @Test fun v1MigrationPreservesCredentialsOverlayAndUnicodeSearch() {
        catalogue.close()
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("CREATE TABLE sources (id TEXT PRIMARY KEY, profile INTEGER NOT NULL, label TEXT NOT NULL, kind TEXT NOT NULL, account_id TEXT NOT NULL, config_version INTEGER NOT NULL, requested INTEGER NOT NULL, active_generation INTEGER, active_config INTEGER, connection BLOB NOT NULL, validators BLOB)")
            db.execSQL("CREATE TABLE identities (id TEXT PRIMARY KEY, source TEXT NOT NULL REFERENCES sources(id))")
            db.execSQL("CREATE TABLE catalogue (source TEXT NOT NULL REFERENCES sources(id), generation INTEGER NOT NULL, id TEXT NOT NULL REFERENCES identities(id), name TEXT NOT NULL, available INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(source,generation,id))")
            db.execSQL("CREATE TABLE overlays (id TEXT NOT NULL REFERENCES identities(id), profile INTEGER NOT NULL, custom_name TEXT, favourite_rank INTEGER, hidden INTEGER NOT NULL, guide_feed TEXT, guide_id TEXT, PRIMARY KEY(id,profile))")
            val connection = secrets.seal("iptv.v1:1:legacy:connection", "{\"endpoint\":\"https://fixture.invalid/legacy\"}")
            val row = secrets.seal("iptv.v1:1:legacy:channel:old", "{\"name\":\"НОВОСТИ\",\"locator\":\"https://fixture.invalid/live\",\"attributes\":{}}")
            db.execSQL("INSERT INTO sources VALUES(?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>("legacy", 1, "Legacy", "M3U", "shared", 1, 1, 1, 1, connection, null))
            db.execSQL("INSERT INTO identities VALUES('old','legacy')")
            db.execSQL("INSERT INTO catalogue VALUES(?,?,?,?,?,?)", arrayOf("legacy", 1, "old", "НОВОСТИ", 1, row))
            db.execSQL("INSERT INTO overlays VALUES('old',1,'MÜNCHEN',0,0,NULL,NULL)")
            db.version = 1
        }
        catalogue = IptvCatalogueStore(context, name, secrets)
        val ref = IptvSourceRef(1, "legacy")
        assertEquals("old", catalogue.page(ref, IptvBrowseQuery("münchen", favouritesOnly = true)).items.single().channel.id)
        assertEquals("https://fixture.invalid/legacy", catalogue.connection(ref).endpoint)
        catalogue.setOverlay(ref, "old", IptvChannelOverlay())
        assertEquals("old", catalogue.page(ref, IptvBrowseQuery("новости")).items.single().channel.id)
    }
    private class CountingBox(private val real: IptvSecretBox) : IptvSecretBox {
        var opens = 0
        override fun seal(context: String, plaintext: String): ByteArray = real.seal(context, plaintext)
        override fun open(context: String, ciphertext: ByteArray): String { opens++; return real.open(context, ciphertext) }
    }
}
