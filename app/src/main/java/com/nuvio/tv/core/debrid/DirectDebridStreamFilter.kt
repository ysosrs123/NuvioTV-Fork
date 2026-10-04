package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.DebridStreamAudioChannel
import com.nuvio.tv.domain.model.DebridStreamAudioTag
import com.nuvio.tv.domain.model.DebridStreamCodecFilter
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamFeatureFilter
import com.nuvio.tv.domain.model.DebridStreamLanguage
import com.nuvio.tv.domain.model.DebridStreamMinimumQuality
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.DebridStreamQuality
import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.domain.model.DebridStreamSortCriterion
import com.nuvio.tv.domain.model.DebridStreamSortDirection
import com.nuvio.tv.domain.model.DebridStreamSortKey
import com.nuvio.tv.domain.model.DebridStreamSortMode
import com.nuvio.tv.domain.model.DebridStreamVisualTag
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import java.util.concurrent.ConcurrentHashMap

object DirectDebridStreamFilter {
    const val FALLBACK_SOURCE_NAME = "Direct Debrid"

    private val tokenRegexCache = ConcurrentHashMap<String, Regex>()
    private val DV_TEXT_REGEX = Regex("(^|[^a-z0-9])(dv|dovi|dolby[ ._-]?vision)([^a-z0-9]|$)")
    private val HDR_TEXT_REGEX = Regex("(^|[^a-z0-9])(hdr|hdr10|hdr10plus|hdr10\\+|hlg)([^a-z0-9]|$)")
    private val CONTAINER_EXTENSION_REGEX = Regex("\\.(mkv|mp4|m4v|avi|ts|m2ts|webm|mov)$", RegexOption.IGNORE_CASE)
    private val LEADING_GROUP_TOKEN_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._]{0,23}")
    private val BRACKET_GROUP_REGEX = Regex("^\\[([A-Za-z0-9][A-Za-z0-9._-]{1,23})\\]")
    private val CHANNEL_TOKEN_REGEX = Regex("^\\d\\.\\d$")

    /** Hyphen-suffixed tokens in release names that are never the group. */
    private val NON_GROUP_TOKENS = setOf(
        "dl", "rip", "hd", "uhd", "sd", "web", "bluray", "remux", "hdr", "hdr10", "sdr",
        "dv", "dovi", "ma", "es", "x", "atmos", "truehd", "dts", "aac", "ac3", "eac3",
        "flac", "opus", "avc", "hevc", "av1", "x264", "x265", "h264", "h265", "vc1",
        "10bit", "8bit", "hi10p", "imax", "proper", "repack", "extended", "remastered",
        "unrated", "multi", "dual", "sub", "subs", "dubbed", "hc", "cam", "ts", "tc", "scr",
        "2160p", "1440p", "1080p", "720p", "576p", "480p", "360p", "4k", "2k"
    )

    /** Ladder/exclusion names containing '-' or '.' that last-hyphen splitting would mangle. */
    private val COMPOUND_GROUP_REGEX = Regex(
        "(?<![A-Za-z0-9])(VISIONPLUSHDR\\-X|BR\\-GuyZo|Pahe\\.in|Pahe\\.ph|D\\-Z0N3|YTS\\.LT|YTS\\.MX|YTS\\.AG|C\\.A\\.A)(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE
    )

    fun filterInstant(streams: List<Stream>, settings: DebridSettings? = null): List<Stream> {
        val instantStreams = streams
            .filter { isInstantCandidate(it) }
            .map { stream ->
                val sourceName = sourceName(stream)
                stream.copy(
                    name = stream.name ?: sourceName,
                    addonName = sourceName,
                    addonLogo = null
                )
            }
            .distinctBy { stream ->
                listOf(
                    stream.clientResolve?.infoHash?.lowercase(),
                    stream.clientResolve?.fileIdx?.toString(),
                    stream.clientResolve?.filename,
                    stream.name,
                    stream.title
                ).joinToString("|")
            }
        return if (settings == null) instantStreams else applyPreferences(instantStreams, settings)
    }

    fun isInstantCandidate(stream: Stream): Boolean {
        val resolve = stream.clientResolve ?: return false
        return resolve.type.equals("debrid", ignoreCase = true) &&
            DebridProviders.isSupported(resolve.service) &&
            resolve.isCached == true
    }

    fun isDirectDebridSourceName(addonName: String): Boolean {
        return DebridProviders.all().any { addonName == DebridProviders.instantName(it.id) }
    }

    fun applyPreferences(streams: List<Stream>, settings: DebridSettings): List<Stream> {
        val preferences = effectivePreferences(settings)
        val matchedStreams = streams.map { it to streamFacts(it, preferences) }
            .filter { (_, facts) -> facts.matchesFilters(preferences) }

        // Cached streams always sort above CHECKING/UNKNOWN entries; the sort
        // is stable, so relative order within each cache bucket is preserved.
        val orderedStreams = if (preferences.sortCriteria.isEmpty()) {
            matchedStreams.sortedBy { (stream, _) -> cachedRank(stream) }
        } else {
            matchedStreams.sortedWith { left, right ->
                val byCache = cachedRank(left.first).compareTo(cachedRank(right.first))
                if (byCache != 0) byCache
                else compareFacts(left.second, right.second, preferences.sortCriteria)
            }
        }

        return applyLimits(orderedStreams, preferences)
            .map { it.first }
    }

    fun facts(stream: Stream, settings: DebridSettings): StreamFacts =
        streamFacts(stream, effectivePreferences(settings))

    private fun effectivePreferences(settings: DebridSettings): DebridStreamPreferences {
        // The datastore always materialises streamPreferences (parsed blob or
        // legacy-derived), so the historical in-filter legacy reconstruction
        // is gone: it re-activated whenever the stored object equalled the
        // shipped defaults -- exactly the state a defaults re-baseline
        // produces -- and silently downgraded the sort chain.
        return settings.streamPreferences
    }

    private fun applyLimits(
        streams: List<Pair<Stream, StreamFacts>>,
        preferences: DebridStreamPreferences
    ): List<Pair<Stream, StreamFacts>> {
        val resolutionCounts = mutableMapOf<DebridStreamResolution, Int>()
        val qualityCounts = mutableMapOf<DebridStreamQuality, Int>()
        val result = mutableListOf<Pair<Stream, StreamFacts>>()
        for (stream in streams) {
            if (preferences.maxResults > 0 && result.size >= preferences.maxResults) break
            if (preferences.maxPerResolution > 0) {
                val count = resolutionCounts[stream.second.resolution] ?: 0
                if (count >= preferences.maxPerResolution) continue
            }
            if (preferences.maxPerQuality > 0) {
                val count = qualityCounts[stream.second.quality] ?: 0
                if (count >= preferences.maxPerQuality) continue
            }
            resolutionCounts[stream.second.resolution] = (resolutionCounts[stream.second.resolution] ?: 0) + 1
            qualityCounts[stream.second.quality] = (qualityCounts[stream.second.quality] ?: 0) + 1
            result += stream
        }
        return result
    }

    private fun StreamFacts.matchesFilters(preferences: DebridStreamPreferences): Boolean =
        passesExclusions(preferences) && passesRequirements(preferences)

    /** Excluded-list checks only; shared with auto-select ranking across all sources. */
    private fun StreamFacts.passesExclusions(preferences: DebridStreamPreferences): Boolean {
        if (resolution in preferences.excludedResolutions) return false
        if (quality in preferences.excludedQualities) return false
        if (visualTags.any { it in preferences.excludedVisualTags }) return false
        if (audioTags.any { it in preferences.excludedAudioTags }) return false
        if (audioChannels.any { it in preferences.excludedAudioChannels }) return false
        if (encode in preferences.excludedEncodes) return false
        if (languages.isNotEmpty() && languages.all { it in preferences.excludedLanguages }) return false
        if (preferences.excludedReleaseGroups.any { releaseGroup.equals(it, ignoreCase = true) }) return false
        return true
    }

    private fun StreamFacts.passesRequirements(preferences: DebridStreamPreferences): Boolean {
        if (preferences.requiredResolutions.isNotEmpty() && resolution !in preferences.requiredResolutions) return false
        if (preferences.requiredQualities.isNotEmpty() && quality !in preferences.requiredQualities) return false
        if (preferences.requiredVisualTags.isNotEmpty() && visualTags.none { it in preferences.requiredVisualTags }) return false
        if (preferences.requiredAudioTags.isNotEmpty() && audioTags.none { it in preferences.requiredAudioTags }) return false
        if (preferences.requiredAudioChannels.isNotEmpty() && audioChannels.none { it in preferences.requiredAudioChannels }) return false
        if (preferences.requiredEncodes.isNotEmpty() && encode !in preferences.requiredEncodes) return false
        if (preferences.requiredLanguages.isNotEmpty() && languages.none { it in preferences.requiredLanguages }) return false
        if (preferences.requiredReleaseGroups.isNotEmpty() && preferences.requiredReleaseGroups.none { releaseGroup.equals(it, ignoreCase = true) }) return false
        if (preferences.sizeMinGb > 0 && size != null && size < preferences.sizeMinGb.gigabytes()) return false
        if (preferences.sizeMaxGb > 0 && size != null && size > preferences.sizeMaxGb.gigabytes()) return false
        return true
    }

    /** Exposed for StreamQualityRank so auto-pick shares list exclusion semantics. */
    fun passesExclusionFilters(facts: StreamFacts, preferences: DebridStreamPreferences): Boolean =
        facts.passesExclusions(preferences)

    /** Exposed for StreamQualityRank so auto-pick shares list fact extraction. */
    fun factsFor(stream: Stream, preferences: DebridStreamPreferences): StreamFacts =
        streamFacts(stream, preferences)

    private fun compareFacts(
        left: StreamFacts,
        right: StreamFacts,
        criteria: List<DebridStreamSortCriterion>
    ): Int {
        for (criterion in criteria) {
            val comparison = compareKey(left, right, criterion)
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun compareKey(
        left: StreamFacts,
        right: StreamFacts,
        criterion: DebridStreamSortCriterion
    ): Int {
        val direction = if (criterion.direction == DebridStreamSortDirection.ASC) 1 else -1
        return when (criterion.key) {
            DebridStreamSortKey.RESOLUTION -> left.resolutionRank.compareTo(right.resolutionRank) * -direction
            DebridStreamSortKey.QUALITY -> left.qualityRank.compareTo(right.qualityRank) * -direction
            DebridStreamSortKey.VISUAL_TAG -> left.visualRank.compareTo(right.visualRank) * -direction
            DebridStreamSortKey.AUDIO_TAG -> left.audioRank.compareTo(right.audioRank) * -direction
            DebridStreamSortKey.AUDIO_CHANNEL -> left.channelRank.compareTo(right.channelRank) * -direction
            DebridStreamSortKey.ENCODE -> left.encodeRank.compareTo(right.encodeRank) * -direction
            DebridStreamSortKey.SIZE -> (left.size ?: 0L).compareTo(right.size ?: 0L) * direction
            DebridStreamSortKey.LANGUAGE -> left.languageRank.compareTo(right.languageRank) * -direction
            DebridStreamSortKey.RELEASE_GROUP -> {
                val byRank = left.groupRank.compareTo(right.groupRank) * -direction
                if (byRank != 0) byRank
                else left.releaseGroup.compareTo(right.releaseGroup, ignoreCase = true)
            }
        }
    }

    private fun streamFacts(stream: Stream, preferences: DebridStreamPreferences): StreamFacts {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val searchText = streamSearchText(stream)
        val resolution = streamResolution(parsed?.resolution, parsed?.quality, stream.quality, searchText)
        val quality = streamQuality(parsed?.quality, searchText)
        val visualTags = streamVisualTags(parsed?.hdr.orEmpty(), searchText)
        val audioTags = streamAudioTags(parsed?.audio.orEmpty(), searchText)
        val audioChannels = streamAudioChannels(parsed?.channels.orEmpty(), searchText)
        val encode = streamEncode(parsed?.codec, searchText)
        val languages = parsed?.languages.orEmpty().mapNotNull { languageFor(it) }.ifEmpty {
            DebridStreamLanguage.entries.filter { searchText.hasToken(it.code) }
        }
        val releaseGroup = releaseGroupOf(stream)
        return StreamFacts(
            resolution = resolution,
            quality = quality,
            visualTags = visualTags,
            audioTags = audioTags,
            audioChannels = audioChannels,
            encode = encode,
            languages = languages,
            releaseGroup = releaseGroup,
            size = streamSize(stream),
            resolutionRank = rank(resolution, preferences.preferredResolutions),
            qualityRank = rank(quality, preferences.preferredQualities),
            visualRank = rankAny(visualTags, preferences.preferredVisualTags),
            audioRank = rankAny(audioTags, preferences.preferredAudioTags),
            channelRank = rankAny(audioChannels, preferences.preferredAudioChannels),
            encodeRank = rank(encode, preferences.preferredEncodes),
            languageRank = if (languages.isEmpty()) Int.MAX_VALUE else languages.minOf { rank(it, preferences.preferredLanguages) },
            groupRank = releaseGroupRank(releaseGroup, preferences.preferredReleaseGroups)
        )
    }

    private fun streamResolution(vararg values: String?): DebridStreamResolution {
        return values.firstNotNullOfOrNull { resolutionValue(it) } ?: DebridStreamResolution.UNKNOWN
    }

    private fun resolutionValue(value: String?): DebridStreamResolution? {
        val normalized = value?.lowercase().orEmpty()
        return when {
            normalized.hasResolutionToken("2160p?", "4k", "uhd") -> DebridStreamResolution.P2160
            normalized.hasResolutionToken("1440p?", "2k") -> DebridStreamResolution.P1440
            normalized.hasResolutionToken("1080p?", "fhd") -> DebridStreamResolution.P1080
            normalized.hasResolutionToken("720p?", "hd") -> DebridStreamResolution.P720
            normalized.hasResolutionToken("576p?") -> DebridStreamResolution.P576
            normalized.hasResolutionToken("480p?", "sd") -> DebridStreamResolution.P480
            normalized.hasResolutionToken("360p?") -> DebridStreamResolution.P360
            else -> null
        }
    }

    private fun streamQuality(parsedQuality: String?, searchText: String): DebridStreamQuality {
        val text = listOfNotNull(parsedQuality, searchText).joinToString(" ").lowercase()
        return when {
            text.contains("remux") -> DebridStreamQuality.BLURAY_REMUX
            text.contains("blu-ray") || text.contains("bluray") || text.contains("bdrip") || text.contains("brrip") -> DebridStreamQuality.BLURAY
            text.contains("web-dl") || text.contains("webdl") -> DebridStreamQuality.WEB_DL
            text.contains("webrip") || text.contains("web-rip") -> DebridStreamQuality.WEBRIP
            text.contains("hdrip") -> DebridStreamQuality.HDRIP
            text.contains("hd-rip") || text.contains("hcrip") -> DebridStreamQuality.HD_RIP
            text.contains("dvdrip") -> DebridStreamQuality.DVDRIP
            text.contains("hdtv") -> DebridStreamQuality.HDTV
            text.hasToken("cam") -> DebridStreamQuality.CAM
            text.hasToken("ts") -> DebridStreamQuality.TS
            text.hasToken("tc") -> DebridStreamQuality.TC
            text.hasToken("scr") -> DebridStreamQuality.SCR
            else -> DebridStreamQuality.UNKNOWN
        }
    }

    private fun streamVisualTags(parsedHdr: List<String>, searchText: String): List<DebridStreamVisualTag> {
        val text = (parsedHdr + searchText).joinToString(" ").lowercase()
        val tags = mutableListOf<DebridStreamVisualTag>()
        val hasDv = parsedHdr.any { it.isDolbyVisionToken() } || DV_TEXT_REGEX.containsMatchIn(searchText)
        val hasHdr = parsedHdr.any { it.isHdrToken() } || HDR_TEXT_REGEX.containsMatchIn(searchText)
        if (hasDv && hasHdr) tags += DebridStreamVisualTag.HDR_DV
        if (hasDv && !hasHdr) tags += DebridStreamVisualTag.DV_ONLY
        if (hasHdr && !hasDv) tags += DebridStreamVisualTag.HDR_ONLY
        if (text.contains("hdr10+") || text.contains("hdr10plus")) tags += DebridStreamVisualTag.HDR10_PLUS
        if (text.contains("hdr10")) tags += DebridStreamVisualTag.HDR10
        if (hasDv) tags += DebridStreamVisualTag.DV
        if (hasHdr) tags += DebridStreamVisualTag.HDR
        if (text.hasToken("hlg")) tags += DebridStreamVisualTag.HLG
        if (text.contains("10bit") || text.contains("10 bit")) tags += DebridStreamVisualTag.TEN_BIT
        if (text.hasToken("3d")) tags += DebridStreamVisualTag.THREE_D
        if (text.hasToken("imax")) tags += DebridStreamVisualTag.IMAX
        if (text.hasToken("ai")) tags += DebridStreamVisualTag.AI
        if (text.hasToken("sdr")) tags += DebridStreamVisualTag.SDR
        if (text.contains("h-ou")) tags += DebridStreamVisualTag.H_OU
        if (text.contains("h-sbs")) tags += DebridStreamVisualTag.H_SBS
        return tags.distinct().ifEmpty { listOf(DebridStreamVisualTag.UNKNOWN) }
    }

    private fun streamAudioTags(parsedAudio: List<String>, searchText: String): List<DebridStreamAudioTag> {
        val text = (parsedAudio + searchText).joinToString(" ").lowercase()
        val tags = mutableListOf<DebridStreamAudioTag>()
        if (text.hasToken("atmos")) tags += DebridStreamAudioTag.ATMOS
        if (text.contains("dd+") || text.contains("ddp") || text.contains("dolby digital plus")) tags += DebridStreamAudioTag.DD_PLUS
        if (text.hasToken("dd") || text.contains("ac3") || text.contains("dolby digital")) tags += DebridStreamAudioTag.DD
        if (text.contains("dts:x") || text.contains("dtsx")) tags += DebridStreamAudioTag.DTS_X
        if (text.contains("dts-hd ma") || text.contains("dtshd ma")) tags += DebridStreamAudioTag.DTS_HD_MA
        if (text.contains("dts-hd") || text.contains("dtshd")) tags += DebridStreamAudioTag.DTS_HD
        if (text.contains("dts-es") || text.contains("dtses")) tags += DebridStreamAudioTag.DTS_ES
        if (text.hasToken("dts")) tags += DebridStreamAudioTag.DTS
        if (text.contains("truehd") || text.contains("true hd")) tags += DebridStreamAudioTag.TRUEHD
        if (text.hasToken("opus")) tags += DebridStreamAudioTag.OPUS
        if (text.hasToken("flac")) tags += DebridStreamAudioTag.FLAC
        if (text.hasToken("aac")) tags += DebridStreamAudioTag.AAC
        return tags.distinct().ifEmpty { listOf(DebridStreamAudioTag.UNKNOWN) }
    }

    private fun streamAudioChannels(parsedChannels: List<String>, searchText: String): List<DebridStreamAudioChannel> {
        val text = (parsedChannels + searchText).joinToString(" ").lowercase()
        val channels = mutableListOf<DebridStreamAudioChannel>()
        if (text.hasToken("7.1")) channels += DebridStreamAudioChannel.CH_7_1
        if (text.hasToken("6.1")) channels += DebridStreamAudioChannel.CH_6_1
        if (text.hasToken("5.1") || text.hasToken("6ch")) channels += DebridStreamAudioChannel.CH_5_1
        if (text.hasToken("2.0")) channels += DebridStreamAudioChannel.CH_2_0
        return channels.distinct().ifEmpty { listOf(DebridStreamAudioChannel.UNKNOWN) }
    }

    private fun streamEncode(parsedCodec: String?, searchText: String): DebridStreamEncode {
        val text = listOfNotNull(parsedCodec, searchText).joinToString(" ").lowercase()
        return when {
            text.hasToken("av1") -> DebridStreamEncode.AV1
            text.hasToken("hevc") || text.hasToken("h265") || text.hasToken("x265") -> DebridStreamEncode.HEVC
            text.hasToken("avc") || text.hasToken("h264") || text.hasToken("x264") -> DebridStreamEncode.AVC
            text.hasToken("xvid") -> DebridStreamEncode.XVID
            text.hasToken("divx") -> DebridStreamEncode.DIVX
            else -> DebridStreamEncode.UNKNOWN
        }
    }

    private fun languageFor(value: String): DebridStreamLanguage? {
        val normalized = value.lowercase()
        return DebridStreamLanguage.entries.firstOrNull {
            normalized == it.code || normalized == it.label.lowercase()
        }
    }

    /**
     * Parses the release group from a stream's advertised fields, tried
     * most-reliable-first: resolver-parsed group, then filenames, torrent
     * names, and finally display text. URLs are deliberately not candidates.
     */
    internal fun releaseGroupOf(stream: Stream): String {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        val parsedGroup = raw?.parsed?.group
        if (!parsedGroup.isNullOrBlank()) return parsedGroup.trim()
        val candidates = listOfNotNull(
            stream.behaviorHints?.filename,
            resolve?.filename,
            raw?.filename,
            resolve?.torrentName,
            raw?.torrentName,
            stream.name,
            stream.title,
            stream.description
        )
        for (candidate in candidates) {
            val group = releaseGroupFromText(candidate)
            if (group.isNotEmpty()) return group
        }
        return ""
    }

    /**
     * Extracts the group as the token after the LAST hyphen of a line once
     * the container extension is stripped, rejecting codec/source/resolution
     * tokens. Known compound names containing '-' or '.' (e.g. D-Z0N3) are
     * matched explicitly first, and leading [Group] prefixes are honoured.
     * Single-character groups are only accepted when terminal on the line.
     */
    internal fun releaseGroupFromText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        COMPOUND_GROUP_REGEX.find(trimmed)?.let { return it.value }
        BRACKET_GROUP_REGEX.find(trimmed)?.let { match ->
            val candidate = match.groupValues[1]
            if (candidate.lowercase() !in NON_GROUP_TOKENS) return candidate
        }
        for (line in trimmed.lineSequence()) {
            val stripped = CONTAINER_EXTENSION_REGEX.replace(line.trim(), "")
            val hyphen = stripped.lastIndexOf('-')
            if (hyphen <= 0 || hyphen >= stripped.length - 1) continue
            val tail = stripped.substring(hyphen + 1).trim()
            var token = LEADING_GROUP_TOKEN_REGEX.find(tail)?.value ?: continue
            token = CONTAINER_EXTENSION_REGEX.replace(token, "")
            if (token.isEmpty()) continue
            val lower = token.lowercase()
            if (lower in NON_GROUP_TOKENS) continue
            if (token.all { it.isDigit() }) continue
            if (CHANNEL_TOKEN_REGEX.matches(token)) continue
            if (token.length == 1 && tail != token) continue
            return token
        }
        return ""
    }

    private fun releaseGroupRank(group: String, preferred: List<String>): Int {
        if (group.isEmpty()) return Int.MAX_VALUE
        val index = preferred.indexOfFirst { it.equals(group, ignoreCase = true) }
        return if (index >= 0) index else Int.MAX_VALUE
    }

    private fun <T> rank(value: T, preferred: List<T>): Int {
        val index = preferred.indexOf(value)
        return if (index >= 0) index else Int.MAX_VALUE
    }

    private fun <T> rankAny(values: List<T>, preferred: List<T>): Int {
        return values.minOfOrNull { rank(it, preferred) } ?: Int.MAX_VALUE
    }

    private fun String.hasResolutionToken(vararg tokens: String): Boolean {
        val pattern = "(^|[^a-z0-9])(${tokens.joinToString("|")})([^a-z0-9]|\$)"
        return tokenRegexCache.getOrPut(pattern) { Regex(pattern) }.containsMatchIn(this)
    }

    private fun String.hasToken(token: String): Boolean {
        val pattern = "(^|[^a-z0-9])${Regex.escape(token.lowercase())}([^a-z0-9]|\$)"
        return tokenRegexCache.getOrPut(pattern) { Regex(pattern) }.containsMatchIn(lowercase())
    }

    private fun String.isDolbyVisionToken(): Boolean {
        val normalized = lowercase().replace(Regex("[^a-z0-9]"), "")
        return normalized == "dv" || normalized == "dovi" || normalized == "dolbyvision"
    }

    private fun String.isHdrToken(): Boolean {
        val normalized = lowercase().replace(Regex("[^a-z0-9+]"), "")
        return normalized == "hdr" ||
            normalized == "hdr10" ||
            normalized == "hdr10+" ||
            normalized == "hdr10plus" ||
            normalized == "hlg"
    }

    private fun streamSize(stream: Stream): Long? =
        StreamTextSizeParser.effectiveSizeBytes(stream)

    private fun streamSearchText(stream: Stream): String {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        val parsed = raw?.parsed
        return listOfNotNull(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints?.filename,
            stream.quality,
            resolve?.torrentName,
            resolve?.filename,
            raw?.torrentName,
            raw?.filename,
            stream.debridCacheStatus?.cachedName,
            parsed?.resolution,
            parsed?.quality,
            parsed?.codec,
            parsed?.hdr?.joinToString(" "),
            parsed?.audio?.joinToString(" ")
        ).joinToString(" ").lowercase()
    }

    private fun cachedRank(stream: Stream): Int {
        if (stream.clientResolve?.isCached == true) return 0
        if (stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED) return 0
        return 1
    }

    private fun sourceName(stream: Stream): String {
        return DebridProviders.instantName(stream.clientResolve?.service)
    }

    private fun Int.gigabytes(): Long = this * 1_000_000_000L

    data class StreamFacts(
        val resolution: DebridStreamResolution,
        val quality: DebridStreamQuality,
        val visualTags: List<DebridStreamVisualTag>,
        val audioTags: List<DebridStreamAudioTag>,
        val audioChannels: List<DebridStreamAudioChannel>,
        val encode: DebridStreamEncode,
        val languages: List<DebridStreamLanguage>,
        val releaseGroup: String,
        val size: Long?,
        val resolutionRank: Int,
        val qualityRank: Int,
        val visualRank: Int,
        val audioRank: Int,
        val channelRank: Int,
        val encodeRank: Int,
        val languageRank: Int,
        val groupRank: Int
    )
}
