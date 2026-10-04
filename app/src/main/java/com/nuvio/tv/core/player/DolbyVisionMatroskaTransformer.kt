package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.DolbyVisionConfig
import com.nuvio.tv.core.player.dvmkv.MatroskaExtractor
import java.io.ByteArrayOutputStream

/**
 * App-level (AAR mode) implementation of the vendored Matroska extractor's
 * [MatroskaExtractor.DolbyVisionSampleTransformer] seam.
 *
 * Performs the DV7 to DV8.1 conversion for MKV, wired to [DoviBridge]. The
 * extractor calls:
 *
 *  - [onDolbyVisionBlockAdditionalData] when it reads the DV7 enhancement-layer
 *    RPU from a Matroska BlockAdditional; we convert it to an 8.1 RPU NAL.
 *  - [transformHevcSample] just before committing the HEVC sample; we rewrite
 *    the base-layer NALs (dropping EL NALs, converting any in-band RPU) and
 *    append the converted BlockAdditional RPU.
 *  - [onDolbyVisionCodecString] when building the output Format; dvhe.07/dvh1.07
 *    becomes dvhe.08/dvh1.08 to advertise single-layer 8.1.
 *
 * Mode selection, the manual-DV8.1 mode-2 default and its per-RPU fallback to
 * mode 1 all come from [config], so behaviour matches the MP4/TS path in
 * [DolbyVisionExtractorsFactory].
 */
@UnstableApi
internal class DolbyVisionMatroskaTransformer(
    private val config: DolbyVisionConversionConfig,
    private val stripRpuOnly: Boolean = false,
    private val stripHdr10PlusSei: Boolean = false,
    private val injectHdr10Sei: Boolean = false,
) : MatroskaExtractor.DolbyVisionSampleTransformer {

    private var lastTransformedLength = 0

    // HDR10 SEI NALs (MDCV/CLLI) built once per stream from the RPU
    // static metadata, injected on the strip path so non-DV HDR10 sinks tone-map
    // correctly. Null until first resolved; empty-checked before use.
    private var cachedHdr10SeiNals: List<ByteArray>? = null

    // Logs the strip-path injection outcome once per stream, so a log capture can
    // tell "injected" from "skipped as already-present".
    private var injectOutcomeLogged = false

    // Reused across samples; grows to the largest frame once.
    private val scratch = ExposedByteArrayOutputStream(64 * 1024)

    // Cached per-stream once resolved. SEI carrying mastering-display/CLL info
    // is typically only sent on keyframes, so we keep checking each sample
    // until we find it or hit the detection window, rather than deciding off
    // just the first frame.
    private var dv5StripDecision: Boolean? = null
    private var dv5DetectionSamplesChecked = 0

    private fun shouldStripDv5Rpu(
        sample: ByteArray,
        sampleLength: Int,
        nalUnitLengthFieldLength: Int
    ): Boolean {
        dv5StripDecision?.let { return it }
        val detected = HevcDvRpuStripper.containsHdr10StaticMetadataSei(
            sample, sampleLength, nalUnitLengthFieldLength
        )
        if (detected) {
            dv5StripDecision = true
            android.util.Log.i("DVStrip", "DV5_STRIP_DECISION: hdr10StaticSeiPresent=true -> STRIP")
            return true
        }
        dv5DetectionSamplesChecked++
        if (dv5DetectionSamplesChecked >= DV5_DETECTION_SAMPLE_LIMIT) {
            dv5StripDecision = false
            android.util.Log.i("DVStrip", "DV5_STRIP_DECISION: hdr10StaticSeiPresent=false after $dv5DetectionSamplesChecked samples -> SKIP")
            return false
        }
        return false
    }

    // Reuses the package-private ExposedByteArrayOutputStream from HevcDvRpuStripper.kt

    // True when pendingDolbyVisionBlockAdditionalData holds bytes already
    // converted by the hook below (the extractor stores our return value), so
    // transformHevcSample must append them verbatim rather than converting a
    // second time. The extractor calls the hook and the transform on the same
    // thread in block order, and laced frames sharing one BlockAdditional all
    // see the same (flag, bytes) pair, so the pairing holds.
    private var pendingRpuPreConverted = false
    private var consecutiveRpuFailures = 0
    private var rpuConversionAbandoned = false
    private var droppedRpuCount = 0L

    // EL-type detection runs once per stream, on the first in-band
    // RPU seen by the rewriter. (The BlockAdditional hook path is not probed:
    // the content class that uses it -- P8 hybrids with bare RPUs -- has no
    // enhancement layer to type.)
    private var elTypeProbed = false
    // Once-per-stream RPU static-metadata probe state (diagnostics only).
    private var metadataProbed = false
    private var metadataProbeAttempts = 0

    private fun probeElType(sample: ByteArray, offset: Int, nalSize: Int) {
        val code = DoviBridge.detectRpuElType(sample, offset, nalSize)
        config.stats.recordElType(code)
    }

    /**
     * Reads the RPU's static HDR metadata once per stream for
     * Diagnostics. Called from the top of [transformHevcSample] so it covers
     * both the strip (non-DV sink) and convert paths. Best-effort and
     * read-only; attempt-bounded so a stream whose RPU never rides in-band
     * (e.g. dual-track BlockAdditional, not covered here) can't scan forever.
     */
    private fun probeRpuMetadata(sample: ByteArray, sampleLength: Int, nalUnitLengthFieldLength: Int) {
        if (metadataProbed || metadataProbeAttempts >= METADATA_PROBE_ATTEMPT_LIMIT) return
        if (!DoviBridge.isAvailable()) return
        metadataProbeAttempts++
        HevcDvRpuStripper.findRpuNalLengthDelimited(sample, sampleLength, nalUnitLengthFieldLength)?.let { rpu ->
            metadataProbed = true
            val meta = DoviBridge.getRpuStaticMetadata(sample, rpu.first, rpu.second)
            config.stats.recordRpuMetadata(meta)
            android.util.Log.i(
                "DVMetaProbe",
                "hookA mkv rpuLen=${rpu.second} meta=${meta?.toDiagnosticLine() ?: "null"}"
            )
        }
    }

    override fun onDolbyVisionBlockAdditionalData(
        blockAdditionalData: ByteArray?,
        blockAddIdType: Int,
        dolbyVisionConfigBytes: ByteArray?
    ): ByteArray? {
        pendingRpuPreConverted = false
        if (blockAdditionalData == null) return null
        if (stripRpuOnly) return ByteArray(0)
        val profile = resolveProfile(null, dolbyVisionConfigBytes)
        if (!config.shouldConvert(profile)) return null
        // Fail fast: a stream that is failing every frame stops burning two
        // libdovi calls per frame for nothing.
        if (rpuConversionAbandoned) {
            registerRpuFailure()
            return null
        }
        // The only conversion site for the BlockAdditional RPU; transformHevcSample
        // appends the stored result verbatim.
        val converted = convertRpuNal(blockAdditionalData, config.conversionMode(profile))
        if (converted != null) {
            pendingRpuPreConverted = true
            consecutiveRpuFailures = 0
            return converted
        }
        registerRpuFailure()
        return null
    }

    /**
     * A failed conversion DROPS the RPU instead of forwarding raw P7 bytes
     * (or an unparseable blob) under dvhe.08 signalling, which would give a
     * stream whose codec string and per-frame metadata disagree. The
     * base layer continues (effectively HDR10). After
     * [RPU_FAILURE_ABANDON_THRESHOLD] consecutive failures the per-frame
     * conversion attempts stop entirely for this stream.
     */
    private fun registerRpuFailure() {
        consecutiveRpuFailures++
        droppedRpuCount++
        config.stats.recordRpuDrop()
        if (consecutiveRpuFailures == 1 || consecutiveRpuFailures % 100 == 0) {
            android.util.Log.w(
                TAG,
                "DV7_MKV: RPU conversion failed; dropping RPU " +
                    "(consecutive=$consecutiveRpuFailures dropped=$droppedRpuCount)"
            )
        }
        if (!rpuConversionAbandoned && consecutiveRpuFailures >= RPU_FAILURE_ABANDON_THRESHOLD) {
            rpuConversionAbandoned = true
            android.util.Log.e(
                TAG,
                "DV7_MKV: $RPU_FAILURE_ABANDON_THRESHOLD consecutive RPU conversion " +
                    "failures; abandoning conversion for this stream " +
                    "(RPUs dropped, base layer continues as HDR10)"
            )
        }
    }

    override fun shouldTransform(codecs: String?, dolbyVisionConfigBytes: ByteArray?): Boolean {
        if (stripHdr10PlusSei) return true
        val isDv = codecs?.startsWith("dv", ignoreCase = true) == true ||
                (dolbyVisionConfigBytes != null && dolbyVisionConfigBytes.isNotEmpty())
        if (stripRpuOnly) return isDv
        val profile = resolveProfile(codecs, dolbyVisionConfigBytes)
        return config.shouldConvert(profile)
    }

    override fun onHevcSample(
        sampleSizeBytes: Int,
        blockAdditionalData: ByteArray?,
        dolbyVisionConfigBytes: ByteArray?
    ) {
        // Telemetry-only seam; nothing to do.
    }

    override fun lastTransformedSampleLength(): Int = lastTransformedLength

    // HUD: media time of the sample the next transformHevcSample call carries.
    private var sampleTimeUs = C.TIME_UNSET

    override fun onSampleTimeUs(timeUs: Long) {
        sampleTimeUs = timeUs
    }

    /** Live RPU display-management summary for the HUD, from the source RPU (before conversion), every ~0.5 s. */
    private fun sampleLiveDm(sample: ByteArray, sampleLength: Int, nalUnitLengthFieldLength: Int) {
        val timeUs = sampleTimeUs
        if (timeUs == C.TIME_UNSET || !DoviBridge.isAvailable()) return
        val writer = config.stats.LIVE_DM_WRITER_EXTRACTOR
        if (!config.stats.shouldSampleLiveDm(writer, timeUs)) return
        val rpu = HevcDvRpuStripper.findRpuNalLengthDelimited(sample, sampleLength, nalUnitLengthFieldLength)
        config.stats.recordLiveDm(
            writer, timeUs, rpu?.let { DoviBridge.getRpuDmInfo(sample, it.first, it.second) }
        )
    }

    override fun transformHevcSample(
        sampleLengthDelimitedData: ByteArray?,
        sampleLength: Int,
        nalUnitLengthFieldLength: Int,
        blockAdditionalData: ByteArray?,
        dolbyVisionConfigBytes: ByteArray?
    ): ByteArray? {
        val sample = sampleLengthDelimitedData ?: return null
        val profile = resolveProfile(null, dolbyVisionConfigBytes)

        lastTransformedLength = sampleLength

        probeRpuMetadata(sample, sampleLength, nalUnitLengthFieldLength)
        sampleLiveDm(sample, sampleLength, nalUnitLengthFieldLength)

        if (stripRpuOnly) {
            if (profile == 5) {
                if (!shouldStripDv5Rpu(sample, sampleLength, nalUnitLengthFieldLength)) {
                    return stripHdr10PlusIfEnabled(sample, sampleLength, nalUnitLengthFieldLength) ?: sample
                }
            }
            // Use the shared ExposedByteArrayOutputStream scratch buffer to avoid GC allocations on every frame
            val changed = HevcDvRpuStripper.stripRpuLengthDelimited(
                sample, sampleLength, nalUnitLengthFieldLength, scratch
            )
            if (changed) {
                val stripped = finishScratch()
                val afterHdr10Plus =
                    stripHdr10PlusIfEnabled(stripped, lastTransformedLength, nalUnitLengthFieldLength) ?: stripped
                return injectHdr10SeiIfEnabled(
                    afterHdr10Plus, lastTransformedLength, profile, nalUnitLengthFieldLength
                ) ?: afterHdr10Plus
            }
            return stripHdr10PlusIfEnabled(sample, sampleLength, nalUnitLengthFieldLength) ?: sample
        }

        if (!config.shouldConvert(profile)) {
            return stripHdr10PlusIfEnabled(sample, sampleLength, nalUnitLengthFieldLength) ?: sample
        }

        if (profile == 5 && !config.convertDv5Rpu) {
            return stripHdr10PlusIfEnabled(sample, sampleLength, nalUnitLengthFieldLength) ?: sample
        }

        val mode = config.conversionMode(profile)
        val baseChanged = rewriteMp4HevcSampleInto(sample, sampleLength, nalUnitLengthFieldLength, mode)

        if (blockAdditionalData == null) {
            if (!baseChanged) {
                return stripHdr10PlusIfEnabled(sample, sampleLength, nalUnitLengthFieldLength) ?: sample
            }
            val dvResult = finishScratch()
            return stripHdr10PlusIfEnabled(dvResult, lastTransformedLength, nalUnitLengthFieldLength) ?: dvResult
        }

        // The hook failed to convert this RPU (or conversion is
        // abandoned): drop it rather than appending raw P7 bytes / the
        // unparsed blob under dvhe.08 signalling. Emit the (possibly
        // EL-stripped) base layer only.
        if (!pendingRpuPreConverted) {
            return if (baseChanged) {
                val dvResult = finishScratch()
                stripHdr10PlusIfEnabled(dvResult, lastTransformedLength, nalUnitLengthFieldLength) ?: dvResult
            } else {
                stripHdr10PlusIfEnabled(sample, sampleLength, nalUnitLengthFieldLength) ?: sample
            }
        }

        if (!baseChanged) {
            scratch.reset()
            scratch.write(sample, 0, sampleLength)
        }
        // The BlockAdditional bytes were already converted in
        // onDolbyVisionBlockAdditionalData (single conversion site); append
        // them verbatim.
        if (!appendLengthDelimitedNalToScratch(blockAdditionalData, nalUnitLengthFieldLength)) {
            return null
        }
        val dvResult = finishScratch()
        return stripHdr10PlusIfEnabled(dvResult, lastTransformedLength, nalUnitLengthFieldLength) ?: dvResult
    }

    private fun finishScratch(): ByteArray {
        lastTransformedLength = scratch.size()
        return scratch.backingArray()
    }

    override fun onDolbyVisionCodecString(
        codecs: String?,
        dolbyVisionConfigBytes: ByteArray?
    ): String? {
        if (stripRpuOnly) {
            return null
        }
        val profile = resolveProfile(codecs, dolbyVisionConfigBytes)
        if (!config.shouldConvert(profile)) return null
        config.stats.recordSourceProfile(profile)
        val normalized = normalizeDolbyVisionCodecString(codecs)
        return if (normalized != null && normalized != codecs) {
            config.stats.recordCodecStringRewrite()
            normalized
        } else {
            null
        }
    }

    /**
     * Applies HDR10+ SEI stripping to [data] if [stripHdr10PlusSei] is enabled.
     * Returns null when the feature is off or no HDR10+ was found; otherwise
     * returns the stripped bytes and updates [lastTransformedLength].
     */
    private fun stripHdr10PlusIfEnabled(
        data: ByteArray,
        len: Int,
        nalLengthFieldLength: Int
    ): ByteArray? {
        if (!stripHdr10PlusSei) return null
        val stripped = HevcHdr10PlusStripper.stripHdr10PlusLengthDelimited(data, len, nalLengthFieldLength)
        if (stripped != null) {
            lastTransformedLength = stripped.size
            return stripped
        }
        return null
    }

    /**
     * Injects the source RPU's HDR10 static-metadata SEI (MDCV always,
     * CLLI when known) into [data] on the strip path, so a non-DV HDR10 sink
     * tone-maps against the master. Only for P7/P8.1, only when the base layer
     * does not already carry HDR10 static SEI, and only once the RPU metadata has
     * been read this stream. Returns null (caller keeps [data]) when nothing is
     * injected; otherwise returns the enlarged bytes and updates
     * [lastTransformedLength]. [len] is the valid length of [data], which may be
     * an oversized scratch buffer.
     */
    private fun injectHdr10SeiIfEnabled(
        data: ByteArray,
        len: Int,
        profile: Int?,
        nalLengthFieldLength: Int
    ): ByteArray? {
        if (!injectHdr10Sei) return null
        if (profile != 8) {
            logInjectOutcomeOnce("skipped: profile $profile not DV8 (P8.1)")
            return null
        }
        val nals = cachedHdr10SeiNals ?: run {
            val meta = config.stats.getLastRpuMetadata() ?: return null
            val built = Hdr10SeiInjector.buildSeiNals(meta)
            if (built.isEmpty()) return null   // don't cache empty; retry next sample
            cachedHdr10SeiNals = built
            built
        }
        if (Hdr10SeiInjector.hasHdr10StaticSei(data, len, annexB = false, nalLengthFieldLength = nalLengthFieldLength)) {
            logInjectOutcomeOnce("skipped: base already carries HDR10 SEI")
            return null
        }
        val injected = Hdr10SeiInjector.injectLengthDelimited(data, len, nalLengthFieldLength, nals)
        logInjectOutcomeOnce("injected MDCV+CLLI (+${injected.size - len} bytes, ${nals.size} NAL(s))")
        lastTransformedLength = injected.size
        return injected
    }

    private fun logInjectOutcomeOnce(msg: String) {
        if (!injectOutcomeLogged) {
            injectOutcomeLogged = true
            android.util.Log.i("DVInject", "MKV strip: $msg")
        }
    }

    // ── Conversion + NAL helpers ──

    private fun convertRpuNal(nal: ByteArray, primaryMode: Int): ByteArray? {
        val outLen = DoviBridge.convertDv7RpuToDv81NonAllocating(nal, 0, nal.size, primaryMode)
        if (outLen > 0) {
            config.stats.recordConversionMode(primaryMode)
            return DoviBridge.rpuOutBuffer.copyOfRange(0, outLen)
        }
        if (config.allowMode2Fallback && primaryMode == 2) {
            val fallbackLen = DoviBridge.convertDv7RpuToDv81NonAllocating(nal, 0, nal.size, 1)
            if (fallbackLen > 0) {
                config.stats.recordConversionMode(1)
                return DoviBridge.rpuOutBuffer.copyOfRange(0, fallbackLen)
            }
        }
        return null
    }

    private fun rewriteMp4HevcSampleInto(
        sample: ByteArray,
        sampleLength: Int,
        nalUnitLengthFieldLength: Int,
        mode: Int
    ): Boolean {
        if (nalUnitLengthFieldLength !in 1..4) return false
        var offset = 0
        var changed = false
        val out = scratch
        out.reset()
        while (offset + nalUnitLengthFieldLength <= sampleLength) {
            val nalSize = readLengthField(sample, offset, nalUnitLengthFieldLength)
            if (nalSize < 0) return false
            offset += nalUnitLengthFieldLength
            if (offset + nalSize > sampleLength) return false
            val nalType = if (nalSize >= 1) nalUnitTypeAt(sample, offset) else -1
            val layerId = nuhLayerIdAt(sample, offset, nalSize)
            when {
                // Enhancement-layer NAL that isn't the RPU: drop it.
                layerId > 0 && nalType != NAL_TYPE_UNSPEC62 -> changed = true
                // In-band EL strip: the P7 EL rides in unspec63 wrapper
                // NALs at layer 0 on single-track remuxes, so the layer-id
                // filter above never matches it. A DV8.1 stream must not carry
                // an enhancement layer -- forwarding these under dvhe.08
                // signalling makes the Amlogic DV core fall back to HDR10.
                nalType == NAL_TYPE_UNSPEC63 -> changed = true
                // RPU NAL: convert directly from sample buffer without JVM allocations
                nalType == NAL_TYPE_UNSPEC62 -> {
                    // One-shot FEL/MEL detection on the first in-band
                    // RPU, plus the soft preserve-mapping-on-FEL guard.
                    if (!elTypeProbed) {
                        elTypeProbed = true
                        probeElType(sample, offset, nalSize)
                    }
                    val outLen = if (rpuConversionAbandoned) {
                        -1
                    } else {
                        DoviBridge.convertDv7RpuToDv81NonAllocating(sample, offset, nalSize, mode)
                    }
                    if (outLen > 0) {
                        changed = true
                        consecutiveRpuFailures = 0
                        if (!writeLengthField(out, outLen, nalUnitLengthFieldLength)) return false
                        out.write(DoviBridge.rpuOutBuffer, 0, outLen)
                    } else {
                        // Conversion failed: DROP the RPU rather than forwarding
                        // the original P7 RPU under dvhe.08 signalling.
                        changed = true
                        registerRpuFailure()
                    }
                }
                // Base-layer NAL: forward straight from the sample buffer, no copy.
                else -> {
                    if (!writeLengthField(out, nalSize, nalUnitLengthFieldLength)) return false
                    out.write(sample, offset, nalSize)
                }
            }
            offset += nalSize
        }
        if (offset != sampleLength) return false
        if (!changed) return false
        return out.size() > 0
    }

    private fun appendLengthDelimitedNalToScratch(
        nalPayload: ByteArray,
        nalUnitLengthFieldLength: Int
    ): Boolean {
        if (nalUnitLengthFieldLength !in 1..4 || nalPayload.isEmpty()) return false
        val maxNalSize = when (nalUnitLengthFieldLength) {
            1 -> 0xFF
            2 -> 0xFFFF
            3 -> 0xFFFFFF
            else -> Int.MAX_VALUE
        }
        if (nalPayload.size > maxNalSize) return false
        if (!writeLengthField(scratch, nalPayload.size, nalUnitLengthFieldLength)) return false
        scratch.write(nalPayload)
        return true
    }

    private fun normalizeDolbyVisionCodecString(codecs: String?): String? {
        val raw = codecs?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parts = raw.split('.').toMutableList()
        if (parts.size < 2) return null
        val prefix = parts[0].lowercase()
        if (prefix != "dvhe" && prefix != "dvh1") return null
        val profileValue = parts[1].toIntOrNull() ?: return null
        if (profileValue != 5 && profileValue != 7) return null
        val width = parts[1].length.coerceAtLeast(2)
        parts[1] = "8".padStart(width, '0')
        return parts.joinToString(".")
    }

    private fun downgradeDolbyVisionCodecStringToHevc(codecs: String?): String? {
        val raw = codecs?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parts = raw.split('.').toMutableList()
        if (parts.size < 2) return null
        return when (parts[0].lowercase()) {
            "dvhe" -> { parts[0] = "hvc1"; parts.joinToString(".") }
            "dvh1" -> { parts[0] = "hev1"; parts.joinToString(".") }
            else -> null
        }
    }

    private fun resolveProfile(codecs: String?, configBytes: ByteArray?): Int? {
        if (configBytes != null && configBytes.isNotEmpty()) {
            val parsedProfile = runCatching {
                DolbyVisionConfig.parse(ParsableByteArray(configBytes))?.profile
            }.getOrNull()
            if (parsedProfile != null) return parsedProfile
        }
        return resolveProfileFromCodecString(codecs)
    }

    private fun resolveProfileFromCodecString(codecs: String?): Int? {
        val raw = codecs?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parts = raw.split('.')
        if (parts.size < 2) return null
        val prefix = parts[0].lowercase()
        if (prefix != "dvhe" && prefix != "dvh1") return null
        return parts[1].toIntOrNull()
    }

    private fun nalUnitTypeAt(sample: ByteArray, offset: Int): Int =
        (sample[offset].toInt() ushr 1) and 0x3F

    private fun nuhLayerIdAt(sample: ByteArray, offset: Int, nalSize: Int): Int {
        if (nalSize < 2) return 0
        val b0 = sample[offset].toInt() and 0x01
        val b1 = sample[offset + 1].toInt() and 0xF8
        return (b0 shl 5) or (b1 ushr 3)
    }

    private fun getNuhLayerId(nalPayload: ByteArray): Int {
        if (nalPayload.size < 2) return 0
        val b0 = nalPayload[0].toInt() and 0x01
        val b1 = nalPayload[1].toInt() and 0xF8
        return (b0 shl 5) or (b1 ushr 3)
    }

    private fun normalizeNuhLayerIdToZero(nalPayload: ByteArray): ByteArray {
        if (nalPayload.size < 2 || getNuhLayerId(nalPayload) == 0) return nalPayload
        val out = nalPayload.copyOf()
        out[0] = (out[0].toInt() and 0xFE).toByte()
        out[1] = (out[1].toInt() and 0x07).toByte()
        return out
    }

    private fun readLengthField(data: ByteArray, offset: Int, lengthBytes: Int): Int {
        var value = 0
        for (i in 0 until lengthBytes) {
            value = (value shl 8) or (data[offset + i].toInt() and 0xFF)
        }
        return value
    }

    private fun writeLengthField(out: ByteArrayOutputStream, value: Int, lengthBytes: Int): Boolean {
        if (value < 0) return false
        val maxNalSize = when (lengthBytes) {
            1 -> 0xFF
            2 -> 0xFFFF
            3 -> 0xFFFFFF
            4 -> Int.MAX_VALUE
            else -> return false
        }
        if (value > maxNalSize) return false
        for (shift in (lengthBytes - 1) downTo 0) {
            out.write((value ushr (shift * 8)) and 0xFF)
        }
        return true
    }

    private companion object {
        const val NAL_TYPE_UNSPEC62 = 62
        // unspec63 is the Dolby single-track carriage wrapper for the
        // P7 enhancement layer (EL rides at nuh_layer_id 0 inside type-63
        // NALs on FraMeSToR-class UHD remuxes).
        const val NAL_TYPE_UNSPEC63 = 63
        const val DV5_DETECTION_SAMPLE_LIMIT = 15
        // Bound the diagnostics RPU probe (see probeRpuMetadata).
        const val METADATA_PROBE_ATTEMPT_LIMIT = 30
        const val TAG = "DolbyVisionMkvXform"

        // Consecutive conversion failures before per-frame
        // attempts stop for the stream (~2.5s of 24fps video). Real failure
        // modes here (wrong parser lock, non-RPU BlockAdditional layout) are
        // all-or-nothing, so a run this long means every frame will fail.
        const val RPU_FAILURE_ABANDON_THRESHOLD = 60
    }
}
