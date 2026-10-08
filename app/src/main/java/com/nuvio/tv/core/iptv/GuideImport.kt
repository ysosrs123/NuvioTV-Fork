package com.nuvio.tv.core.iptv

import java.security.MessageDigest
import java.util.Locale

private const val GUIDE_DAY_MILLIS = 86_400_000L

data class GuideDays(val past: Int = 1, val future: Int = 3) {
    init { require(past in PAST_OPTIONS && future in FUTURE_OPTIONS) }

    fun window(nowMillis: Long): Pair<Long, Long> {
        val day = Math.floorDiv(nowMillis, GUIDE_DAY_MILLIS) * GUIDE_DAY_MILLIS
        return day - past * GUIDE_DAY_MILLIS to day + (future + 1) * GUIDE_DAY_MILLIS
    }

    companion object {
        val PAST_OPTIONS = listOf(1, 3, 7)
        val FUTURE_OPTIONS = listOf(3, 7)
        val OPTIONS = PAST_OPTIONS.flatMap { past -> FUTURE_OPTIONS.map { GuideDays(past, it) } }
        fun of(past: Int, future: Int) = GuideDays(past.takeIf { it in PAST_OPTIONS } ?: 1, future.takeIf { it in FUTURE_OPTIONS } ?: 3)
    }
}

fun guideChannelKey(externalId: String): String = externalId.trim().lowercase(Locale.ROOT)

class GuideImportFilter(guideIds: Collection<String>, nameKeys: Collection<String>) {
    val ids: Set<String> = guideIds.mapNotNullTo(HashSet()) { guideChannelKey(it).takeIf(String::isNotEmpty) }
    val names: Set<String> = nameKeys.filterTo(HashSet()) { it.length >= 2 }
    val empty: Boolean get() = ids.isEmpty() && names.isEmpty()

    fun matches(channel: GuideChannel): Boolean {
        if (empty) return false
        if (guideChannelKey(channel.externalId) in ids) return true
        if (guideIdWithoutFeedSuffix(channel.externalId)?.let { guideChannelKey(it) in ids } == true) return true
        return names.isNotEmpty() && guideChannelNameKeys(channel).any { it in names }
    }

    val fingerprint: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        for (value in ids.sorted()) { digest.update(value.toByteArray(Charsets.UTF_8)); digest.update(0) }
        digest.update(1)
        for (value in names.sorted()) { digest.update(value.toByteArray(Charsets.UTF_8)); digest.update(0) }
        digest.digest().take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

class GuideImportChannels(private val filter: GuideImportFilter?, private val maxChannels: Int) {
    enum class Admission { NEW, DUPLICATE, SKIPPED }

    private val known = HashMap<String, String>()
    private val implied = LinkedHashMap<String, String>()
    private val wanted = HashSet<String>()
    private val moved = mutableListOf<Pair<String, String>>()
    var skipped = 0
        private set

    init { require(maxChannels > 0) }

    val renames: List<Pair<String, String>> get() = moved
    val impliedChannels: List<GuideChannel> get() = implied.values.map(::impliedChannel)
    val channelCount: Int get() = known.size + implied.size

    fun channel(channel: GuideChannel): Admission {
        val key = guideChannelKey(channel.externalId)
        if (key in known) return Admission.DUPLICATE
        val earlier = implied.remove(key)
        if (earlier == null && channelCount >= maxChannels) { skipped++; return Admission.SKIPPED }
        known[key] = channel.externalId
        val earlierWanted = earlier != null && wanted.remove(earlier)
        if (earlier != null && earlier != channel.externalId) moved += earlier to channel.externalId
        if (earlierWanted || filter == null || filter.matches(channel)) wanted += channel.externalId
        return Admission.NEW
    }

    fun storedId(externalId: String): String? = known[guideChannelKey(externalId)]

    fun programme(externalId: String): String? {
        val key = guideChannelKey(externalId)
        (known[key] ?: implied[key])?.let { return it.takeIf(wanted::contains) }
        if (key.isEmpty() || channelCount >= maxChannels) return null
        val id = externalId.trim()
        implied[key] = id
        if (filter == null || filter.matches(impliedChannel(id))) wanted += id
        return id.takeIf(wanted::contains)
    }

    fun wants(externalId: String?): Boolean {
        val key = externalId?.let(::guideChannelKey)?.takeIf(String::isNotEmpty) ?: return false
        (known[key] ?: implied[key])?.let { return it in wanted }
        return filter == null || filter.matches(impliedChannel(externalId.trim()))
    }

    private fun impliedChannel(id: String) = GuideChannel(id, listOf(LocalizedGuideText(id, null)))
}

enum class GuideImportIssue { OUT_OF_DATE, NO_CHANNELS, TOO_MANY_INVALID, TOO_LARGE, NOT_XMLTV, FEWER_PROGRAMMES, NOT_LINKED }

data class GuideImportResult(val decision: RefreshDecision, val issue: GuideImportIssue? = null)

data class GuideImportCounts(
    val channels: Int,
    val programmes: Long,
    val matched: Long,
    val inWindow: Long,
    val stored: Long,
    val rejected: Long,
    val previous: Long = 0,
    val programmesWanted: Boolean = true,
    val comparable: Boolean = true,
)

fun guideImportDecision(counts: GuideImportCounts): GuideImportResult = with(counts) {
    when {
        channels == 0 && programmes == 0L && rejected == 0L -> GuideImportResult(RefreshDecision.EMPTY_REQUIRES_REVIEW, GuideImportIssue.NO_CHANNELS)
        !programmesWanted -> GuideImportResult(RefreshDecision.PUBLISH, GuideImportIssue.NOT_LINKED)
        !guideQuarantineAccepted(inWindow, rejected) || (stored == 0L && rejected > 0) ->
            GuideImportResult(RefreshDecision.INVALID, GuideImportIssue.TOO_MANY_INVALID)
        stored == 0L && (matched > 0 || programmes == 0L) -> GuideImportResult(RefreshDecision.EMPTY_REQUIRES_REVIEW, GuideImportIssue.OUT_OF_DATE)
        comparable && previous > 0 && stored * 2 < previous -> GuideImportResult(RefreshDecision.SHRINK_REQUIRES_REVIEW, GuideImportIssue.FEWER_PROGRAMMES)
        else -> GuideImportResult(RefreshDecision.PUBLISH)
    }
}

fun guideFailureIssue(error: Throwable): GuideImportIssue? = when (error) {
    is GuideFormatException -> if (error.issue == GuideFormatIssue.INPUT_LIMIT) GuideImportIssue.TOO_LARGE else GuideImportIssue.NOT_XMLTV
    is GuideLimitException -> GuideImportIssue.TOO_LARGE
    else -> null
}
