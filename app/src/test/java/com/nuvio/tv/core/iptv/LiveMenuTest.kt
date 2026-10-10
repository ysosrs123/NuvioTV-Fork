package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.LiveMenuItem.*
import org.junit.Assert.*
import org.junit.Test

class LiveMenuTest {
    private fun ids(vararg items: LiveMenuItem) = items.map { it.id }

    @Test fun nothingSavedGivesDefaultOrder() {
        assertEquals(LiveMenuLayout.DEFAULT, LiveMenuLayout.parse(null))
        assertEquals(LiveMenuLayout.DEFAULT, LiveMenuLayout.parse(""))
        assertFalse(LiveMenuLayout.customised(LiveMenuLayout.parse(null), emptySet()))
    }

    @Test fun savedOrderRoundTrips() {
        val order = listOf(SPORT, SEARCH) + LiveMenuLayout.DEFAULT.filter { it != SPORT && it != SEARCH }
        assertEquals(order, LiveMenuLayout.parse(LiveMenuLayout.encode(order)))
        assertTrue(LiveMenuLayout.customised(order, emptySet()))
    }

    @Test fun unknownAndDuplicateIdsAreIgnored() {
        val saved = ids(*LiveMenuLayout.DEFAULT.reversed().toTypedArray()) + listOf("future-item", "search", " ")
        assertEquals(LiveMenuLayout.DEFAULT.reversed(), LiveMenuLayout.order(saved))
    }

    @Test fun itemsMissingFromAnOlderSaveAppearInTheirDefaultPlace() {
        val saved = ids(SETTINGS, SEARCH, AIRING, RECORDINGS, MOVIES, SERIES, PHONE_SETUP, FAVOURITES, ALL_SOURCES, CATEGORIES, SOURCES)
        assertEquals(listOf(SETTINGS, SEARCH, AIRING, RECORDINGS, MOVIES, SERIES, SPORT, PHONE_SETUP, FAVOURITES, ALL_SOURCES, CATEGORIES, SOURCES),
            LiveMenuLayout.order(saved))
        assertEquals(listOf(SEARCH, AIRING, RECORDINGS, MOVIES, SERIES, SPORT, SETTINGS, PHONE_SETUP, FAVOURITES, ALL_SOURCES, CATEGORIES, SOURCES),
            LiveMenuLayout.order(ids(AIRING, RECORDINGS)))
        assertEquals(LiveMenuLayout.DEFAULT, LiveMenuLayout.order(ids(SOURCES)))
    }

    @Test fun requiredItemsCannotBeHidden() {
        assertEquals(setOf(SPORT), LiveMenuLayout.hidden(listOf("search", "settings", "sources", "categories", "sport", "gone")))
        assertEquals(emptySet<LiveMenuItem>(), LiveMenuLayout.toggle(emptySet(), SETTINGS))
        assertEquals(setOf(MOVIES), LiveMenuLayout.toggle(emptySet(), MOVIES))
        assertEquals(emptySet<LiveMenuItem>(), LiveMenuLayout.toggle(setOf(MOVIES), MOVIES))
        assertTrue(LiveMenuLayout.customised(LiveMenuLayout.DEFAULT, setOf(MOVIES)))
    }

    @Test fun shownSkipsHiddenAndUnavailableButKeepsRequired() {
        val shown = LiveMenuLayout.shown(LiveMenuLayout.DEFAULT, setOf(AIRING, SEARCH)) { it != MOVIES && it != ALL_SOURCES }
        assertEquals(listOf(SEARCH, RECORDINGS, SERIES, SPORT, SETTINGS, PHONE_SETUP, FAVOURITES, CATEGORIES, SOURCES), shown)
    }

    @Test fun movingStepsOverItemsThatAreNotShown() {
        val order = LiveMenuLayout.DEFAULT
        val shown = LiveMenuLayout.shown(order, setOf(MOVIES, SERIES)) { true }
        val down = requireNotNull(LiveMenuLayout.move(order, shown, RECORDINGS, ListMove.DOWN))
        assertEquals(LiveMenuLayout.shown(down, setOf(MOVIES, SERIES)) { true }.take(4), listOf(SEARCH, AIRING, SPORT, RECORDINGS))
        assertEquals(listOf(SEARCH, AIRING, SPORT, MOVIES, SERIES, RECORDINGS), down.take(6))
        val top = requireNotNull(LiveMenuLayout.move(order, shown, SETTINGS, ListMove.TOP))
        assertEquals(SETTINGS, top.first())
        val bottom = requireNotNull(LiveMenuLayout.move(order, shown, SEARCH, ListMove.BOTTOM))
        assertEquals(SEARCH, bottom.last())
        assertEquals(order.toSet(), bottom.toSet())
        assertNull(LiveMenuLayout.move(order, shown, SEARCH, ListMove.UP))
        assertNull(LiveMenuLayout.move(order, shown, MOVIES, ListMove.DOWN))
    }

    @Test fun menuKeysAreDeviceSettingsNotProfileKeys() {
        assertTrue(LivePreferenceKeys.ofAllProfiles(listOf(LivePreferenceKeys.MENU_ORDER, LivePreferenceKeys.MENU_HIDDEN)).isEmpty())
    }

    @Test fun externalPlayerHeadersPreferTheChannelAgent() {
        assertEquals(mapOf("Referer" to "https://r.invalid/", "User-Agent" to "Source/1"),
            LiveExternalPlayer.headers(mapOf("Referer" to "https://r.invalid/"), "Source/1"))
        assertEquals(mapOf("User-Agent" to "Channel/2"), LiveExternalPlayer.headers(mapOf("User-Agent" to "Channel/2"), "Source/1"))
        assertEquals(emptyMap<String, String>(), LiveExternalPlayer.headers(emptyMap(), null))
        assertEquals(emptyMap<String, String>(), LiveExternalPlayer.headers(mapOf("Referer" to "  "), " "))
    }
}
