package com.nuvio.tv.core.build

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueReportsTest {
    @Test fun `blank or missing report server means reports are unavailable`() {
        assertFalse(IssueReports.isConfigured(null))
        assertFalse(IssueReports.isConfigured(""))
        assertFalse(IssueReports.isConfigured("   "))
    }

    @Test fun `a configured report server brings reports back`() {
        assertTrue(IssueReports.isConfigured("https://reports.example.org/"))
        assertTrue(IssueReports.isConfigured("https://arc-reports.someone.workers.dev/", "key"))
    }

    @Test fun `an official Nuvio address is never accepted`() {
        for (url in listOf("https://nuvio.tv/", "https://api.nuvio.tv/", "https://reports.nuvio.app/", "https://NUVIO.TV./x/")) {
            assertFalse(url, IssueReports.isConfigured(url, "key"))
        }
        assertFalse(IssueReports.isOfficialHost("notnuvio.tv.example.org"))
        assertFalse(IssueReports.isOfficialHost("evilnuvio.tv"))
    }

    @Test fun `plain http and a missing signing key are refused`() {
        assertFalse(IssueReports.isConfigured("http://reports.example.org/", "key"))
        assertFalse(IssueReports.isConfigured("https://reports.example.org/", ""))
        assertFalse(IssueReports.isConfigured("https://reports.example.org/", null))
    }
}
