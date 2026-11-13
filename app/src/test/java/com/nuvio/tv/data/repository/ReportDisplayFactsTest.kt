package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.dto.PlaybackIssueDisplayDto
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportDisplayFactsTest {
    @Test fun `same rate is matched`() {
        assertEquals("matched", cadenceLabel(23.976f, 23.976f))
        assertEquals("matched", cadenceLabel(24f, 24f))
        assertEquals("matched", cadenceLabel(50f, 50f))
    }

    @Test fun `24p on a 60 Hz screen is 3-2 pulldown`() {
        assertEquals("3:2 pulldown (24p on 60 Hz)", cadenceLabel(24f, 60f))
        assertEquals("3:2 pulldown (23.976p on 59.94 Hz)", cadenceLabel(23.976f, 59.94f))
        assertEquals("3:2 pulldown (23.976p on 60 Hz)", cadenceLabel(23.976f, 60f))
    }

    @Test fun `whole multiples are even`() {
        assertEquals("even multiple x2 (25p on 50 Hz)", cadenceLabel(25f, 50f))
        assertEquals("even multiple x2 (30p on 60 Hz)", cadenceLabel(30f, 60f))
    }

    @Test fun `near match says how often a frame slips`() {
        assertEquals("near match, one frame repeated or dropped every 42 s (23.976p on 24 Hz)", cadenceLabel(23.976f, 24f))
        assertEquals("near match, one frame repeated or dropped every 33 s (29.97p on 60 Hz)", cadenceLabel(29.97f, 60f))
    }

    @Test fun `other pairs are uneven`() {
        assertEquals("uneven (25p on 60 Hz)", cadenceLabel(25f, 60f))
        assertEquals("uneven (24p on 50 Hz)", cadenceLabel(24f, 50f))
    }

    @Test fun `missing rates are unknown`() {
        assertEquals("unknown", cadenceLabel(null, 60f))
        assertEquals("unknown", cadenceLabel(24f, null))
        assertEquals("unknown", cadenceLabel(0f, 60f))
    }

    @Test fun `system match values and hdr names`() {
        assertEquals("never", systemMatchLabel(0))
        assertEquals("seamless only", systemMatchLabel(1))
        assertEquals("always", systemMatchLabel(2))
        assertEquals("unknown", systemMatchLabel(-1))
        assertEquals("unknown", systemMatchLabel(null))
        assertEquals(listOf("DolbyVision", "HDR10", "HLG", "HDR10+"), listOf(1, 2, 3, 4).map(::hdrTypeName))
    }

    @Test fun `display facts reach the report json`() {
        val dto = PlaybackIssueDisplayDto(
            refreshHz = 60f, width = 3840, height = 2160, refreshRatesAtThisSize = listOf(23.976f, 24f, 50f, 60f),
            hdrTypes = listOf("HDR10"), systemMatchFrameRate = "never", arcFrameRateMatching = "OFF",
            filmFps = 23.976f, cadence = cadenceLabel(23.976f, 60f)
        )
        val json = Moshi.Builder().build().adapter(PlaybackIssueDisplayDto::class.java).toJson(dto)
        assertTrue(json, json.contains("\"refreshHz\":60.0"))
        assertTrue(json, json.contains("\"systemMatchFrameRate\":\"never\""))
        assertTrue(json, json.contains("\"arcFrameRateMatching\":\"OFF\""))
        assertTrue(json, json.contains("\"cadence\":\"3:2 pulldown (23.976p on 60 Hz)\""))
    }
}
