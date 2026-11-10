package com.nuvio.tv.domain.model

private val WATCHING_LIST_KEYS = setOf("simkl:status:watching", "wetrakr:status:watching")

/**
 * Titles on the Library source's Plan to watch and Watching lists, under every id each one is known by.
 * Home's two "Also hide" switches and Scout's picks both read this.
 */
data class ListedTitles(
    val planIds: Set<String> = emptySet(),
    val watchingIds: Set<String> = emptySet(),
    val planListKeys: Set<String> = emptySet(),
    val watchingListKeys: Set<String> = emptySet()
) {
    fun isPlanned(id: String): Boolean = id in planIds
    fun isWatching(id: String): Boolean = id in watchingIds
}

/** Watching also counts what is part way through in Continue watching, since not every tracker has that list. */
fun listedTitles(
    tabs: List<LibraryListTab>,
    items: List<LibraryEntry>,
    inProgressIds: List<String> = emptyList()
): ListedTitles {
    val planKeys = tabs.filter { it.type == LibraryListTab.Type.WATCHLIST }.mapTo(hashSetOf()) { it.key }
    val watchingKeys = tabs.filter { it.key in WATCHING_LIST_KEYS }.mapTo(hashSetOf()) { it.key }
    val planIds = hashSetOf<String>()
    val watchingIds = HashSet(inProgressIds)
    items.forEach { entry ->
        val onPlan = entry.listKeys.any(planKeys::contains)
        val onWatching = entry.listKeys.any(watchingKeys::contains)
        if (!onPlan && !onWatching) return@forEach
        val ids = entry.titleIds()
        if (onPlan) planIds += ids
        if (onWatching) watchingIds += ids
    }
    return ListedTitles(planIds, watchingIds, planKeys, watchingKeys)
}

private fun LibraryEntry.titleIds(): List<String> = listOfNotNull(
    id,
    imdbId,
    tmdbId?.let { "tmdb:$it" }
)
