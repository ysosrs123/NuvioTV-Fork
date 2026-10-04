package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.AudioPassthroughPolicy.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRejectionLedgerTest {

    private val route = "type:hdmi|name:box"
    private val persisted = setOf("$route::TRUEHD", "$route::DTS_HD", "type:hdmi|name:other::AC3", "garbage")

    @Test
    fun learnedFor_filtersByRouteAndParsesGroups() {
        val ledger = AudioRejectionLedger()
        assertEquals(setOf(Group.TRUEHD, Group.DTS_HD), ledger.learnedFor(route, persisted))
        assertEquals(setOf(Group.AC3), ledger.learnedFor("type:hdmi|name:other", persisted))
        assertTrue(ledger.learnedFor(null, persisted).isEmpty())
    }

    @Test
    fun verifiedEntries_dropOutOfTheLearnedSetUntilInvalidated() {
        val ledger = AudioRejectionLedger()
        ledger.markVerified("$route::TRUEHD")
        assertEquals(setOf(Group.DTS_HD), ledger.learnedFor(route, persisted))
        ledger.invalidate()
        assertEquals(setOf(Group.TRUEHD, Group.DTS_HD), ledger.learnedFor(route, persisted))
    }

    @Test
    fun entriesToProbe_runsOncePerRouteUntilInvalidated() {
        val ledger = AudioRejectionLedger()
        assertEquals(listOf("$route::TRUEHD", "$route::DTS_HD"), ledger.entriesToProbe(route, persisted))
        assertTrue(ledger.entriesToProbe(route, persisted).isEmpty())
        ledger.invalidate()
        ledger.markVerified("$route::TRUEHD")
        assertEquals(listOf("$route::DTS_HD"), ledger.entriesToProbe(route, persisted))
    }

    @Test
    fun pendingRejection_commitsOnlyForTheSameStream() {
        val ledger = AudioRejectionLedger()
        ledger.stashPending("url-a", "$route::TRUEHD")
        assertNull(ledger.takePendingFor("url-b"))
        assertNull(ledger.takePendingFor("url-a"))
        ledger.stashPending("url-a", "$route::TRUEHD")
        assertEquals("$route::TRUEHD", ledger.takePendingFor("url-a"))
        assertNull(ledger.takePendingFor("url-a"))
        ledger.stashPending("url-a", "$route::DTS_HD")
        ledger.dropPending()
        assertNull(ledger.takePendingFor("url-a"))
    }

    @Test
    fun entryHelpers_roundTrip() {
        val entry = AudioRejectionLedger.entry(route, Group.EAC3)
        assertEquals("$route::EAC3", entry)
        assertEquals(Group.EAC3, AudioRejectionLedger.groupOf(entry))
        assertEquals(route, AudioRejectionLedger.routeOf(entry))
        assertNull(AudioRejectionLedger.groupOf("garbage"))
        assertNull(AudioRejectionLedger.routeOf("garbage"))
    }

    private fun AudioRejectionLedger.refuseThreeTimes(group: Group, on: String = route) {
        repeat(3) { noteOpenRefused(on, group) }
    }

    @Test
    fun oneFormatRefusedOnAWorkingRoute_isLearned() {
        val ledger = AudioRejectionLedger()
        ledger.noteBitstreamOpened(route)
        ledger.refuseThreeTimes(Group.DTS_HD)
        val commit = ledger.commit("$route::DTS_HD")
        assertTrue(commit.learn)
        assertTrue(commit.forget.isEmpty())
    }

    @Test
    fun theFirstRefusalAfterStart_isLearned() {
        val ledger = AudioRejectionLedger()
        ledger.refuseThreeTimes(Group.TRUEHD)
        assertTrue(ledger.commit("$route::TRUEHD").learn)
    }

    @Test
    fun everyFormatRefusedWhileHdmiAudioIsDown_isNotLearnedAndTheFirstIsForgotten() {
        val ledger = AudioRejectionLedger()
        ledger.refuseThreeTimes(Group.DTS)
        assertTrue(ledger.commit("$route::DTS").learn)

        ledger.refuseThreeTimes(Group.EAC3)
        val eac3 = ledger.commit("$route::EAC3")
        assertFalse(eac3.learn)
        assertEquals(listOf("$route::DTS"), eac3.forget)
        assertEquals(setOf(Group.DTS, Group.EAC3), eac3.refusedOnRoute)

        ledger.refuseThreeTimes(Group.AC3)
        val ac3 = ledger.commit("$route::AC3")
        assertFalse(ac3.learn)
        assertTrue(ac3.forget.isEmpty())
    }

    @Test
    fun aBitstreamOpenInBetween_meansTheNextRefusalIsTheFormats() {
        val ledger = AudioRejectionLedger()
        ledger.refuseThreeTimes(Group.DTS_HD)
        assertTrue(ledger.commit("$route::DTS_HD").learn)
        ledger.noteBitstreamOpened(route)
        ledger.refuseThreeTimes(Group.TRUEHD)
        val commit = ledger.commit("$route::TRUEHD")
        assertTrue(commit.learn)
        assertTrue(commit.forget.isEmpty())
    }

    @Test
    fun refusalsOnAnotherRoute_doNotCount() {
        val ledger = AudioRejectionLedger()
        ledger.refuseThreeTimes(Group.DTS, on = "type:hdmi|name:other")
        ledger.refuseThreeTimes(Group.EAC3)
        assertTrue(ledger.commit("$route::EAC3").learn)
    }

    @Test
    fun aRouteChange_startsTheCountAgain() {
        val ledger = AudioRejectionLedger()
        ledger.refuseThreeTimes(Group.DTS)
        ledger.refuseThreeTimes(Group.EAC3)
        ledger.invalidate()
        ledger.refuseThreeTimes(Group.AC3)
        assertTrue(ledger.commit("$route::AC3").learn)
    }

    @Test
    fun anArcLinkRefusingTrueHdAndDtsHd_learnsBoth() {
        val ledger = AudioRejectionLedger()
        ledger.refuseThreeTimes(Group.TRUEHD)
        assertTrue(ledger.commit("$route::TRUEHD").learn)
        ledger.refuseThreeTimes(Group.DTS_HD)
        val dtsHd = ledger.commit("$route::DTS_HD")
        assertTrue(dtsHd.learn)
        assertTrue(dtsHd.forget.isEmpty())
    }

    @Test
    fun aSoundbarWithoutDts_learnsDtsAndDtsHd() {
        val ledger = AudioRejectionLedger()
        ledger.refuseThreeTimes(Group.DTS)
        assertTrue(ledger.commit("$route::DTS").learn)
        ledger.refuseThreeTimes(Group.DTS_HD)
        assertTrue(ledger.commit("$route::DTS_HD").learn)
    }

    @Test
    fun ac3AndDtsRefusedWithNothingOpen_meansTheOutputIsDown() {
        val ledger = AudioRejectionLedger()
        ledger.refuseThreeTimes(Group.DTS)
        assertTrue(ledger.commit("$route::DTS").learn)
        ledger.refuseThreeTimes(Group.AC3)
        val ac3 = ledger.commit("$route::AC3")
        assertFalse(ac3.learn)
        assertEquals(listOf("$route::DTS"), ac3.forget)
    }

    @Test
    fun anOutageAfterTwoLosslessRefusals_forgetsBoth() {
        val ledger = AudioRejectionLedger()
        ledger.refuseThreeTimes(Group.TRUEHD)
        ledger.commit("$route::TRUEHD")
        ledger.refuseThreeTimes(Group.DTS_HD)
        ledger.commit("$route::DTS_HD")
        ledger.refuseThreeTimes(Group.EAC3)
        val eac3 = ledger.commit("$route::EAC3")
        assertFalse(eac3.learn)
        assertEquals(listOf("$route::TRUEHD", "$route::DTS_HD"), eac3.forget)
    }

    private val start = 1_700_000_000_000L

    private fun ledgerAfterOutageBeforeRestart(): Set<String> {
        val ledger = AudioRejectionLedger()
        listOf(Group.DTS, Group.EAC3, Group.AC3).forEachIndexed { i, group ->
            repeat(3) { ledger.noteOpenRefused(route, group, start + i * 30_000L) }
            ledger.commit(AudioRejectionLedger.entry(route, group), start + i * 30_000L)
        }
        return ledger.evidenceSnapshot()
    }

    @Test
    fun outageEvidence_survivesARestartWithinTheWindow() {
        val saved = ledgerAfterOutageBeforeRestart()
        val restarted = AudioRejectionLedger()
        val later = start + 60_000L + 5 * 60_000L
        restarted.restoreEvidence(saved, later)
        repeat(3) { restarted.noteOpenRefused(route, Group.TRUEHD, later) }
        val trueHd = restarted.commit("$route::TRUEHD", later)
        assertFalse(trueHd.learn)
        assertEquals(setOf(Group.DTS, Group.EAC3, Group.AC3, Group.TRUEHD), trueHd.refusedOnRoute)
    }

    @Test
    fun aLearningBeforeTheRestart_isForgottenWhenTheOutageShowsAfterIt() {
        val ledger = AudioRejectionLedger()
        repeat(3) { ledger.noteOpenRefused(route, Group.DTS, start) }
        assertTrue(ledger.commit("$route::DTS", start).learn)
        val restarted = AudioRejectionLedger()
        restarted.restoreEvidence(ledger.evidenceSnapshot(), start + 120_000L)
        repeat(3) { restarted.noteOpenRefused(route, Group.EAC3, start + 120_000L) }
        val eac3 = restarted.commit("$route::EAC3", start + 120_000L)
        assertFalse(eac3.learn)
        assertEquals(listOf("$route::DTS"), eac3.forget)
    }

    @Test
    fun outageEvidence_expiresAfterTheWindow() {
        val saved = ledgerAfterOutageBeforeRestart()
        val restarted = AudioRejectionLedger()
        val later = start + 60_000L + AudioRejectionLedger.EVIDENCE_TTL_MS + 1
        restarted.restoreEvidence(saved, later)
        repeat(3) { restarted.noteOpenRefused(route, Group.TRUEHD, later) }
        assertTrue(restarted.commit("$route::TRUEHD", later).learn)
    }

    @Test
    fun savedEvidence_isClearedByABitstreamOpenAndRestoredOnlyOnce() {
        val saved = ledgerAfterOutageBeforeRestart()
        val restarted = AudioRejectionLedger()
        restarted.restoreEvidence(saved, start + 120_000L)
        assertTrue(restarted.noteBitstreamOpened(route))
        assertFalse(restarted.noteBitstreamOpened(route))
        assertTrue(restarted.evidenceSnapshot().isEmpty())
        restarted.restoreEvidence(saved, start + 120_000L)
        assertTrue(restarted.evidenceSnapshot().isEmpty())
    }

    @Test
    fun savedEvidence_skipsBrokenRecordsAndKeepsRouteKeysWithSeparators() {
        val odd = "type:hdmi|name:a;b"
        val ledger = AudioRejectionLedger()
        ledger.noteOpenRefused(odd, Group.AC3, start)
        val restarted = AudioRejectionLedger()
        restarted.restoreEvidence(ledger.evidenceSnapshot() + setOf("garbage", "x;y;z;"), start)
        repeat(3) { restarted.noteOpenRefused(odd, Group.DTS, start) }
        assertFalse(restarted.commit("$odd::DTS", start).learn)
    }
}
