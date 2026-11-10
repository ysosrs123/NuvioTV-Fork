package com.nuvio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinPadTest {
    @Test
    fun `digits fill up to the length and no further`() {
        assertEquals("1", pinAfterKey("", '1', 4))
        assertEquals("1234", pinAfterKey("123", '4', 4))
        assertEquals("1234", pinAfterKey("1234", '5', 4))
    }

    @Test
    fun `clear empties and delete takes one back`() {
        assertEquals("", pinAfterKey("123", PIN_PAD_CLEAR, 4))
        assertEquals("12", pinAfterKey("123", PIN_PAD_DELETE, 4))
        assertEquals("", pinAfterKey("", PIN_PAD_DELETE, 4))
    }

    @Test
    fun `the pad has every digit once, and focus starts on the middle key`() {
        val keys = PIN_PAD_ROWS.joinToString("")
        assertEquals("0123456789".toList(), keys.filter(Char::isDigit).toList().sorted())
        assertTrue(PIN_PAD_ROWS.all { it.length == 3 })
        assertEquals(PIN_PAD_START, PIN_PAD_ROWS[1][1])
    }
}
