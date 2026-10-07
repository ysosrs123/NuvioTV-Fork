package com.nuvio.tv.core.iptv

enum class ListMove { UP, DOWN, TOP, BOTTOM }

data class OrderedChannel(val id: String, val key: Long)

fun moveTarget(index: Int, size: Int, move: ListMove): Int? {
    if (index !in 0 until size) return null
    val target = when (move) {
        ListMove.UP -> index - 1
        ListMove.DOWN -> index + 1
        ListMove.TOP -> 0
        ListMove.BOTTOM -> size - 1
    }
    return target.takeIf { it in 0 until size && it != index }
}

fun <T> movedList(items: List<T>, index: Int, move: ListMove): List<T>? = moveTarget(index, items.size, move)?.let { moveItem(items, index, it) }

object ChannelOrder {
    const val SCALE = 1L shl 32

    fun baseKey(position: Long): Long = position * SCALE

    fun plan(channels: List<OrderedChannel>, id: String, move: ListMove): Map<String, Long> {
        val index = channels.indexOfFirst { it.id == id }
        val target = moveTarget(index, channels.size, move) ?: return emptyMap()
        val others = channels.filterIndexed { i, _ -> i != index }
        val before = others.getOrNull(target - 1)
        val after = others.getOrNull(target)
        val key = when {
            before == null && after == null -> null
            before == null -> requireNotNull(after).key.takeIf { it > Long.MIN_VALUE + SCALE }?.minus(SCALE)
            after == null -> before.key.takeIf { it < Long.MAX_VALUE - SCALE }?.plus(SCALE)
            after.key - before.key >= 2 -> before.key + (after.key - before.key) / 2
            else -> null
        }
        if (key != null) return mapOf(id to key)
        val reordered = others.toMutableList().apply { add(target, channels[index]) }
        val base = channels.minOf { it.key }.coerceIn(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2 - SCALE * channels.size)
        return reordered.mapIndexedNotNull { position, channel ->
            val next = base + position * SCALE
            if (next != channel.key) channel.id to next else null
        }.toMap()
    }
}

object CategoryOrder {
    const val MAX_SAVED = 2000

    fun apply(names: List<String>, saved: List<String>): List<String> {
        if (saved.isEmpty()) return names
        val present = names.toSet()
        val first = saved.filter { it in present }.distinct()
        val placed = first.toSet()
        return first + names.filter { it !in placed }
    }

    fun move(visible: List<String>, hidden: List<String>, name: String, move: ListMove): List<String>? =
        movedList(visible, visible.indexOf(name), move)?.let { (it + hidden.filter { h -> h !in it }).take(MAX_SAVED) }
}
