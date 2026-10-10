package com.nuvio.tv.data.iptv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.database.sqlite.SQLiteFullException
import com.nuvio.tv.core.iptv.ChannelCandidate
import com.nuvio.tv.core.iptv.GuideKey
import com.nuvio.tv.core.iptv.ListMove
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

    @Test fun streamFormatIsLocalDurableAndSurvivesRefreshWithoutReplacingOtherPreferences() {
        val ref = source(); val other = source(2)
        publish(ref, listOf(row(1), row(2))); publish(other)
        val item = store.page(ref).items.first()
        assertEquals(IptvStreamFormat.AUTO, item.overlay.streamFormat)
        val overlay = IptvChannelOverlay("Personal", 4, false, GuideKey("guide", "one"), IptvStreamFormat.HLS)
        store.setOverlay(ref, item.channel.id, overlay)
        assertEquals(overlay, store.playbackItem(ref, item.channel.id)!!.overlay)
        assertEquals(IptvStreamFormat.AUTO, store.snapshot(other).channels.single().overlay.streamFormat)
        publish(ref, listOf(row(1, "Changed"), row(2)))
        assertEquals(overlay, store.snapshot(ref).channels.single { it.channel.id == item.channel.id }.overlay)
        store.close()
        store = IptvCatalogueStore(context, name, AndroidIptvSecretBox(alias))
        assertEquals(overlay, store.playbackItem(ref, item.channel.id)!!.overlay)
        store.setOverlay(ref, item.channel.id, overlay.copy(streamFormat = IptvStreamFormat.MPEG_TS))
        assertEquals(IptvStreamFormat.MPEG_TS, store.page(ref).items.single { it.channel.id == item.channel.id }.overlay.streamFormat)
        store.setOverlay(ref, item.channel.id, overlay.copy(streamFormat = IptvStreamFormat.AUTO))
        assertEquals(IptvStreamFormat.AUTO, store.playbackItem(ref, item.channel.id)!!.overlay.streamFormat)
    }

    @Test fun publishRecordsRefreshTimeAndProviderGuideIdsIncludeManualMappings() {
        store.close(); store = IptvCatalogueStore(context, name, secrets, now = { 4321L })
        val ref = source()
        assertNull(store.sources(1).single().refreshedAtMillis)
        publish(ref, listOf(row(1), row(2)))
        assertEquals(4321L, store.sources(1).single().refreshedAtMillis)
        val feed = IptvGuideRef(1, "provider-feed")
        val item = store.page(ref).items.first()
        store.setOverlay(ref, item.channel.id, item.overlay.copy(manualGuide = GuideKey(feed.feedId, "manual-id")))
        assertEquals(setOf("guide-1", "guide-2", "manual-id"), store.guideChannelIds(ref, feed))
        assertEquals(setOf("guide-1", "guide-2"), store.guideChannelIds(ref, IptvGuideRef(1, "other-feed")))
    }

    @Test fun browsingFollowsProviderOrderAndFiltersByCategory() {
        val ref = source()
        fun grouped(id: Int, name: String, group: String?) = IptvCatalogueRecord(ChannelCandidate(name, "https://fixture.invalid/live/$id", providerId = id.toString()),
            group?.let { mapOf("group-title" to it) } ?: emptyMap())
        publish(ref, listOf(grouped(1, "Zulu", "News"), grouped(2, "Alpha", "Sport"), grouped(3, "Mike", "News"), grouped(4, "Bravo", null)))
        assertEquals(listOf("Zulu", "Alpha", "Mike", "Bravo"), store.page(ref).items.map { it.channel.data.name })
        assertEquals(listOf(IptvCategory("News", 2), IptvCategory("Sport", 1), IptvCategory("", 1)), store.categories(ref))
        assertEquals(listOf("Zulu", "Mike"), store.page(ref, IptvBrowseQuery(category = "News")).items.map { it.channel.data.name })
        assertEquals(listOf("Bravo"), store.page(ref, IptvBrowseQuery(category = "")).items.map { it.channel.data.name })
        assertEquals(listOf("Alpha"), store.page(ref, IptvBrowseQuery(excludedCategories = setOf("News", ""))).items.map { it.channel.data.name })
        assertEquals(listOf("Zulu", "Mike"), store.page(ref, IptvBrowseQuery(category = "News", excludedCategories = setOf("News"))).items.map { it.channel.data.name })
        assertEquals(listOf("Alpha", "Bravo"), store.page(ref, IptvBrowseQuery(search = "a")).items.map { it.channel.data.name }.filter { it in setOf("Alpha", "Bravo") })
    }

    @Test fun itemsByIdAndSearchAnyFindPicksAndBroadcasters() {
        val ref = source()
        fun grouped(id: Int, name: String, group: String?) = IptvCatalogueRecord(ChannelCandidate(name, "https://fixture.invalid/live/$id", providerId = id.toString()),
            group?.let { mapOf("group-title" to it) } ?: emptyMap())
        publish(ref, listOf(grouped(1, "Fox Footy HD", "AU Sport"), grouped(2, "Sky Sports Main", "UK Sports "), grouped(3, "Fox News", "News"), grouped(4, "Kayo 1", null)))
        val ids = store.page(ref).items.associate { it.channel.data.name to it.channel.id }
        assertEquals(listOf("Kayo 1", "Fox Footy HD"), store.items(ref, listOf(ids.getValue("Kayo 1"), "missing", ids.getValue("Fox Footy HD"))).map { it.channel.data.name })
        assertEquals(listOf("Fox Footy HD", "Fox News", "Kayo 1"), store.searchAny(ref, listOf("fox", "KAYO")).map { it.channel.data.name })
        assertEquals(listOf("Fox Footy HD", "Kayo 1"), store.searchAny(ref, listOf("fox", "kayo"), excludedCategories = setOf("News")).map { it.channel.data.name })
        assertEquals(listOf("Sky Sports Main"), store.searchAny(ref, listOf("sky", "fox"), categories = setOf("UK Sports")).map { it.channel.data.name })
        assertEquals(listOf("Fox Footy HD"), store.searchAny(ref, listOf("fox"), limit = 1).map { it.channel.data.name })
        assertTrue(store.searchAny(ref, listOf(" ")).isEmpty())
    }
    @Test fun accountGroupsAndSourceOrderPersistWithoutInvalidatingCatalogues() {
        val a = source(); val b = source(); val c = source(); val other = source(2)
        publish(a)
        assertEquals(listOf(a, b, c), store.sources(1).map { it.ref })
        store.moveSource(c, 0); assertEquals(listOf(c, a, b), store.sources(1).map { it.ref })
        assertEquals(listOf(IptvAccountGroup("account-shared", "account-shared", 1, listOf(c, a, b))), store.accounts(1))
        store.saveAccount(1, "provider-one", "Provider one", 2)
        store.assignAccount(a, "provider-one")
        assertTrue(store.sources(1).single { it.ref == a }.playbackEligible)
        val groups = store.accounts(1).associateBy { it.id }
        assertEquals(IptvAccountGroup("provider-one", "Provider one", 2, listOf(a)), groups["provider-one"])
        assertEquals(listOf(c, b), groups.getValue("account-shared").sources)
        assertEquals(listOf(other), store.accounts(2).single().sources)
        assertThrows(IllegalArgumentException::class.java) { store.saveAccount(1, "provider-one", "Provider one", 17) }
        store.removeProfile(1); assertTrue(store.accounts(1).isEmpty())
    }
    @Test fun formatChangeInvalidatesPagingAndCannotCrossBindAnotherSource() {
        val ref = source(); val other = source()
        publish(ref, listOf(row(1), row(2))); publish(other)
        val page = store.page(ref, limit = 1)
        val id = page.items.single().channel.id
        assertThrows(IllegalArgumentException::class.java) {
            store.setOverlay(other, id, IptvChannelOverlay(streamFormat = IptvStreamFormat.HLS))
        }
        store.setOverlay(ref, id, IptvChannelOverlay(streamFormat = IptvStreamFormat.HLS))
        assertThrows(IptvCatalogueChangedException::class.java) { store.page(ref, cursor = page.next) }
        assertEquals(IptvStreamFormat.AUTO, store.snapshot(other).channels.single().overlay.streamFormat)
    }

    @Test fun v2MigrationPreservesEncryptedCatalogueAndExistingOverlayAndDefaultsFormatToAuto() {
        val ref = source(); publish(ref)
        val item = store.snapshot(ref).channels.single()
        val overlay = IptvChannelOverlay("Custom", 7, false, GuideKey("feed", "channel"))
        store.setOverlay(ref, item.channel.id, overlay)
        store.close()
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("CREATE TABLE v2_sources (id TEXT PRIMARY KEY, profile INTEGER NOT NULL, label TEXT NOT NULL, kind TEXT NOT NULL, account_id TEXT NOT NULL, config_version INTEGER NOT NULL, requested INTEGER NOT NULL, active_generation INTEGER, active_config INTEGER, connection BLOB NOT NULL, validators BLOB, browse_revision INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("INSERT INTO v2_sources SELECT id,profile,label,kind,account_id,config_version,requested,active_generation,active_config,connection,validators,browse_revision FROM sources")
            db.execSQL("DROP TABLE sources")
            db.execSQL("ALTER TABLE v2_sources RENAME TO sources")
            db.execSQL("CREATE INDEX source_profile ON sources(profile)")
            db.execSQL("CREATE TABLE v2_catalogue (source TEXT NOT NULL REFERENCES sources(id), generation INTEGER NOT NULL, id TEXT NOT NULL REFERENCES identities(id), name TEXT NOT NULL, available INTEGER NOT NULL, payload BLOB NOT NULL, search_name TEXT NOT NULL DEFAULT '', PRIMARY KEY(source,generation,id))")
            db.execSQL("INSERT INTO v2_catalogue SELECT source,generation,id,name,available,payload,search_name FROM catalogue")
            db.execSQL("DROP TABLE catalogue")
            db.execSQL("ALTER TABLE v2_catalogue RENAME TO catalogue")
            db.execSQL("CREATE INDEX catalogue_browse ON catalogue(source,generation,available,search_name,id)")
            db.execSQL("CREATE TABLE v2_overlays (id TEXT NOT NULL REFERENCES identities(id), profile INTEGER NOT NULL, custom_name TEXT, favourite_rank INTEGER, hidden INTEGER NOT NULL, guide_feed TEXT, guide_id TEXT, search_name TEXT, PRIMARY KEY(id,profile))")
            db.execSQL("INSERT INTO v2_overlays SELECT id,profile,custom_name,favourite_rank,hidden,guide_feed,guide_id,search_name FROM overlays")
            db.execSQL("DROP TABLE overlays")
            db.execSQL("ALTER TABLE v2_overlays RENAME TO overlays")
            db.execSQL("DROP TABLE accounts")
            db.version = 2
        }
        store = IptvCatalogueStore(context, name, AndroidIptvSecretBox(alias))
        assertEquals(connection, store.connection(ref))
        assertEquals(listOf(ref), store.sources(1).map { it.ref })
        val migrated = store.page(ref).items.single()
        assertEquals(item.channel, migrated.channel)
        assertEquals(overlay, migrated.overlay)
        store.setOverlay(ref, item.channel.id, overlay.copy(streamFormat = IptvStreamFormat.HLS))
        assertEquals(IptvStreamFormat.HLS, store.playbackItem(ref, item.channel.id)!!.overlay.streamFormat)
        assertEquals(RefreshDecision.PUBLISH, publish(ref, listOf(row(1), row(2)), etag = "v2"))
        assertEquals(2, store.page(ref).items.size)
    }

    @Test fun guideMatchCandidatesFindIdsFeedSuffixesNamesAndManualGuidesInChannelOrder() {
        val ref = source(); val other = source(2)
        fun channel(id: Int, name: String, guide: String?, group: String? = null) = IptvCatalogueRecord(ChannelCandidate(name, "https://fixture.invalid/live/$id", providerId = id.toString(), guideId = guide),
            group?.let { mapOf("group-title" to it) }.orEmpty())
        publish(ref, listOf(channel(1, "UK: ABC HD", "abc.uk@SD"), channel(2, "ABC", "abc.uk"), channel(3, "Hidden", "abc.uk"),
            channel(4, "BBC One FHD", null), channel(5, "Manual", "zzz"), channel(6, "Unrelated", "xyz"), channel(7, "ABC Late", "abc.uk", "Late")))
        publish(other, listOf(channel(1, "ABC", "abc.uk")))
        val items = store.page(ref).items.associateBy { it.channel.data.name }
        store.setOverlay(ref, items.getValue("Hidden").channel.id, IptvChannelOverlay(hidden = true))
        store.setOverlay(ref, items.getValue("Manual").channel.id, IptvChannelOverlay(customName = "Mine", manualGuide = GuideKey("feed", "manual.id")))
        fun names(guideIds: Set<String> = emptySet(), nameKeys: Set<String> = emptySet(), manual: Set<GuideKey> = emptySet(), limit: Int = 400,
            source: IptvSourceRef = ref, excluded: Set<String> = emptySet()) = store.guideMatchCandidates(source, guideIds, nameKeys, manual, limit, excluded).map { it.channel.data.name }
        assertEquals(listOf("UK: ABC HD", "ABC", "ABC Late"), names(setOf("abc.uk")))
        assertEquals(listOf("UK: ABC HD", "ABC"), names(setOf("abc.uk"), excluded = setOf("Late")))
        assertEquals(listOf("ABC Late"), names(setOf("abc.uk"), excluded = setOf("")))
        assertEquals(listOf("UK: ABC HD"), names(setOf("abc.uk@SD")))
        assertEquals(listOf("BBC One FHD"), names(nameKeys = setOf("bbcone")))
        assertEquals(listOf("Manual"), names(manual = setOf(GuideKey("feed", "manual.id"))))
        assertEquals("Mine", store.guideMatchCandidates(ref, emptySet(), emptySet(), setOf(GuideKey("feed", "manual.id"))).single().overlay.customName)
        assertTrue(names(manual = setOf(GuideKey("other", "manual.id"))).isEmpty())
        assertEquals(listOf("UK: ABC HD", "ABC", "BBC One FHD", "Manual", "ABC Late"), names(setOf("abc.uk", "missing"), setOf("bbcone", "abc"), setOf(GuideKey("feed", "manual.id"))))
        assertEquals(listOf("UK: ABC HD", "ABC"), names(setOf("abc.uk"), setOf("bbcone"), limit = 2))
        assertEquals(listOf("ABC"), names(setOf("abc.uk"), source = other))
        assertTrue(names(manual = setOf(GuideKey("feed", "manual.id")), source = other).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { names((1..501).map { "id$it" }.toSet()) }
        assertThrows(IllegalArgumentException::class.java) { names(setOf("abc.uk"), limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { names(setOf(" ")) }
    }

    @Test fun v7MigrationLeavesGuideKeysEmptyUntilTheNextRefresh() {
        val ref = source()
        val record = IptvCatalogueRecord(ChannelCandidate("UK: ABC HD", "https://fixture.invalid/live/1", providerId = "1", guideId = "abc.uk@SD"))
        publish(ref, listOf(record))
        store.close()
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("ALTER TABLE catalogue RENAME TO newer_catalogue")
            db.execSQL("CREATE TABLE catalogue (source TEXT NOT NULL REFERENCES sources(id), generation INTEGER NOT NULL, id TEXT NOT NULL REFERENCES identities(id), name TEXT NOT NULL, available INTEGER NOT NULL, payload BLOB NOT NULL, search_name TEXT NOT NULL DEFAULT '', position INTEGER NOT NULL DEFAULT 0, category TEXT, PRIMARY KEY(source,generation,id))")
            db.execSQL("INSERT INTO catalogue SELECT source,generation,id,name,available,payload,search_name,position,category FROM newer_catalogue")
            db.execSQL("DROP TABLE newer_catalogue")
            db.execSQL("CREATE INDEX catalogue_browse ON catalogue(source,generation,available,search_name,id)")
            db.execSQL("CREATE INDEX catalogue_order ON catalogue(source,generation,category,position)")
            db.execSQL("CREATE INDEX catalogue_identity ON catalogue(id)")
            db.version = 7
        }
        store = IptvCatalogueStore(context, name, secrets)
        val item = store.page(ref).items.single()
        assertEquals("abc.uk@SD", item.channel.data.guideId)
        assertTrue(store.guideMatchCandidates(ref, setOf("abc.uk"), setOf("abc"), emptySet()).isEmpty())
        publish(ref, listOf(record), etag = "v2")
        assertEquals(listOf(item.channel.id), store.guideMatchCandidates(ref, setOf("abc.uk"), emptySet(), emptySet()).map { it.channel.id })
        assertEquals(listOf(item.channel.id), store.guideMatchCandidates(ref, emptySet(), setOf("abc"), emptySet()).map { it.channel.id })
    }

    @Test fun movedChannelsKeepTheirPlaceInAllChannelsAndCategoriesAcrossReopenAndRefresh() {
        val ref = source()
        fun grouped(id: Int, name: String, group: String) = IptvCatalogueRecord(ChannelCandidate(name, "https://fixture.invalid/live/$id", providerId = id.toString()), mapOf("group-title" to group))
        val rows = listOf(grouped(1, "One", "News"), grouped(2, "Two", "Sport"), grouped(3, "Three", "News"), grouped(4, "Four", "News"))
        publish(ref, rows)
        fun names(query: IptvBrowseQuery = IptvBrowseQuery()) = store.page(ref, query).items.map { it.channel.data.name }
        fun id(name: String) = store.page(ref).items.single { it.channel.data.name == name }.channel.id
        assertTrue(store.moveChannel(ref, id("Four"), IptvBrowseQuery(), ListMove.TOP))
        assertEquals(listOf("Four", "One", "Two", "Three"), names())
        val news = IptvBrowseQuery(category = "News")
        assertTrue(store.moveChannel(ref, id("One"), news, ListMove.DOWN))
        assertEquals(listOf("Four", "Three", "One"), names(news))
        assertFalse(store.moveChannel(ref, id("Four"), news, ListMove.UP))
        assertTrue(store.moveChannel(ref, id("Four"), news, ListMove.BOTTOM))
        assertEquals(listOf("Three", "One", "Four"), names(news))
        val first = store.page(ref).items.first()
        store.setOverlay(ref, first.channel.id, first.overlay.copy(favouriteRank = 0))
        val before = names()
        store.close()
        store = IptvCatalogueStore(context, name, secrets)
        assertEquals(before, names())
        assertEquals(RefreshDecision.PUBLISH, publish(ref, rows, etag = "v2"))
        assertEquals(before, names())
        assertEquals(listOf("Three", "One", "Four"), names(news))
        assertEquals(listOf("Two", "Three", "One", "Four"), names())
        repeat(80) { assertTrue(store.moveChannel(ref, id(if (it % 2 == 0) "One" else "Three"), IptvBrowseQuery(), ListMove.UP)) }
        assertEquals(listOf("Two", "Three", "One", "Four"), names())
    }

    @Test fun v8MigrationAddsChannelOrderWithoutTouchingOverlays() {
        val ref = source(); publish(ref, listOf(row(1), row(2), row(3)))
        val items = store.page(ref).items
        val overlay = IptvChannelOverlay("Custom", 3, false, GuideKey("feed", "channel"), IptvStreamFormat.HLS)
        store.setOverlay(ref, items[1].channel.id, overlay)
        store.close()
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("CREATE TABLE v8_overlays (id TEXT NOT NULL REFERENCES identities(id), profile INTEGER NOT NULL, custom_name TEXT, favourite_rank INTEGER, hidden INTEGER NOT NULL, guide_feed TEXT, guide_id TEXT, search_name TEXT, stream_format TEXT NOT NULL DEFAULT 'AUTO' CHECK(stream_format IN ('AUTO','HLS','MPEG_TS')), PRIMARY KEY(id,profile))")
            db.execSQL("INSERT INTO v8_overlays SELECT id,profile,custom_name,favourite_rank,hidden,guide_feed,guide_id,search_name,stream_format FROM overlays")
            db.execSQL("DROP TABLE overlays")
            db.execSQL("ALTER TABLE v8_overlays RENAME TO overlays")
            db.version = 8
        }
        store = IptvCatalogueStore(context, name, secrets)
        val migrated = store.page(ref).items
        assertEquals(items.map { it.channel.id }, migrated.map { it.channel.id })
        assertEquals(overlay, migrated[1].overlay)
        assertTrue(store.moveChannel(ref, items[2].channel.id, IptvBrowseQuery(), ListMove.TOP))
        assertEquals(listOf(items[2], items[0], items[1]).map { it.channel.id }, store.page(ref).items.map { it.channel.id })
        assertEquals(overlay.copy(userOrder = store.page(ref).items[2].overlay.userOrder), store.page(ref).items[2].overlay)
    }

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

    @Test fun chunkedSaveKeepsThePreviousListVisibleUntilPublish() {
        store.close(); store = IptvCatalogueStore(context, name, secrets, saveChunkRows = 2)
        val ref = source(); publish(ref, (1..3).map { row(it) })
        val ticket = store.beginRefresh(ref)
        val seen = mutableSetOf<Int>()
        assertEquals(RefreshDecision.PUBLISH, store.commitCatalogue(ref, ticket, (1..7).map { row(it) }, true) {
            seen += store.snapshot(ref).channels.count { it.channel.available }
        })
        assertEquals(setOf(3), seen)
        assertEquals(7, store.snapshot(ref).channels.count { it.channel.available })
    }

    @Test fun failureInALaterChunkKeepsThePreviousListAndLeavesNoPartialRows() {
        store.close(); store = IptvCatalogueStore(context, name, secrets, saveChunkRows = 2)
        val ref = source(); publish(ref, (1..3).map { row(it) })
        val before = store.snapshot(ref)
        val ticket = store.beginRefresh(ref)
        secrets.sealsBeforeFailure = 3
        assertThrows(IOException::class.java) { store.commitCatalogue(ref, ticket, (4..9).map { row(it) }, true, acceptedLargeChange = true) }
        secrets.sealsBeforeFailure = null
        assertEquals(before.channels, store.snapshot(ref).channels)
        store.close()
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.rawQuery("SELECT (SELECT COUNT(*) FROM catalogue),(SELECT COUNT(*) FROM identities)", null).use {
                assertTrue(it.moveToFirst()); assertEquals(3, it.getInt(0)); assertEquals(3, it.getInt(1))
            }
        }
        store = IptvCatalogueStore(context, name, secrets, saveChunkRows = 2)
        assertEquals(RefreshDecision.PUBLISH, store.commitCatalogue(ref, ticket, (4..9).map { row(it) }, true, acceptedLargeChange = true))
        assertEquals((4..9).map { it.toString() }.toSet(), store.snapshot(ref).channels.filter { it.channel.available }.map { it.channel.data.providerId }.toSet())
    }

    @Test fun newerRefreshDuringAChunkedSaveMakesTheOlderSaveStale() {
        store.close(); store = IptvCatalogueStore(context, name, secrets, saveChunkRows = 2)
        val ref = source(); publish(ref)
        val before = store.snapshot(ref)
        val older = store.beginRefresh(ref)
        var newer: com.nuvio.tv.core.iptv.RefreshTicket? = null
        var checks = 0
        assertEquals(RefreshDecision.STALE, store.commitCatalogue(ref, older, (1..6).map { row(it) }, true) {
            if (++checks == 9) newer = store.beginRefresh(ref)
        })
        assertEquals(before.channels, store.snapshot(ref).channels)
        assertEquals(RefreshDecision.PUBLISH, store.commitCatalogue(ref, newer!!, listOf(row(1), row(2)), true))
    }

    @Test fun removingASourceDropsItsDataRenumbersAndCannotBeResurrectedByARefresh() {
        val a = source(); val b = source(); val c = source(); val other = source(2)
        publish(a, listOf(row(1), row(2))); publish(b); publish(other)
        val id = store.snapshot(a).channels.first().channel.id
        store.setOverlay(a, id, IptvChannelOverlay(favouriteRank = 0))
        store.setGuideFeeds(a, listOf(IptvGuideRef(1, "feed-a")))
        val ticket = store.beginRefresh(a)
        store.removeSource(a)
        assertEquals(listOf(b, c), store.sources(1).map { it.ref })
        assertEquals(RefreshDecision.STALE, store.commitCatalogue(a, ticket, listOf(row(3)), true))
        assertFalse(store.acceptNotModified(a, ticket))
        assertEquals(listOf(b, c), store.sources(1).map { it.ref })
        assertThrows(IllegalArgumentException::class.java) { store.snapshot(a) }
        assertThrows(IllegalArgumentException::class.java) { store.removeSource(IptvSourceRef(2, b.sourceId)) }
        assertEquals(1, store.snapshot(other).channels.size)
        store.moveSource(c, 0)
        assertEquals(listOf(c, b), store.sources(1).map { it.ref })
        store.close()
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.rawQuery("SELECT (SELECT COUNT(*) FROM catalogue WHERE source=?),(SELECT COUNT(*) FROM identities WHERE source=?),(SELECT COUNT(*) FROM overlays),(SELECT COUNT(*) FROM source_guides)",
                arrayOf(a.sourceId, a.sourceId)).use {
                assertTrue(it.moveToFirst()); (0..3).forEach { column -> assertEquals(0, it.getInt(column)) }
            }
        }
        store = IptvCatalogueStore(context, name, secrets)
    }

    @Test fun removingAGuideFeedDropsItFromEverySourceInTheProfileOnly() {
        val a = source(); val b = source(); val other = source(2)
        publish(a)
        val gone = IptvGuideRef(1, "gone"); val kept = IptvGuideRef(1, "kept")
        store.setGuideFeeds(a, listOf(gone, kept), listOf(gone, kept))
        store.setGuideFeeds(b, listOf(kept, gone))
        store.setGuideFeeds(other, listOf(IptvGuideRef(2, "gone")))
        val id = store.snapshot(a).channels.single().channel.id
        store.setOverlay(a, id, IptvChannelOverlay(manualGuide = GuideKey("gone", "x"), favouriteRank = 1))
        store.removeGuideFeed(gone)
        assertEquals(IptvGuideAssociations(listOf("kept"), listOf("kept")), store.guideAssociations(a))
        assertEquals(IptvGuideAssociations(listOf("kept"), emptyList()), store.guideAssociations(b))
        assertEquals(listOf("gone"), store.guideAssociations(other).feedIds)
        val overlay = store.snapshot(a).channels.single().overlay
        assertNull(overlay.manualGuide); assertEquals(1, overlay.favouriteRank)
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
