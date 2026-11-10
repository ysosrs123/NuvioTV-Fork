package com.nuvio.tv.core.plugin.cloudstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MissingClassNameTest {

    @Test
    fun `finds the class in an Android resolution error`() {
        assertEquals(
            "com.lagradost.cloudstream3.syncproviders.AccountManager",
            missingClassName("Failed resolution of: Lcom/lagradost/cloudstream3/syncproviders/AccountManager;")
        )
    }

    @Test
    fun `finds a plain class name`() {
        assertEquals("org.mozilla.javascript.Context", missingClassName("org/mozilla/javascript/Context"))
    }

    @Test
    fun `a message with no class name gives nothing`() {
        assertNull(missingClassName("something went wrong"))
        assertNull(missingClassName(null))
    }
}
