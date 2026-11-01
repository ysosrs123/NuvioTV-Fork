package com.nuvio.tv.core.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.extractor.text.SubtitleParser

/**
 * Embedded subtitle formats that reach the text renderer undecoded, so only the selected track is
 * decoded, and only as its samples fall due. Parsed while the file is read, every track is decoded
 * whether it is shown or not, which for PGS means a full-picture bitmap per sample on the loader thread.
 *
 * ASS stays parsed during extraction: libass creates its track from the parser. While AutoSync
 * reads embedded text timings, SRT and VobSub stay parsed too, since it reads them from decoded cues.
 */
@UnstableApi
internal object RenderTimeSubtitles {

    private val ALWAYS = setOf(MimeTypes.APPLICATION_PGS)
    private val UNLESS_AUTO_SYNC = setOf(MimeTypes.APPLICATION_SUBRIP, MimeTypes.APPLICATION_VOBSUB)

    fun decodedAtRenderTime(format: Format, autoSyncObservesText: Boolean): Boolean {
        val mime = format.sampleMimeType ?: return false
        return mime in ALWAYS || (!autoSyncObservesText && mime in UNLESS_AUTO_SYNC)
    }

    /** The parser factory for extractors: formats decoded at render time are left unparsed. */
    fun extractionParsers(
        delegate: SubtitleParser.Factory,
        autoSyncObservesText: Boolean
    ): SubtitleParser.Factory = ExtractionParsers(delegate, autoSyncObservesText)

    /** Raw subtitle samples need the text renderer's own decoders; cue samples are unaffected. */
    fun enableRenderTimeDecoding(renderers: List<Renderer>, fromIndex: Int = 0) {
        for (index in fromIndex until renderers.size) {
            (renderers[index] as? TextRenderer)?.experimentalSetLegacyDecodingEnabled(true)
        }
    }

    private class ExtractionParsers(
        private val delegate: SubtitleParser.Factory,
        private val autoSyncObservesText: Boolean
    ) : SubtitleParser.Factory {
        override fun supportsFormat(format: Format): Boolean =
            !decodedAtRenderTime(format, autoSyncObservesText) && delegate.supportsFormat(format)

        override fun getCueReplacementBehavior(format: Format): Int = delegate.getCueReplacementBehavior(format)

        override fun create(format: Format): SubtitleParser = delegate.create(format)
    }
}
