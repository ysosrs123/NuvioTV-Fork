package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.dto.ReportStatusItemDto
import com.nuvio.tv.data.remote.dto.ReportStatusResponseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportFollowTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun followed(code: String, status: ReportStatus = ReportStatus.Sent, ageDays: Long = 1) =
        SentReport(code, "bug", now - ageDays * day, token = "abcd2345efgh", status = status)

    @Test fun `the key is taken from the follow link and nothing else`() {
        assertEquals("abcd2345efgh", followToken("https://relay.example/s/ARC-4MFX?t=abcd2345efgh"))
        assertEquals("abcd2345efgh", followToken("https://relay.example/s/ARC-4MFX?x=1&t=abcd2345efgh&y=2"))
        assertNull(followToken("https://relay.example/s/ARC-4MFX"))
        assertNull(followToken("https://relay.example/s/ARC-4MFX?t=ab"))
        assertNull(followToken("https://relay.example/s/ARC-4MFX?t=abc\tdefgh"))
        assertNull(followToken(null))
    }

    @Test fun `key, status, dates and what the viewer saw survive saving and reading`() {
        val report = followed("ARC-4MFX", ReportStatus.Fixed).copy(
            fixedIn = "1.2.0-arc.1",
            seenAt = mapOf(ReportStatus.Read to 11L, ReportStatus.Fixed to 22L),
            viewed = ReportStatus.Read,
            announced = ReportStatus.Fixed
        )
        val list = listOf(report, SentReport("ARC-OLD1", "playback", 5L))
        assertEquals(list, SentReportsList.decode(SentReportsList.encode(list)))
    }

    @Test fun `rows saved by older versions still read, without a key`() {
        val old = SentReportsList.decode("ARC-ABCD\tplayback\t1762560000000")
        assertEquals(SentReport("ARC-ABCD", "playback", 1762560000000L), old.single())
        assertFalse(old.single().followed)
    }

    @Test fun `only open, followed reports younger than 90 days are asked about`() {
        val reports = listOf(
            followed("ARC-AAAA"),
            followed("ARC-BBBB", ReportStatus.CauseFound),
            followed("ARC-CCCC", ReportStatus.Fixed),
            followed("ARC-DDDD", ReportStatus.Closed),
            followed("ARC-EEEE", ReportStatus.Gone),
            followed("ARC-FFFF", ReportStatus.Outside),
            followed("ARC-GGGG", ageDays = 91),
            SentReport("ARC-HHHH", "bug", now - day)
        )
        assertEquals(listOf("ARC-AAAA", "ARC-BBBB", "ARC-FFFF"), ReportFollow.toAsk(reports, now).map { it.code })
    }

    @Test fun `a check is due once a day, only when switched on and something is open`() {
        val open = listOf(followed("ARC-AAAA"))
        assertTrue(ReportFollow.due(true, open, now, lastCheckMs = 0L))
        assertTrue(ReportFollow.due(true, open, now, lastCheckMs = now - 21 * 60 * 60 * 1000))
        assertFalse(ReportFollow.due(true, open, now, lastCheckMs = now - 3 * 60 * 60 * 1000))
        assertFalse(ReportFollow.due(false, open, now, lastCheckMs = 0L))
        assertFalse(ReportFollow.due(true, listOf(followed("ARC-CCCC", ReportStatus.Fixed)), now, lastCheckMs = 0L))
        assertTrue("a clock set back does not stop checks", ReportFollow.due(true, open, now, lastCheckMs = now + day))
    }

    @Test fun `a new status is dated, marked new and announced once`() {
        val before = listOf(followed("ARC-AAAA", ReportStatus.Read).copy(viewed = ReportStatus.Read, announced = ReportStatus.Read))
        val after = ReportFollow.applied(before, listOf(ReportStatusResult("ARC-AAAA", ReportStatus.Fixed, "1.2.0-arc.1")), now)
        val report = after.single()
        assertEquals(ReportStatus.Fixed, report.status)
        assertEquals("1.2.0-arc.1", report.fixedIn)
        assertEquals(now, report.seenAt[ReportStatus.Fixed])
        assertTrue(report.isNew)
        assertTrue(report.toAnnounce)
        assertFalse(report.copy(announced = ReportStatus.Fixed).toAnnounce)
        assertTrue("the dot stays after the notice", report.copy(announced = ReportStatus.Fixed).isNew)
        assertEquals(1, ReportFollow.newCount(after))
    }

    @Test fun `the same status, unknown reports and settled reports are left alone`() {
        val read = followed("ARC-AAAA", ReportStatus.Read)
        assertEquals(listOf(read), ReportFollow.applied(listOf(read), listOf(ReportStatusResult("ARC-AAAA", ReportStatus.Read)), now))
        val fixed = followed("ARC-CCCC", ReportStatus.Fixed)
        assertEquals(listOf(fixed), ReportFollow.applied(listOf(fixed), listOf(ReportStatusResult("ARC-CCCC", ReportStatus.Closed)), now))
        assertEquals(listOf(read), ReportFollow.applied(listOf(read), listOf(ReportStatusResult("ARC-ZZZZ", ReportStatus.Fixed)), now))
    }

    @Test fun `not caused by Arc can still change when it proves to be Arc's`() {
        val outside = followed("ARC-FFFF", ReportStatus.Outside)
        val after = ReportFollow.applied(listOf(outside), listOf(ReportStatusResult("ARC-FFFF", ReportStatus.CauseFound)), now)
        assertEquals(ReportStatus.CauseFound, after.single().status)
    }

    @Test fun `sent and deleted reports never show as new`() {
        assertFalse(followed("ARC-AAAA").isNew)
        assertFalse(followed("ARC-EEEE", ReportStatus.Gone).isNew)
    }

    @Test fun `a version that could break a saved row is dropped`() {
        val read = followed("ARC-AAAA", ReportStatus.Read)
        val after = ReportFollow.applied(listOf(read), listOf(ReportStatusResult("ARC-AAAA", ReportStatus.Fixed, "1.2\t0")), now)
        assertNull(after.single().fixedIn)
    }

    @Test fun `relay answers become statuses, unknown and damaged items are skipped`() {
        val body = ReportStatusResponseDto(
            listOf(
                ReportStatusItemDto("ARC-AAAA", "fixed", "1.2.0-arc.1"),
                ReportStatusItemDto("ARC-BBBB", "unknown"),
                ReportStatusItemDto(null, "read"),
                ReportStatusItemDto("ARC-CCCC", "outside")
            )
        )
        assertEquals(
            listOf(
                ReportStatusResult("ARC-AAAA", ReportStatus.Fixed, "1.2.0-arc.1"),
                ReportStatusResult("ARC-CCCC", ReportStatus.Outside)
            ),
            reportStatusResults(body)
        )
        assertEquals(emptyList<ReportStatusResult>(), reportStatusResults(null))
    }
}
