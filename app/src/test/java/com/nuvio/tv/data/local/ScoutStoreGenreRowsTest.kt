package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutStoreGenreRowsTest {
    private val store = ScoutStore(mockk(relaxed = true), mockk(relaxed = true))
    private val picked = booleanPreferencesKey("picked_row")
    private val genre = booleanPreferencesKey("genre_rows")

    @Test
    fun `genre rows start on`() {
        assertTrue(store.readSettings(mutablePreferencesOf()).genreRows)
        assertTrue(store.readSettings(mutablePreferencesOf(picked to true)).genreRows)
    }

    @Test
    fun `picked for you switched off earlier keeps the genre rows off`() {
        assertFalse(store.readSettings(mutablePreferencesOf(picked to false)).genreRows)
    }

    @Test
    fun `a saved genre switch wins over picked for you`() {
        assertTrue(store.readSettings(mutablePreferencesOf(picked to false, genre to true)).genreRows)
        assertFalse(store.readSettings(mutablePreferencesOf(picked to true, genre to false)).genreRows)
    }
}
