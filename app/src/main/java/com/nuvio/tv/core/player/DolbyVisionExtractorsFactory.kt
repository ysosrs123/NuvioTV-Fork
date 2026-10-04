package com.nuvio.tv.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import com.nuvio.tv.core.player.dvmkv.MatroskaExtractor as DvMatroskaExtractor
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * App-level Dolby Vision Profile 7 to 8.1 conversion that needs no forked Media3.
 *
 * It wraps a stock [ExtractorsFactory] and, for video tracks of containers whose
 * RPU rides in-band as NAL units (MP4 / fMP4 = length-delimited, TS = Annex-B),
 * intercepts the sample stream at the [TrackOutput] level and rewrites the
 * Dolby Vision RPU NAL (type 62) via [DoviBridge], drops the enhancement-layer
 * NAL units, and rewrites the codec string (dvhe.07 becomes dvhe.08).
 *
 * Matroska is special for two reasons:
 * 1. DV7 RPU arrives as BlockAdditional data that stock Media3 discards before
 *    any TrackOutput, so MKV swaps in the vendored
 *    [com.nuvio.tv.core.player.dvmkv.MatroskaExtractor] for RPU transform.
 * 2. That same vendored extractor sniffs the first DTS sample to distinguish
 *    core DTS vs DTS-HD MA / DTS:X (mkvmerge tags every DTS variant as A_DTS;
 *    stock media3 maps that to core DTS with no inspection). The Matroska swap
 *    is therefore unconditional — even when DV conversion is inactive — so
 *    native-DV / policy-OFF boxes still get correct HD audio mime for bitstream.
 *
 * For any non-DV7 content (or when [config] is inactive) every non-Matroska
 * wrapper is a strict pass-through, so normal playback of all formats is unaffected.
 */
@UnstableApi
internal class DolbyVisionExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val config: DolbyVisionConversionConfig,
    private val stripDvRpu: Boolean = false,
    private val stripHdr10PlusSei: Boolean = false,
    private val injectHdr10Sei: Boolean = false
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>
    ): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    private fun wrap(extractor: Extractor): Extractor {
        // Matroska is swapped unconditionally: the vendored
        // extractor carries the DTS-HD MA / DTS:X first-sample sniff (mkvmerge
        // writes A_DTS for every DTS variant; stock media3 1.8 maps that to core
        // DTS with no inspection). The devices most likely to bitstream HD audio
        // are native-DV7 boxes where the policy resolves to OFF, and without the
        // sniff they would negotiate core DTS for DTS-HD content. With an
        // inactive config the transformer's shouldTransform() returns false,
        // so non-DV HEVC samples stream through
        // the stock write path at no extra cost.
        if (extractor.javaClass.name == STOCK_MATROSKA_EXTRACTOR) {
            val mkv = DvMatroskaExtractor(
                DefaultSubtitleParserFactory(),
                /* flags= */ 0,
                DolbyVisionMatroskaTransformer(
                    config = config,
                    stripRpuOnly = stripDvRpu && !config.active,
                    stripHdr10PlusSei = stripHdr10PlusSei,
                    injectHdr10Sei = injectHdr10Sei,
                )
            )
            // On the MKV strip path the base layer is HDR10 but its Format
            // carries no colorInfo; wrap so the video Format is tagged HDR10 and
            // the display switches to HDR mode. Convert path is untouched (it
            // outputs DV, whose own decoder path signals HDR).
            return if (stripDvRpu && !config.active) HdrColorSignalingExtractor(mkv) else mkv
        }
        if (!config.active && !stripDvRpu && !stripHdr10PlusSei) return extractor
        val nalFormat = nalFormatFor(extractor) ?: return extractor
        return DolbyVisionExtractor(extractor, config, nalFormat, stripDvRpu, stripHdr10PlusSei)
    }

    private fun nalFormatFor(extractor: Extractor): NalFormat? {
        val name = extractor.javaClass.name
        return when {
            // RPU is in-band in the sample for these containers, so reachable here.
            name.contains("FragmentedMp4Extractor") -> NalFormat.LENGTH_DELIMITED
            name.contains("Mp4Extractor") -> NalFormat.LENGTH_DELIMITED
            name.contains("TsExtractor") -> NalFormat.ANNEX_B
            else -> null
        }
    }

    private companion object {
        const val STOCK_MATROSKA_EXTRACTOR = "androidx.media3.extractor.mkv.MatroskaExtractor"
    }
}

/** How HEVC NAL units are framed in the sample stream for a given container. */
internal enum class NalFormat { ANNEX_B, LENGTH_DELIMITED }

/**
 * Per-playback DV7 to 8.1 conversion diagnostics, fed by the factory/transformer and read
 * by the player diagnostics. Reset per playback alongside the DoviBridge counters.
 */
internal object DolbyVisionConversionStats {
    const val LIVE_DM_WRITER_EXTRACTOR = 0
    const val LIVE_DM_WRITER_RENDERER = 1
    @Volatile var session = DolbyVisionStatsSession(); private set
    fun reset() { session = DolbyVisionStatsSession() }
    fun getCodecStringRewriteCount() = session.getCodecStringRewriteCount()
    fun getRpuDropCount() = session.getRpuDropCount()
    fun getLastSourceProfile() = session.getLastSourceProfile()
    fun getLastSelectedConversionMode() = session.getLastSelectedConversionMode()
    fun getLastElType() = session.getLastElType()
    fun getLastRpuMetadata() = session.getLastRpuMetadata()
    fun getFirstDm() = session.getFirstDm()
    fun liveDmAt(positionUs: Long) = session.liveDmAt(positionUs)
}

internal class DolbyVisionStatsSession {
    private val codecStringRewriteCount = AtomicLong(0)
    // RPUs dropped because conversion failed (never forwarded raw).
    private val rpuDropCount = AtomicLong(0)
    @Volatile private var lastSourceProfile: Int? = null
    @Volatile private var lastConversionMode: Int? = null
    // EL type of the current stream (DoviBridge.EL_TYPE_* / -1 / -2).
    @Volatile private var lastElType: Int? = null
    // Static HDR metadata read from the current stream's first RPU.
    @Volatile private var lastRpuMetadata: DoviBridge.RpuStaticMetadata? = null

    // HUD: RPU display-management summaries keyed by media time (us). The feeders (extractor hooks,
    // native FEL renderer) run seconds ahead of playback, so the HUD shows the entry at the playback position.
    // Writers: the extractor hooks (loader thread, tens of seconds ahead) and the native FEL renderer (playback
    // thread, <= 2.5 s ahead) can both run for one stream (e.g. HDR10+ SEI stripping in Native FEL mode), so each
    // keeps its own sampling throttle; a shared one would make every sample of both look due.
    val LIVE_DM_WRITER_EXTRACTOR = 0
    val LIVE_DM_WRITER_RENDERER = 1
    private val liveDm = java.util.TreeMap<Long, DoviBridge.RpuDmInfo>()
    private val liveDmLastSampledUs = java.util.concurrent.atomic.AtomicLongArray(2)
    private val liveDmInserts = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var firstDm: DoviBridge.RpuDmInfo? = null

    init {
        resetLiveDmThrottle()
    }

    private fun resetLiveDmThrottle() {
        for (i in 0 until liveDmLastSampledUs.length()) liveDmLastSampledUs.set(i, Long.MIN_VALUE)
    }

    @Synchronized fun reset() {
        codecStringRewriteCount.set(0)
        rpuDropCount.set(0)
        lastSourceProfile = null
        lastConversionMode = null
        lastElType = null
        lastRpuMetadata = null
        liveDmInserts.set(0)
        liveDm.clear()
        resetLiveDmThrottle()
        firstDm = null
    }

    /**
     * True when the sample at [mediaTimeUs] should be parsed for the live HUD rows by [writer]: every
     * [LIVE_DM_INTERVAL_US] of media, and after a backward jump (seek). Samples arrive in decode order, so PTS
     * step back by a few frames all the time; only a step back of more than [LIVE_DM_BACK_JUMP_US] is a seek.
     */
    @Synchronized fun shouldSampleLiveDm(writer: Int, mediaTimeUs: Long): Boolean {
        val last = liveDmLastSampledUs.get(writer)
        return last == Long.MIN_VALUE || mediaTimeUs - last >= LIVE_DM_INTERVAL_US ||
            mediaTimeUs < last - LIVE_DM_BACK_JUMP_US
    }

    @Synchronized fun recordLiveDm(writer: Int, mediaTimeUs: Long, info: DoviBridge.RpuDmInfo?) {
        val previousUs = liveDmLastSampledUs.get(writer)
        if (previousUs != Long.MIN_VALUE && mediaTimeUs < previousUs - LIVE_DM_BACK_JUMP_US) {
            // A full future window must not evict every new entry after a backward seek.
            // Small decode-order PTS reversals do not invalidate the presentation cache.
            liveDm.clear()
            resetLiveDmThrottle()
        }
        liveDmLastSampledUs.set(writer, mediaTimeUs)
        if (info == null) return
        val first = firstDm
        // Stream-constant facts: prefer a summary whose RPU carried sequence info (bit depths, EL resampling).
        if (first == null || (first.blBitDepth == null && info.blBitDepth != null)) firstDm = info
        liveDm[mediaTimeUs] = info
        // Strict cap under the session lock, including during concurrent loader/renderer writes.
        while (liveDm.size > LIVE_DM_MAX_ENTRIES) liveDm.pollFirstEntry()
    }

    /** First RPU summary of this playback (stream-constant facts: CM version, levels, bit depths). */
    @Synchronized fun getFirstDm(): DoviBridge.RpuDmInfo? = firstDm

    /** The summary sampled at or just before [positionUs] (within [LIVE_DM_MAX_AGE_US]); prunes older entries. */
    @Synchronized fun liveDmAt(positionUs: Long): DoviBridge.RpuDmInfo? {
        val entry = liveDm.floorEntry(positionUs) ?: return null
        liveDm.headMap(positionUs - LIVE_DM_KEEP_BEHIND_US).clear()
        return entry.value.takeIf { positionUs - entry.key <= LIVE_DM_MAX_AGE_US }
    }

    private val LIVE_DM_INTERVAL_US = 500_000L
    private val LIVE_DM_BACK_JUMP_US = 1_000_000L
    private val LIVE_DM_MAX_AGE_US = 2_000_000L
    private val LIVE_DM_KEEP_BEHIND_US = 30_000_000L
    private val LIVE_DM_MAX_ENTRIES = 4096

    @Synchronized fun recordElType(code: Int) {
        lastElType = code
    }

    @Synchronized fun getLastElType(): Int? = lastElType

    @Synchronized fun recordRpuMetadata(metadata: DoviBridge.RpuStaticMetadata?) {
        if (metadata != null) lastRpuMetadata = metadata
    }

    @Synchronized fun getLastRpuMetadata(): DoviBridge.RpuStaticMetadata? = lastRpuMetadata

    @Synchronized fun recordRpuDrop() {
        rpuDropCount.incrementAndGet()
    }

    @Synchronized fun getRpuDropCount(): Long = rpuDropCount.get()

    /** Records the SOURCE DV profile (pre-conversion), e.g. 7. */
    @Synchronized fun recordSourceProfile(profile: Int?) {
        if (profile != null) lastSourceProfile = profile
    }

    @Synchronized fun recordConversionMode(mode: Int) {
        lastConversionMode = mode
    }

    @Synchronized fun recordCodecStringRewrite() {
        codecStringRewriteCount.incrementAndGet()
    }

    @Synchronized fun getCodecStringRewriteCount(): Long = codecStringRewriteCount.get()
    @Synchronized fun getLastSourceProfile(): Int? = lastSourceProfile
    @Synchronized fun getLastSelectedConversionMode(): Int? = lastConversionMode
}

/**
 * Drives the per-stream conversion decision. Mirrors the auto-pick used by the
 * extractor hook installer: profile-7 AUTO = mode 1 (ToMel); profile-7 manual
 * Convert-to-DV8.1 = mode 2; profile 5 = mode 2; a [forcedMode] in 0..4
 * overrides all of it.
 */
internal data class DolbyVisionConversionConfig(
    val active: Boolean,
    val forcedMode: Int = -1,
    val dv5Enabled: Boolean = false,
    /** True when the user explicitly chose "Convert to DV8.1" (not AUTO). */
    val manualDv81: Boolean = false,
    val nativeProfile7: Boolean = false,
    val stats: DolbyVisionStatsSession = DolbyVisionConversionStats.session
) {
    /** Manual mode-2 default with per-RPU fallback to mode 1 (not for AUTO / forced). */
    val allowMode2Fallback: Boolean get() = manualDv81 && forcedMode !in 0..4

    /**
     * DV5 via the toggle is signal-only (codec rewritten to 8.1, profile-5 RPU kept). A
     * libdovi RPU rewrite runs only when a mode is forced in Advanced, OR when the user
     * explicitly chose Convert to DV8.1 with DV5 enabled. DV7 always converts.
     */
    val convertDv5Rpu: Boolean get() = forcedMode in 0..4 || (dv5Enabled && manualDv81)

    /** True when a track of [profile] should be converted. */
    fun shouldConvert(profile: Int?): Boolean {
        if (!active) return false
        return when (profile) {
            7 -> !nativeProfile7
            // DV5 is already single-layer; only convert it when the user explicitly chose
            // Convert to DV8.1. AUTO leaves DV5 alone (converting it breaks colors).
            5 -> dv5Enabled && manualDv81
            else -> false
        }
    }

    /**
     * libdovi conversion mode to use for [profile].
     *
     * In libdovi 3.3.2 the C API maps 0 -> Lossless, 1 -> ToMel, 2 and 3 ->
     * To81, 4 -> To84, anything else -> Lossless; the header's per-mode
     * comment disagrees with the code. P5 uses mode 2, the value where both
     * agree. To81MappingPreserved is not reachable through the C API and is
     * never requested: it keeps composer curves that assume the enhancement
     * layer this pipeline strips.
     */
    fun conversionMode(profile: Int?): Int {
        if (forcedMode in 0..4) return forcedMode
        return when {
            profile == 5 -> 2
            manualDv81 -> 2 // manual Convert to DV8.1 prefers mode 2 (falls back to 1)
            else -> 1       // AUTO convert stays on mode 1
        }
    }
}

// Fixed HDR10 (BT.2020 / limited-range / PQ) colour signalling. Applied to
// the stripped base layer's Format so the Android display framework switches the
// sink into HDR mode. The stripped stream is genuine HDR10 in-band (the SPS VUI
// is preserved by the strip), but Format.colorInfo is usually null on DV
// containers, and some sinks (e.g. Amlogic AM9 Pro) act on the framework's
// colorInfo, not the in-band SPS - leaving the display in SDR (PQ shown as SDR
// reads as a purple tint) until this is set.
@UnstableApi
private fun hdr10ColorInfo(): ColorInfo = ColorInfo.Builder()
    .setColorSpace(C.COLOR_SPACE_BT2020)
    .setColorRange(C.COLOR_RANGE_LIMITED)
    .setColorTransfer(C.COLOR_TRANSFER_ST2084)
    .build()

/** DV profile from a codec string (dvhe/dvav/dvh1/dva1.NN.*), or null. */
private fun dvProfileOf(codecs: String?): Int? {
    if (codecs.isNullOrBlank()) return null
    val m = Regex("^(?:dvhe|dvav|dvh1|dva1)\\.(\\d+)\\.").find(codecs.trim().lowercase()) ?: return null
    return m.groupValues[1].toIntOrNull()
}

/**
 * Wraps the vendored MKV extractor on the strip path to add HDR10
 * colorInfo to the video Format. The MKV path rewrites samples via the
 * transformer but never touches the Format, so - unlike the native path's
 * NativeOptimizedVideoTrackOutput.format() - it needs this thin output wrapper.
 * P7/P8.1 only; leaves an already-HDR (ST2084) Format untouched.
 */
@UnstableApi
internal class HdrColorSignalingExtractor(
    internal val delegate: Extractor
) : Extractor {
    override fun init(output: ExtractorOutput) =
        delegate.init(HdrColorSignalingExtractorOutput(output))

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)
    override fun release() = delegate.release()
    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

@UnstableApi
private class HdrColorSignalingExtractorOutput(
    private val delegate: ExtractorOutput
) : ExtractorOutput {
    override fun track(id: Int, type: Int): TrackOutput {
        val track = delegate.track(id, type)
        return if (type == C.TRACK_TYPE_VIDEO) HdrColorSignalingTrackOutput(track) else track
    }

    override fun endTracks() = delegate.endTracks()
    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

@UnstableApi
private class HdrColorSignalingTrackOutput(
    private val delegate: TrackOutput
) : TrackOutput by delegate {
    override fun format(format: Format) {
        if (format.drmInitData != null || format.cryptoType != C.CRYPTO_TYPE_NONE) {
            delegate.format(format)
            return
        }
        val profile = dvProfileOf(format.codecs)
        val out = if ((profile == 7 || profile == 8) &&
            format.colorInfo?.colorTransfer != C.COLOR_TRANSFER_ST2084 &&
            format.colorInfo?.colorTransfer != C.COLOR_TRANSFER_HLG
        ) {
            format.buildUpon().setColorInfo(hdr10ColorInfo()).build()
        } else {
            format
        }
        delegate.format(out)
    }
}

/** Wraps an [Extractor] to inject a DV-rewriting [ExtractorOutput]. */
@UnstableApi
private class DolbyVisionExtractor(
    private val delegate: Extractor,
    private val config: DolbyVisionConversionConfig,
    private val nalFormat: NalFormat,
    private val stripDvRpu: Boolean = false,
    private val stripHdr10PlusSei: Boolean = false
) : Extractor {

    override fun init(output: ExtractorOutput) {
        delegate.init(DolbyVisionExtractorOutput(output, config, nalFormat, stripDvRpu, stripHdr10PlusSei))
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

/** Wraps an [ExtractorOutput] to swap video [TrackOutput]s for DV-rewriting ones. */
@UnstableApi
private class DolbyVisionExtractorOutput(
    private val delegate: ExtractorOutput,
    private val config: DolbyVisionConversionConfig,
    private val nalFormat: NalFormat,
    private val stripDvRpu: Boolean = false,
    private val stripHdr10PlusSei: Boolean = false
) : ExtractorOutput {

    override fun track(id: Int, type: Int): TrackOutput {
        val track = delegate.track(id, type)
        return if (type == C.TRACK_TYPE_VIDEO) {
            NativeOptimizedVideoTrackOutput(
                delegate = track,
                config = config,
                nalFormat = nalFormat,
                stripDvRpu = stripDvRpu,
                stripHdr10PlusSei = stripHdr10PlusSei
            )
        } else {
            track
        }
    }

    override fun endTracks() = delegate.endTracks()

    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

@UnstableApi
private class NativeOptimizedVideoTrackOutput(
    private val delegate: TrackOutput,
    private val config: DolbyVisionConversionConfig,
    private val nalFormat: NalFormat,
    private val stripDvRpu: Boolean = false,
    private val stripHdr10PlusSei: Boolean = false
) : TrackOutput {

    private var pendingBuf = ByteArray(0)
    private var pendingLen = 0
    private var inputScratch = ByteArray(0)
    private val scratch = ParsableByteArray()

    private var converting = false
    private var shouldProcess = false
    private var profile: Int? = null
    private var codecs: String? = null
    private var nalLengthFieldLength = 4
    // Once-per-stream RPU static-metadata probe state (diagnostics only).
    private var metadataProbed = false
    private var metadataProbeAttempts = 0

    private fun ensurePendingCapacity(extra: Int) {
        val need = pendingLen + extra
        if (pendingBuf.size < need) {
            var newSize = if (pendingBuf.isEmpty()) 16 * 1024 else pendingBuf.size
            while (newSize < need) newSize = newSize shl 1
            pendingBuf = pendingBuf.copyOf(newSize)
        }
    }

    private fun ensureInputScratch(size: Int) {
        if (inputScratch.size < size) {
            var newSize = if (inputScratch.isEmpty()) 16 * 1024 else inputScratch.size
            while (newSize < size) newSize = newSize shl 1
            inputScratch = ByteArray(newSize)
        }
    }

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun format(format: Format) {
        if (format.drmInitData != null || format.cryptoType != C.CRYPTO_TYPE_NONE) {
            shouldProcess = false
            converting = false
            pendingLen = 0
            delegate.format(format)
            return
        }
        profile = parseDvProfile(format.codecs)
        converting = config.active && config.shouldConvert(profile)
        nalLengthFieldLength = parseNalLengthFieldLength(format)
        // The SOURCE profile for the HUD / diagnostics (the codecs rewrite below says 8 for converted P7;
        // the MKV path records it in its transformer).
        if (profile != null) config.stats.recordSourceProfile(profile)

        val strippedCodecs = if (stripDvRpu) stripDvCodecString(format.codecs) else null
        val codecsToUse = strippedCodecs ?: if (converting) rewriteDvCodecString(format.codecs) else format.codecs

        var outFormat = format
        if (codecsToUse != null && codecsToUse != format.codecs) {
            outFormat = format.buildUpon().setCodecs(codecsToUse).build()
        }
        if (stripDvRpu && strippedCodecs != null) {
            outFormat = outFormat.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).build()
            // The stripped base layer is HDR10, but the container/DV Format
            // rarely carries colorInfo, so on some sinks (e.g. Amlogic AM9 Pro)
            // the Android display framework never learns the content is HDR and
            // leaves the display in SDR - PQ shown as SDR reads as a purple tint.
            // Populate colorInfo so the framework drives the HDR mode switch.
            // P7/P8.1 only (P5's base is IPT-PQ, not BT.2020).
            if ((profile == 7 || profile == 8) &&
                outFormat.colorInfo?.colorTransfer != C.COLOR_TRANSFER_ST2084 &&
                outFormat.colorInfo?.colorTransfer != C.COLOR_TRANSFER_HLG
            ) {
                outFormat = outFormat.buildUpon().setColorInfo(hdr10ColorInfo()).build()
            }
        }

        val rewriteSamples = converting && !(profile == 5 && !config.convertDv5Rpu)
        val shouldStripDovi = stripDvRpu && strippedCodecs != null && strippedCodecs != format.codecs
        
        shouldProcess = rewriteSamples || shouldStripDovi || stripHdr10PlusSei
        codecs = outFormat.codecs
        delegate.format(outFormat)
    }

    @Throws(IOException::class)
    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int
    ): Int {
        if (!shouldProcess) return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        ensureInputScratch(length)
        val read = input.read(inputScratch, 0, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        if (read <= 0) return read
        if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) {
            ensurePendingCapacity(read)
            System.arraycopy(inputScratch, 0, pendingBuf, pendingLen, read)
            pendingLen += read
        } else {
            scratch.reset(inputScratch, read)
            delegate.sampleData(scratch, read, sampleDataPart)
        }
        return read
    }

    override fun sampleData(
        data: ParsableByteArray,
        length: Int,
        sampleDataPart: Int
    ) {
        if (!shouldProcess || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN || length <= 0) {
            delegate.sampleData(data, length, sampleDataPart)
            return
        }
        ensurePendingCapacity(length)
        data.readBytes(pendingBuf, pendingLen, length)
        pendingLen += length
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        if (!shouldProcess || pendingLen == 0) {
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }

        if (cryptoData != null || flags and C.BUFFER_FLAG_ENCRYPTED != 0) {
            throw IOException("Protected sample cannot enter the Dolby Vision transform path")
        }
        // Read the RPU's static HDR metadata once per stream for
        // Diagnostics. Covers MP4/fMP4/TS on both the convert and strip paths
        // (both reach here with shouldProcess). Best-effort and read-only: it
        // never alters the sample or the output below.
        if (!metadataProbed && metadataProbeAttempts < METADATA_PROBE_ATTEMPT_LIMIT &&
            DoviBridge.isAvailable()
        ) {
            metadataProbeAttempts++
            // The extractor's declared nalFormat is not reliable for the bytes
            // that actually reach this track output: an Mp4Extractor stream can
            // arrive Annex-B (a leading 00 00 (00) 01 start code) rather than
            // length-delimited, so detect the framing from the sample itself.
            val annexB = pendingLen >= 4 &&
                pendingBuf[0].toInt() == 0 && pendingBuf[1].toInt() == 0 &&
                (pendingBuf[2].toInt() == 1 ||
                    (pendingBuf[2].toInt() == 0 && pendingBuf[3].toInt() == 1))
            if (metadataProbeAttempts == 1) {
                val diag = if (annexB) "framing=annexB" else
                    "framing=LD nlf=$nalLengthFieldLength nalTypes=" +
                        HevcDvRpuStripper.listNalTypesLengthDelimited(pendingBuf, pendingLen, nalLengthFieldLength)
                android.util.Log.i(
                    "DVMetaProbe",
                    "hookB entered fmt=$nalFormat pendingLen=$pendingLen $diag"
                )
            }
            val rpu = if (annexB) {
                HevcDvRpuStripper.findRpuNalAnnexB(pendingBuf, pendingLen)
            } else {
                HevcDvRpuStripper.findRpuNalLengthDelimited(pendingBuf, pendingLen, nalLengthFieldLength)
            }
            if (rpu != null) {
                metadataProbed = true
                val meta = DoviBridge.getRpuStaticMetadata(pendingBuf, rpu.first, rpu.second)
                config.stats.recordRpuMetadata(meta)
                // FEL/MEL for the HUD's "Profile 7.x FEL/MEL -> 8.1" (the MKV transformer probes its own).
                if (profile == 7) {
                    config.stats.recordElType(
                        DoviBridge.detectRpuElType(pendingBuf, rpu.first, rpu.second)
                    )
                }
                android.util.Log.i(
                    "DVMetaProbe",
                    "hookB fmt=$nalFormat rpuLen=${rpu.second} meta=${meta?.toDiagnosticLine() ?: "null"}"
                )
            }
        }

        // HUD: live RPU display-management summary (L1 scene light, L5 active area, ...), read from
        // the source RPU before any conversion, every ~0.5 s of media. Read-only.
        val liveWriter = config.stats.LIVE_DM_WRITER_EXTRACTOR
        if (DoviBridge.isAvailable() && config.stats.shouldSampleLiveDm(liveWriter, timeUs)) {
            // Only this access unit: the last `offset` bytes already belong to the next one.
            val auLen = pendingLen - offset.coerceIn(0, pendingLen)
            val annexB = auLen >= 4 &&
                pendingBuf[0].toInt() == 0 && pendingBuf[1].toInt() == 0 &&
                (pendingBuf[2].toInt() == 1 ||
                    (pendingBuf[2].toInt() == 0 && pendingBuf[3].toInt() == 1))
            val rpu = if (annexB) {
                HevcDvRpuStripper.findRpuNalAnnexB(pendingBuf, auLen)
            } else {
                HevcDvRpuStripper.findRpuNalLengthDelimited(pendingBuf, auLen, nalLengthFieldLength)
            }
            config.stats.recordLiveDm(
                liveWriter,
                timeUs,
                rpu?.let { DoviBridge.getRpuDmInfo(pendingBuf, it.first, it.second) }
            )
        }

        val carrySize = offset.coerceIn(0, pendingLen)
        val sampleEnd = pendingLen - carrySize

        val mode = if (converting) config.conversionMode(profile) else 1
        val formatVal = if (nalFormat == NalFormat.LENGTH_DELIMITED) 1 else 0

        // Process in native C++ layer
        val written = DoviBridge.processVideoSampleNonAllocating(
            sample = pendingBuf,
            sampleLen = sampleEnd,
            nalFormat = formatVal,
            nalLengthFieldLength = nalLengthFieldLength,
            convertDovi = converting,
            doviMode = mode,
            doviProfile = profile ?: -1,
            stripDoviRpu = stripDvRpu,
            stripHdr10Plus = stripHdr10PlusSei
        )

        val useRewritten = written > 0
        val outputData = if (useRewritten) DoviBridge.rpuOutBuffer else pendingBuf
        val outputLen = if (useRewritten) written else sampleEnd

        scratch.reset(outputData, outputLen)
        delegate.sampleData(scratch, outputLen)
        delegate.sampleMetadata(timeUs, flags, outputLen, 0, cryptoData)

        if (carrySize > 0) {
            System.arraycopy(pendingBuf, sampleEnd, pendingBuf, 0, carrySize)
        }
        pendingLen = carrySize
    }

    private companion object {
        // Bound the diagnostics RPU probe so a mis-signalled stream that
        // never yields an RPU can't walk NALs on every sample indefinitely.
        const val METADATA_PROBE_ATTEMPT_LIMIT = 30

        fun parseDvProfile(codecs: String?): Int? {
            if (codecs.isNullOrBlank()) return null
            val m = Regex("^(?:dvhe|dvav|dvh1|dva1)\\.(\\d+)\\.")
                .find(codecs.trim().lowercase()) ?: return null
            return m.groupValues[1].toIntOrNull()
        }

        fun rewriteDvCodecString(codecs: String?): String? {
            if (codecs.isNullOrBlank()) return null
            return Regex("(?i)(dvhe|dvav|dvh1|dva1)\\.0[57]\\.")
                .replace(codecs) { mr -> "${mr.groupValues[1]}.08." }
        }

        fun stripDvCodecString(codecs: String?): String? {
            if (codecs.isNullOrBlank()) return null
            return Regex("(?i)(dvhe|dvh1)\\.[0-9]+\\.[0-9]+")
                .replace(codecs.trim()) { "hvc1.2.4.L153.B0" }
                .takeIf { it != codecs }
        }

        fun parseNalLengthFieldLength(format: Format): Int {
            val csd = format.initializationData.firstOrNull() ?: return 4
            if (csd.size <= 21) return 4
            if (csd[0].toInt() != 1) return 4
            return (csd[21].toInt() and 0x03) + 1
        }
    }
}
