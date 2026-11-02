package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.util.languageCodeToName
import java.util.Locale

/**
 * What is playing, written the ways badge packs look for it: release-name spellings (DDP5.1,
 * H.265, TrueHD.7.1.Atmos) and AIOStreams labels (DV Only, DTS-HD MA). Every token is something
 * the player reports; only Atmos, DTS-HD MA, DTS:X and HDR10+ come from the release name, as
 * ExoPlayer cannot tell them apart. One format per family, and spellings that would read as
 * another badge are left out: "Full HD" (a 720p "HD" badge), HDR beside Dolby Vision, and
 * DD+, EAC3 or E-AC-3, which simple Dolby Digital rules (dd, ac-?3) also match.
 */
internal fun playbackMatchText(
    info: PlayerMediaInfo,
    languages: List<String>,
    releaseText: String = "",
    hdr10Plus: Boolean = false
): String {
    val channels = channelLabel(info.audioChannels)
    return buildList {
        addAll(resolutionTokens(info))
        addAll(dynamicRangeTokens(info, hdr10Plus))
        playingBitDepth(info)?.takeIf { it > 8 }?.let { addAll(listOf("${it}bit", "$it-bit")) }
        addAll(videoCodecTokens(info.videoCodec))
        audioFacts(info.audioCodec, info.audioProfiles, releaseText.lowercase())?.let { addAll(audioTokens(it, channels)) }
        channels?.let { addAll(channelTokens(info.audioChannels, it)) }
        addAll(languages)
    }.distinct().joinToString(" ")
}

/**
 * What a badge pack is matched against on the player: the file name the stats HUD shows comes first,
 * then what is playing. File name first because AIOStreams-style packs gate rules on a video
 * extension followed by more text (e.g. `\.mkv\s+\S`), and with the extension last those rules
 * (5.1, 7.1) never match. The file name's words for what the player knows are taken out, so each
 * of those families reads only as it plays: a Dolby Vision file playing as HDR10 reads HDR10, and
 * a switch to another audio track drops the file name's audio.
 */
internal fun playerBadgeMatchText(
    info: PlayerMediaInfo,
    languages: List<String>,
    releaseText: String,
    fileName: String,
    audioFromRelease: Boolean
): String {
    val playing = playerBadgePlayingText(info, languages, releaseText, fileName, audioFromRelease)
    val file = playbackFileName(fileName, info, audioFromRelease)
    return "$file $playing".trim()
}

/**
 * What is playing without the file name, matched first: a badge group it hits (a language, a
 * resolution) takes nothing more from the file name. HDR10+ comes from the name, as no player reports it.
 */
internal fun playerBadgePlayingText(
    info: PlayerMediaInfo,
    languages: List<String>,
    releaseText: String,
    fileName: String,
    audioFromRelease: Boolean
): String {
    val hdr10Plus = FILE_HDR10_PLUS.containsMatchIn(releaseText) || FILE_HDR10_PLUS.containsMatchIn(fileName)
    return playbackMatchText(info, languages, releaseText.takeIf { audioFromRelease }.orEmpty(), hdr10Plus)
}

/**
 * [fileName] without its words for the families the player knows: picture format, another
 * resolution, video codec, bit depth, audio format, Atmos and channels. A family the player cannot tell yet
 * keeps the file name's words; source, edition and release group always stay.
 */
internal fun playbackFileName(
    fileName: String,
    info: PlayerMediaInfo,
    audioFromRelease: Boolean = true
): String {
    if (fileName.isBlank()) return ""
    val audioKnown = !audioFromRelease || info.audioCodec != null
    fun Regex.dropIf(known: Boolean, text: String) = if (known) replace(text, " ") else text
    var text = fileName.replace('\n', ' ')
    text = FILE_DV.dropIf(info.hdrKnown, text)
    text = FILE_HDR.dropIf(info.hdrKnown, text)
    text = FILE_SDR.dropIf(info.hdrKnown, text)
    // A word for the class that plays stays: packs' rules for names with no file extension want
    // the resolution twice.
    val resolutions = resolutionTokens(info).map(::resolutionKey).toSet()
    text = FILE_RESOLUTION.replace(text) { if (resolutions.isEmpty() || resolutionKey(it.value) in resolutions) it.value else " " }
    text = FILE_VIDEO_CODEC.dropIf(info.videoCodec != null, text)
    text = FILE_BIT_DEPTH.dropIf(playingBitDepth(info) != null, text)
    text = FILE_AUDIO_CODEC.dropIf(audioKnown, text)
    text = FILE_ATMOS.dropIf(audioKnown, text)
    text = FILE_CHANNELS.dropIf(!audioFromRelease || channelLabel(info.audioChannels) != null, text)
    return text
}

private fun resolutionKey(word: String): String = when (val key = word.lowercase().filter { it.isLetterOrDigit() }) {
    "fullhd" -> "fhd"
    "ultrahd" -> "uhd"
    else -> key
}

/** HDR is at least 10-bit, for containers that leave the depth out. */
private fun playingBitDepth(info: PlayerMediaInfo): Int? = info.bitDepth ?: 10.takeIf { info.hdr.isNotEmpty() }

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
        audioProfiles = previous.audioProfiles,
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
private val FILE_HDR = Regex("""$WORD_START(?:hdr(?:[ ._-]?10(?:\+|[ ._-]?plus|p)?)?|hlg)$WORD_END""", RegexOption.IGNORE_CASE)
private val FILE_HDR10_PLUS = Regex("""$WORD_START(?:hdr[ ._-]?10(?:\+|[ ._-]?plus|p))(?![A-Za-z0-9+])""", RegexOption.IGNORE_CASE)
private val FILE_SDR = Regex("""${WORD_START}sdr$WORD_END""", RegexOption.IGNORE_CASE)
private val FILE_RESOLUTION = Regex(
    """$WORD_START(?:\d{3,4}[pi]|[248]k|uhd|qhd|fhd|full[ ._-]?hd|ultra[ ._-]?hd)$WORD_END""",
    RegexOption.IGNORE_CASE
)
private val FILE_VIDEO_CODEC = Regex(
    """$WORD_START(?:hevc|[xh][ .]?265|avc|[xh][ .]?264|av1|vp9|vc-?1|mpeg-?2|xvid|divx)$WORD_END""",
    RegexOption.IGNORE_CASE
)
private val FILE_BIT_DEPTH = Regex("""$WORD_START(?:(?:8|10|12)[ ._-]?bits?|hi10p?|main[ ._-]?10)$WORD_END""", RegexOption.IGNORE_CASE)
/** Digits may follow, as in DDP5.1 or AAC2.0; Atmos may follow straight on, as in DDPAtmos. */
private val FILE_AUDIO_CODEC = Regex(
    """$WORD_START(?:(?:ddp|true[ ._-]?hd)(?=atmos)|ddpa?|dd\+|e-?ac-?3|dd|ac-?3|dolby[ ._-]?digital(?:[ ._-]?plus)?|true[ ._-]?hd|""" +
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

/**
 * Only the format being shown, spelt one way: Dolby Vision as DV with no HDR beside it, and a DV
 * file the device plays as HDR10 as HDR10. HDR10+ is read from the release name over HDR10.
 */
private fun dynamicRangeTokens(info: PlayerMediaInfo, hdr10Plus: Boolean): List<String> {
    val dv = "DV" in info.hdr
    val hdr10 = "HDR10" in info.hdr
    val hlg = "HLG" in info.hdr
    return when {
        // A space-separated "Vision" reads as the release group ViSiON in packs' media-source tier
        // lists; packs match dolby[-_. ]?vision, so DolbyVision still reads as Dolby Vision.
        // Before mpv decodes a frame it knows DV from the file but not yet what is under it.
        dv -> listOf("DV", "DoVi", "DolbyVision") + listOfNotNull("DV Only".takeIf { info.hdrKnown && !hdr10 && !hlg })
        hdr10 && hdr10Plus -> listOf("HDR10+", "HDR10Plus")
        hdr10 -> listOf("HDR10")
        hlg -> listOf("HLG")
        !info.hdrKnown -> emptyList()
        // The stats HUD reads SDR for the same frame.
        info.videoWidth != null -> listOf("SDR")
        else -> emptyList()
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
private fun audioFacts(codec: String?, profiles: List<String>, release: String): AudioFacts? {
    if (codec.isNullOrBlank()) return null
    val label = codec.uppercase()
    val key = codecKey(codec)
    val profileLabel = profiles.joinToString(" ").uppercase()
    val atmos = key.endsWith("JOC") || profileLabel.contains("ATMOS") || releaseSaysAtmos(label, release)
    return when {
        key.startsWith("EAC3") -> AudioFacts(listOf("DDP"), listOf("DDP"), atmos)
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
    val ma = x || profileLabel.contains("MA") || releaseSaysDtsHdMa(label, release)
    val hd = ma || label.contains("HD") || profileLabel.contains("DTS-HD")
    val es = profileLabel.contains("DTS-ES")
    val express = label.contains("EXPRESS") || profileLabel.contains("EXPRESS")
    // Only the most exact name: a pack's DTS, DTS-HD and DTS-HD MA rules each match their own word, one badge each.
    val labels = buildList {
        if (x) addAll(listOf("DTS:X", "DTS-X", "DTSX"))
        when {
            ma -> addAll(listOf("DTS-HD MA", "DTS-HD.MA", "DTS-HDMA", "DTS-MA"))
            hd -> addAll(listOf("DTS-HD", "DTSHD"))
            es -> add("DTS-ES")
            express -> add("DTS Express")
            else -> add("DTS")
        }
    }
    val forms = buildList {
        if (x) add("DTS-X")
        add(when {
            ma -> "DTS-HD.MA"
            hd -> "DTS-HD"
            else -> "DTS"
        })
    }
    return AudioFacts(labels, forms, atmos = false)
}

private fun audioTokens(audio: AudioFacts, channels: String?): List<String> = buildList {
    addAll(audio.labels)
    if (audio.atmos) add("Atmos")
    audio.forms.forEach { form ->
        // DD5.1 but not AC35.1, which reads as another number.
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
