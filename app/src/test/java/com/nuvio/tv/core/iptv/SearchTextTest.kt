package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class SearchTextTest {
    @Test fun caseFoldingMatchesSharpSFinalSigmaAndCompatibilityForms() {
        assertTrue(foldSearchText("Straße").contains(foldSearchText("STRASSE")))
        assertEquals(foldSearchText("ΟΔΟΣ"), foldSearchText("οδος"))
        assertTrue(foldSearchText("Οδός").contains(foldSearchText("ΟΔΌΣ")))
        assertEquals(foldSearchText("ＢＢＣ One"), foldSearchText("bbc one"))
        assertEquals(foldSearchText("ﬁlm"), foldSearchText("FILM"))
    }
}
