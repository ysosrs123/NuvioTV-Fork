package com.nuvio.tv.core.iptv

import com.nuvio.tv.data.iptv.IptvLivePreferences
import com.nuvio.tv.data.iptv.IptvStartView
import com.nuvio.tv.data.iptv.IptvStreamFormat
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SetupSettingsTest {
    private fun body(vararg values: Pair<String, Any?>) = JSONObject().apply { values.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }.toString()
    private fun rejects(field: String, json: String) {
        try { SetupSettingsInput.parse(json); fail(json) } catch (error: SetupInputException) { assertEquals(json, field, error.field) }
    }

    @Test fun acceptsEveryKnownValue() {
        val change = SetupSettingsInput.parse(body("format" to "mpegts", "timeshift" to false, "sport" to false, "startView" to "favourites",
            "layout" to "focus", "quality" to "lightest", "recordEarly" to 10, "recordLate" to 30))
        val next = change.applied(SetupSettings())
        assertEquals(SetupSettings("mpegts", false, false, "favourites", "focus", "lightest", 10, 30), next)
        assertEquals(SetupSetting.entries, change.changes(SetupSettings()))
        for (format in SetupSettings.FORMATS) assertEquals(format, SetupSettingsInput.parse(body("format" to format)).format)
        for (view in SetupSettings.START_VIEWS) assertEquals(view, SetupSettingsInput.parse(body("startView" to view)).startView)
        for (minutes in SetupSettings.EARLY_MINUTES) assertEquals(minutes, SetupSettingsInput.parse(body("recordEarly" to minutes)).recordEarly)
        for (minutes in SetupSettings.LATE_MINUTES) assertEquals(minutes, SetupSettingsInput.parse(body("recordLate" to minutes)).recordLate)
    }

    @Test fun onlySentAndDifferentValuesCount() {
        val current = SetupSettings(format = "hls", recordLate = 5)
        val change = SetupSettingsInput.parse(body("format" to "hls", "recordLate" to 15, "sport" to null))
        assertEquals(listOf(SetupSetting.RECORD_LATE), change.changes(current))
        assertEquals(current.copy(recordLate = 15), change.applied(current))
        assertTrue(SetupSettingsInput.parse("{}").changes(current).isEmpty())
        assertTrue(SetupSettingsInput.parse(body("timeshift" to true)).changes(current).isEmpty())
    }

    @Test fun unknownValuesAreRejected() {
        rejects("format", body("format" to "dash"))
        rejects("format", body("format" to "AUTO"))
        rejects("format", body("format" to 1))
        rejects("startView", body("startView" to "recent"))
        rejects("layout", body("layout" to "large"))
        rejects("quality", body("quality" to ""))
        rejects("timeshift", body("timeshift" to "true"))
        rejects("sport", body("sport" to 1))
    }

    @Test fun minutesMustBeOneOfTheChoices() {
        for (bad in listOf(-1, 3, 11, 60, Int.MAX_VALUE)) rejects("recordEarly", body("recordEarly" to bad))
        for (bad in listOf(-2, 1, 31, 45, 1000)) rejects("recordLate", body("recordLate" to bad))
        rejects("recordEarly", body("recordEarly" to "5"))
        rejects("recordEarly", """{"recordEarly":5.0}""")
        rejects("recordLate", """{"recordLate":1e1}""")
        rejects("recordLate", body("recordLate" to true))
    }

    @Test fun malformedBodiesAndExtraKeysAreRejected() {
        rejects("body", "not json")
        rejects("body", "[]")
        rejects("body", "")
        rejects("body", body("format" to "auto", "password" to "x"))
        rejects("body", body("Format" to "auto"))
        rejects("body", """{"format":{"a":{"b":{"c":{"d":1}}}}}""")
        rejects("body", "{\"format\":\"auto\"}/*x*/")
        rejects("body", " ".repeat(SetupDrafts.MAX_BODY_BYTES + 1))
    }

    @Test fun sportViewNeedsSportAndMultiviewNeedsSupport() {
        val sportOff = SetupSettings(sport = false)
        assertEquals("startView", SetupSettingsInput.check(SetupSettingsInput.parse(body("startView" to "sport")), sportOff))
        assertEquals("startView", SetupSettingsInput.check(SetupSettingsInput.parse(body("startView" to "sport", "sport" to false)), SetupSettings()))
        assertNull(SetupSettingsInput.check(SetupSettingsInput.parse(body("startView" to "sport", "sport" to true)), sportOff))
        assertNull(SetupSettingsInput.check(SetupSettingsInput.parse(body("sport" to false)), SetupSettings(startView = "sport")))
        val single = SetupSettings(multiview = false)
        assertEquals("layout", SetupSettingsInput.check(SetupSettingsInput.parse(body("layout" to "focus")), single))
        assertEquals("quality", SetupSettingsInput.check(SetupSettingsInput.parse(body("quality" to "sharpest")), single))
        assertNull(SetupSettingsInput.check(SetupSettingsInput.parse(body("layout" to "grid", "format" to "hls")), single))
        assertNull(SetupSettingsInput.check(SetupSettingsInput.parse(body("layout" to "focus")), SetupSettings()))
    }

    @Test fun wireNamesMatchTheStoredChoices() {
        assertEquals(SetupSettings.FORMATS, IptvStreamFormat.entries.map(SetupSettings::wire))
        assertEquals(SetupSettings.START_VIEWS, IptvStartView.entries.map(SetupSettings::wire))
        assertEquals(SetupSettings.LAYOUTS, MultiviewLayout.entries.map(SetupSettings::wire))
        assertEquals(SetupSettings.QUALITIES, MultiviewQuality.entries.map(SetupSettings::wire))
        assertEquals(IptvStreamFormat.MPEG_TS, SetupSettings.choice(IptvStreamFormat.entries, "mpegts"))
        assertEquals(IptvStartView.FAVOURITES, SetupSettings.choice(IptvStartView.entries, "favourites"))
        assertTrue(SetupSettings.EARLY_MINUTES.all { it in 0..IptvLivePreferences.MAX_EARLY_MINUTES })
        assertTrue(SetupSettings.LATE_MINUTES.all { it in 0..IptvLivePreferences.MAX_LATE_MINUTES })
        val defaults = SetupSettings()
        assertTrue(defaults.format in SetupSettings.FORMATS && defaults.startView in SetupSettings.START_VIEWS)
        assertTrue(defaults.recordEarly in SetupSettings.EARLY_MINUTES && defaults.recordLate in SetupSettings.LATE_MINUTES)
    }

    @Test fun currentValuesAreSentAsPlainJson() {
        val json = JSONObject(SetupSettings("hls", false, true, "all", "focus", "sharpest", 5, 15, multiview = false).toJson())
        assertEquals("hls", json.getString("format"))
        assertFalse(json.getBoolean("timeshift"))
        assertEquals("all", json.getString("startView"))
        assertEquals(5, json.getInt("recordEarly"))
        assertEquals(15, json.getInt("recordLate"))
        assertFalse(json.getBoolean("multiview"))
        assertEquals(9, json.length())
    }

    @Test fun settingsChangesShareTheChangeBook() {
        val book = SetupChangeBook()
        val change = SetupSettingsInput.parse(body("timeshift" to false))
        val id = book.propose("owner", change)!!
        assertNull(book.propose("owner", SetupDrafts.parse(body("kind" to "m3u", "label" to "A", "address" to "http://a.example/x"))))
        assertSame(change, book.pending(id))
        assertTrue(book.resolve(id, SetupChangeBook.Status.SAVED))
        assertNull(book.pending(id))
        assertFalse(change.toString().contains("false"))
    }
}
