package com.nuvio.tv.core.scout

import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.ScoutSettings
import com.nuvio.tv.domain.model.TmdbCollectionFilters

/** Leave out genres and Leave out languages, as saved rows and tile pages apply them. */
data class ScoutLeaveOut(val genres: Set<Int> = emptySet(), val languages: Set<String> = emptySet()) {
    val isEmpty: Boolean get() = genres.isEmpty() && languages.isEmpty()

    /** True when everything the tile asks for is left out, such as Japanese with Japanese left out. */
    fun covers(slice: ScoutSlice): Boolean {
        if (isEmpty) return false
        val asked = listOfNotNull(slice.movie, slice.tv)
        return asked.isNotEmpty() && asked.all { languageLeftOut(it.withOriginalLanguage) || genresLeftOut(it.withGenres) }
    }

    fun applyTo(filters: TmdbCollectionFilters): TmdbCollectionFilters {
        if (genres.isEmpty()) return filters
        val without = (filters.withoutGenres?.split(',').orEmpty().map(String::trim).filter(String::isNotEmpty) +
            genres.sorted().map(Int::toString)).distinct()
        return filters.copy(withoutGenres = without.joinToString(","))
    }

    fun keeps(item: MetaPreview): Boolean {
        val language = item.originalLanguage?.lowercase()
        if (language != null && language in languages) return false
        return item.tmdbGenreIds.orEmpty().none { it in genres }
    }

    private fun languageLeftOut(codes: String?): Boolean {
        val list = codes?.split('|', ',')?.map { it.trim().lowercase() }?.filter(String::isNotEmpty).orEmpty()
        return list.isNotEmpty() && list.all { it in languages }
    }

    private fun genresLeftOut(ids: String?): Boolean {
        if (ids.isNullOrBlank()) return false
        return if (',' in ids) {
            ids.split(',').mapNotNull { it.trim().toIntOrNull() }.any { it in genres }
        } else {
            val list = ids.split('|').mapNotNull { it.trim().toIntOrNull() }
            list.isNotEmpty() && list.all { it in genres }
        }
    }

    companion object {
        fun of(settings: ScoutSettings) = ScoutLeaveOut(
            genres = settings.leaveOutGenres.flatMapTo(HashSet(), ::scoutGenreLeaveOutIds),
            languages = scoutLeaveOutLanguageCodes(settings.leaveOutLanguages)
        )
    }
}
