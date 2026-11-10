package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class RequestBudgetTest {
    private var now = 1_000_000L
    private val budget = RequestBudget { now }
    private val store13 = "https://store-013.wnam.tb-cdn.io/dld/a?token=x"
    private val store27 = "https://store-027.wnam.tb-cdn.io/dld/b?token=x"
    private val nexus = "https://nexus-341.wnam.tb-cdn.io/dld/c?token=x"
    private val otherDomain = "https://store-059.weur.tb-cdn.st/dld/d?token=x"

    private fun send(host: String, count: Int) = repeat(count) { budget.note(host) }

    @Test fun storeServersOfADomainCountTogether() {
        send("store-013.wnam.tb-cdn.io", RequestBudget.THUMBNAILS_HOLD_AT - 1)
        assertFalse(budget.holdsThumbnails(store27))
        send("store-027.wnam.tb-cdn.io", 1)
        assertTrue(budget.holdsThumbnails(store13))
        assertTrue(budget.holdsThumbnails(store27))
    }

    @Test fun otherServersAndDomainsAreNotHeld() {
        send("store-013.wnam.tb-cdn.io", RequestBudget.THUMBNAILS_HOLD_AT)
        assertFalse(budget.holdsThumbnails(nexus))
        assertFalse(budget.holdsThumbnails(otherDomain))
        send("nexus-341.wnam.tb-cdn.io", 2_000)
        assertFalse(budget.holdsThumbnails(nexus))
    }

    @Test fun theHoldEndsAsRequestsAgeOut() {
        send("store-013.wnam.tb-cdn.io", RequestBudget.THUMBNAILS_HOLD_AT)
        now += RequestTally.WINDOW_MS - 1
        assertTrue(budget.holdsThumbnails(store13))
        now += 1
        assertFalse(budget.holdsThumbnails(store13))
    }

    @Test fun aLongRefusalHoldsUntilItEndsAndAShortOneDoesNot() {
        budget.noteRefused("store-013.wnam.tb-cdn.io", 300_000L)
        assertTrue(budget.holdsThumbnails(store27))
        assertFalse(budget.holdsThumbnails(nexus))
        now += 299_999L
        assertTrue(budget.holdsThumbnails(store13))
        now += 1L
        assertFalse(budget.holdsThumbnails(store13))
        budget.noteRefused("store-013.wnam.tb-cdn.io", 5_000L)
        budget.noteRefused("store-013.wnam.tb-cdn.io", null)
        assertFalse(budget.holdsThumbnails(store13))
    }

    @Test fun aServerWithoutAStoreNameIsHeldOnlyByItsOwnLongRefusal() {
        budget.noteRefused("addon.example.com", 120_000L)
        assertTrue(budget.holdsThumbnails("https://addon.example.com/x"))
        assertFalse(budget.holdsThumbnails(store13))
    }

    @Test fun storeHostsAreRecognised() {
        assertTrue(RequestBudget.isStoreHost("store-059.weur.tb-cdn.st"))
        assertTrue(RequestBudget.isStoreHost("store.wnam.tb-cdn.io"))
        assertFalse(RequestBudget.isStoreHost("nexus-341.wnam.tb-cdn.io"))
        assertFalse(RequestBudget.isStoreHost("wormhole-002.wnam.tb-cdn.io"))
        assertFalse(RequestBudget.isStoreHost("store-1.example.com"))
        assertFalse(RequestBudget.isStoreUrl(null))
    }

    @Test fun aHeldThumbnailWaitsPastItsSlotWaitUntilCancelled() {
        val ledger = ConnectionLedger(clock = { now }, wallClock = { 0L }, budgetHolds = { true })
        var asked = 0
        try {
            ledger.acquire(store13, maxWaitMs = 0L) { ++asked > 2 }
            fail("still held")
        } catch (e: ConnectionLedger.NoSlotException) {
            fail("a hold must not use up the slot wait")
        } catch (e: IOException) {
            assertEquals("closed", e.message)
        }
        assertEquals(3, asked)
    }

    @Test fun aHeldRequestIsReportedOnlyWhileItIsRecent() {
        var held = true
        val ledger = ConnectionLedger(clock = { now }, wallClock = { 0L }, budgetHolds = { held })
        assertFalse(ledger.budgetHolding())
        ledger.acquire(store13, maxWaitMs = 1_000L) { held = false; false }.close()
        assertTrue(ledger.budgetHolding())
        now += 2_000L
        assertFalse(ledger.budgetHolding())
    }

    @Test fun afterTheHoldTheThumbnailGoes() {
        var held = true
        val ledger = ConnectionLedger(clock = { now }, wallClock = { 0L }, budgetHolds = { held })
        val slot = ledger.acquire(store13, maxWaitMs = 1_000L) { held = false; false }
        slot.close()
    }

    @Test fun unhurriedTorBoxThumbnailsLeaveAGapAndOthersDoNot() {
        val ledger = ConnectionLedger(clock = { now }, wallClock = { 0L }, budgetHolds = { false })
        ledger.setUnhurried(true)
        ledger.acquire(store13, maxWaitMs = 1_000L).close()
        var waited = false
        val second = ledger.acquire(store13, maxWaitMs = 1_000L) {
            waited = true
            now += ConnectionLedger.UNHURRIED_GAP_MS
            false
        }
        second.close()
        assertTrue(waited)
        ledger.acquire("https://server.example.com/a", maxWaitMs = 1_000L).close()
        ledger.acquire("https://server.example.com/a", maxWaitMs = 1_000L) { fail("no gap"); false }.close()
        ledger.setUnhurried(false)
        ledger.acquire(store13, maxWaitMs = 1_000L) { fail("gap is over"); false }.close()
    }
}
