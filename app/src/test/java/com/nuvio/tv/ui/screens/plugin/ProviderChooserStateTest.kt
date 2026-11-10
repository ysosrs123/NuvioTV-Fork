package com.nuvio.tv.ui.screens.plugin

import com.nuvio.tv.domain.model.RepositoryType
import com.nuvio.tv.domain.model.ScraperInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderChooserStateTest {

    private fun provider(id: String, types: List<String>, tags: List<String> = emptyList(), lang: String? = null, enabled: Boolean = true) =
        ScraperInfo(
            id = id, name = id, description = "", version = "1", filename = "$id.cs3",
            supportedTypes = types, enabled = enabled, manifestEnabled = true, logo = null,
            contentLanguage = listOfNotNull(lang), repositoryId = "repo", formats = null,
            type = RepositoryType.EXTERNAL_DEX, tags = tags
        )

    private val providers = listOf(
        provider("Bolly", listOf("movie", "tv"), listOf("Movie", "TvSeries"), "hi"),
        provider("Kaca", listOf("movie"), listOf("Movie"), "id", enabled = false),
        provider("Ani", listOf("tv"), listOf("Anime"), "en"),
        provider("Sport", listOf("tv"), listOf("Live"), "hi")
    )

    @Test
    fun `a new repo starts with nothing ticked, an existing one with what is on`() {
        assertTrue(ProviderChooserState.forRepo("repo", "Phisher", providers, isNewRepo = true).selected.isEmpty())
        assertEquals(
            setOf("Bolly", "Ani", "Sport"),
            ProviderChooserState.forRepo("repo", "Phisher", providers, isNewRepo = false).selected
        )
    }

    @Test
    fun `type and language filters narrow the list`() {
        val state = ProviderChooserState.forRepo("repo", "Phisher", providers, isNewRepo = true)
        assertEquals(listOf("Bolly", "Kaca"), state.copy(type = ProviderTypeFilter.FILMS).shown.map { it.id })
        assertEquals(listOf("Ani"), state.copy(type = ProviderTypeFilter.ANIME).shown.map { it.id })
        assertEquals(listOf("Sport"), state.copy(type = ProviderTypeFilter.LIVE).shown.map { it.id })
        assertEquals(listOf("Bolly", "Sport"), state.copy(language = "hi").shown.map { it.id })
        assertEquals("hi", state.languages.first())
    }

    @Test
    fun `select all shown only adds what the filter shows`() {
        val state = ProviderChooserState.forRepo("repo", "Phisher", providers, isNewRepo = true)
            .copy(language = "hi")
            .selectShown()
        assertEquals(setOf("Bolly", "Sport"), state.selected)
        assertEquals(setOf("Bolly"), state.toggle("Sport").selected)
        assertTrue(state.clear().selected.isEmpty())
    }

    @Test
    fun `filters are hidden when they would not help`() {
        val films = listOf(provider("a", listOf("movie"), lang = "en"), provider("b", listOf("movie"), lang = "en"))
        val state = ProviderChooserState.forRepo("repo", "Small", films, isNewRepo = true)
        assertTrue(state.types.isEmpty())
        assertTrue(state.languages.isEmpty())
    }
}
