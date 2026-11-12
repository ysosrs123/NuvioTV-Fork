package com.nuvio.tv.core.formatcheck

enum class FormatGroup { VIDEO, HDR, AUDIO }

enum class FormatFamily(val pattern: Regex?) {
    DOLBY_VISION(Regex("(^|[^a-z0-9])(dv|dovi|dolby[ ._-]?vision)([^a-z0-9]|$)")),
    AV1(Regex("(^|[^a-z0-9])av1([^a-z0-9]|$)")),
    VP9(Regex("(^|[^a-z0-9])vp9([^a-z0-9]|$)")),
    HEVC(Regex("(^|[^a-z0-9])(hevc|h[ ._]?265|x265)([^a-z0-9]|$)")),
    HI10P(Regex("(^|[^a-z0-9])(hi10p?|10[ ._-]?bit[ ._-]?(h[ ._]?264|x264|avc))([^a-z0-9]|$)")),
    VC1(Regex("(^|[^a-z0-9])(vc[ ._-]?1|wvc1)([^a-z0-9]|$)")),
    MPEG2(Regex("(^|[^a-z0-9])mpeg[ ._-]?2([^a-z0-9]|$)")),
    XVID(Regex("(^|[^a-z0-9])xvid([^a-z0-9]|$)")),
    DIVX(Regex("(^|[^a-z0-9])divx([^a-z0-9]|$)")),
    AVI(Regex("\\.avi([^a-z0-9]|$)")),
    OTHER(null)
}

enum class AudioRoute { AC3, EAC3, TRUEHD, DTS, DTS_HD }

enum class QuestionKind { PICTURE, SOUND }

data class FormatQuestion(
    val kind: QuestionKind,
    val expected: String,
    val options: List<String>,
    /** Options that say the expected format did not arrive. Any other answer leaves the result as played. */
    val wrong: Set<String>
)

data class FormatClip(
    val id: String,
    val file: String,
    val label: String,
    val group: FormatGroup,
    val families: Set<FormatFamily> = setOf(FormatFamily.OTHER),
    val audioRoute: AudioRoute? = null,
    val question: FormatQuestion? = null,
    /** Set for a format the app decodes itself whenever its software decoders are on. */
    val questionWhenDecoded: FormatQuestion? = null
) {
    val dolbyVision: Boolean
        get() = FormatFamily.DOLBY_VISION in families || label.startsWith("Dolby Vision")

    fun decodedByApp(settings: FormatCheckSettings) = settings.softwareDecoders && questionWhenDecoded != null

    fun questionFor(settings: FormatCheckSettings) = if (decodedByApp(settings)) questionWhenDecoded else question
}

enum class FormatVerdict { PLAYED, PLAYED_SLOW, PLAYED_MPV, CONFIRMED, NOT_AS_EXPECTED, FAILED, NOT_TESTED }

enum class DolbyVisionRoute { NATIVE_FEL, CONVERTED, STRIPPED }

enum class FormatSettingNote { TUNNELING, MAIN_PLAYER_USED, AUDIO_OFF, NO_PASSTHROUGH, FORCE_AC3, DV_MODE, DV5_CONVERT, HDR10_PLUS_STRIPPED, CLOSED_APP_LAST_RUN }

data class FormatCheckSettings(
    val tunneling: Boolean = false,
    val mpvChosen: Boolean = false,
    val forceAc3: Boolean = false,
    val dolbyVisionNotAuto: Boolean = false,
    val dv5Convert: Boolean = false,
    val stripHdr10Plus: Boolean = false,
    val softwareDecoders: Boolean = false,
    /** Formats switched off in manual audio mode. Empty on Automatic. */
    val manualAudioOff: Set<AudioRoute> = emptySet(),
    /** What the box says it can't send on. Empty when it doesn't say. */
    val boxDenies: Set<AudioRoute> = emptySet()
)

/** Which of the box's current settings affect a clip's result. */
object FormatCheckSettingNotes {
    private val PROFILE_7 = "fc02"
    private val PROFILE_5 = "fc01"
    private val HDR10_PLUS = "fc08"

    fun forClip(clip: FormatClip, settings: FormatCheckSettings): List<FormatSettingNote> = buildList {
        if (settings.mpvChosen) add(FormatSettingNote.MAIN_PLAYER_USED)
        if (settings.tunneling) add(FormatSettingNote.TUNNELING)
        clip.audioRoute?.let { route ->
            if (!clip.decodedByApp(settings)) {
                if (route in settings.manualAudioOff) add(FormatSettingNote.AUDIO_OFF)
                else if (route in settings.boxDenies) add(FormatSettingNote.NO_PASSTHROUGH)
            }
            if (settings.forceAc3) add(FormatSettingNote.FORCE_AC3)
        }
        if (clip.id == PROFILE_7 && settings.dolbyVisionNotAuto) add(FormatSettingNote.DV_MODE)
        if (clip.id == PROFILE_5 && settings.dv5Convert) add(FormatSettingNote.DV5_CONVERT)
        if (clip.id == HDR10_PLUS && settings.stripHdr10Plus) add(FormatSettingNote.HDR10_PLUS_STRIPPED)
    }
}

data class ClipObservation(
    val firstFrame: Boolean = false,
    val error: String? = null,
    val decoder: String? = null,
    val droppedFrames: Int = 0,
    val movedToMpv: Boolean = false,
    val passthrough: Boolean? = null,
    val dolbyVisionRoute: DolbyVisionRoute? = null
)

data class ClipResult(
    val clipId: String,
    val verdict: FormatVerdict,
    val decoder: String? = null,
    val droppedFrames: Int = 0,
    val passthrough: Boolean? = null,
    val answer: String? = null,
    val dolbyVisionRoute: DolbyVisionRoute? = null,
    val settingNotes: List<FormatSettingNote> = emptyList()
)

/** What a run shows for one clip. A clip that only fails when retried still counts as played. */
object FormatCheckVerdicts {
    fun skippedAfterAppClosed(clipId: String) = ClipResult(
        clipId = clipId,
        verdict = FormatVerdict.FAILED,
        settingNotes = listOf(FormatSettingNote.CLOSED_APP_LAST_RUN)
    )

    private const val SLOW_DROPPED_FRAMES = 12

    fun of(observation: ClipObservation, audioOnly: Boolean = false, dolbyVision: Boolean = false): FormatVerdict = when {
        !observation.firstFrame -> FormatVerdict.FAILED
        observation.error != null -> FormatVerdict.FAILED
        observation.movedToMpv -> FormatVerdict.PLAYED_MPV
        dolbyVision && observation.dolbyVisionRoute == DolbyVisionRoute.STRIPPED -> FormatVerdict.NOT_AS_EXPECTED
        !audioOnly && observation.droppedFrames >= SLOW_DROPPED_FRAMES -> FormatVerdict.PLAYED_SLOW
        else -> FormatVerdict.PLAYED
    }

    /**
     * Dolby Vision badges and picture modes differ too much between TVs, so for those clips the answer is only shown,
     * never turned into a warning. Only the box's decoder or Arc's route can fail a Dolby Vision clip.
     */
    fun answerIsInfoOnly(question: FormatQuestion) = question.expected == FormatCheckAnswers.DOLBY_VISION

    fun withAnswer(verdict: FormatVerdict, question: FormatQuestion, answer: String?): FormatVerdict = when {
        verdict == FormatVerdict.FAILED || answer == null -> verdict
        answerIsInfoOnly(question) ->
            if (answer == question.expected && verdict == FormatVerdict.PLAYED) FormatVerdict.CONFIRMED else verdict
        answer == question.expected -> FormatVerdict.CONFIRMED
        answer in question.wrong -> FormatVerdict.NOT_AS_EXPECTED
        else -> verdict
    }

    fun isFine(verdict: FormatVerdict) = verdict == FormatVerdict.PLAYED || verdict == FormatVerdict.CONFIRMED

    fun isCatch(verdict: FormatVerdict) = verdict == FormatVerdict.PLAYED_SLOW ||
        verdict == FormatVerdict.PLAYED_MPV || verdict == FormatVerdict.NOT_AS_EXPECTED

    fun canTestAgain(verdict: FormatVerdict) = !isFine(verdict)
}

object FormatCheckRetest {
    /** Clips to test again, in catalogue order. A clip that closed the app last time stays out. */
    fun clipsToRetest(results: List<ClipResult>): Set<String> = results
        .filter { FormatCheckVerdicts.canTestAgain(it.verdict) && FormatSettingNote.CLOSED_APP_LAST_RUN !in it.settingNotes }
        .map { it.clipId }
        .toSet()

    /** New results replace their clip's old one; the rest stay as they were. */
    fun merge(old: List<ClipResult>, new: List<ClipResult>): List<ClipResult> {
        val byId = new.associateBy { it.clipId }
        return old.map { byId[it.clipId] ?: it } + new.filter { n -> old.none { it.clipId == n.clipId } }
    }
}

enum class StreamAction { WARN, HIDE }

sealed interface FormatSuggestion {
    /** The app decodes this audio on this audio connection instead of sending it on. */
    data class AudioDecode(val route: AudioRoute) : FormatSuggestion

    data class Streams(val family: FormatFamily, val action: StreamAction = StreamAction.WARN) : FormatSuggestion
}

/** Only outright failures lead to a suggestion: a clip the box played never changes anything. */
object FormatCheckSuggestions {
    fun from(results: List<ClipResult>, clips: List<FormatClip> = FormatCheckCatalog.clips): List<FormatSuggestion> {
        val byId = results.associateBy { it.clipId }
        fun allFailed(of: List<FormatClip>) = of.isNotEmpty() && of.all { byId[it.id]?.verdict == FormatVerdict.FAILED }

        fun sentOnHdmi(clip: FormatClip) = clip.questionWhenDecoded == null || byId[clip.id]?.passthrough != false

        val audio = AudioRoute.entries
            .filter { route -> allFailed(clips.filter { it.audioRoute == route && sentOnHdmi(it) }) }
            .map { FormatSuggestion.AudioDecode(it) }
        val streams = FormatFamily.entries
            .filter { it.pattern != null && allFailed(clips.filter { clip -> it in clip.families }) }
            .map { FormatSuggestion.Streams(it) }
        return audio + streams
    }
}

/** Formats are read from a stream's text, so a match means "probably this format". */
object FormatCheckStreamRules {
    fun actionFor(streamText: String, rules: Map<FormatFamily, StreamAction>): StreamAction? {
        if (rules.isEmpty()) return null
        val text = streamText.lowercase()
        val hits = rules.filterKeys { it.pattern?.containsMatchIn(text) == true }.values
        return when {
            hits.isEmpty() -> null
            StreamAction.HIDE in hits -> StreamAction.HIDE
            else -> StreamAction.WARN
        }
    }

    fun actionFor(stream: com.nuvio.tv.domain.model.Stream, rules: Map<FormatFamily, StreamAction>): StreamAction? =
        if (rules.isEmpty()) null else actionFor(streamText(stream), rules)

    fun streamText(stream: com.nuvio.tv.domain.model.Stream): String =
        listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename).joinToString(" ")

    /** Hidden formats leave every list the app picks from, so auto play and next episode can't start them either. */
    fun withoutHidden(
        groups: List<com.nuvio.tv.domain.model.AddonStreams>,
        rules: Map<FormatFamily, StreamAction>
    ): List<com.nuvio.tv.domain.model.AddonStreams> {
        if (StreamAction.HIDE !in rules.values) return groups
        return groups.mapNotNull { group ->
            val kept = group.streams.filter { actionFor(it, rules) != StreamAction.HIDE }
            when {
                kept.size == group.streams.size -> group
                kept.isEmpty() && group.thumbnailCopyStreams.isEmpty() -> null
                else -> group.copy(streams = kept)
            }
        }
    }
}
