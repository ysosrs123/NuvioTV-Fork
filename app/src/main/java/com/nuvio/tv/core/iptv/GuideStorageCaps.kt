package com.nuvio.tv.core.iptv

data class GuideStorageCaps(
    val programmesPerChannel: Int = 200,
    val descriptionCharacters: Int = 250,
    val channels: Int = 50_000,
    val descriptions: Int = 3,
) {
    init { require(programmesPerChannel > 0 && descriptionCharacters > 0 && channels > 0 && descriptions > 0) }
}

fun guideStorageCaps(fromMillis: Long, untilMillis: Long): GuideStorageCaps {
    require(untilMillis > fromMillis)
    val days = (untilMillis - fromMillis + 86_399_999L) / 86_400_000L
    return GuideStorageCaps(programmesPerChannel = maxOf(200L, days * 40).toInt())
}

const val GUIDE_MIN_DATABASE_BYTES = 256L * 1024 * 1024
const val GUIDE_MAX_DATABASE_BYTES = 2L * 1024 * 1024 * 1024
const val GUIDE_MIN_FEED_BUDGET_BYTES = 16L * 1024 * 1024

fun guideDatabaseCap(freeBytes: Long): Long = (maxOf(0L, freeBytes) / 4).coerceIn(GUIDE_MIN_DATABASE_BYTES, GUIDE_MAX_DATABASE_BYTES)

fun guideFeedBudget(capBytes: Long, otherGuides: Int, previousCopyKept: Boolean): Long {
    require(capBytes > 0 && otherGuides >= 0)
    return maxOf(GUIDE_MIN_FEED_BUDGET_BYTES, capBytes / (otherGuides + if (previousCopyKept) 2 else 1))
}

fun guideProgrammeBytes(externalId: String, payload: String, searchTitle: String?): Long =
    2L * externalId.length + payload.length + (searchTitle?.length ?: 0) + 48

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
        val kept = programme.descriptions.distinctBy { it.language }.take(caps.descriptions)
        if (kept.size == programme.descriptions.size && kept.none { it.text.length > caps.descriptionCharacters }) return programme
        return programme.copy(descriptions = kept.map { if (it.text.length > caps.descriptionCharacters) it.copy(text = shortenGuideText(it.text, caps.descriptionCharacters)) else it })
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
