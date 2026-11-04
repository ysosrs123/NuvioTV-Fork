package com.nuvio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorStateIdLineTest {

    @Test
    fun `meta not found keeps the id as it was and gives the no-metadata advice`() {
        val text = formatErrorStateMessage("Meta not found\n\nID: tt1839337")

        assertTrue(text.contains("\n\nID: tt1839337\n\n"))
        assertFalse(text.contains("Tt1839337"))
        assertTrue(text.startsWith("This id could not load details"))
    }

    @Test
    fun `no supported addon keeps the id line between issue and fix`() {
        val text = formatErrorStateMessage("No installed addon supports metadata for \"series\".\n\nID: tt1839337")

        assertTrue(text.startsWith("No installed addon supports metadata for \"series\".\n\nID: tt1839337\n\nPossible fix:"))
    }

    @Test
    fun `an empty message with an id line is not read as an addon called ID`() {
        assertFalse(formatErrorStateMessage("\n\nID: tt1839337").startsWith("ID"))
    }

    @Test
    fun `a message without an id line is unchanged by the split`() {
        assertEquals("Meta not found" to null, splitIdLine("Meta not found"))
        assertNull(splitIdLine("Addon: timed out").second)
    }
}
