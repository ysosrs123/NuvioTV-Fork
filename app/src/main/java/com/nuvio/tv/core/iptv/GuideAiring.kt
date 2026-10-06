package com.nuvio.tv.core.iptv

const val GUIDE_AIRING_LOOKBACK_MILLIS = 24L * 60 * 60 * 1000
const val GUIDE_SEARCH_TITLE_CHARACTERS = 512

data class GuideAiringCandidates(val guideIds: Set<String>, val nameKeys: Set<String>, val keys: Set<GuideKey>)

fun guideSearchTitle(titles: List<LocalizedGuideText>): String? {
    val text = titles.map { foldSearchText(it.text).trim() }.filter(String::isNotEmpty).distinct().joinToString("\n")
    if (text.length <= GUIDE_SEARCH_TITLE_CHARACTERS) return text.ifEmpty { null }
    val end = if (Character.isHighSurrogate(text[GUIDE_SEARCH_TITLE_CHARACTERS - 1])) GUIDE_SEARCH_TITLE_CHARACTERS - 1 else GUIDE_SEARCH_TITLE_CHARACTERS
    return text.substring(0, end)
}

fun guideSearchQuery(query: String): String? {
    require(query.length <= 256)
    return foldSearchText(query.trim()).trim().takeIf { it.isNotEmpty() && '\n' !in it }
}

fun guideChannelNameKeys(channel: GuideChannel): List<String> =
    (channel.names.map { guideMatchName(it.text) } + guideIdMatchName(channel.externalId)).filter { it.length >= 2 }.distinct()

fun channelNameKey(name: String): String? = guideMatchName(name).takeIf { it.length >= 2 }

fun guideAiringAt(programme: GuideProgramme, nowMillis: Long): Boolean {
    val start = programme.start
    val stop = programme.stop
    return start.precise && start.epochMillis <= nowMillis && start.epochMillis > nowMillis - GUIDE_AIRING_LOOKBACK_MILLIS &&
        (stop == null || (stop.precise && stop.epochMillis > nowMillis))
}

fun guideAiringCandidates(channels: List<Pair<GuideKey, GuideChannel>>, maxNames: Int = 500): GuideAiringCandidates {
    require(maxNames >= 0)
    return GuideAiringCandidates(
        channels.mapTo(LinkedHashSet()) { it.first.externalId },
        channels.asSequence().flatMap { guideChannelNameKeys(it.second) }.distinct().take(maxNames).toCollection(LinkedHashSet()),
        channels.mapTo(LinkedHashSet()) { it.first },
    )
}

fun earliestAiring(matches: List<Pair<GuideKey, GuideProgramme>>): Map<GuideKey, GuideProgramme> =
    matches.groupBy({ it.first }, { it.second }).mapValues { (_, programmes) ->
        programmes.minWith(compareBy({ it.start.epochMillis }, { it.stop?.epochMillis ?: Long.MAX_VALUE }, { it.titles.firstOrNull()?.text.orEmpty() }))
    }

fun <T> airingChannels(channels: List<T>, id: (T) -> String, key: (T) -> GuideKey?, airing: Map<GuideKey, GuideProgramme>, limit: Int): List<Pair<T, GuideProgramme>> {
    require(limit > 0)
    val seen = HashSet<String>()
    val results = mutableListOf<Pair<T, GuideProgramme>>()
    for (channel in channels) {
        if (results.size >= limit) break
        val programme = key(channel)?.let(airing::get) ?: continue
        if (seen.add(id(channel))) results += channel to programme
    }
    return results
}
