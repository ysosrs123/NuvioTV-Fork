package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QmsHintMemoryTest {
    @Test
    fun `each TV gets its own key and an unknown screen still gets one`() {
        assertEquals("GSM|1234|LG TV", QmsHintMemory.keyOf("GSM", "1234", "LG TV"))
        assertNotEquals(QmsHintMemory.keyOf("GSM", "1234", "LG TV"), QmsHintMemory.keyOf("SAM", "77", "Samsung"))
        assertEquals("screen", QmsHintMemory.keyOf(null, null, null))
        assertEquals("||Built-in screen", QmsHintMemory.keyOf(null, " ", "Built-in screen"))
    }
}
