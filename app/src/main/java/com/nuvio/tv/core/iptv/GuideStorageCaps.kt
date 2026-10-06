package com.nuvio.tv.core.iptv

data class GuideStorageCaps(
    val programmesPerChannel: Int = 200,
    val descriptionCharacters: Int = 400,
    val channels: Int = 50_000,
) {
    init { require(programmesPerChannel > 0 && descriptionCharacters > 0 && channels > 0) }
}

class GuideProgrammeCap(private val caps: GuideStorageCaps = GuideStorageCaps()) {
    private val counts = HashMap<String, Int>()
    var dropped = 0L
        private set

    fun admit(programme: GuideProgramme): GuideProgramme? {
        val count = counts[programme.channelExternalId]
        if ((count == null && counts.size >= caps.channels) || (count ?: 0) >= caps.programmesPerChannel) {
            dropped++
            return null
        }
        counts[programme.channelExternalId] = (count ?: 0) + 1
        if (programme.descriptions.none { it.text.length > caps.descriptionCharacters }) return programme
        return programme.copy(descriptions = programme.descriptions.map { it.copy(text = shortenGuideText(it.text, caps.descriptionCharacters)) })
    }
}

fun shortenGuideText(text: String, limit: Int): String {
    require(limit > 0)
    if (text.length <= limit) return text
    var end = limit - 1
    if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
    val cut = text.substring(0, end)
    val word = cut.lastIndexOf(' ').takeIf { it >= limit * 3 / 4 } ?: cut.length
    return cut.substring(0, word).trimEnd() + "…"
}
