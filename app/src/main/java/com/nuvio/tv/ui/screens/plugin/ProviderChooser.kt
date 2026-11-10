package com.nuvio.tv.ui.screens.plugin

import com.nuvio.tv.domain.model.ScraperInfo

enum class ProviderTypeFilter { ALL, FILMS, SERIES, ANIME, LIVE }

private val ANIME_TAGS = setOf("anime", "animemovie", "ova")

internal fun ScraperInfo.matches(filter: ProviderTypeFilter): Boolean = when (filter) {
    ProviderTypeFilter.ALL -> true
    ProviderTypeFilter.FILMS -> supportsType("movie")
    ProviderTypeFilter.SERIES -> supportsType("tv")
    ProviderTypeFilter.ANIME -> tags.any { it.lowercase() in ANIME_TAGS }
    ProviderTypeFilter.LIVE -> tags.any { it.equals("live", ignoreCase = true) }
}

data class ProviderChooserState(
    val repoId: String,
    val repoName: String,
    val providers: List<ScraperInfo>,
    val selected: Set<String>,
    val isNewRepo: Boolean,
    val type: ProviderTypeFilter = ProviderTypeFilter.ALL,
    val language: String? = null
) {
    val shown: List<ScraperInfo>
        get() = providers.filter { it.matches(type) && (language == null || language in it.contentLanguage) }

    val types: List<ProviderTypeFilter>
        get() = ProviderTypeFilter.entries.filter { filter ->
            filter == ProviderTypeFilter.ALL || providers.any { it.matches(filter) }
        }.takeIf { it.size > 2 } ?: emptyList()

    val languages: List<String>
        get() = providers.flatMap { it.contentLanguage }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { it.key }
            .takeIf { it.size > 1 } ?: emptyList()

    fun toggle(id: String) = copy(selected = if (id in selected) selected - id else selected + id)
    fun selectShown() = copy(selected = selected + shown.map { it.id })
    fun clear() = copy(selected = emptySet())

    companion object {
        fun forRepo(repoId: String, repoName: String, providers: List<ScraperInfo>, isNewRepo: Boolean) =
            ProviderChooserState(
                repoId = repoId,
                repoName = repoName,
                providers = providers.sortedBy { it.name.lowercase() },
                selected = if (isNewRepo) emptySet() else providers.filter { it.enabled }.map { it.id }.toSet(),
                isNewRepo = isNewRepo
            )
    }
}
