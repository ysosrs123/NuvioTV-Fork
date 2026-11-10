package com.nuvio.tv.core.formatcheck

import com.nuvio.tv.core.formatcheck.FormatFamily.AV1
import com.nuvio.tv.core.formatcheck.FormatFamily.AVI
import com.nuvio.tv.core.formatcheck.FormatFamily.DIVX
import com.nuvio.tv.core.formatcheck.FormatFamily.DOLBY_VISION
import com.nuvio.tv.core.formatcheck.FormatFamily.HEVC
import com.nuvio.tv.core.formatcheck.FormatFamily.HI10P
import com.nuvio.tv.core.formatcheck.FormatFamily.MPEG2
import com.nuvio.tv.core.formatcheck.FormatFamily.VC1
import com.nuvio.tv.core.formatcheck.FormatFamily.VP9
import com.nuvio.tv.core.formatcheck.FormatFamily.XVID
import com.nuvio.tv.core.formatcheck.FormatGroup.AUDIO
import com.nuvio.tv.core.formatcheck.FormatGroup.HDR
import com.nuvio.tv.core.formatcheck.FormatGroup.VIDEO

object FormatCheckAnswers {
    const val DOLBY_VISION = "dolby_vision"
    const val HDR10 = "hdr10"
    const val HDR10_PLUS = "hdr10_plus"
    const val HLG = "hlg"
    const val NOTHING_SPECIAL = "nothing_special"
    const val PICTURE_WRONG = "picture_wrong"
    const val CANT_TELL = "cant_tell"

    const val ATMOS = "atmos"
    const val TRUEHD = "truehd"
    const val EAC3 = "eac3"
    const val DTS_X = "dts_x"
    const val DTS_HD = "dts_hd"
    const val DTS_OTHER = "dts_other"
    const val PCM = "pcm"
    const val SOMETHING_ELSE = "something_else"
    const val NO_PLAY = "no_play"
    const val NO_DISPLAY = "no_display"

    /** Not an answer: plays the clip again from the start and asks once more. */
    const val SHOW_AGAIN = "show_again"
}

object FormatCheckCatalog {
    private fun picture(expected: String): FormatQuestion {
        val options = listOf(
            FormatCheckAnswers.DOLBY_VISION,
            FormatCheckAnswers.HDR10_PLUS,
            FormatCheckAnswers.HDR10,
            FormatCheckAnswers.HLG,
            FormatCheckAnswers.NOTHING_SPECIAL,
            FormatCheckAnswers.PICTURE_WRONG,
            FormatCheckAnswers.CANT_TELL
        )
        return FormatQuestion(
            kind = QuestionKind.PICTURE,
            expected = expected,
            options = options,
            wrong = (options - expected - FormatCheckAnswers.CANT_TELL).toSet()
        )
    }

    private fun sound(expected: String, vararg alternatives: String): FormatQuestion {
        val options = (listOf(expected) + alternatives + listOf(
            FormatCheckAnswers.PCM,
            FormatCheckAnswers.SOMETHING_ELSE,
            FormatCheckAnswers.NO_PLAY,
            FormatCheckAnswers.NO_DISPLAY
        )).distinct()
        return FormatQuestion(
            kind = QuestionKind.SOUND,
            expected = expected,
            options = options,
            wrong = (options - expected - FormatCheckAnswers.NO_DISPLAY).toSet()
        )
    }

    private fun video(id: Int, ext: String, label: String, vararg families: FormatFamily) =
        FormatClip(clipId(id), "${clipId(id)}.$ext", label, VIDEO, families.toSet().ifEmpty { setOf(FormatFamily.OTHER) })

    private fun hdr(id: Int, ext: String, label: String, question: FormatQuestion, vararg families: FormatFamily) =
        FormatClip(clipId(id), "${clipId(id)}.$ext", label, HDR, families.toSet().ifEmpty { setOf(FormatFamily.OTHER) }, question = question)

    private fun audio(
        id: Int,
        label: String,
        route: AudioRoute? = null,
        question: FormatQuestion? = null,
        questionWhenDecoded: FormatQuestion? = null
    ) = FormatClip(
        clipId(id), "${clipId(id)}.mkv", label, AUDIO,
        audioRoute = route, question = question, questionWhenDecoded = questionWhenDecoded
    )

    private fun clipId(id: Int) = "fc%02d".format(id)

    val clips: List<FormatClip> = listOf(
        video(13, "mp4", "H.264 1080p"),
        video(14, "mp4", "H.264 Main 4.0"),
        video(15, "mkv", "H.264 High 4.1"),
        video(16, "mkv", "H.264 16 reference frames"),
        video(17, "mkv", "H.264 4K"),
        video(18, "mkv", "H.264 4K60"),
        video(19, "mkv", "H.264 level 6.2 label"),
        video(20, "mkv", "H.264 10-bit Hi10P", HI10P),
        video(21, "ts", "H.264 interlaced 1080i"),
        video(22, "mkv", "HEVC 8-bit 4K", HEVC),
        video(23, "mkv", "HEVC 10-bit 4K60", HEVC),
        video(24, "mkv", "AV1 1080p 8-bit", AV1),
        video(25, "mp4", "AV1 1080p 10-bit", AV1),
        video(26, "mkv", "AV1 4K 8-bit", AV1),
        video(27, "mkv", "AV1 4K60 10-bit", AV1),
        video(28, "mkv", "VP9 4K", VP9),
        video(29, "mkv", "VC-1", VC1),
        video(30, "ts", "MPEG-2 720p", MPEG2),
        video(31, "ts", "MPEG-2 1080i", MPEG2),
        video(32, "avi", "XviD in AVI", XVID, AVI),
        video(33, "avi", "DivX 3 in AVI", DIVX, AVI),
        video(34, "avi", "DivX 5 in AVI", DIVX, AVI),
        hdr(1, "mp4", "Dolby Vision Profile 5", picture(FormatCheckAnswers.DOLBY_VISION), DOLBY_VISION),
        hdr(2, "mkv", "Dolby Vision Profile 7 FEL", picture(FormatCheckAnswers.DOLBY_VISION), DOLBY_VISION),
        hdr(3, "mp4", "Dolby Vision Profile 8.1", picture(FormatCheckAnswers.DOLBY_VISION), DOLBY_VISION),
        hdr(4, "mp4", "Dolby Vision Profile 8.4", picture(FormatCheckAnswers.DOLBY_VISION), DOLBY_VISION),
        hdr(5, "mkv", "Dolby Vision Profile 10.1 AV1", picture(FormatCheckAnswers.DOLBY_VISION), AV1),
        hdr(6, "mp4", "Dolby Vision Profile 10.4 AV1", picture(FormatCheckAnswers.DOLBY_VISION), AV1),
        hdr(7, "mp4", "HDR10 HEVC 4K", picture(FormatCheckAnswers.HDR10), HEVC),
        hdr(8, "mp4", "HDR10+ HEVC 4K", picture(FormatCheckAnswers.HDR10_PLUS), HEVC),
        hdr(9, "mp4", "HLG HEVC 4K", picture(FormatCheckAnswers.HLG), HEVC),
        FormatClip(clipId(10), "fc10.mkv", "HDR10 AV1 4K", HDR, setOf(AV1)),
        FormatClip(clipId(11), "fc11.mkv", "HLG AV1 4K", HDR, setOf(AV1)),
        FormatClip(clipId(12), "fc12.webm", "HDR VP9 Profile 2 4K", HDR, setOf(VP9)),
        audio(35, "TrueHD + Atmos", AudioRoute.TRUEHD, sound(FormatCheckAnswers.ATMOS, FormatCheckAnswers.TRUEHD)),
        audio(36, "E-AC3 + Atmos 5.1", AudioRoute.EAC3, sound(FormatCheckAnswers.ATMOS, FormatCheckAnswers.EAC3)),
        audio(37, "E-AC3 + Atmos 7.1", AudioRoute.EAC3, sound(FormatCheckAnswers.ATMOS, FormatCheckAnswers.EAC3)),
        audio(38, "DTS:X", AudioRoute.DTS_HD, sound(FormatCheckAnswers.DTS_X, FormatCheckAnswers.DTS_HD)),
        audio(39, "DTS-HD MA", AudioRoute.DTS_HD, sound(FormatCheckAnswers.DTS_HD, FormatCheckAnswers.DTS_OTHER)),
        audio(40, "DTS-HD High Resolution", AudioRoute.DTS_HD, sound(FormatCheckAnswers.DTS_HD, FormatCheckAnswers.DTS_OTHER)),
        audio(41, "DTS-ES", AudioRoute.DTS, sound(FormatCheckAnswers.DTS_OTHER, FormatCheckAnswers.DTS_HD)),
        audio(
            42, "DTS Express", AudioRoute.DTS_HD,
            sound(FormatCheckAnswers.DTS_OTHER, FormatCheckAnswers.DTS_HD),
            questionWhenDecoded = sound(FormatCheckAnswers.PCM, FormatCheckAnswers.DTS_OTHER, FormatCheckAnswers.DTS_HD)
        ),
        audio(43, "TrueHD 5.1", AudioRoute.TRUEHD),
        audio(45, "DTS 5.1", AudioRoute.DTS),
        audio(46, "AC-3 5.1", AudioRoute.AC3),
        audio(47, "E-AC3 5.1", AudioRoute.EAC3),
        audio(49, "AAC 5.1"),
        audio(50, "HE-AAC stereo"),
        audio(51, "HE-AAC v2 stereo"),
        audio(52, "HE-AAC 5.1"),
        audio(53, "FLAC 5.1"),
        audio(54, "Opus 5.1"),
        audio(55, "MP3"),
        audio(56, "PCM stereo")
    )

    fun byId(id: String): FormatClip? = clips.firstOrNull { it.id == id }
}
