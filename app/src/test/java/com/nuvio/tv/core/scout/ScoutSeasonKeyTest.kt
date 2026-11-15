package com.nuvio.tv.core.scout

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ScoutSeasonKeyTest {
    @Test
    fun `a hidden holiday is hidden for this year's season only`() {
        assertEquals("thanksgiving:2026", ScoutOccasion.THANKSGIVING.seasonKey(LocalDate.of(2026, 11, 14)))
        assertEquals("thanksgiving:2027", ScoutOccasion.THANKSGIVING.seasonKey(LocalDate.of(2027, 11, 3)))
        assertEquals("christmas:2026", ScoutOccasion.CHRISTMAS.seasonKey(LocalDate.of(2026, 12, 20)))
        assertEquals("christmas:2026", ScoutOccasion.CHRISTMAS.seasonKey(LocalDate.of(2027, 1, 1)))
    }
}
