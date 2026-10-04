package com.nuvio.tv.ui.util

import androidx.compose.ui.AbsoluteAlignment
import org.junit.Assert.assertEquals
import org.junit.Test

class TextDirectionUtilsTest {
    @Test fun `content alignment follows first strong character after neutral prefixes`() {
        assertEquals(AbsoluteAlignment.Right, "2026 - שלום".contentTextDirection().toAbsoluteAlignment())
        assertEquals(AbsoluteAlignment.Right, "★ العربية".contentTextDirection().toAbsoluteAlignment())
        assertEquals(AbsoluteAlignment.Left, "2026 - English العربية".contentTextDirection().toAbsoluteAlignment())
        assertEquals(AbsoluteAlignment.Left, "123 …".contentTextDirection().toAbsoluteAlignment())
    }
}
