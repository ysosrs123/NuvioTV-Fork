package com.nuvio.tv.core.iptv

enum class LiveMenuItem(val id: String, val required: Boolean = false, val list: Boolean = false) {
    SEARCH("search", required = true),
    AIRING("airing"),
    RECORDINGS("recordings"),
    MOVIES("movies"),
    SERIES("series"),
    SPORT("sport"),
    SETTINGS("settings", required = true),
    PHONE_SETUP("phone-setup"),
    FAVOURITES("favourites", list = true),
    ALL_SOURCES("all-sources", list = true),
    CATEGORIES("categories", required = true, list = true),
    SOURCES("sources", required = true, list = true)
}

object LiveMenuLayout {
    val DEFAULT: List<LiveMenuItem> = LiveMenuItem.entries.toList()
    private const val MAX_SAVED = 64

    fun item(id: String): LiveMenuItem? = LiveMenuItem.entries.firstOrNull { it.id == id.trim() }

    fun order(saved: List<String>): List<LiveMenuItem> {
        val placed = saved.take(MAX_SAVED).mapNotNull(::item).distinct().toMutableList()
        DEFAULT.forEachIndexed { index, item ->
            if (item in placed) return@forEachIndexed
            val before = DEFAULT.subList(0, index).lastOrNull { it in placed }
            val after = DEFAULT.subList(index + 1, DEFAULT.size).firstOrNull { it in placed }
            placed.add(when {
                before != null -> placed.indexOf(before) + 1
                after != null -> placed.indexOf(after)
                else -> placed.size
            }, item)
        }
        return placed
    }

    fun parse(saved: String?): List<LiveMenuItem> = order(saved?.split(',').orEmpty().filter { it.isNotBlank() })

    fun encode(order: List<LiveMenuItem>): String = order.distinct().joinToString(",") { it.id }

    fun hidden(saved: Collection<String>): Set<LiveMenuItem> = saved.take(MAX_SAVED).mapNotNull(::item).filterNot { it.required }.toSet()

    fun toggle(hidden: Set<LiveMenuItem>, item: LiveMenuItem): Set<LiveMenuItem> = when {
        item.required -> hidden - item
        item in hidden -> hidden - item
        else -> hidden + item
    }

    fun shown(order: List<LiveMenuItem>, hidden: Set<LiveMenuItem>, available: (LiveMenuItem) -> Boolean): List<LiveMenuItem> =
        order.filter { (it.required || it !in hidden) && available(it) }

    fun move(order: List<LiveMenuItem>, shown: List<LiveMenuItem>, item: LiveMenuItem, move: ListMove): List<LiveMenuItem>? {
        val visible = order.filter { it in shown }
        val moved = movedList(visible, visible.indexOf(item), move) ?: return null
        val next = moved.iterator()
        return order.map { if (it in shown) next.next() else it }
    }

    fun customised(order: List<LiveMenuItem>, hidden: Set<LiveMenuItem>): Boolean = order != DEFAULT || hidden.any { !it.required }
}
