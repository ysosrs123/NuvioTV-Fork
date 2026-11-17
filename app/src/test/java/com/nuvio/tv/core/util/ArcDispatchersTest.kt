package com.nuvio.tv.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ArcDispatchersTest {

    @Test
    fun `a 32-bit process keeps IO and the lanes within 24 threads whatever the memory`() {
        listOf(0L, 1_900L, 4_096L).forEach { mb ->
            val budget = ArcDispatchers.budgetFor(is64Bit = false, totalMb = mb, lowRam = mb == 1_900L)
            assertEquals(ThreadBudget(16, 8), budget)
            assertEquals(24, budget.ioParallelism!! + budget.backgroundLanes)
        }
    }

    @Test
    fun `boxes up to two and a half gigabytes get the small budget`() {
        assertEquals(ThreadBudget(24, 8), ArcDispatchers.budgetFor(is64Bit = true, totalMb = 1_900))
        assertEquals(ThreadBudget(24, 8), ArcDispatchers.budgetFor(is64Bit = true, totalMb = 2_560))
        assertEquals(ThreadBudget(24, 8), ArcDispatchers.budgetFor(is64Bit = true, totalMb = 3_800, lowRam = true))
    }

    @Test
    fun `bigger 64-bit boxes keep the default IO pool`() {
        assertEquals(ThreadBudget(null, 16), ArcDispatchers.budgetFor(is64Bit = true, totalMb = 2_561))
        assertEquals(ThreadBudget(null, 16), ArcDispatchers.budgetFor(is64Bit = true, totalMb = 3_800))
    }

    @Test
    fun `unknown memory on a 64-bit box is not treated as small`() {
        assertEquals(ThreadBudget(null, 16), ArcDispatchers.budgetFor(is64Bit = true, totalMb = 0))
    }
}
