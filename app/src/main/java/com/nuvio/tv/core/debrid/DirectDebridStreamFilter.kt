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

    private const val FACTS_CACHE_SIZE = 4096
    private const val TITLE_PATTERN_CACHE_SIZE = 256

    /** Facts that do not depend on preferences, by stream value: ranking the same list again costs a lookup. */
    private val baseFactsCache = object : LinkedHashMap<Stream, BaseFacts>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Stream, BaseFacts>?): Boolean =
            size > FACTS_CACHE_SIZE
    }
    private val titlePatternCache = ConcurrentHashMap<String, TitlePattern>()
    private val RESOLUTION_TOKENS = listOf(
        resolutionRegex("2160p?", "4k", "uhd") to DebridStreamResolution.P2160,
        resolutionRegex("1440p?", "2k") to DebridStreamResolution.P1440,
        resolutionRegex("1080p?", "fhd") to DebridStreamResolution.P1080,
        resolutionRegex("720p?", "hd") to DebridStreamResolution.P720,
        resolutionRegex("576p?") to DebridStreamResolution.P576,
        resolutionRegex("480p?", "sd") to DebridStreamResolution.P480,
        resolutionRegex("360p?") to DebridStreamResolution.P360
    )
    private val DV_TEXT_REGEX = Regex("(^|[^a-z0-9])(dv|dovi|dolby[ ._-]?vision)([^a-z0-9]|$)")
    private val HDR_TEXT_REGEX = Regex("(^|[^a-z0-9])(hdr|hdr10|hdr10plus|hdr10\\+|hlg)([^a-z0-9]|$)")
    private val CONTAINER_EXTENSION_REGEX = Regex("\\.(mkv|mp4|m4v|avi|ts|m2ts|webm|mov)$", RegexOption.IGNORE_CASE)
    private val LEADING_GROUP_TOKEN_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._]{0,23}")
    private val BRACKET_GROUP_REGEX = Regex("^\\[([A-Za-z0-9][A-Za-z0-9._-]{1,23})\\]")
    private val CHANNEL_TOKEN_REGEX = Regex("^\\d\\.\\d$")
    private val NON_ALPHANUMERIC_RUN_REGEX = Regex("[^a-z0-9]+")
    private val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9]")
    private val NON_ALPHANUMERIC_OR_PLUS_REGEX = Regex("[^a-z0-9+]")

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

    // Words release names use for each language besides its code. Short words that are common in
    // titles ("por", "chi", "fra") are left out so a title is not read as a language.
    private val languageAliases = mapOf(
        DebridStreamLanguage.EN to listOf("eng", "english"),
        DebridStreamLanguage.HI to listOf("hin", "hindi"),
        DebridStreamLanguage.IT to listOf("ita", "italian", "italiano"),
        DebridStreamLanguage.ES to listOf("spa", "esp", "spanish", "espanol", "castellano"),
        DebridStreamLanguage.FR to listOf("fre", "french", "truefrench"),
        DebridStreamLanguage.DE to listOf("ger", "deu", "german", "deutsch"),
        DebridStreamLanguage.PT to listOf("portuguese", "portugues"),
        DebridStreamLanguage.PL to listOf("pol", "polish"),
        DebridStreamLanguage.CS to listOf("cze", "czech"),
        DebridStreamLanguage.LA to listOf("latino"),
        DebridStreamLanguage.JA to listOf("jpn", "japanese"),
        DebridStreamLanguage.KO to listOf("kor", "korean"),
        DebridStreamLanguage.ZH to listOf("chinese"),
        DebridStreamLanguage.HE to listOf("heb", "hebrew")
    )
    private val languageMatchers = DebridStreamLanguage.entries.map { language ->
        val tokens = listOf(language.code) + languageAliases[language].orEmpty()
        LanguageMatcher(language, tokens.map { it.lowercase() }, languageRegex(tokens))
    }

    private class LanguageMatcher(
        val language: DebridStreamLanguage,
        val tokens: List<String>,
        val regex: Regex
    ) {
        fun matches(text: String): Boolean = tokens.any { text.contains(it) } && regex.containsMatchIn(text)
    }

    private class TitlePattern(val regex: Regex?)

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

    fun applyPreferences(streams: List<Stream>, settings: DebridSettings): List<Stream> =
        presentWithCopies(streams, settings).shown

    /** Same as [applyPreferences], but keeps each stream's facts so callers don't recompute them. */
    fun applyPreferencesWithFacts(
        streams: List<Stream>,
        settings: DebridSettings
    ): List<Pair<Stream, StreamFacts>> = presentWithCopies(streams, settings).shownWithFacts

    class Presented(
        val shownWithFacts: List<Pair<Stream, StreamFacts>>,
        /** Left out by the resolution rules only: offered as thumbnail copies, never listed or played. */
        val thumbnailCopiesWithFacts: List<Pair<Stream, StreamFacts>>
    ) {
        val shown: List<Stream> get() = shownWithFacts.map { it.first }
        val thumbnailCopies: List<Stream> get() = thumbnailCopiesWithFacts.map { it.first }
    }

    fun presentWithCopies(streams: List<Stream>, settings: DebridSettings): Presented {
        val preferences = effectivePreferences(settings)
        val anyResolution = preferences.copy(excludedResolutions = emptyList(), requiredResolutions = emptyList())
        val (matched, unmatched) = streams.map { it to streamFacts(it, preferences) }
            .partition { (_, facts) -> facts.matchesFilters(preferences) }
        val copies = if (anyResolution == preferences) {
            emptyList()
        } else {
            unmatched.filter { (_, facts) -> facts.matchesFilters(anyResolution) }
        }
        return Presented(
            shownWithFacts = orderAndLimit(matched, preferences),
            thumbnailCopiesWithFacts = orderAndLimit(copies, preferences)
        )
    }

    private fun orderAndLimit(
        matchedStreams: List<Pair<Stream, StreamFacts>>,
        preferences: DebridStreamPreferences
    ): List<Pair<Stream, StreamFacts>> {
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

    private class BaseFacts(
        val resolution: DebridStreamResolution,
        val quality: DebridStreamQuality,
        val visualTags: List<DebridStreamVisualTag>,
        val audioTags: List<DebridStreamAudioTag>,
        val audioChannels: List<DebridStreamAudioChannel>,
        val encode: DebridStreamEncode,
        val languages: List<DebridStreamLanguage>,
        val releaseGroup: String,
        val size: Long?
    )

    private fun streamFacts(stream: Stream, preferences: DebridStreamPreferences): StreamFacts {
        val base = baseFacts(stream)
        return StreamFacts(
            resolution = base.resolution,
            quality = base.quality,
            visualTags = base.visualTags,
            audioTags = base.audioTags,
            audioChannels = base.audioChannels,
            encode = base.encode,
            languages = base.languages,
            releaseGroup = base.releaseGroup,
            size = base.size,
            resolutionRank = rank(base.resolution, preferences.preferredResolutions),
            qualityRank = rank(base.quality, preferences.preferredQualities),
            visualRank = rankAny(base.visualTags, preferences.preferredVisualTags),
            audioRank = rankAny(base.audioTags, preferences.preferredAudioTags),
            channelRank = rankAny(base.audioChannels, preferences.preferredAudioChannels),
            encodeRank = rank(base.encode, preferences.preferredEncodes),
            languageRank = if (base.languages.isEmpty()) Int.MAX_VALUE else base.languages.minOf { rank(it, preferences.preferredLanguages) },
            groupRank = releaseGroupRank(base.releaseGroup, preferences.preferredReleaseGroups)
        )
    }

    private fun baseFacts(stream: Stream): BaseFacts {
        synchronized(baseFactsCache) { baseFactsCache[stream] }?.let { return it }
        val computed = computeBaseFacts(stream)
        synchronized(baseFactsCache) { baseFactsCache[stream] = computed }
        return computed
    }

    private fun computeBaseFacts(stream: Stream): BaseFacts {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val searchText = streamSearchText(stream)
        val languages = parsed?.languages.orEmpty().mapNotNull { languageFor(it) }.ifEmpty {
            val languageText = languageSearchText(stream)
            languageMatchers.filter { it.matches(languageText) }.map { it.language }
        }
        return BaseFacts(
            resolution = streamResolution(parsed?.resolution, parsed?.quality, stream.quality, searchText),
            quality = streamQuality(parsed?.quality, searchText),
            visualTags = streamVisualTags(parsed?.hdr.orEmpty(), searchText),
            audioTags = streamAudioTags(parsed?.audio.orEmpty(), searchText),
            audioChannels = streamAudioChannels(parsed?.channels.orEmpty(), searchText),
            encode = streamEncode(parsed?.codec, searchText),
            languages = languages,
            releaseGroup = releaseGroupOf(stream),
            size = streamSize(stream)
        )
    }

    private fun streamResolution(vararg values: String?): DebridStreamResolution {
        return values.firstNotNullOfOrNull { resolutionValue(it) } ?: DebridStreamResolution.UNKNOWN
    }

    private fun resolutionValue(value: String?): DebridStreamResolution? {
        val normalized = value?.lowercase().orEmpty()
        return RESOLUTION_TOKENS.firstOrNull { (regex, _) -> regex.containsMatchIn(normalized) }?.second
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
        val normalized = value.trim().lowercase()
        return DebridStreamLanguage.entries.firstOrNull {
            normalized == it.code ||
                normalized == it.label.lowercase() ||
                normalized in languageAliases[it].orEmpty()
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

    private fun resolutionRegex(vararg tokens: String): Regex =
        Regex("(^|[^a-z0-9])(${tokens.joinToString("|")})([^a-z0-9]|\$)")

    private fun languageRegex(tokens: List<String>): Regex =
        Regex("(^|[^a-z0-9])(${tokens.joinToString("|") { Regex.escape(it) }})([^a-z0-9]|\$)")

    /** [token] as a whole word: no letter or digit right before or after it. The receiver is lower case. */
    private fun String.hasToken(token: String): Boolean {
        var from = 0
        while (true) {
            val at = indexOf(token, from)
            if (at < 0) return false
            val end = at + token.length
            if ((at == 0 || !this[at - 1].isWordChar()) && (end == length || !this[end].isWordChar())) return true
            from = at + 1
        }
    }

    private fun Char.isWordChar(): Boolean = this in 'a'..'z' || this in '0'..'9'

    private fun String.isDolbyVisionToken(): Boolean {
        val normalized = lowercase().replace(NON_ALPHANUMERIC_REGEX, "")
        return normalized == "dv" || normalized == "dovi" || normalized == "dolbyvision"
    }

    private fun String.isHdrToken(): Boolean {
        val normalized = lowercase().replace(NON_ALPHANUMERIC_OR_PLUS_REGEX, "")
        return normalized == "hdr" ||
            normalized == "hdr10" ||
            normalized == "hdr10+" ||
            normalized == "hdr10plus" ||
            normalized == "hlg"
    }

    private fun streamSize(stream: Stream): Long? =
        StreamTextSizeParser.effectiveSizeBytes(stream)

    private fun streamSearchText(stream: Stream): String {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val parsedTexts = listOfNotNull(
            parsed?.resolution,
            parsed?.quality,
            parsed?.codec,
            parsed?.hdr?.joinToString(" "),
            parsed?.audio?.joinToString(" ")
        )
        return (streamTexts(stream) + parsedTexts).joinToString(" ").lowercase()
    }

    private fun streamTexts(stream: Stream): List<String> {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
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
            stream.debridCacheStatus?.cachedName
        )
    }

    // Removes the media title from each text before languages are matched, so title words
    // ("The French Dispatch", "The Italian Job") are not read as the stream language. Only the
    // first match per text is removed: a release name repeats the title only as a language word.
    private fun languageSearchText(stream: Stream): String {
        val resolve = stream.clientResolve
        val titleRegexes = listOfNotNull(resolve?.stream?.raw?.parsed?.parsedTitle, resolve?.title)
            .mapNotNull { titlePattern(it) }
            .distinctBy { it.pattern }
            .sortedByDescending { it.pattern.length }
        return streamTexts(stream).joinToString(" ") { text ->
            titleRegexes.fold(text.lowercase()) { remaining, regex -> regex.replaceFirst(remaining, " ") }
        }
    }

    private fun titlePattern(title: String): Regex? {
        titlePatternCache[title]?.let { return it.regex }
        if (titlePatternCache.size >= TITLE_PATTERN_CACHE_SIZE) titlePatternCache.clear()
        return TitlePattern(titleRegex(title)).also { titlePatternCache[title] = it }.regex
    }

    private fun titleRegex(title: String): Regex? {
        val words = title.lowercase().split(NON_ALPHANUMERIC_RUN_REGEX).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        return Regex("(^|[^a-z0-9])${words.joinToString("[^a-z0-9]+")}(?=[^a-z0-9]|\$)")
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
