package com.nuvio.tv.core.iptv

import java.util.Locale

enum class SportsPickKind { CATEGORY, CHANNEL }

data class SportsChannelPick(val kind: SportsPickKind, val value: String, val label: String = value) {
    val key: String get() = "${kind.name}:${if (kind == SportsPickKind.CATEGORY) SportsChannelPicks.category(value) else value}"
}

enum class SportsPickList { ALWAYS, NEVER, PREFERRED, EXCLUDED }

data class SportsChannelRules(val always: List<SportsChannelPick> = emptyList(), val never: List<SportsChannelPick> = emptyList(),
    val preferred: List<SportsChannelPick> = emptyList(), val excluded: List<SportsChannelPick> = emptyList()) {
    fun list(which: SportsPickList): List<SportsChannelPick> = when (which) {
        SportsPickList.ALWAYS -> always
        SportsPickList.NEVER -> never
        SportsPickList.PREFERRED -> preferred
        SportsPickList.EXCLUDED -> excluded
    }

    fun sportOnly(channelId: String, category: String?, matched: Boolean): Boolean =
        !SportsChannelPicks.has(never, channelId, category) && (matched || SportsChannelPicks.has(always, channelId, category))

    fun offered(channelId: String, category: String?): Boolean = !SportsChannelPicks.has(excluded, channelId, category)

    fun rank(channelId: String, category: String?): Int = when {
        preferred.any { it.kind == SportsPickKind.CHANNEL && it.value == channelId } -> 0
        SportsChannelPicks.category(category).let { wanted -> wanted.isNotEmpty() && preferred.any { it.kind == SportsPickKind.CATEGORY && SportsChannelPicks.category(it.value) == wanted } } -> 1
        else -> 2
    }

    val linking: SportsChannelRules get() = SportsChannelRules(preferred = preferred, excluded = excluded)

    fun categories(which: SportsPickList): List<String> = list(which).filter { it.kind == SportsPickKind.CATEGORY }.map { it.value }

    fun channels(which: SportsPickList): List<SportsChannelPick> = list(which).filter { it.kind == SportsPickKind.CHANNEL }

    fun <T> ranked(links: List<T>, channel: (T) -> Pair<String, String?>): List<T> =
        if (preferred.isEmpty() && excluded.isEmpty()) links
        else links.filter { channel(it).let { (id, category) -> offered(id, category) } }.sortedBy { channel(it).let { (id, category) -> rank(id, category) } }
}

object SportsChannelPicks {
    const val MAX = 100
    private const val SEPARATOR = '\t'

    fun category(name: String?): String = name?.trim()?.lowercase(Locale.ROOT).orEmpty()

    fun has(picks: List<SportsChannelPick>, channelId: String, category: String?): Boolean {
        if (picks.isEmpty()) return false
        val wanted = category(category)
        return picks.any { pick ->
            when (pick.kind) {
                SportsPickKind.CHANNEL -> pick.value == channelId
                SportsPickKind.CATEGORY -> wanted.isNotEmpty() && category(pick.value) == wanted
            }
        }
    }

    fun encode(pick: SportsChannelPick): String =
        "${if (pick.kind == SportsPickKind.CATEGORY) 'c' else 'h'}$SEPARATOR${clean(pick.value)}$SEPARATOR${clean(pick.label).trim()}"

    fun decode(text: String): SportsChannelPick? {
        val parts = text.split(SEPARATOR)
        if (parts.size != 3) return null
        val kind = when (parts[0]) { "c" -> SportsPickKind.CATEGORY; "h" -> SportsPickKind.CHANNEL; else -> return null }
        val value = parts[1].takeIf { it.isNotBlank() && it.length <= 240 } ?: return null
        return SportsChannelPick(kind, value, parts[2].trim().ifEmpty { value.trim() }.take(240))
    }

    fun clean(picks: List<SportsChannelPick>): List<SportsChannelPick> =
        picks.filter { it.value.isNotBlank() && it.value.length <= 240 }.distinctBy { it.key }.take(MAX)

    fun toggle(picks: List<SportsChannelPick>, pick: SportsChannelPick): List<SportsChannelPick> =
        if (picks.any { it.key == pick.key }) picks.filter { it.key != pick.key } else clean(picks + pick)

    fun set(rules: SportsChannelRules, which: SportsPickList, picks: List<SportsChannelPick>): SportsChannelRules {
        val kept = clean(picks)
        val keys = kept.map { it.key }.toSet()
        fun without(list: List<SportsChannelPick>) = list.filter { it.key !in keys }
        return when (which) {
            SportsPickList.ALWAYS -> rules.copy(always = kept, never = without(rules.never))
            SportsPickList.NEVER -> rules.copy(never = kept, always = without(rules.always))
            SportsPickList.PREFERRED -> rules.copy(preferred = kept, excluded = without(rules.excluded))
            SportsPickList.EXCLUDED -> rules.copy(excluded = kept, preferred = without(rules.preferred))
        }
    }

    private fun clean(value: String): String = value.replace(SEPARATOR, ' ').replace('\n', ' ').take(240)
}

object SportsOnlyWindow {
    const val AHEAD_MILLIS = 24L * 60 * 60 * 1000
    private const val SPAN_MILLIS = 28L * 60 * 60 * 1000
    private const val MIN_SHIFT_MILLIS = 60L * 60 * 1000

    fun range(nowMillis: Long, viewStart: Long, viewEnd: Long): Pair<Long, Long> = minOf(nowMillis, viewStart) to maxOf(viewEnd, nowMillis + AHEAD_MILLIS)

    fun guide(nowMillis: Long): GuideGridWindow {
        val slot = GuideGridWindow.SLOT_MILLIS
        val start = Math.floorDiv(nowMillis, slot) * slot - slot
        return GuideGridWindow(start, start + minOf(SPAN_MILLIS, GuideGridWindow.MAX_SPAN_MILLIS))
    }

    fun fresh(window: GuideGridWindow, nowMillis: Long): Boolean = window.spanMillis == guide(nowMillis).spanMillis && nowMillis >= window.startMillis &&
        nowMillis <= window.startMillis + maxOf(MIN_SHIFT_MILLIS, window.spanMillis - AHEAD_MILLIS - GuideGridWindow.SLOT_MILLIS)

    fun overlaps(spans: LongArray?, fromMillis: Long, untilMillis: Long): Boolean {
        if (spans == null) return false
        var index = 0
        while (index + 1 < spans.size) {
            if (spans[index] < untilMillis && spans[index + 1] > fromMillis) return true
            index += 2
        }
        return false
    }
}
