package com.nuvio.tv.data.iptv

import android.database.sqlite.SQLiteFullException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.iptv.RefreshDecision
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.security.KeyStore
import java.time.Instant
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
        assertEquals("20261005000000 +0000", row.start.raw)
        assertTrue(row.canSchedulePrecisely)
        assertEquals("one", store.channelPage(ref).single().externalId)
        assertEquals("v1", store.validators(ref)!!.etag)
        store.close()
        assertFalse(context.getDatabasePath(name).readBytes().toString(Charsets.ISO_8859_1).contains("UNIQUE_GUIDE_SECRET"))
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
        publish(ref, xml(programme("A") + programme("B") + programme("Unknown", to = null)))
        val page = store.programmes(ref, "one", window, limit = 2)
        assertTrue(page.hasMore); assertEquals(2, page.programmes.size)
        assertEquals(1, store.programmes(ref, "one", window, offset = 2, limit = 2).programmes.size)
        val later = store.programmes(ref, "one", IptvGuideWindow(start + 3_600_000, start + 7_200_000))
        assertTrue(later.programmes.isEmpty())
        assertFalse(store.programmes(ref, "one", window).programmes.single { it.stop == null }.canSchedulePrecisely)
    }
    @Test fun emptyOrLargeShrinkNeedsReviewAndDoesNotPromoteValidators() {
        val ref = feed(); publish(ref, xml((1..4).joinToString("") { programme("Show $it") }))
        assertEquals(RefreshDecision.SHRINK_REQUIRES_REVIEW, publish(ref))
        assertEquals(RefreshDecision.EMPTY_REQUIRES_REVIEW, publish(ref, "<tv>$channel</tv>"))
        assertEquals(4, store.programmes(ref, "one", window).programmes.size)
    }
    @Test fun unknownChannelProgrammesAreDroppedAndDuplicateChannelNamesMerge() {
        val ref = feed(); publish(ref)
        assertEquals(RefreshDecision.EMPTY_REQUIRES_REVIEW, publish(ref, xml(programme(id = "missing"))))
        assertEquals(RefreshDecision.PUBLISH, publish(ref, xml(programme() + programme("Elsewhere", id = "missing"))))
        assertEquals(1, store.programmes(ref, "one", window).programmes.size)
        assertEquals(RefreshDecision.PUBLISH, publish(ref, "<tv>$channel<channel id=\"one\"><display-name>Different</display-name></channel>${programme()}</tv>"))
        assertEquals(1, store.channelPage(ref).size)
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
        val large = xml((1..150).joinToString("") { programme("Large $it " + "x".repeat(2000)) })
        assertThrows(SQLiteFullException::class.java) { publish(ref, large) }
        assertEquals("Morning", store.programmes(ref, "one", window).programmes.single().titles.first().text)
        store.close(); store = IptvGuideStore(context, name, AndroidIptvSecretBox(alias))
        assertEquals(RefreshDecision.PUBLISH, publish(ref, large))
        assertTrue(store.programmes(ref, "one", window).hasMore)
    }
    @Test fun gzipInputAndExactDuplicatesAreHandledWithoutDuplicateRows() {
        val ref = feed()
        val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(xml(programme() + programme()).toByteArray()) } }.toByteArray()
        assertEquals(RefreshDecision.PUBLISH, store.importGuide(store.beginRefresh(ref), bytes.inputStream(), window))
        assertEquals(1, store.programmes(ref, "one", window).programmes.size)
    }
}
