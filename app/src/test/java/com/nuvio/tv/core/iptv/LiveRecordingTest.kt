package com.nuvio.tv.core.iptv

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LiveRecordingTest {
    private val now = 1_791_331_200_000L
    private val minute = 60_000L
    private val hour = 60 * minute

    @Test fun transitionsOnlyMoveForward() {
        assertTrue(RecordingTransitions.allowed(RecordingStatus.SCHEDULED, RecordingStatus.RECORDING))
        assertTrue(RecordingTransitions.allowed(RecordingStatus.SCHEDULED, RecordingStatus.CANCELLED))
        assertTrue(RecordingTransitions.allowed(RecordingStatus.RECORDING, RecordingStatus.PARTIAL))
        assertFalse(RecordingTransitions.allowed(RecordingStatus.SCHEDULED, RecordingStatus.DONE))
        assertFalse(RecordingTransitions.allowed(RecordingStatus.RECORDING, RecordingStatus.SCHEDULED))
        RecordingStatus.entries.filter { it.finished }.forEach { from ->
            RecordingStatus.entries.forEach { to -> assertFalse("$from -> $to", RecordingTransitions.allowed(from, to)) }
        }
        assertEquals(setOf(RecordingStatus.SCHEDULED, RecordingStatus.RECORDING), RecordingStatus.entries.filter { it.holdsConnection }.toSet())
    }

    @Test fun outcomeReflectsBytesGapsAndStops() {
        assertEquals(RecordingOutcome(RecordingStatus.DONE, null), RecordingTransitions.outcome(10, null, 0, null))
        assertEquals(RecordingOutcome(RecordingStatus.DONE, null), RecordingTransitions.outcome(10, null, 0, RecordingStop.USER))
        assertEquals(RecordingOutcome(RecordingStatus.DONE, null), RecordingTransitions.outcome(10, null, 0, RecordingStop.ENDED))
        assertEquals(RecordingOutcome(RecordingStatus.PARTIAL, RecordingFailure.NETWORK), RecordingTransitions.outcome(10, null, 1, RecordingStop.ENDED))
        assertEquals(RecordingOutcome(RecordingStatus.PARTIAL, RecordingFailure.NETWORK), RecordingTransitions.outcome(10, null, 2, RecordingStop.USER))
        assertEquals(RecordingOutcome(RecordingStatus.PARTIAL, RecordingFailure.LOW_STORAGE), RecordingTransitions.outcome(10, RecordingFailure.LOW_STORAGE, 0, null))
        assertEquals(RecordingOutcome(RecordingStatus.PARTIAL, RecordingFailure.TIME_LIMIT), RecordingTransitions.outcome(10, null, 0, RecordingStop.TIME_LIMIT))
        assertEquals(RecordingOutcome(RecordingStatus.CANCELLED, null), RecordingTransitions.outcome(0, null, 0, RecordingStop.USER))
        assertEquals(RecordingOutcome(RecordingStatus.FAILED, RecordingFailure.NETWORK), RecordingTransitions.outcome(0, null, 0, null))
        assertEquals(RecordingOutcome(RecordingStatus.FAILED, RecordingFailure.NO_CONNECTION), RecordingTransitions.outcome(0, RecordingFailure.NO_CONNECTION, 0, null))
        assertEquals(RecordingOutcome(RecordingStatus.PARTIAL, RecordingFailure.INTERRUPTED), RecordingTransitions.interrupted(1))
        assertEquals(RecordingOutcome(RecordingStatus.FAILED, RecordingFailure.INTERRUPTED), RecordingTransitions.interrupted(0))
    }

    @Test fun recordNowFollowsProgrammeEndWithPostRollAndCap() {
        assertEquals(RecordingWindow(now, now + 30 * minute + RecordingPlan.POST_ROLL_MILLIS), RecordingPlan.now(now, now + 30 * minute))
        assertEquals(RecordingWindow(now, now + RecordingPlan.DEFAULT_DURATION_MILLIS), RecordingPlan.now(now))
        assertEquals(RecordingWindow(now, now + 6 * hour), RecordingPlan.now(now, now + 9 * hour))
        assertNull(RecordingPlan.now(now, now))
    }

    @Test fun programmeWindowAddsRollsStartsNowWhenLateAndCapsSixHours() {
        assertEquals(RecordingWindow(now + hour - RecordingPlan.PRE_ROLL_MILLIS, now + 2 * hour + RecordingPlan.POST_ROLL_MILLIS),
            RecordingPlan.programme(now, now + hour, now + 2 * hour))
        assertEquals(RecordingWindow(now, now + hour + RecordingPlan.POST_ROLL_MILLIS), RecordingPlan.programme(now, now - hour, now + hour))
        assertEquals(RecordingWindow(now + hour - RecordingPlan.PRE_ROLL_MILLIS, now + hour - RecordingPlan.PRE_ROLL_MILLIS + 6 * hour),
            RecordingPlan.programme(now, now + hour, now + 10 * hour))
        assertEquals(RecordingWindow(now + hour - RecordingPlan.PRE_ROLL_MILLIS, now + 2 * hour + RecordingPlan.POST_ROLL_MILLIS),
            RecordingPlan.programme(now, now + hour, null))
        assertNull(RecordingPlan.programme(now, now - 2 * hour, now - hour))
        assertNull(RecordingPlan.programme(now, now + hour, now + hour))
    }

    @Test fun conflictCountsPeakOverlapPerAccount() {
        val candidate = RecordingSlot("a", now, now + 2 * hour)
        val sequential = listOf(RecordingSlot("a", now - hour, now + hour), RecordingSlot("a", now + hour, now + 3 * hour))
        assertEquals(1, recordingPeak(sequential, candidate))
        assertFalse(recordingConflicts(sequential, candidate, 2))
        assertTrue(recordingConflicts(sequential, candidate, 1))
        val stacked = sequential + RecordingSlot("a", now + 30 * minute, now + 90 * minute)
        assertEquals(2, recordingPeak(stacked, candidate))
        assertTrue(recordingConflicts(stacked, candidate, 2))
        assertFalse(recordingConflicts(stacked, candidate, 3))
        val other = listOf(RecordingSlot("b", now, now + 2 * hour), RecordingSlot("a", now + 2 * hour, now + 3 * hour), RecordingSlot("a", now - hour, now))
        assertEquals(0, recordingPeak(other, candidate))
        assertFalse(recordingConflicts(other, candidate, 1))
    }

    @Test fun alarmActionDistinguishesFutureDueAndMissed() {
        val window = RecordingWindow(now + hour, now + 2 * hour)
        assertEquals(RecordingAlarmAction.ARM, recordingAlarmAction(window, now))
        assertEquals(RecordingAlarmAction.START_NOW, recordingAlarmAction(window, now + hour))
        assertEquals(RecordingAlarmAction.START_NOW, recordingAlarmAction(window, now + hour - minute, earlyMillis = 2 * minute))
        assertEquals(RecordingAlarmAction.START_NOW, recordingAlarmAction(window, now + 2 * hour - 1))
        assertEquals(RecordingAlarmAction.MISSED, recordingAlarmAction(window, now + 2 * hour))
    }

    @Test fun retryBacksOffAndGivesUpOnlyWithoutData() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 30_000L), (0..6).map(RecordingRetry::delayMillis))
        assertEquals(1_000L, RecordingRetry.delayMillis(-1))
        assertFalse(RecordingRetry.giveUp(5, everReceived = false))
        assertTrue(RecordingRetry.giveUp(6, everReceived = false))
        assertFalse(RecordingRetry.giveUp(100, everReceived = true))
    }

    @Test fun storageKeepsReserveAndStartMargin() {
        val reserve = RecordingStorage.RESERVE_BYTES
        assertTrue(RecordingStorage.canContinue(reserve))
        assertFalse(RecordingStorage.canContinue(reserve - 1))
        assertFalse(RecordingStorage.canStart(reserve))
        assertTrue(RecordingStorage.canStart(reserve + RecordingStorage.START_MARGIN_BYTES))
        assertTrue(RecordingStorage.canStart(RecordingStorage.START_MARGIN_BYTES, reserveBytes = 0))
        val gigabyte = 1024L * 1024 * 1024
        assertEquals(2_500L * 1024 * 1024, RecordingStorage.estimatedBytes(hour))
        assertEquals(RecordingStorage.estimatedBytes(6 * hour), RecordingStorage.estimatedBytes(9 * hour))
        assertEquals(0L, RecordingStorage.estimatedBytes(-1))
        val needed = reserve + RecordingStorage.START_MARGIN_BYTES + RecordingStorage.estimatedBytes(hour)
        assertTrue(RecordingStorage.hasRoomFor(needed, hour))
        assertFalse(RecordingStorage.hasRoomFor(needed - 1, hour))
        assertFalse(RecordingStorage.hasRoomFor(needed, hour, committedBytes = gigabyte))
        assertTrue(RecordingStorage.hasRoomFor(4 * gigabyte, hour))
        assertFalse(RecordingStorage.hasRoomFor(4 * gigabyte, 2 * hour))
    }

    @Test fun fileNamesAreSafeBoundedAndUnique() {
        val zone = ZoneId.of("Australia/Sydney")
        val name = RecordingFiles.name("UK: BBC One/HD", "News at Six: \"Special\" <live>", now, zone)
        assertEquals("UK BBC One HD - News at Six Special live - 07-Oct-26 1100.ts", name)
        assertEquals("$name.part", RecordingFiles.partial(name))
        assertFalse(name.contains('/') || name.contains(':') || name.contains('"'))
        val long = RecordingFiles.name("x".repeat(500), "y".repeat(500), now, zone)
        assertTrue(long.length < 200)
        assertEquals("07-Oct-26 1100.ts", RecordingFiles.name("///", null, now, zone))
        assertEquals("Sky Sports Tennis HD - Live Tennis ATP Shanghai Masters 2026 Day 2 Live - 07-Oct-26 1100.ts",
            RecordingFiles.name("Sky Sports Tennis ᴴᴰ", "Live Tennis: ATP Shanghai Masters 2026 : Day 2 ᴸᶦᵛᵉ", now, zone))
        assertEquals("Ｆｕｌｌ 1 - 07-Oct-26 1100.ts".replace("Ｆｕｌｌ", "Full"), RecordingFiles.name("Ｆｕｌｌ ¹", null, now, zone))
        val busy = setOf("BBC One - News - 07-Oct-26 1100.ts", "BBC One - News - 07-Oct-26 1100 (2).ts")
        assertEquals("BBC One - News - 07-Oct-26 1100 (3).ts", RecordingFiles.name("BBC One", "News", now, zone) { it in busy })
        assertEquals("天気予報々 - 07-Oct-26 1100.ts", RecordingFiles.name("天気予報々", null, now, zone))
    }

    @Test fun nextCopyNameKeepsTheFormatFolderAndLimits() {
        assertEquals("BBC One - News - 10-Oct-26 1355 (2).ts", RecordingFiles.next("BBC One - News - 10-Oct-26 1355.ts"))
        assertEquals("BBC One - News - 10-Oct-26 1355 (3).ts", RecordingFiles.next("BBC One - News - 10-Oct-26 1355 (2).ts"))
        assertEquals("TV/Rec/News - 10-Oct-26 1355 (12).ts", RecordingFiles.next("TV/Rec/News - 10-Oct-26 1355 (11).ts"))
        assertEquals("10-Oct-26 1355 (2).ts", RecordingFiles.next("10-Oct-26 1355.ts"))
        assertEquals("old name (2)", RecordingFiles.next("old name"))
        val zone = ZoneId.of("Australia/Sydney")
        val long = RecordingFiles.name("x".repeat(500), "y".repeat(500), now, zone)
        var name = long
        repeat(RecordingFiles.MAX_COPIES) {
            name = RecordingFiles.next(name)
            assertTrue(name.length <= RecordingFiles.MAX_NAME_CHARS && name.toByteArray().size <= RecordingFiles.MAX_NAME_BYTES)
            assertTrue(name.startsWith("x") && name.contains(" - 07-Oct-26 1100 ("))
        }
        assertTrue(name.endsWith(" - 07-Oct-26 1100 (${RecordingFiles.MAX_COPIES + 1}).ts"))
        val wide = RecordingFiles.name("天".repeat(60), "気".repeat(100), now, zone)
        assertTrue(RecordingFiles.next(wide).toByteArray().size <= RecordingFiles.MAX_NAME_BYTES)
        assertTrue(RecordingFiles.next(wide).endsWith("07-Oct-26 1100 (2).ts"))
        val seen = HashSet<String>()
        generateSequence("a - 07-Oct-26 1100.ts", RecordingFiles::next).take(150).forEach { assertTrue(seen.add(it)) }
    }

    @Test fun spanUsesProgrammeTimesInsidePadding() {
        val pre = RecordingPlan.PRE_ROLL_MILLIS
        val post = RecordingPlan.POST_ROLL_MILLIS
        assertEquals(RecordingSpan(now - pre, now + hour + post, now, now + hour), RecordingSpan.of(now - pre, now + hour + post, now, now + hour))
        assertEquals(RecordingSpan(now, now + hour, now, now + hour), RecordingSpan.of(now, now + hour, null, null))
        assertEquals(RecordingSpan(now, now + hour, now, now + hour - post), RecordingSpan.of(now, now + hour, now - hour, now + hour - post))
    }

    @Test fun backToBackProgrammesSplitPaddingAtTheBoundary() {
        val first = RecordingPlan.programme(now, now + hour, now + 2 * hour)!!
        val second = RecordingPlan.programme(now, now + 2 * hour, now + 3 * hour)!!
        val existing = RecordingSpan.of(first.startMillis, first.stopMillis, now + hour, now + 2 * hour)
        val candidate = RecordingSpan.of(second.startMillis, second.stopMillis, now + 2 * hour, now + 3 * hour)
        assertTrue(existing.stopMillis > candidate.startMillis)
        assertFalse(recordingConflicts(listOf(RecordingSlot("a", existing.coreStartMillis, existing.coreStopMillis)),
            RecordingSlot("a", candidate.coreStartMillis, candidate.coreStopMillis), 1))
        val trim = trimRecordingPadding(candidate, listOf(existing))
        assertEquals(now + 2 * hour, trim.candidate.startMillis)
        assertEquals(candidate.stopMillis, trim.candidate.stopMillis)
        assertEquals(now + 2 * hour, trim.neighbours.single().stopMillis)
        assertEquals(existing.startMillis, trim.neighbours.single().startMillis)
        val earlier = trimRecordingPadding(existing, listOf(candidate))
        assertEquals(now + 2 * hour, earlier.candidate.stopMillis)
        assertEquals(now + 2 * hour, earlier.neighbours.single().startMillis)
    }

    @Test fun trimLeavesGapsOverlapsAndDistantRecordingsAlone() {
        val candidate = RecordingSpan.of(now + hour - minute, now + 2 * hour + 2 * minute, now + hour, now + 2 * hour)
        val far = RecordingSpan.of(now + 3 * hour, now + 4 * hour, now + 3 * hour, now + 4 * hour)
        val overlapping = RecordingSpan.of(now + 90 * minute, now + 3 * hour, now + 90 * minute, now + 3 * hour)
        val gap = RecordingSpan.of(now, now + hour - 5 * minute, now, now + hour - 5 * minute)
        val trim = trimRecordingPadding(candidate, listOf(far, overlapping, gap))
        assertEquals(candidate, trim.candidate)
        assertEquals(listOf(far, overlapping, gap), trim.neighbours)
    }

    @Test fun customStartEarlyAndRunOverAreUsed() {
        val now = 1_800_000_000_000L; val minute = 60_000L; val hour = 60 * minute
        assertEquals(RecordingWindow(now + hour - 5 * minute, now + 2 * hour + 15 * minute),
            RecordingPlan.programme(now, now + hour, now + 2 * hour, 5 * minute, 15 * minute))
        assertEquals(RecordingWindow(now + hour, now + 2 * hour), RecordingPlan.programme(now, now + hour, now + 2 * hour, 0, 0))
        assertEquals(RecordingWindow(now, now + 30 * minute), RecordingPlan.now(now, now + 30 * minute, postRollMillis = 0))
    }
}
