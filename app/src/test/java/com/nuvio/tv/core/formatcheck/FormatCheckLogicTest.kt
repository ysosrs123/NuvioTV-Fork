package com.nuvio.tv.core.formatcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatCheckLogicTest {

    @Test
    fun `a clip that closed the app counts as failed and keeps a note`() {
        val result = FormatCheckVerdicts.skippedAfterAppClosed("fc18")
        assertEquals(FormatVerdict.FAILED, result.verdict)
        assertEquals(listOf(FormatSettingNote.CLOSED_APP_LAST_RUN), result.settingNotes)
    }

    @Test
    fun `dropped frames count against a video clip but not a blank audio picture`() {
        val seen = ClipObservation(firstFrame = true, droppedFrames = 38)
        assertEquals(FormatVerdict.PLAYED_SLOW, FormatCheckVerdicts.of(seen))
        assertEquals(FormatVerdict.PLAYED, FormatCheckVerdicts.of(seen, audioOnly = true))
    }
    @Test
    fun `no picture or an error is a failure`() {
        assertEquals(FormatVerdict.FAILED, FormatCheckVerdicts.of(ClipObservation()))
        assertEquals(FormatVerdict.FAILED, FormatCheckVerdicts.of(ClipObservation(firstFrame = true, error = "boom")))
    }

    @Test
    fun `a picture with few drops is played and many drops is slow`() {
        assertEquals(FormatVerdict.PLAYED, FormatCheckVerdicts.of(ClipObservation(firstFrame = true, droppedFrames = 11)))
        assertEquals(FormatVerdict.PLAYED_SLOW, FormatCheckVerdicts.of(ClipObservation(firstFrame = true, droppedFrames = 12)))
    }

    @Test
    fun `a move to MPV that shows a picture is reported as MPV`() {
        val seen = ClipObservation(firstFrame = true, movedToMpv = true, droppedFrames = 50)
        assertEquals(FormatVerdict.PLAYED_MPV, FormatCheckVerdicts.of(seen))
        assertEquals(FormatVerdict.FAILED, FormatCheckVerdicts.of(ClipObservation(movedToMpv = true)))
    }

    private val question = FormatCheckCatalog.byId("fc07")!!.question!!

    @Test
    fun `answers change a played result only when they say something`() {
        assertEquals(FormatVerdict.CONFIRMED, FormatCheckVerdicts.withAnswer(FormatVerdict.PLAYED, question, FormatCheckAnswers.HDR10))
        assertEquals(FormatVerdict.NOT_AS_EXPECTED, FormatCheckVerdicts.withAnswer(FormatVerdict.PLAYED, question, FormatCheckAnswers.NOTHING_SPECIAL))
        assertEquals(FormatVerdict.PLAYED, FormatCheckVerdicts.withAnswer(FormatVerdict.PLAYED, question, FormatCheckAnswers.CANT_TELL))
        assertEquals(FormatVerdict.PLAYED, FormatCheckVerdicts.withAnswer(FormatVerdict.PLAYED, question, null))
        assertEquals(FormatVerdict.FAILED, FormatCheckVerdicts.withAnswer(FormatVerdict.FAILED, question, FormatCheckAnswers.HDR10))
    }

    private val dolbyVisionClips = listOf("fc01", "fc02", "fc03", "fc04", "fc05", "fc06").map { FormatCheckCatalog.byId(it)!! }

    @Test
    fun `a Dolby Vision answer alone never turns a played clip into a warning`() {
        dolbyVisionClips.forEach { clip ->
            val dv = clip.question!!
            assertTrue(FormatCheckVerdicts.answerIsInfoOnly(dv))
            dv.options.forEach { answer ->
                val verdict = FormatCheckVerdicts.withAnswer(FormatVerdict.PLAYED, dv, answer)
                assertTrue("${clip.id} $answer", verdict == FormatVerdict.PLAYED || verdict == FormatVerdict.CONFIRMED)
            }
            assertEquals(FormatVerdict.CONFIRMED, FormatCheckVerdicts.withAnswer(FormatVerdict.PLAYED, dv, FormatCheckAnswers.DOLBY_VISION))
            assertEquals(FormatVerdict.PLAYED, FormatCheckVerdicts.withAnswer(FormatVerdict.PLAYED, dv, FormatCheckAnswers.PICTURE_WRONG))
        }
    }

    @Test
    fun `a Dolby Vision answer doesn't hide a slow or failed play`() {
        val dv = FormatCheckCatalog.byId("fc03")!!.question!!
        assertEquals(FormatVerdict.PLAYED_SLOW, FormatCheckVerdicts.withAnswer(FormatVerdict.PLAYED_SLOW, dv, FormatCheckAnswers.DOLBY_VISION))
        assertEquals(FormatVerdict.FAILED, FormatCheckVerdicts.withAnswer(FormatVerdict.FAILED, dv, FormatCheckAnswers.DOLBY_VISION))
        assertEquals(FormatVerdict.NOT_AS_EXPECTED, FormatCheckVerdicts.withAnswer(FormatVerdict.NOT_AS_EXPECTED, dv, FormatCheckAnswers.DOLBY_VISION))
    }

    @Test
    fun `Dolby Vision clips warn only when the box or the route fails`() {
        dolbyVisionClips.forEach { assertTrue(it.id, it.dolbyVision) }
        assertFalse(FormatCheckCatalog.byId("fc07")!!.dolbyVision)
        val played = ClipObservation(firstFrame = true, decoder = "c2.x.hevc")
        assertEquals(FormatVerdict.PLAYED, FormatCheckVerdicts.of(played, dolbyVision = true))
        assertEquals(FormatVerdict.PLAYED, FormatCheckVerdicts.of(played.copy(dolbyVisionRoute = DolbyVisionRoute.CONVERTED), dolbyVision = true))
        assertEquals(FormatVerdict.NOT_AS_EXPECTED, FormatCheckVerdicts.of(played.copy(dolbyVisionRoute = DolbyVisionRoute.STRIPPED), dolbyVision = true))
        assertEquals(FormatVerdict.PLAYED, FormatCheckVerdicts.of(played.copy(dolbyVisionRoute = DolbyVisionRoute.STRIPPED)))
        assertEquals(FormatVerdict.FAILED, FormatCheckVerdicts.of(ClipObservation(), dolbyVision = true))
        assertEquals(FormatVerdict.FAILED, FormatCheckVerdicts.of(played.copy(error = "decoder"), dolbyVision = true))
    }

    private fun results(verdict: FormatVerdict, vararg ids: String) = ids.map { ClipResult(it, verdict) }

    @Test
    fun `test again picks every result that isn't fine, but not a clip that closed the app`() {
        val saved = results(FormatVerdict.PLAYED, "fc13") + results(FormatVerdict.CONFIRMED, "fc01") +
            results(FormatVerdict.FAILED, "fc20") + results(FormatVerdict.NOT_AS_EXPECTED, "fc35") +
            results(FormatVerdict.PLAYED_MPV, "fc33") + results(FormatVerdict.NOT_TESTED, "fc40") +
            FormatCheckVerdicts.skippedAfterAppClosed("fc18")
        assertEquals(setOf("fc20", "fc35", "fc33", "fc40"), FormatCheckRetest.clipsToRetest(saved))
    }

    @Test
    fun `a retest replaces only the clips it played and keeps the order`() {
        val old = results(FormatVerdict.PLAYED, "fc13") + results(FormatVerdict.FAILED, "fc20") +
            results(FormatVerdict.NOT_AS_EXPECTED, "fc35")
        val merged = FormatCheckRetest.merge(old, results(FormatVerdict.CONFIRMED, "fc35"))
        assertEquals(listOf("fc13", "fc20", "fc35"), merged.map { it.clipId })
        assertEquals(listOf(FormatVerdict.PLAYED, FormatVerdict.FAILED, FormatVerdict.CONFIRMED), merged.map { it.verdict })
    }

    @Test
    fun `show it again is never an answer that counts`() {
        assertFalse(FormatCheckCatalog.clips.any { FormatCheckAnswers.SHOW_AGAIN in (it.question?.options ?: emptyList()) })
        assertEquals(FormatVerdict.PLAYED, FormatCheckVerdicts.withAnswer(FormatVerdict.PLAYED, question, FormatCheckAnswers.SHOW_AGAIN))
    }

    @Test
    fun `a suggestion needs every clip of a format to fail`() {
        val all = FormatCheckCatalog.clips.filter { FormatFamily.AVI in it.families }.map { it.id }.toTypedArray()
        assertTrue(FormatCheckSuggestions.from(results(FormatVerdict.FAILED, *all)).contains(FormatSuggestion.Streams(FormatFamily.AVI)))
        val partly = results(FormatVerdict.FAILED, all[0]) + results(FormatVerdict.PLAYED, *all.drop(1).toTypedArray())
        assertFalse(FormatCheckSuggestions.from(partly).contains(FormatSuggestion.Streams(FormatFamily.AVI)))
    }

    @Test
    fun `played with a catch and not tested never suggest anything`() {
        val ids = FormatCheckCatalog.clips.map { it.id }.toTypedArray()
        listOf(FormatVerdict.PLAYED_SLOW, FormatVerdict.PLAYED_MPV, FormatVerdict.NOT_AS_EXPECTED, FormatVerdict.NOT_TESTED).forEach {
            assertTrue(FormatCheckSuggestions.from(results(it, *ids)).isEmpty())
        }
    }

    @Test
    fun `audio suggestion follows its connection group`() {
        val ids = FormatCheckCatalog.clips.filter { it.audioRoute == AudioRoute.TRUEHD }.map { it.id }.toTypedArray()
        assertEquals(listOf<FormatSuggestion>(FormatSuggestion.AudioDecode(AudioRoute.TRUEHD)), FormatCheckSuggestions.from(results(FormatVerdict.FAILED, *ids)))
    }

    @Test
    fun `stream rules match real release names and hide wins`() {
        val rules = mapOf(FormatFamily.AVI to StreamAction.WARN, FormatFamily.DOLBY_VISION to StreamAction.HIDE)
        assertEquals(StreamAction.WARN, FormatCheckStreamRules.actionFor("Old.Film.1999.DVDRip.AVI", rules))
        assertEquals(StreamAction.HIDE, FormatCheckStreamRules.actionFor("Film 2160p DV HDR10 Remux", rules))
        assertEquals(StreamAction.HIDE, FormatCheckStreamRules.actionFor("film.dovi.mkv.avi", rules))
        assertNull(FormatCheckStreamRules.actionFor("Film 1080p WEB-DL DVDRip Davinci.mkv", rules))
        assertNull(FormatCheckStreamRules.actionFor("Anything", emptyMap()))
    }

    private fun stream(title: String, addon: String = "A") = com.nuvio.tv.domain.model.Stream(
        name = addon, title = title, description = null, url = "https://host/$title", ytId = null, infoHash = null,
        fileIdx = null, externalUrl = null, behaviorHints = null, addonName = addon, addonLogo = null
    )

    @Test
    fun `hidden formats leave the stream lists auto play and next episode pick from`() {
        val dv = stream("Film 2160p DV Remux")
        val plain = stream("Film 1080p WEB-DL")
        val avi = stream("Film DVDRip.avi")
        val groups = listOf(
            com.nuvio.tv.domain.model.AddonStreams("A", null, listOf(dv, plain, avi)),
            com.nuvio.tv.domain.model.AddonStreams("B", null, listOf(stream("Film DV", "B")))
        )
        val rules = mapOf(FormatFamily.DOLBY_VISION to StreamAction.HIDE, FormatFamily.AVI to StreamAction.WARN)

        val kept = FormatCheckStreamRules.withoutHidden(groups, rules)
        assertEquals(listOf("A"), kept.map { it.addonName })
        assertEquals(listOf(plain, avi), kept.single().streams)

        val warnOnly = mapOf(FormatFamily.DOLBY_VISION to StreamAction.WARN)
        assertTrue(FormatCheckStreamRules.withoutHidden(groups, warnOnly) === groups)
        assertTrue(FormatCheckStreamRules.withoutHidden(groups, emptyMap()) === groups)
        assertTrue(FormatCheckStreamRules.withoutHidden(groups.take(1), mapOf(FormatFamily.DOLBY_VISION to StreamAction.HIDE, FormatFamily.AVI to StreamAction.HIDE))
            .single().streams == listOf(plain))
    }

    @Test
    fun `saved results and rules read back the same`() {
        val saved = SavedFormatCheck(5L, true, "type:hdmi|x", listOf(ClipResult("fc01", FormatVerdict.CONFIRMED, "c2.x", 3, true, FormatCheckAnswers.DOLBY_VISION, DolbyVisionRoute.CONVERTED, listOf(FormatSettingNote.TUNNELING))))
        assertEquals(saved, FormatCheckStore.decodeResults(FormatCheckStore.encodeResults(saved)))
        val rules = mapOf(FormatFamily.VC1 to StreamAction.HIDE)
        assertEquals(rules, FormatCheckStore.decodeRules(FormatCheckStore.encodeRules(rules)))
        assertNull(FormatCheckStore.decodeResults("not json"))
    }

    @Test
    fun `ranges and pack names are handled safely`() {
        assertEquals(10L..99L, LocalClipServer.parseRange("bytes=10-", 100))
        assertEquals(0L..99L, LocalClipServer.parseRange("bytes=0-500", 100))
        assertNull(LocalClipServer.parseRange("bytes=100-", 100))
        assertNull(LocalClipServer.parseRange(null, 100))
        assertTrue(FormatCheckPack.isSafeEntryName("fc01.mp4"))
        assertFalse(FormatCheckPack.isSafeEntryName("../fc01.mp4"))
        assertFalse(FormatCheckPack.isSafeEntryName("a/fc01.mp4"))
    }

    @Test
    fun `the catalogue has 54 unique clips and every question can be answered`() {
        val clips = FormatCheckCatalog.clips
        assertEquals(54, clips.size)
        assertEquals(54, clips.map { it.id }.toSet().size)
        clips.mapNotNull { it.question }.forEach { assertTrue(it.expected in it.options) }
    }

    private val express = FormatCheckCatalog.byId("fc42")!!

    @Test
    fun `DTS Express expects PCM when the app decodes it and DTS when it can't`() {
        val decoded = express.questionFor(FormatCheckSettings(softwareDecoders = true))!!
        assertEquals(FormatCheckAnswers.PCM, decoded.expected)
        assertEquals(decoded.options, decoded.options.distinct())
        assertEquals(FormatVerdict.CONFIRMED, FormatCheckVerdicts.withAnswer(FormatVerdict.PLAYED, decoded, FormatCheckAnswers.PCM))
        assertEquals(FormatVerdict.NOT_AS_EXPECTED, FormatCheckVerdicts.withAnswer(FormatVerdict.PLAYED, decoded, FormatCheckAnswers.DTS_OTHER))
        assertEquals(FormatCheckAnswers.DTS_OTHER, express.questionFor(FormatCheckSettings(softwareDecoders = false))!!.expected)
    }

    @Test
    fun `other DTS clips keep their answer with software decoders on`() {
        val settings = FormatCheckSettings(softwareDecoders = true)
        assertEquals(FormatCheckAnswers.DTS_HD, FormatCheckCatalog.byId("fc39")!!.questionFor(settings)!!.expected)
    }

    @Test
    fun `a decoded DTS Express has no passthrough notes but keeps Force AC-3`() {
        val off = FormatCheckSettings(softwareDecoders = true, manualAudioOff = setOf(AudioRoute.DTS_HD), boxDenies = setOf(AudioRoute.DTS_HD))
        assertTrue(FormatCheckSettingNotes.forClip(express, off).isEmpty())
        assertEquals(listOf(FormatSettingNote.FORCE_AC3), FormatCheckSettingNotes.forClip(express, off.copy(forceAc3 = true)))
        assertEquals(listOf(FormatSettingNote.AUDIO_OFF), FormatCheckSettingNotes.forClip(express, off.copy(softwareDecoders = false)))
    }

    @Test
    fun `a decoded DTS Express doesn't stop a DTS-HD suggestion`() {
        val failed = FormatCheckCatalog.clips.filter { it.audioRoute == AudioRoute.DTS_HD && it.id != "fc42" }
            .map { ClipResult(it.id, FormatVerdict.FAILED) }
        val suggestion = FormatSuggestion.AudioDecode(AudioRoute.DTS_HD)
        val decoded = ClipResult("fc42", FormatVerdict.PLAYED, passthrough = false)
        val sent = ClipResult("fc42", FormatVerdict.PLAYED, passthrough = true)
        assertTrue(suggestion in FormatCheckSuggestions.from(failed + decoded))
        assertFalse(suggestion in FormatCheckSuggestions.from(failed + sent))
    }

    @Test
    fun `settings notes only appear on the clips they affect`() {
        val truehd = FormatCheckCatalog.byId("fc35")!!
        val video = FormatCheckCatalog.byId("fc13")!!
        val settings = FormatCheckSettings(manualAudioOff = setOf(AudioRoute.TRUEHD))
        assertEquals(listOf(FormatSettingNote.AUDIO_OFF), FormatCheckSettingNotes.forClip(truehd, settings))
        assertTrue(FormatCheckSettingNotes.forClip(video, settings).isEmpty())
        assertTrue(FormatSettingNote.MAIN_PLAYER_USED in FormatCheckSettingNotes.forClip(video, FormatCheckSettings(mpvChosen = true)))
        assertEquals(listOf(FormatSettingNote.DV_MODE), FormatCheckSettingNotes.forClip(FormatCheckCatalog.byId("fc02")!!, FormatCheckSettings(dolbyVisionNotAuto = true)))
        assertTrue(FormatCheckSettingNotes.forClip(FormatCheckCatalog.byId("fc03")!!, FormatCheckSettings(dolbyVisionNotAuto = true)).isEmpty())
    }
}
