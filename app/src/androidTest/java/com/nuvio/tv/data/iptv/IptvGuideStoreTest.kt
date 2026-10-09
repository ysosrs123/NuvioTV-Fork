package com.nuvio.tv.data.iptv

import android.database.sqlite.SQLiteFullException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.iptv.GuideImportFilter
import com.nuvio.tv.core.iptv.GuideImportIssue
import com.nuvio.tv.core.iptv.GuideImportResult
import com.nuvio.tv.core.iptv.GuideKey
import com.nuvio.tv.core.iptv.RefreshDecision
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.GZIPOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IptvGuideStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val suffix = UUID.randomUUID().toString()
    private val name = "guide-test-$suffix.db"
    private val alias = "nuvio.guide.test.$suffix"
    private lateinit var store: IptvGuideStore
    private val start = Instant.parse("2026-10-05T00:00:00Z").toEpochMilli()
    private val window = IptvGuideWindow(start, start + 86_400_000)
    private val channel = "<channel id=\"one\"><display-name lang=\"en\">One</display-name></channel>"
    private fun programme(title: String = "Morning", from: String = "20261005000000 +0000", to: String? = "20261005010000 +0000", id: String = "one") =
        "<programme channel=\"$id\" start=\"$from\"${to?.let { " stop=\"$it\"" }.orEmpty()}><title lang=\"en\">$title</title><title lang=\"fr\">Matin</title><desc>Fixture</desc></programme>"
    private fun xml(rows: String = programme()) = "<tv>$channel$rows</tv>"
    private fun stamp(minutes: Int) = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(start + minutes * 60_000L)) + " +0000"
    private fun slot(title: String, minutes: Int) = programme(title, stamp(minutes), stamp(minutes + 1))
    private fun feed(profile: Int = 1) = store.createFeed(profile, "Fixture", "https://fixture.invalid/epg?token=UNIQUE_GUIDE_SECRET")
    private fun publish(ref: IptvGuideRef, value: String = xml()) = store.importGuide(store.beginRefresh(ref), value.byteInputStream(), window, IptvCacheValidators("v1"))

    @Before fun setup() { store = IptvGuideStore(context, name, AndroidIptvSecretBox(alias)) }
    @After fun cleanup() {
        store.close(); context.deleteDatabase(name)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
    @Test fun feedIdentityLanguagesAndCacheSurviveReopening() {
        val ref = feed(); assertEquals(RefreshDecision.PUBLISH, publish(ref))
        store.close(); store = IptvGuideStore(context, name, AndroidIptvSecretBox(alias))
        val row = store.programmes(ref, "one", window).programmes.single()
        assertEquals(listOf("en", "fr"), row.titles.map { it.language })
        assertEquals(14, row.start.precisionDigits)
        assertTrue(row.canSchedulePrecisely)
        assertEquals("one", store.channelPage(ref).single().externalId)
        assertEquals("v1", store.validators(ref)!!.etag)
        store.close()
        assertFalse(context.getDatabasePath(name).readBytes().toString(Charsets.ISO_8859_1).contains("UNIQUE_GUIDE_SECRET"))
    }
    @Test fun filterKeepsEveryChannelButOnlyWantedProgrammesAndPublishRecordsRefreshTime() {
        store.close(); store = IptvGuideStore(context, name, AndroidIptvSecretBox(alias), now = { 1234L })
        val ref = feed()
        assertNull(store.feeds(1).single().refreshedAtMillis)
        val other = "<channel id=\"two\"><display-name>Two</display-name></channel>"
        val value = "<tv>$channel$other${programme()}${programme("Elsewhere", id = "two")}</tv>"
        val filter = GuideImportFilter(setOf("ONE"), emptySet())
        assertEquals(RefreshDecision.PUBLISH, store.importGuide(store.beginRefresh(ref), value.byteInputStream(), window, filter = filter))
        assertEquals(listOf("one", "two"), store.channelPage(ref).map { it.externalId })
        assertEquals(1, store.programmes(ref, "one", window).programmes.size)
        assertTrue(store.programmes(ref, "two", window).programmes.isEmpty())
        assertEquals(1234L, store.feeds(1).single().refreshedAtMillis)
        assertTrue(store.upToDate(ref, window, filter.fingerprint, 60_000))
        assertFalse(store.upToDate(ref, window, GuideImportFilter(setOf("two"), emptySet()).fingerprint, 60_000))
    }
    @Test fun aGuideWithoutLinkedChannelsStoresChannelsOnly() {
        val ref = feed()
        val result = store.importGuideResult(store.beginRefresh(ref), xml().byteInputStream(), window, filter = GuideImportFilter(emptySet(), emptySet()))
        assertEquals(GuideImportResult(RefreshDecision.PUBLISH, GuideImportIssue.NOT_LINKED), result)
        assertEquals(listOf("one"), store.channelPage(ref).map { it.externalId })
        assertTrue(store.programmes(ref, "one", window).programmes.isEmpty())
    }
    @Test fun changedFilterDropsCacheValidatorsAndChoiceSeesProgrammes() {
        val ref = feed()
        val first = GuideImportFilter(setOf("one"), emptySet())
        assertEquals(RefreshDecision.PUBLISH, store.importGuide(store.beginRefresh(ref), xml().byteInputStream(), window, IptvCacheValidators("v1"), filter = first))
        assertEquals("v1", store.prepareRefresh(ref, window, first.fingerprint).validators?.etag)
        assertNull(store.prepareRefresh(ref, window, GuideImportFilter(setOf("one", "two"), emptySet()).fingerprint).validators)
        val keys = setOf(GuideKey(ref.feedId, "one"), GuideKey(ref.feedId, "two"))
        assertEquals(setOf(GuideKey(ref.feedId, "one")), store.programmeKeys(1, keys, window))
        assertTrue(store.programmeKeys(1, keys, IptvGuideWindow(start + 2 * 3_600_000, start + 3 * 3_600_000)).isEmpty())
        assertTrue(store.programmeKeys(2, emptyList(), window).isEmpty())
    }
    @Test fun droppingTheActiveCopyLeavesNoRows() {
        val ref = feed(); publish(ref)
        store.dropActive(ref)
        assertTrue(store.channelPage(ref).isEmpty()); assertNull(store.validators(ref))
        store.close()
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.rawQuery("SELECT (SELECT COUNT(*) FROM stages),(SELECT COUNT(*) FROM channels),(SELECT COUNT(*) FROM programmes),(SELECT COUNT(*) FROM channel_names)", null).use {
                assertTrue(it.moveToFirst()); for (column in 0..3) assertEquals(0, it.getInt(column))
            }
        }
        store = IptvGuideStore(context, name, AndroidIptvSecretBox(alias))
        assertEquals(RefreshDecision.PUBLISH, publish(ref))
    }
    @Test fun removingAFeedDropsItsGuideAndAnImportInFlightIsStale() {
        val ref = feed(); val other = feed(); val otherProfile = feed(2)
        publish(ref); publish(other)
        val ticket = store.beginRefresh(ref)
        store.removeFeed(ref)
        assertEquals(listOf(other, otherProfile).map { it.feedId }.toSet(), (store.feeds(1) + store.feeds(2)).map { it.ref.feedId }.toSet())
        assertEquals(RefreshDecision.STALE, store.importGuide(ticket, xml().byteInputStream(), window))
        assertFalse(store.acceptNotModified(ticket, window))
        assertThrows(IllegalArgumentException::class.java) { store.programmes(ref, "one", window) }
        assertThrows(IllegalArgumentException::class.java) { store.removeFeed(IptvGuideRef(2, other.feedId)) }
        assertEquals(1, store.programmes(other, "one", window).programmes.size)
        store.close()
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.rawQuery("SELECT (SELECT COUNT(*) FROM stages WHERE feed=?),(SELECT COUNT(*) FROM channels),(SELECT COUNT(*) FROM programmes)", arrayOf(ref.feedId)).use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals(1, it.getInt(1)); assertEquals(1, it.getInt(2))
            }
        }
        store = IptvGuideStore(context, name, AndroidIptvSecretBox(alias))
    }
    @Test fun importKeepsAPerChannelProgrammeCapAndShortDescriptions() {
        val ref = feed()
        val rows = (0 until 6).joinToString("") { slot ->
            "<programme channel=\"one\" start=\"2026100500${slot}000 +0000\" stop=\"2026100500${slot}500 +0000\"><title>Show $slot</title><desc>${"long ".repeat(200)}</desc></programme>"
        }
        assertEquals(RefreshDecision.PUBLISH, store.importGuide(store.beginRefresh(ref), xml(rows).byteInputStream(), window,
            caps = com.nuvio.tv.core.iptv.GuideStorageCaps(programmesPerChannel = 4, descriptionCharacters = 50)))
        val stored = store.programmes(ref, "one", window).programmes
        assertEquals(4, stored.size)
        assertTrue(stored.all { it.descriptions.single().text.length <= 50 })
    }
    @Test fun sameGuideIdInAnotherFeedOrProfileCannotBleedIntoQueries() {
        val one = feed(); val two = feed(); val otherProfile = feed(2)
        publish(one); publish(two, xml(programme("Different"))); publish(otherProfile, xml(programme("Private")))
        assertEquals("Morning", store.programmes(one, "one", window).programmes.single().titles.first().text)
        assertEquals("Different", store.programmes(two, "one", window).programmes.single().titles.first().text)
        assertThrows(IllegalArgumentException::class.java) { store.channelPage(IptvGuideRef(2, one.feedId)) }
    }
    @Test fun malformedOrPartiallyRejectedXmlPreservesTheWorkingGuideAndEtag() {
        val ref = feed(); publish(ref)
        val before = store.programmes(ref, "one", window)
        assertThrows(Exception::class.java) { publish(ref, xml().dropLast(4)) }
        assertEquals(RefreshDecision.INVALID, publish(ref, xml(programme() + (1..17).joinToString("") { programme("bad $it", to = "not-a-date") })))
        assertEquals(before, store.programmes(ref, "one", window))
        assertEquals("v1", store.validators(ref)!!.etag)
        assertEquals(RefreshDecision.PUBLISH, publish(ref, xml(programme() + programme("bad", to = "not-a-date"))))
        assertEquals(before, store.programmes(ref, "one", window))
    }
    @Test fun olderTicketCannotPublishAndSourceEditInvalidatesVisibleGuide() {
        val ref = feed(); publish(ref, xml((1..4).joinToString("") { programme("Old $it") }))
        val stale = store.beginRefresh(ref); store.beginRefresh(ref)
        assertEquals(RefreshDecision.STALE, store.importGuide(stale, xml(programme("Old")).byteInputStream(), window))
        store.editFeed(ref, "Renamed", store.endpoint(ref))
        assertFalse(store.channelPage(ref).isEmpty())
        val changed = store.beginRefresh(ref)
        store.editFeed(ref, "New source", "https://fixture.invalid/new")
        assertEquals(RefreshDecision.STALE, store.importGuide(changed, xml().byteInputStream(), window))
        assertTrue(store.channelPage(ref).isEmpty())
        assertNull(store.validators(ref))
        assertEquals(RefreshDecision.PUBLISH, publish(ref))
    }
    @Test fun paginationPreservesOverlapsAndDoesNotInventMissingEnds() {
        val ref = feed()
        publish(ref, xml(programme("A") + programme("B", stamp(10)) + programme("Unknown", stamp(20), to = null)))
        val page = store.programmes(ref, "one", window, limit = 2)
        assertTrue(page.hasMore); assertEquals(2, page.programmes.size)
        assertEquals(1, store.programmes(ref, "one", window, offset = 2, limit = 2).programmes.size)
        val later = store.programmes(ref, "one", IptvGuideWindow(start + 3_600_000, start + 7_200_000))
        assertTrue(later.programmes.isEmpty())
        assertFalse(store.programmes(ref, "one", window).programmes.single { it.stop == null }.canSchedulePrecisely)
    }
    @Test fun emptyOrLargeShrinkNeedsReviewAndDoesNotPromoteValidators() {
        val ref = feed(); publish(ref, xml((1..4).joinToString("") { slot("Show $it", it) }))
        assertEquals(RefreshDecision.SHRINK_REQUIRES_REVIEW, publish(ref))
        assertEquals(RefreshDecision.EMPTY_REQUIRES_REVIEW, publish(ref, "<tv>$channel</tv>"))
        assertEquals(4, store.programmes(ref, "one", window).programmes.size)
    }
    @Test fun unknownChannelProgrammesAreKeptUnderAnImpliedChannelAndDuplicateChannelNamesMerge() {
        val ref = feed(); publish(ref)
        assertEquals(RefreshDecision.PUBLISH, publish(ref, xml(programme(id = "missing"))))
        assertEquals(listOf("missing", "one"), store.channelPage(ref).map { it.externalId })
        assertEquals("Morning", store.programmes(ref, "missing", window).programmes.single().titles.first().text)
        assertEquals(RefreshDecision.PUBLISH, publish(ref, xml(programme() + programme("Elsewhere", id = " ONE "))))
        assertEquals(1, store.programmes(ref, "one", window).programmes.size)
        assertEquals(RefreshDecision.PUBLISH, publish(ref, "<tv>$channel<channel id=\"one\"><display-name>Different</display-name></channel>${programme()}</tv>"))
        assertEquals(1, store.channelPage(ref).size)
    }
    @Test fun documentGrantsAreReleasedOnlyWhenNoFeedStillUsesThem() {
        val released = mutableListOf<String>(); store.close()
        store = IptvGuideStore(context, name, AndroidIptvSecretBox(alias), releaseDocument = { released += it })
        val doc = "content://fixture.documents/guide.xml"
        val one = store.createFeed(1, "One", doc); val two = store.createFeed(2, "Two", doc)
        store.editFeed(one, "One", "https://fixture.invalid/epg"); assertTrue(released.isEmpty())
        store.removeProfile(2); assertEquals(listOf(doc), released)
        store.editFeed(one, "One", doc); store.clearAllProfiles(); assertEquals(listOf(doc, doc), released)
    }
    @Test fun cancellationAfterAFlushedBatchLeavesNoVisiblePartialGuide() {
        val ref = feed(); publish(ref)
        val text = xml((1..150).joinToString("") { programme("Candidate $it") })
        val triggerAfter = xml((1..110).joinToString("") { programme("Candidate $it") }).toByteArray().size
        val input = object : ByteArrayInputStream(text.toByteArray()) {
            var bytesRead = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (bytesRead > triggerAfter) throw InterruptedIOException("fixture cancellation")
                return super.read(bytes, offset, minOf(length, 1024)).also { if (it > 0) bytesRead += it }
            }
        }
        assertThrows(InterruptedIOException::class.java) { store.importGuide(store.beginRefresh(ref), input, window) }
        assertEquals("Morning", store.programmes(ref, "one", window).programmes.single().titles.first().text)
        assertEquals(RefreshDecision.PUBLISH, publish(ref))
    }
    @Test fun aRefreshSupersededBetweenBatchesCannotActivate() {
        val ref = feed(); publish(ref)
        val ticket = store.beginRefresh(ref)
        var replaced = false
        val text = xml((1..150).joinToString("") { programme("Candidate $it") })
        val triggerAfter = xml((1..110).joinToString("") { programme("Candidate $it") }).toByteArray().size
        val input = object : ByteArrayInputStream(text.toByteArray()) {
            var bytesRead = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (!replaced && bytesRead > triggerAfter) {
                    IptvGuideStore(context, name, AndroidIptvSecretBox(alias)).use { it.beginRefresh(ref) }
                    replaced = true
                }
                return super.read(bytes, offset, minOf(length, 1024)).also { if (it > 0) bytesRead += it }
            }
        }
        assertEquals(RefreshDecision.STALE, store.importGuide(ticket, input, window))
        assertTrue(replaced)
        assertEquals("Morning", store.programmes(ref, "one", window).programmes.single().titles.first().text)
    }
    @Test fun realCapacityFailurePreservesActiveGuideAndRetrySucceeds() {
        val ref = feed(); publish(ref)
        store.close(); store = IptvGuideStore(context, name, AndroidIptvSecretBox(alias), maxDatabaseBytes = 128L * 1024)
        val large = xml((1..150).joinToString("") { slot("Large $it " + "x".repeat(2000), it) })
        assertThrows(SQLiteFullException::class.java) { publish(ref, large) }
        assertEquals("Morning", store.programmes(ref, "one", window).programmes.single().titles.first().text)
        store.close(); store = IptvGuideStore(context, name, AndroidIptvSecretBox(alias))
        assertEquals(RefreshDecision.PUBLISH, publish(ref, large))
        assertTrue(store.programmes(ref, "one", window).hasMore)
    }
    @Test fun airingMatchesFoldEveryTitleLanguageAndKeepOnlyWhatIsOnNow() {
        val ref = feed(); val minute = 60_000L
        val rows = programme("ＢＢＣ News") + programme("Weather", "20261005010000 +0000", "20261005020000 +0000") +
            programme("Open", "20261005020000 +0000", null) + programme("Later", "20261005030000 +0000", "20261005040000 +0000")
        assertEquals(RefreshDecision.PUBLISH, publish(ref, xml(rows)))
        fun titles(query: String, at: Long, profile: Int = 1) = store.airingMatches(profile, listOf(ref.feedId), query, at).map { it.programme.titles.first().text }
        val match = store.airingMatches(1, listOf(ref.feedId), " bbc NEWS ", start + 30 * minute).single()
        assertEquals(GuideKey(ref.feedId, "one"), match.key)
        assertEquals(listOf("One"), match.channel.names.map { it.text })
        assertEquals(listOf("ＢＢＣ News"), titles("matin", start))
        assertEquals(listOf("Weather"), titles("matin", start + 60 * minute))
        assertEquals(listOf("Open"), titles("matin", start + 150 * minute))
        assertTrue(titles("open", start + 210 * minute).isEmpty())
        assertTrue(titles("later", start + 150 * minute).isEmpty())
        assertTrue(titles("matin", start - 1).isEmpty())
        assertTrue(titles("   ", start).isEmpty())
        assertTrue(titles("news", start, profile = 2).isEmpty())
        assertTrue(titles("%", start).isEmpty())
    }
    @Test fun airingMatchesFollowFeedOrderOnlyReadActiveGuidesAndAreBounded() {
        val one = feed(); val two = feed(); val otherProfile = feed(2)
        publish(one, xml(programme("News one"))); publish(two, xml(programme("News two"))); publish(otherProfile, xml(programme("News private")))
        fun titles(feeds: List<IptvGuideRef>, limit: Int = 200) = store.airingMatches(1, feeds.map { it.feedId }, "news", start, limit).map { it.programme.titles.first().text }
        assertEquals(listOf("News two", "News one"), titles(listOf(two, one)))
        assertEquals(listOf("News one"), titles(listOf(one, two), limit = 1))
        assertEquals(listOf("News one"), titles(listOf(one, otherProfile)))
        assertEquals(RefreshDecision.PUBLISH, publish(one, xml(programme("Replaced"))))
        assertEquals(listOf("News two"), titles(listOf(one, two)))
        store.editFeed(two, "Moved", "https://fixture.invalid/moved")
        assertTrue(titles(listOf(one, two)).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { store.airingMatches(1, (1..17).map { "feed$it" }, "news", start) }
        assertThrows(IllegalArgumentException::class.java) { store.airingMatches(1, listOf(one.feedId), "news", start, 501) }
        assertThrows(IllegalArgumentException::class.java) { store.airingMatches(1, listOf(one.feedId), "x".repeat(257), start) }
        assertThrows(IllegalArgumentException::class.java) { store.airingMatches(1, listOf(one.feedId, one.feedId), "news", start) }
    }
    @Test fun v5MigrationKeepsFeedsDropsProgrammeDataAndEnablesIncrementalVacuum() {
        store.close(); context.deleteDatabase(name)
        val secrets = AndroidIptvSecretBox(alias)
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("CREATE TABLE feeds(id TEXT PRIMARY KEY, profile INTEGER NOT NULL, label TEXT NOT NULL, endpoint BLOB NOT NULL, version INTEGER NOT NULL, requested INTEGER NOT NULL, active_stage TEXT, active_generation INTEGER, active_version INTEGER, validators BLOB, window_from INTEGER, window_until INTEGER, refreshed_at INTEGER)")
            db.execSQL("CREATE TABLE stages(id TEXT PRIMARY KEY, feed TEXT NOT NULL REFERENCES feeds(id), generation INTEGER NOT NULL, UNIQUE(feed,generation))")
            db.execSQL("CREATE TABLE channels(stage TEXT NOT NULL REFERENCES stages(id) ON DELETE CASCADE, external_id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(stage,external_id))")
            db.execSQL("CREATE TABLE programmes(stage TEXT NOT NULL REFERENCES stages(id) ON DELETE CASCADE, id TEXT NOT NULL, external_id TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER, precise INTEGER NOT NULL, payload TEXT NOT NULL, search_title TEXT, sport INTEGER, PRIMARY KEY(stage,id))")
            db.execSQL("CREATE INDEX guide_window ON programmes(stage,external_id,start,stop)")
            db.execSQL("CREATE INDEX guide_sport ON programmes(stage,start,stop) WHERE sport=1")
            db.execSQL("CREATE TABLE channel_names(stage TEXT NOT NULL REFERENCES stages(id) ON DELETE CASCADE, external_id TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(stage,name,external_id))")
            db.execSQL("INSERT INTO feeds VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>("legacy", 1, "Legacy", secrets.seal("iptv.guide.v1:1:legacy:endpoint", "https://fixture.invalid/legacy"),
                3, 7, "stage", 7, 3, secrets.seal("iptv.guide.v1:1:legacy:validators", "{\"etag\":\"old\"}"), window.fromMillis, window.untilMillis, 1L))
            db.execSQL("INSERT INTO stages VALUES('stage','legacy',7)")
            db.execSQL("INSERT INTO channels VALUES('stage','one','{\"id\":\"one\",\"names\":[]}')")
            for (index in 0 until 500) db.execSQL("INSERT INTO programmes VALUES('stage',?,'one',?,?,1,?,NULL,NULL)", arrayOf<Any?>("p$index", start + index, start + index + 1, "x".repeat(400)))
            db.execSQL("INSERT INTO channel_names VALUES('stage','one','one')")
            db.version = 5
        }
        store = IptvGuideStore(context, name, secrets)
        val ref = IptvGuideRef(1, "legacy")
        val migrated = store.feed(ref)
        assertEquals("Legacy", migrated.label); assertEquals(3L, migrated.configurationVersion); assertEquals(7L, migrated.requestedGeneration)
        assertNull(migrated.activeGeneration); assertNull(migrated.refreshedAtMillis)
        assertEquals("https://fixture.invalid/legacy", store.endpoint(ref))
        assertTrue(store.channelPage(ref).isEmpty()); assertNull(store.validators(ref))
        assertNull(store.prepareRefresh(ref, window).validators)
        assertEquals(RefreshDecision.PUBLISH, publish(ref, xml(programme("Fresh news"))))
        assertEquals("Fresh news", store.airingMatches(1, listOf(ref.feedId), "NEWS", start).single().programme.titles.first().text)
        store.close()
        context.openOrCreateDatabase(name, 0, null).use { db ->
            assertEquals(6, db.version)
            db.rawQuery("PRAGMA auto_vacuum", null).use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
            db.rawQuery("SELECT typeof(active_stage) FROM feeds", null).use { assertTrue(it.moveToFirst()); assertEquals("integer", it.getString(0)) }
            db.rawQuery("SELECT COUNT(*) FROM programmes", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        }
        store = IptvGuideStore(context, name, secrets)
    }
    @Test fun sportsMatchesListLiveAndUpcomingSportOnly() {
        val ref = feed()
        val rows = programme("Premier League: Leeds v Hull", "20261005000000 +0000", "20261005020000 +0000") +
            programme("Premier League Highlights", "20261005020000 +0000", "20261005030000 +0000") +
            programme("Cooking at Home", "20261005030000 +0000", "20261005040000 +0000") +
            "<programme channel=\"one\" start=\"20261005040000 +0000\" stop=\"20261005060000 +0000\"><title>Saturday Rugby</title><category>Sports</category></programme>" +
            programme("AFL: Carlton v Geelong", "20261005100000 +0000", "20261005120000 +0000")
        assertEquals(RefreshDecision.PUBLISH, publish(ref, xml(rows)))
        val now = start + 30 * 60_000
        val found = store.sportsMatches(1, listOf(ref.feedId), now, now + 6 * 3_600_000)
        assertEquals(listOf("Premier League: Leeds v Hull", "Saturday Rugby"), found.map { it.programme.titles.first().text })
        assertEquals(listOf("Sports"), found[1].programme.categories)
        assertEquals(GuideKey(ref.feedId, "one"), found.first().key)
        assertTrue(store.sportsMatches(1, listOf(ref.feedId), start + 7 * 3_600_000, start + 8 * 3_600_000).isEmpty())
        assertTrue(store.sportsMatches(2, emptyList(), now, now).isEmpty())
    }
    @Test fun sportsMatchesCanSkipProgrammesThatStartedLongBefore() {
        val ref = feed()
        val rows = programme("Premier League: Leeds v Hull", "20261005000000 +0000", "20261005080000 +0000") +
            programme("AFL: Carlton v Geelong", "20261005030000 +0000", "20261005060000 +0000")
        assertEquals(RefreshDecision.PUBLISH, publish(ref, xml(rows)))
        val slice = start + 4 * 3_600_000
        assertEquals(2, store.sportsMatches(1, listOf(ref.feedId), slice, slice + 3_600_000).size)
        assertEquals(listOf("AFL: Carlton v Geelong"), store.sportsMatches(1, listOf(ref.feedId), slice, slice + 3_600_000, lookbackMillis = 3 * 3_600_000L)
            .map { it.programme.titles.first().text })
    }
    @Test fun publishingReplacesTheOldStageWithoutLeftovers() {
        val ref = feed()
        repeat(3) { round -> assertEquals(RefreshDecision.PUBLISH, publish(ref, xml((1..3).joinToString("") { slot -> programme("Round $round $slot", "2026100500${slot}000 +0000", "2026100500${slot}500 +0000") }))) }
        assertEquals(3, store.programmes(ref, "one", window).programmes.size)
        store.close()
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.rawQuery("SELECT (SELECT COUNT(*) FROM stages),(SELECT COUNT(*) FROM programmes),(SELECT COUNT(DISTINCT stage) FROM channels)", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)); assertEquals(3, it.getInt(1)); assertEquals(1, it.getInt(2))
            }
        }
        store = IptvGuideStore(context, name, AndroidIptvSecretBox(alias))
    }
    @Test fun gzipInputAndExactDuplicatesAreHandledWithoutDuplicateRows() {
        val ref = feed()
        val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(xml(programme() + programme()).toByteArray()) } }.toByteArray()
        assertEquals(RefreshDecision.PUBLISH, store.importGuide(store.beginRefresh(ref), bytes.inputStream(), window))
        assertEquals(1, store.programmes(ref, "one", window).programmes.size)
    }
}
