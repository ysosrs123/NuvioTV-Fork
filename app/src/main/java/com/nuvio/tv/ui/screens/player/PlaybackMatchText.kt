package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.util.languageCodeToName
import java.util.Locale

/**
 * What is playing, written the ways badge packs look for it: release-name spellings (DDP5.1,
 * DV.HDR, H.265, TrueHD.7.1.Atmos) and AIOStreams labels (DD+, HDR+DV, DV Only, DTS-HD MA).
 * Every token is something the player reports; only Atmos, DTS-HD MA and DTS:X on a codec
 * family that can carry them come from the release name, as ExoPlayer cannot tell them apart.
 * Spellings that would read as another badge are left out, like "Full HD" (a 720p "HD" badge).
 */
internal fun playbackMatchText(info: PlayerMediaInfo, languages: List<String>, releaseText: String = ""): String {
    val channels = channelLabel(info.audioChannels)
    return buildList {
        addAll(resolutionTokens(info))
        addAll(dynamicRangeTokens(info))
        (info.bitDepth ?: 10.takeIf { info.hdr.isNotEmpty() })?.takeIf { it > 8 }?.let { addAll(listOf("${it}bit", "$it-bit")) }
        addAll(videoCodecTokens(info.videoCodec))
        audioFacts(info.audioCodec, info.audioProfile, releaseText.lowercase())?.let { addAll(audioTokens(it, channels)) }
        channels?.let { addAll(channelTokens(info.audioChannels, it)) }
        addAll(languages)
    }.distinct().joinToString(" ")
}

/**
 * What a badge pack is matched against on the player: the file name the stats HUD shows comes first,
 * then what is playing. File name first because AIOStreams-style packs gate rules on a video
 * extension followed by more text (e.g. `\.mkv\s+\S`), and with the extension last those rules
 * (5.1, 7.1) never match. Words in the file name that the player contradicts are taken out first,
 * so a Dolby Vision file playing as HDR10 still reads HDR only and a switch to another audio track
 * drops the file name's audio.
 */
internal fun playerBadgeMatchText(
    info: PlayerMediaInfo,
    languages: List<String>,
    releaseText: String,
    fileName: String,
    audioFromRelease: Boolean
): String {
    val playing = playbackMatchText(info, languages, releaseText.takeIf { audioFromRelease }.orEmpty())
    val file = playbackFileName(fileName, info, releaseText, audioFromRelease)
    return "$file $playing".trim()
}

/**
 * [fileName] with the words the player contradicts blanked out: picture format, resolution,
 * video codec, audio format, Atmos and channels. A word stays while the player cannot tell yet.
 */
internal fun playbackFileName(
    fileName: String,
    info: PlayerMediaInfo,
    releaseText: String = fileName,
    audioFromRelease: Boolean = true
): String {
    if (fileName.isBlank()) return ""
    val dv = "DV" in info.hdr || !info.hdrKnown
    val hdr = "HDR10" in info.hdr || "HLG" in info.hdr || !info.hdrKnown
    val sdr = info.hdr.isEmpty() || !info.hdrKnown
    val resolutions = resolutionTokens(info).map(::resolutionKey).toSet()
    val videoFamily = info.videoCodec?.let(::videoFamily)
    val audioKey = info.audioCodec?.let(::codecKey)
    val playingAudio = audioKey?.let(::audioWordFamily)
    val atmos = audioFacts(info.audioCodec, info.audioProfile, releaseText.lowercase())?.atmos
    val channels = channelLabel(info.audioChannels)
    val dtsCore = audioKey == "DTS" && info.audioProfile.orEmpty().uppercase().let { "HD" !in it && "MA" !in it && ":X" !in it }
    fun audioWordFits(word: String): Boolean {
        if (!audioFromRelease) return false
        if (playingAudio == null) return true
        val family = audioWordFamily(word.uppercase().filter { it.isLetterOrDigit() || it == '+' })
        if (family != playingAudio) return false
        // DTS-HD MA or DTS:X in the name, but the player reports the DTS core.
        return family != "DTS" || !dtsCore || word.filter { it.isLetter() }.equals("dts", ignoreCase = true)
    }
    return fileName.replace('\n', ' ')
        .replace(FILE_DV) { if (dv) it.value else " " }
        .replace(FILE_HDR) { if (hdr) it.value else " " }
        .replace(FILE_SDR) { if (sdr) it.value else " " }
        .replace(FILE_RESOLUTION) { if (info.videoWidth == null || resolutionKey(it.value) in resolutions) it.value else " " }
        .replace(FILE_VIDEO_CODEC) { if (videoFamily == null || videoFamily(it.value) == videoFamily) it.value else " " }
        .replace(FILE_AUDIO_CODEC) { if (audioWordFits(it.value)) it.value else " " }
        .replace(FILE_ATMOS) { if (audioFromRelease && (playingAudio == null || atmos == true)) it.value else " " }
        .replace(FILE_CHANNELS) {
            val label = "${it.groupValues[1]}.${it.groupValues[2]}"
            if (audioFromRelease && (channels == null || label == channels)) it.value else " "
        }
}

private fun resolutionKey(word: String): String = when (val key = word.lowercase().filter { it.isLetterOrDigit() }) {
    "fullhd" -> "fhd"
    "ultrahd" -> "uhd"
    else -> key
}

private fun videoFamily(codec: String): String = when (val key = codecKey(codec)) {
    "HEVC", "H265", "X265", "DOLBYVISION" -> "HEVC"
    "AVC", "H264", "X264" -> "AVC"
    "VC1" -> "VC1"
    "MPEG2", "MPEG2VIDEO" -> "MPEG2"
    "MPEG4", "XVID", "DIVX" -> "MPEG4"
    else -> key
}

/** [key] upper case letters and digits, with the + of DD+ kept. */
private fun audioWordFamily(key: String): String = when {
    key.startsWith("DDP") || key.startsWith("DD+") || key.startsWith("EAC3") || key == "DOLBYDIGITALPLUS" -> "EAC3"
    key == "DD" || key == "AC3" || key == "DOLBYDIGITAL" -> "AC3"
    key.startsWith("TRUEHD") -> "TRUEHD"
    key.startsWith("DTS") -> "DTS"
    key.startsWith("AAC") -> "AAC"
    key.startsWith("PCM") || key == "LPCM" -> "PCM"
    else -> key
}

/**
 * The selected audio language as packs spell it: the track's code, the upper-case ISO 639-1 code
 * AIOStreams prints (EN, PT-BR), both ISO 639-2 codes, the name, and AIOStreams' flag for it.
 */
internal fun playbackLanguageTokens(code: String?): List<String> {
    val trimmed = code?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() !in UNKNOWN_LANGUAGES } ?: return emptyList()
    val locale = Locale.forLanguageTag(trimmed.replace('_', '-'))
    val iso1 = locale.language.lowercase().let { if (it.length == 2) it else ISO_639_1_BY_ISO_639_2[it] }
    val region = locale.country.uppercase().takeIf { it.length == 2 }
    val iso3 = runCatching { locale.isO3Language }.getOrNull()?.takeIf { it.isNotBlank() }
    return listOfNotNull(
        trimmed,
        iso1?.uppercase(),
        if (iso1 != null && region != null) "${iso1.uppercase()}-$region" else null,
        iso3,
        iso3?.let(ISO_639_2_BIBLIOGRAPHIC::get),
        locale.getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() && !it.equals(trimmed, ignoreCase = true) },
        languageCodeToName(trimmed),
        iso1?.let { languageFlag(it, region) }
    ).distinct()
}

/** AIOStreams' flag for a language (its languageEmojis), so packs that match the flag a formatter prints still hit. */
private fun languageFlag(iso1: String, region: String?): String? =
    if (iso1 == "pt" && region == "BR") "🇧🇷" else LANGUAGE_FLAGS[iso1]

/**
 * The audio track that is playing: the one the player reports by id, else the one selected. Right
 * after a switch the selection has moved but the player still plays the old track for a moment.
 */
internal fun playingAudioTrack(tracks: List<TrackInfo>, info: PlayerMediaInfo): TrackInfo? =
    info.audioTrackId?.let { id -> tracks.firstOrNull { it.trackId == id } } ?: tracks.firstOrNull { it.isSelected }

/**
 * ExoPlayer reports no audio format for a moment while it switches tracks, and mpv no channel
 * count while it reopens the decoder; keep the last rather than drop those badges and bring them
 * back. Not when audio is off: then it is gone.
 */
internal fun PlayerMediaInfo.keepingAudioOf(previous: PlayerMediaInfo, audioSelected: Boolean): PlayerMediaInfo = when {
    !audioSelected -> this
    audioCodec == null && previous.audioCodec != null -> copy(
        audioCodec = previous.audioCodec,
        audioProfile = previous.audioProfile,
        audioChannels = previous.audioChannels,
        audioTrackId = previous.audioTrackId,
        audioLanguage = previous.audioLanguage
    )
    audioChannels == null && audioTrackId == previous.audioTrackId -> copy(audioChannels = previous.audioChannels)
    else -> this
}

/**
 * Whether the release name speaks for [track]: it names the main audio (TrueHD.7.1.Atmos), taken
 * as the first track of that codec family that is not commentary or audio description. So a second
 * E-AC-3 track, like a 2.0 commentary, does not take the Atmos of the first.
 */
internal fun releaseDescribesTrack(tracks: List<TrackInfo>, track: TrackInfo?): Boolean {
    if (track == null) return true
    if (track.role in SECONDARY_AUDIO_ROLES) return false
    val family = audioFamily(track.codec)
    return tracks.firstOrNull { it.role !in SECONDARY_AUDIO_ROLES && audioFamily(it.codec) == family }?.index == track.index
}

/** E-AC-3 and E-AC-3-JOC, or dts and DTS-HD, are one family; null when the track names no codec. */
private fun audioFamily(codec: String?): String? {
    val key = codec?.let(::codecKey)?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        key.startsWith("EAC3") -> "EAC3"
        key.startsWith("DTS") -> "DTS"
        else -> key
    }
}

private const val WORD_START = "(?<![A-Za-z0-9])"
private const val WORD_END = "(?![A-Za-z0-9])"
private val FILE_DV = Regex("""$WORD_START(?:dolby[ ._-]?vision|dovi|dv)$WORD_END""", RegexOption.IGNORE_CASE)
private val FILE_HDR = Regex("""$WORD_START(?:hdr[ ._-]?10(?:\+|[ ._-]?plus|p)?|hdr|hlg)(?![A-Za-z0-9+])""", RegexOption.IGNORE_CASE)
private val FILE_SDR = Regex("""${WORD_START}sdr$WORD_END""", RegexOption.IGNORE_CASE)
private val FILE_RESOLUTION = Regex(
    """$WORD_START(?:\d{3,4}[pi]|[248]k|uhd|qhd|fhd|full[ ._-]?hd|ultra[ ._-]?hd)$WORD_END""",
    RegexOption.IGNORE_CASE
)
private val FILE_VIDEO_CODEC = Regex(
    """$WORD_START(?:hevc|[xh][ .]?265|avc|[xh][ .]?264|av1|vp9|vc-?1|mpeg-?2|xvid|divx)$WORD_END""",
    RegexOption.IGNORE_CASE
)
/** Digits may follow, as in DDP5.1 or AAC2.0. */
private val FILE_AUDIO_CODEC = Regex(
    """$WORD_START(?:ddpa?|dd\+|e-?ac-?3|dd|ac-?3|dolby[ ._-]?digital(?:[ ._-]?plus)?|true[ ._-]?hd|""" +
        """dts(?:[ ._:-]?(?:hd|ma|x|es|hra?|xll|express))*|aac|flac|opus|l?pcm)(?![A-Za-z+])""",
    RegexOption.IGNORE_CASE
)
private val FILE_ATMOS = Regex("""(?:(?<![A-Za-z0-9])|(?<=ddp|truehd))atmos$WORD_END""", RegexOption.IGNORE_CASE)
private val FILE_CHANNELS = Regex("""(?<!\d)(?<!\d[.])([1-8])[ ._]([01])(?![0-9])""")

private val SECONDARY_AUDIO_ROLES = setOf(TrackRole.COMMENTARY, TrackRole.AUDIO_DESCRIPTION)

private fun resolutionTokens(info: PlayerMediaInfo): List<String> {
    val width = info.videoWidth ?: return emptyList()
    val height = info.videoHeight ?: return emptyList()
    // Width or height, so a scope crop (1920×800) or a 4:3 frame still reads as its class.
    return when {
        width >= 3200 || height >= 2000 -> listOf("2160p", "4K", "UHD")
        width >= 2400 || height >= 1350 -> listOf("1440p", "2K", "QHD")
        width >= 1800 || height >= 1000 -> listOf("1080p", "FHD")
        width >= 1200 || height >= 700 -> listOf("720p")
        height >= 500 || width >= 900 -> listOf("576p", "SD")
        height >= 400 || width >= 600 -> listOf("480p", "SD")
        height >= 300 -> listOf("360p", "SD")
        height >= 200 -> listOf("240p", "SD")
        else -> listOf("144p", "SD")
    }
}

/** Only what is being shown: a DV file the device plays as HDR10 reads HDR, not DV. */
private fun dynamicRangeTokens(info: PlayerMediaInfo): List<String> {
    val dv = "DV" in info.hdr
    val hdr10 = "HDR10" in info.hdr
    val hlg = "HLG" in info.hdr
    return buildList {
        // A space-separated "Vision" reads as the release group ViSiON in packs' media-source tier
        // lists; packs match dolby[-_. ]?vision, so DolbyVision still reads as Dolby Vision.
        if (dv) addAll(listOf("DV", "DoVi", "DolbyVision"))
        if (hdr10) addAll(listOf("HDR", "HDR10"))
        if (hlg) addAll(listOf("HDR", "HLG"))
        // Before mpv decodes a frame it knows DV from the file but not yet HDR10 under it, so
        // nothing that says what is missing (DV Only, SDR) until the transfer is known.
        when {
            dv && (hdr10 || hlg) -> addAll(listOf("HDR+DV", "DV HDR", "DV.HDR", "HDR.DV", "DV HDR10", "HDR10 DV", "DoVi HDR"))
            !info.hdrKnown -> Unit
            dv -> add("DV Only")
            hdr10 || hlg -> add("HDR Only")
            // The stats HUD reads SDR for the same frame.
            info.videoWidth != null -> add("SDR")
        }
    }
}

private fun videoCodecTokens(codec: String?): List<String> {
    if (codec.isNullOrBlank()) return emptyList()
    return when (codecKey(codec)) {
        "HEVC", "H265", "DOLBYVISION" -> listOf("HEVC", "H.265", "H265", "x265")
        "AVC", "H264" -> listOf("AVC", "H.264", "H264", "x264")
        "AV1" -> listOf("AV1")
        "VP9" -> listOf("VP9")
        "VP8" -> listOf("VP8")
        "VC1" -> listOf("VC-1", "VC1")
        "MPEG2", "MPEG2VIDEO" -> listOf("MPEG-2", "MPEG2")
        "MPEG4" -> listOf("MPEG-4", "MPEG4")
        else -> listOf(codec)
    }
}

/** [labels] stand alone; [forms] are the release-name prefixes that join the channels, like DDP in DDP5.1. */
private class AudioFacts(val labels: List<String>, val forms: List<String>, val atmos: Boolean)

/** mpv names codecs as ffmpeg does (pcm_s24le, eac3), ExoPlayer as [CustomDefaultTrackNameProvider] does (E-AC-3). */
private fun audioFacts(codec: String?, profile: String?, release: String): AudioFacts? {
    if (codec.isNullOrBlank()) return null
    val label = codec.uppercase()
    val key = codecKey(codec)
    val profileLabel = profile.orEmpty().uppercase()
    val atmos = key.endsWith("JOC") || profileLabel.contains("ATMOS") || releaseSaysAtmos(label, release)
    return when {
        key.startsWith("EAC3") -> AudioFacts(listOf("DD+", "DDP", "EAC3", "E-AC-3"), listOf("DDP", "DD+", "EAC3"), atmos)
        key == "AC3" -> AudioFacts(listOf("DD", "AC3", "AC-3", "Dolby Digital"), listOf("DD", "AC3"), atmos)
        key == "TRUEHD" -> AudioFacts(listOf("TrueHD", "Dolby TrueHD"), listOf("TrueHD"), atmos)
        key.startsWith("DTS") -> dtsFacts(label, key, profileLabel, release)
        key.startsWith("AAC") -> AudioFacts(listOf("AAC"), listOf("AAC"), atmos)
        key == "FLAC" -> AudioFacts(listOf("FLAC"), listOf("FLAC"), atmos)
        key == "OPUS" -> AudioFacts(listOf("Opus", "OPUS"), listOf("Opus"), atmos)
        key.startsWith("PCM") || key == "LPCM" -> AudioFacts(listOf("PCM", "LPCM"), listOf("LPCM", "PCM"), atmos)
        key == "AC4" -> AudioFacts(listOf("AC-4", "AC4"), listOf("AC4"), atmos)
        key == "MPEGH" -> AudioFacts(listOf("MPEG-H"), emptyList(), atmos)
        key == "MP3" || key == "MP2" || key == "VORBIS" || key == "ALAC" -> AudioFacts(listOf(codec), listOf(codec), atmos)
        else -> AudioFacts(listOf(codec), emptyList(), atmos)
    }
}

private fun dtsFacts(label: String, key: String, profileLabel: String, release: String): AudioFacts {
    val x = key == "DTSX" || profileLabel.contains("DTS:X") || releaseSaysDtsX(label, release)
    val ma = profileLabel.contains("MA") || releaseSaysDtsHdMa(label, release)
    val hd = ma || label.contains("HD") || profileLabel.contains("DTS-HD")
    val labels = buildList {
        add("DTS")
        if (hd) addAll(listOf("DTS-HD", "DTSHD"))
        if (ma) addAll(listOf("DTS-HD MA", "DTS-HD.MA", "DTS-HDMA", "DTS-MA"))
        if (x) addAll(listOf("DTS:X", "DTS-X", "DTSX"))
        if (profileLabel.contains("DTS-ES")) add("DTS-ES")
        if (label.contains("EXPRESS") || profileLabel.contains("EXPRESS")) add("DTS Express")
    }
    val forms = buildList {
        if (x) add("DTS-X")
        if (ma) add("DTS-HD.MA")
        if (hd) add("DTS-HD")
        add("DTS")
    }
    return AudioFacts(labels, forms, atmos = false)
}

private fun audioTokens(audio: AudioFacts, channels: String?): List<String> = buildList {
    addAll(audio.labels)
    if (audio.atmos) addAll(listOf("Atmos", "Dolby Atmos"))
    audio.forms.forEach { form ->
        // DDP5.1 but not EAC35.1, which reads as another number.
        val joined = if (form.last().isDigit()) emptyList() else listOfNotNull(channels?.let { "$form$it" })
        channels?.let { addAll(joined + listOf("$form.$it", "$form $it")) }
        if (audio.atmos) {
            addAll(listOf("$form.Atmos", "$form Atmos"))
            channels?.let { addAll(joined.map { j -> "$j.Atmos" } + listOf("$form.$it.Atmos", "$form $it Atmos")) }
        }
    }
    // DDPA5.1: the release-name shorthand for DD+ with Atmos.
    if (audio.atmos && "DDP" in audio.forms) {
        add("DDPA")
        channels?.let { add("DDPA$it") }
    }
}

private fun channelLabel(channels: Int?): String? = when (channels) {
    1 -> "1.0"
    2 -> "2.0"
    3 -> "2.1"
    5 -> "5.0"
    6 -> "5.1"
    7 -> "6.1"
    8 -> "7.1"
    else -> null
}

private fun channelTokens(channels: Int?, label: String): List<String> = when (channels) {
    1 -> listOf(label, "Mono")
    2 -> listOf(label, "Stereo")
    else -> listOf(label)
}

/** Upper case letters and digits only, so E-AC-3, eac3 and E-AC-3-JOC compare alike. */
private fun codecKey(codec: String): String = codec.uppercase().filter { it.isLetterOrDigit() }

private val UNKNOWN_LANGUAGES = setOf("und", "unknown", "unk", "none", "zxx", "mis", "mul")

/** ISO 639-2/B codes that differ from the /T codes [Locale.getISO3Language] gives; release names use both. */
private val ISO_639_2_BIBLIOGRAPHIC = mapOf(
    "fra" to "fre", "deu" to "ger", "zho" to "chi", "ces" to "cze", "nld" to "dut",
    "ell" to "gre", "fas" to "per", "ron" to "rum", "slk" to "slo", "sqi" to "alb",
    "hye" to "arm", "eus" to "baq", "kat" to "geo", "isl" to "ice", "mkd" to "mac",
    "msa" to "may", "mya" to "bur", "bod" to "tib", "cym" to "wel"
)

/** ISO 639-2 (both /T and /B) to ISO 639-1, as MKV tracks often carry eng or fre. */
private val ISO_639_1_BY_ISO_639_2: Map<String, String> by lazy {
    val byTerminology = Locale.getISOLanguages().mapNotNull { iso1 ->
        runCatching { Locale.forLanguageTag(iso1).isO3Language }.getOrNull()?.takeIf { it.isNotBlank() }?.let { it to iso1 }
    }.toMap()
    byTerminology + ISO_639_2_BIBLIOGRAPHIC.mapNotNull { (t, b) -> byTerminology[t]?.let { b to it } }
}

/** From AIOStreams' languageEmojiMap, keyed by ISO 639-1; iw and in are the old codes some Android builds still give. */
private val LANGUAGE_FLAGS = mapOf(
    "en" to "🇬🇧", "ja" to "🇯🇵", "zh" to "🇨🇳", "ru" to "🇷🇺", "ar" to "🇸🇦", "pt" to "🇵🇹",
    "es" to "🇪🇸", "fr" to "🇫🇷", "de" to "🇩🇪", "it" to "🇮🇹", "ko" to "🇰🇷", "hi" to "🇮🇳",
    "bn" to "🇧🇩", "pa" to "🇵🇰", "mr" to "🇮🇳", "gu" to "🇮🇳", "ta" to "🇮🇳", "te" to "🇮🇳",
    "kn" to "🇮🇳", "ml" to "🇮🇳", "th" to "🇹🇭", "vi" to "🇻🇳", "id" to "🇮🇩", "in" to "🇮🇩",
    "tr" to "🇹🇷", "he" to "🇮🇱", "iw" to "🇮🇱", "fa" to "🇮🇷", "uk" to "🇺🇦", "el" to "🇬🇷",
    "lt" to "🇱🇹", "lv" to "🇱🇻", "et" to "🇪🇪", "pl" to "🇵🇱", "cs" to "🇨🇿", "sk" to "🇸🇰",
    "hu" to "🇭🇺", "ro" to "🇷🇴", "bg" to "🇧🇬", "sr" to "🇷🇸", "hr" to "🇭🇷", "sl" to "🇸🇮",
    "nl" to "🇳🇱", "da" to "🇩🇰", "fi" to "🇫🇮", "sv" to "🇸🇪", "no" to "🇳🇴", "nb" to "🇳🇴",
    "nn" to "🇳🇴", "ms" to "🇲🇾"
)
