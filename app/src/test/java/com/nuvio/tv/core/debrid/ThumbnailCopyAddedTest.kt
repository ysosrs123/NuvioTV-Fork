package com.nuvio.tv.core.debrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailCopyAddedTest {
    private class MemoryStorage(var value: String? = null, var ok: Boolean = true) : AddedCopyStorage {
        var saves = 0
        override fun load(): String? = value
        override fun save(value: String): Boolean {
            saves++
            if (ok) this.value = value
            return ok
        }
    }

    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun recordsAreWrittenAtOnceAndSurviveARestart() {
        val disk = MemoryStorage()
        val records = AddedCopyRecords(disk)
        assertTrue(records.add(AddedCopy(41, "ABCDEF", 1_000L, 500L, 2_000L)))
        assertEquals(1, disk.saves)
        val again = AddedCopyRecords(disk)
        assertEquals(listOf(AddedCopy(41, "abcdef", 1_000L, 500L, 2_000L)), again.all())
        again.forget(41)
        assertTrue(AddedCopyRecords(disk).all().isEmpty())
    }

    @Test
    fun recordsLeftOnDiskAreSeenAtTheNextStart() {
        val disk = MemoryStorage()
        assertFalse(ThumbnailCopyAdded(AddedCopyRecords(disk)).hasRecords())
        AddedCopyRecords(disk).add(AddedCopy(41, "abcdef", 1_000L))
        assertTrue(ThumbnailCopyAdded(AddedCopyRecords(disk)).hasRecords())
    }

    @Test
    fun aRecordThatCannotBeWrittenSaysSo() {
        val records = AddedCopyRecords(MemoryStorage(ok = false))
        assertFalse(records.add(AddedCopy(41, "abcdef", 1_000L)))
        assertNotNull(records.of(41))
    }

    @Test
    fun brokenStoredTextIsSkipped() {
        val records = AddedCopyRecords(MemoryStorage("41,abc,5,0,0;junk;x,abc,1,0,0;41,abc,5,0;42,def,7,9,11"))
        assertEquals(listOf(41, 42), records.all().map { it.torrentId })
        assertEquals(9L, records.of(42)?.fromServerMs)
        assertEquals(11L, records.of(42)?.toServerMs)
    }

    @Test
    fun playbackClaimReleasesTheRecord() {
        val disk = MemoryStorage()
        val records = AddedCopyRecords(disk)
        records.add(AddedCopy(41, "abcdef", 0L))
        records.add(AddedCopy(42, "123456", 0L))
        assertEquals(listOf(41), records.claim("ABCDEF").map { it.torrentId })
        assertNull(records.of(41))
        assertNotNull(records.of(42))
        assertTrue(records.claimed("abcdef", null))
        assertEquals(listOf(42), records.claim(null, 42).map { it.torrentId })
        assertTrue(records.claimed(null, 42))
        assertTrue(AddedCopyRecords(disk).all().isEmpty())
        assertTrue(records.claim(null, null).isEmpty())
    }

    @Test
    fun aMarkOnlyRemembersTheClaim() {
        val disk = MemoryStorage()
        val records = AddedCopyRecords(disk)
        records.add(AddedCopy(41, "abcdef", 0L))
        val saves = disk.saves
        assertNull(records.mark("ABCDEF"))
        assertTrue(records.claimed("abcdef", null))
        assertNotNull(records.of(41))
        assertEquals(saves, disk.saves)
    }

    @Test
    fun aClaimDuringADeleteGetsItToWaitFor() {
        val records = AddedCopyRecords(MemoryStorage())
        val record = AddedCopy(41, "abcdef", 0L)
        records.add(record)
        val d = records.beginDelete(record)!!
        val wait = records.mark("abcdef")
        assertNotNull(wait)
        assertFalse(wait!!.isCompleted)
        assertNotNull(records.mark(null, 41))
        records.endDelete(record, d)
        assertTrue(wait.isCompleted)
        assertNull(records.mark(null, 41))
        assertNull(records.mark("abcdef"))
        assertNull(records.beginDelete(record))
    }

    @Test
    fun aClaimBeforeTheDeleteStopsIt() {
        val records = AddedCopyRecords(MemoryStorage())
        val record = AddedCopy(41, "abcdef", 0L)
        records.add(record)
        records.mark(null, 41)
        assertNull(records.beginDelete(record))
        val other = AddedCopy(42, "123456", 0L)
        assertNull(records.beginDelete(other))
    }

    @Test
    fun recordsOlderThanSixHoursAreGivenUpNotRemoved() {
        val records = AddedCopyRecords(MemoryStorage())
        val now = 100 * hour
        records.add(AddedCopy(41, "a1", now - 7 * hour))
        records.add(AddedCopy(42, "a2", now - 5 * hour))
        assertEquals(listOf(41), records.giveUp(now).map { it.torrentId })
        assertNull(records.of(41))
        assertNotNull(records.of(42))
        assertTrue(records.giveUp(now).isEmpty())
    }

    @Test
    fun leftoversAreOlderThanTenMinutesAndNotInUse() {
        val records = AddedCopyRecords(MemoryStorage())
        val now = 100 * minute
        records.add(AddedCopy(41, "a1", now - 11 * minute))
        records.add(AddedCopy(42, "a2", now - 5 * minute))
        records.add(AddedCopy(43, "a3", now - 60 * minute))
        assertEquals(listOf(41, 43), records.leftovers(now, emptySet()).map { it.torrentId })
        assertEquals(listOf(41), records.leftovers(now, setOf(43)).map { it.torrentId })
    }

    @Test
    fun removalOnlyForOurUnclaimedUnchangedItemCreatedDuringTheAdd() {
        val ours = AddedCopy(41, "abcdef", 0L, fromServerMs = 10_000L, toServerMs = 20_000L)
        val at = 15_000L
        assertEquals(CopyRemoval.NOT_OURS, CopyRemoval.decide(null, false, true, "abcdef", at))
        assertEquals(CopyRemoval.WATCHING, CopyRemoval.decide(ours, true, true, "abcdef", at))
        assertEquals(CopyRemoval.CHANGED, CopyRemoval.decide(ours, false, true, "999999", at))
        assertEquals(CopyRemoval.CHANGED, CopyRemoval.decide(ours, false, true, null, at))
        assertEquals(CopyRemoval.GONE, CopyRemoval.decide(ours, false, false, null, null))
        assertEquals(CopyRemoval.OLDER, CopyRemoval.decide(ours, false, true, "abcdef", 9_999L))
        assertEquals(CopyRemoval.OLDER, CopyRemoval.decide(ours, false, true, "abcdef", 20_001L))
        assertEquals(CopyRemoval.OLDER, CopyRemoval.decide(ours, false, true, "abcdef", null))
        assertEquals(CopyRemoval.OLDER, CopyRemoval.decide(ours.copy(fromServerMs = 0L), false, true, "abcdef", at))
        assertEquals(CopyRemoval.OLDER, CopyRemoval.decide(ours.copy(toServerMs = 0L), false, true, "abcdef", at))
        assertEquals(CopyRemoval.DELETE, CopyRemoval.decide(ours, false, true, "ABCDEF", at))
    }

    @Test
    fun torboxTimesAreRead() {
        val utc = 1_791_359_400_000L
        assertEquals(utc, TorboxTime.parse("2026-10-07T07:50:00Z"))
        assertEquals(utc + 123, TorboxTime.parse("2026-10-07T07:50:00.123456+00:00"))
        assertEquals(utc, TorboxTime.parse("2026-10-07T17:50:00+10:00"))
        assertEquals(utc, TorboxTime.parse("2026-10-07 07:50:00"))
        assertNull(TorboxTime.parse("yesterday"))
        assertNull(TorboxTime.parse(""))
        assertNull(TorboxTime.parse(null))
    }

    @Test
    fun threeAddsPerTitleAndARefusalStopsAll() {
        val limits = TorboxAddLimits()
        repeat(3) {
            assertNull(limits.refusal("film"))
            limits.counted("film")
        }
        assertNotNull(limits.refusal("film"))
        assertNull(limits.refusal("other"))
        assertFalse(limits.answered(500, "server error"))
        assertNull(limits.refusal("other"))
        assertTrue(limits.answered(429, null))
        assertNotNull(limits.refusal("other"))
        assertTrue(TorboxAddLimits.refuses(400, "ACTIVE_LIMIT reached"))
        assertTrue(TorboxAddLimits.refuses(200, "Too many requests"))
        assertFalse(TorboxAddLimits.refuses(409, "not cached"))
    }

    @Test
    fun sourceRoutes() {
        val tb = DebridProviders.TORBOX_ID
        val pm = DebridProviders.PREMIUMIZE_ID
        val rd = DebridProviders.REAL_DEBRID_ID
        assertEquals(CopyRoute.Torbox, CopyRoute.of(tb, hasHash = true, torboxKey = true, premiumizeKey = false))
        assertTrue(CopyRoute.of(tb, hasHash = true, torboxKey = false, premiumizeKey = true) is CopyRoute.LeftOut)
        assertEquals(CopyRoute.Premiumize, CopyRoute.of(pm, hasHash = true, torboxKey = true, premiumizeKey = true))
        assertEquals(CopyRoute.Resolve, CopyRoute.of(rd, hasHash = true, torboxKey = true, premiumizeKey = true))
        assertEquals(CopyRoute.Resolve, CopyRoute.of("alldebrid", hasHash = true, torboxKey = false, premiumizeKey = false))
        assertEquals(CopyRoute.Resolve, CopyRoute.of("debridlink", hasHash = true, torboxKey = true, premiumizeKey = true))
        assertEquals(CopyRoute.Torbox, CopyRoute.of(null, hasHash = true, torboxKey = true, premiumizeKey = true))
        assertEquals(CopyRoute.Premiumize, CopyRoute.of(null, hasHash = true, torboxKey = false, premiumizeKey = true))
        assertNull(CopyRoute.of(null, hasHash = true, torboxKey = false, premiumizeKey = false))
        assertNull(CopyRoute.of(null, hasHash = false, torboxKey = true, premiumizeKey = true))
    }
}
