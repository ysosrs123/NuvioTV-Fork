package com.nuvio.tv.core.util

import com.nuvio.tv.LocaleCache
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessLocaleTest {
    private val device = Locale.forLanguageTag("en-AU")

    @Test
    fun `system default goes back to the device locale`() {
        assertEquals(device, processLocale("", device))
        assertEquals(device, processLocale(null, device))
        assertEquals(device, processLocale(LocaleCache.UNSET, device))
    }

    @Test
    fun `an app language becomes the process locale`() {
        assertEquals("ar", processLocale("ar", device).language)
        assertEquals(Locale.forLanguageTag("pt-PT"), processLocale("pt-PT", device))
    }

    @Test
    fun `numbers format with device digits after leaving Arabic`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(processLocale("ar-EG", device))
            Locale.setDefault(processLocale("", device))
            assertEquals("23m left", String.format("%1\$dm left", 23))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `the locale tag change is observable`() {
        val previous = LocaleCache.localeTag
        try {
            LocaleCache.localeTag = "ar"
            assertEquals("ar", LocaleCache.tags.value)
            LocaleCache.localeTag = ""
            assertEquals("", LocaleCache.tags.value)
        } finally {
            LocaleCache.localeTag = previous
        }
    }
}
