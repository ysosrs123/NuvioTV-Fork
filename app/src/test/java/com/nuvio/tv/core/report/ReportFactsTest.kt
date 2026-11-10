package com.nuvio.tv.core.report

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportFactsTest {
    private fun provider(block: suspend () -> Map<String, String>) = object : ReportFactsProvider {
        override suspend fun reportFacts() = block()
    }

    @Test fun `no providers means no extra field`() {
        assertNull(runBlocking { ReportFacts(emptySet()).collect() })
    }

    @Test fun `facts are merged and cleaned like other report text`() {
        val facts = runBlocking {
            ReportFacts(
                setOf(
                    provider { mapOf("channels" to "1200") },
                    provider { mapOf("source" to "http://192.168.1.2/live/user/pass/1.ts?token=SECRETSECRET123456") }
                )
            ).collect()
        }!!
        assertEquals("1200", facts["channels"])
        assertFalse(facts.getValue("source").contains("SECRETSECRET"))
        assertFalse(facts.getValue("source").contains("192.168.1.2"))
    }

    @Test fun `a provider that fails or hangs does not hold up the report`() {
        val facts = runBlocking {
            ReportFacts(
                setOf(
                    provider { error("no") },
                    provider { awaitCancellation() },
                    provider { mapOf("ok" to "yes") }
                )
            ).collect()
        }
        assertEquals(mapOf("ok" to "yes"), facts)
    }

    @Test fun `size is limited`() {
        val facts = cleanReportFacts((0 until 60).associate { "k$it" to "v".repeat(500) })!!
        assertEquals(24, facts.size)
        assertTrue(facts.values.all { it.length <= 240 })
        assertNull(cleanReportFacts(mapOf("blank" to "   ")))
    }
}
