package com.nuvio.tv.core.player

import android.util.Log
import com.nuvio.tv.BuildConfig
import java.util.concurrent.atomic.AtomicLong

object DoviBridge {
    private const val TAG = "DoviBridge"
    private const val LIB_NAME = "dovi_bridge"

    data class RealtimeConversionProbe(
        val supported: Boolean,
        val reason: String,
        val bridgeVersion: String?,
        val extractorHookReady: Boolean,
        val selfTest: SelfTestResult
    )

    data class SelfTestResult(
        val passed: Boolean,
        val reason: String,
        val inputBytes: Int,
        val outputBytes: Int
    )

    private val nativeLoaded: Boolean by lazy { loadNativeLibrary() }
    private var cachedSelfTestResult: SelfTestResult? = null
    private val conversionCallCount = AtomicLong(0L)
    private val conversionSuccessCount = AtomicLong(0L)

    val isNativeEnabledInBuild: Boolean
        get() = BuildConfig.DOVI_NATIVE_ENABLED

    val isExtractorHookReadyInBuild: Boolean
        get() = BuildConfig.DOVI_EXTRACTOR_HOOK_READY

    val isLibraryLoaded: Boolean
        get() = nativeLoaded

    fun isAvailable(): Boolean = isNativeEnabledInBuild && nativeLoaded

    fun getBridgeVersionOrNull(): String? {
        if (!isAvailable()) return null
        return runCatching { nativeGetBridgeVersion() }
            .onFailure { Log.w(TAG, "Failed to read bridge version: ${it.message}") }
            .getOrNull()
    }

    fun probeRealtimeConversionSupport(streamUrl: String): RealtimeConversionProbe {
        if (!isNativeEnabledInBuild) {
            return RealtimeConversionProbe(
                supported = false,
                reason = "native-disabled-in-build",
                bridgeVersion = null,
                extractorHookReady = isExtractorHookReadyInBuild,
                selfTest = SelfTestResult(
                    passed = false,
                    reason = "not-run",
                    inputBytes = 0,
                    outputBytes = 0
                )
            )
        }
        if (!nativeLoaded) {
            return RealtimeConversionProbe(
                supported = false,
                reason = "native-library-load-failed",
                bridgeVersion = null,
                extractorHookReady = isExtractorHookReadyInBuild,
                selfTest = SelfTestResult(
                    passed = false,
                    reason = "not-run",
                    inputBytes = 0,
                    outputBytes = 0
                )
            )
        }
        if (!isExtractorHookReadyInBuild) {
            return RealtimeConversionProbe(
                supported = false,
                reason = "extractor-hook-not-integrated",
                bridgeVersion = getBridgeVersionOrNull(),
                extractorHookReady = false,
                selfTest = runStartupSelfTest(streamUrl)
            )
        }

        val bridgeVersion = runCatching { nativeGetBridgeVersion() }
            .onFailure { Log.w(TAG, "probe version failed host=${streamUrl.safeHost()}: ${it.message}") }
            .getOrNull()

        val ready = runCatching { nativeIsConversionPathReady() }
            .onFailure { Log.w(TAG, "probe readiness failed host=${streamUrl.safeHost()}: ${it.message}") }
            .getOrDefault(false)

        val selfTest = runStartupSelfTest(streamUrl)
        if (!selfTest.passed) {
            return RealtimeConversionProbe(
                supported = false,
                reason = "self-test-failed:${selfTest.reason}",
                bridgeVersion = bridgeVersion,
                extractorHookReady = true,
                selfTest = selfTest
            )
        }

        return if (ready) {
            RealtimeConversionProbe(
                supported = true,
                reason = "ready",
                bridgeVersion = bridgeVersion,
                extractorHookReady = true,
                selfTest = selfTest
            )
        } else {
            RealtimeConversionProbe(
                supported = false,
                reason = "bridge-reports-not-ready",
                bridgeVersion = bridgeVersion,
                extractorHookReady = true,
                selfTest = selfTest
            )
        }
    }

    fun runStartupSelfTest(streamUrl: String): SelfTestResult {
        cachedSelfTestResult?.let { return it }
        if (!isAvailable()) {
            return SelfTestResult(
                passed = false,
                reason = "native-unavailable",
                inputBytes = 0,
                outputBytes = 0
            )
        }

        val payload = byteArrayOf(
            0x7c, 0x01, 0x20, 0x40,
            0x21, 0x33, 0x55, 0x77, 0x11, 0x02, 0x06, 0x10
        )
        val output = convertDv7RpuToDv81(payload, mode = 2)
        val result = if (output != null && output.isNotEmpty()) {
            SelfTestResult(
                passed = true,
                reason = if (output.contentEquals(payload)) {
                    "bridge-path-ok-passthrough"
                } else {
                    "bridge-path-ok-transformed"
                },
                inputBytes = payload.size,
                outputBytes = output.size
            )
        } else if (runCatching { nativeIsConversionPathReady() }.getOrDefault(false)) {
            // The synthetic payload is not guaranteed to be a valid single-frame RPU.
            // If the native bridge reports ready, do not hard-disable runtime probing here.
            SelfTestResult(
                passed = true,
                reason = "bridge-ready-selftest-unverifiable",
                inputBytes = payload.size,
                outputBytes = output?.size ?: 0
            )
        } else {
            SelfTestResult(
                passed = false,
                reason = "null-or-empty-output",
                inputBytes = payload.size,
                outputBytes = output?.size ?: 0
            )
        }

        // Exercise the RPU metadata reader once at startup so a JNI
        // linkage error surfaces here rather than on first DV playback. A null
        // result is expected: the synthetic payload carries no DM metadata.
        runCatching { getRpuStaticMetadata(payload, 0, payload.size) }

        // Validate the HDR10 SEI toolkit's byte layouts at startup via a
        // build-then-parse round trip (no device dependency, no output touched).
        Log.i(TAG, "Hdr10SeiInjector self-test: ${Hdr10SeiInjector.selfTest()}")

        cachedSelfTestResult = result
        Log.i(
            TAG,
            "Self-test host=${streamUrl.safeHost()} passed=${result.passed} " +
                "reason=${result.reason} bytes=${result.inputBytes}->${result.outputBytes}"
        )
        return result
    }

    fun resetRuntimeCounters() {
        conversionCallCount.set(0L)
        conversionSuccessCount.set(0L)
    }

    fun getConversionCallCount(): Long = conversionCallCount.get()

    fun getConversionSuccessCount(): Long = conversionSuccessCount.get()

    fun convertDv7RpuToDv81(payload: ByteArray, mode: Int = 1): ByteArray? {
        if (!isAvailable() || payload.isEmpty()) return null
        conversionCallCount.incrementAndGet()
        val converted = runCatching { nativeConvertDv7RpuToDv81(payload, mode) }
            .onFailure { Log.w(TAG, "Conversion failed: ${it.message}") }
            .getOrNull()
        if (converted != null && converted.isNotEmpty()) {
            conversionSuccessCount.incrementAndGet()
        }
        return converted
    }

    // Reusable output buffer for the non-allocating path. Sized for typical RPU NALs; grows on
    // demand if the native side reports a larger required size (see negative-return contract
    // in [convertDv7RpuToDv81NonAllocating]). Read by the transformer on the same thread that made
    // the call, immediately after it returns.
    @JvmField
    @Volatile
    var rpuOutBuffer = ByteArray(4096)

    /**
     * Converts a DV7 RPU NAL to DV8.1 into [rpuOutBuffer] with no per-call JVM allocation.
     *
     * Returns the number of bytes written (> 0) on success, or 0 on failure. If the native
     * output does not fit in [rpuOutBuffer], the native layer returns the negative required
     * size; we grow the buffer to that size and retry exactly once instead of truncating.
     */
    // FEL/MEL detection result codes.
    const val EL_TYPE_FEL = 2
    const val EL_TYPE_MEL = 1
    const val EL_TYPE_NONE = 0

    /**
     * Detects the profile-7 enhancement-layer type from an RPU NAL via
     * libdovi's pre-derived header field. Returns [EL_TYPE_FEL], [EL_TYPE_MEL],
     * [EL_TYPE_NONE] (parsed, not P7), -1 (parse failure) or -2 (bridge
     * unavailable). Intended to run once per stream on the first RPU.
     */
    fun detectRpuElType(sample: ByteArray, offset: Int, len: Int): Int {
        if (!isAvailable() || len <= 0) return -2
        return runCatching { nativeDetectRpuElType(sample, offset, len) }
            .onFailure { Log.w(TAG, "EL-type detection failed: ${it.message}") }
            .getOrDefault(-2)
    }

    /**
     * The DV RPU's static HDR mastering
     * metadata. Fields absent from the RPU are null (L6 is optional; the
     * source_*_pq pair is always present when DM metadata exists). Values are
     * raw as libdovi reports them: source_*_pq are 12-bit PQ codes; the L6
     * luminance and light-level values follow the ST2086/CTA-861.3 conventions.
     */
    data class RpuStaticMetadata(
        val sourceMinPq: Int,
        val sourceMaxPq: Int,
        val l6MinMasteringLuminance: Int?,
        val l6MaxMasteringLuminance: Int?,
        val maxCll: Int?,
        val maxFall: Int?
    ) {
        /**
         * A one-line human-readable summary for the Diagnostics card. MaxCLL /
         * MaxFALL are shown as libdovi reports them (nits); the mastering-display
         * peak is derived from [sourceMaxPq] via the ST 2084 (PQ) EOTF, which is
         * always available.
         */
        fun toDiagnosticLine(): String {
            val parts = ArrayList<String>(3)
            // A present L6 block with a zero content-light value means "unknown"
            // (common in WEB-DL masters), not literally zero nits - show a dash
            // rather than a misleading "0". A wholly absent L6 stays omitted.
            maxCll?.let { parts += "CLL ${if (it > 0) it.toString() else "-"}" }
            maxFall?.let { parts += "FALL ${if (it > 0) it.toString() else "-"}" }
            parts += "MDL ${pqCodeToNits(sourceMaxPq)} nits"
            return parts.joinToString(" · ")
        }

        private fun pqCodeToNits(pq12: Int): Int = Math.round(pq12ToNits(pq12)).toInt()
    }

    /** SMPTE ST 2084 (PQ) EOTF applied to a 12-bit RPU code value, in nits (cd/m²). */
    fun pq12ToNits(pq12: Int): Double {
        val e = pq12.coerceIn(0, 4095).toDouble() / 4095.0
        val m1 = 0.1593017578125
        val m2 = 78.84375
        val c1 = 0.8359375
        val c2 = 18.8515625
        val c3 = 18.6875
        val ep = Math.pow(e, 1.0 / m2)
        val num = (ep - c1).coerceAtLeast(0.0)
        val den = c2 - c3 * ep
        val l = if (den <= 0.0) 0.0 else Math.pow(num / den, 1.0 / m1)
        return l * 10000.0
    }

    /**
     * One RPU's display-management summary (see [getRpuDmInfo]). Absent values are null; [levels]
     * holds bit n for metadata level n (1..11), bit 12 for L254 and bit 13 for L255.
     */
    data class RpuDmInfo(
        /** 40 = CM v4.0 (L254 present), 29 = CM v2.9, null = no DM metadata in this RPU. */
        val cmVersion: Int?,
        val levels: Int,
        val l1MinPq: Int?,
        val l1MaxPq: Int?,
        val l1AvgPq: Int?,
        val l5Left: Int?,
        val l5Right: Int?,
        val l5Top: Int?,
        val l5Bottom: Int?,
        val guessedProfile: Int?,
        /** [EL_TYPE_FEL], [EL_TYPE_MEL], [EL_TYPE_NONE] or null. */
        val elType: Int?,
        val blBitDepth: Int?,
        val elBitDepth: Int?,
        /** True: the EL is coded at half the BL resolution (upsampled by the Dolby core). */
        val elHalfResolution: Boolean?,
        val level2Count: Int,
        val level8Count: Int,
        val level10Count: Int
    ) {
        fun hasLevel(level: Int): Boolean = when (level) {
            in 1..11 -> levels and (1 shl level) != 0
            254 -> levels and (1 shl 12) != 0
            255 -> levels and (1 shl 13) != 0
            else -> false
        }

        /** "L1 L2×4 L5 L6 L8×2 L9 L11 L254" (list levels with their block count when > 1). */
        fun levelsLabel(): String = buildList {
            for (n in 1..11) {
                if (!hasLevel(n)) continue
                val count = when (n) { 2 -> level2Count; 8 -> level8Count; 10 -> level10Count; else -> 1 }
                add(if (count > 1) "L$n×$count" else "L$n")
            }
            if (hasLevel(254)) add("L254")
            if (hasLevel(255)) add("L255")
        }.joinToString(" ")
    }

    @Volatile private var dmInfoUnavailable = false

    /**
     * Reads [RpuDmInfo] from an RPU NAL (type 62 incl. its 2-byte header, as for [detectRpuElType]).
     * Returns null on a stub build, when the bridge is unavailable or on parse failure. Cheap enough to run a few
     * times per second (one libdovi parse of a ~0.3-1 KB NAL).
     */
    fun getRpuDmInfo(sample: ByteArray, offset: Int, len: Int): RpuDmInfo? {
        if (offset < 0 || len <= 0 || len > 65_536 || offset > sample.size - len) return null
        if (!isAvailable() || len <= 0 || dmInfoUnavailable) return null
        val v = runCatching { nativeGetRpuDmInfo(sample, offset, len) }
            .onFailure {
                // A native library without this entry point (stale build): stop asking, log once.
                if (it is UnsatisfiedLinkError) dmInfoUnavailable = true
                Log.w(TAG, "RPU DM info read failed: ${it.message}")
            }
            .getOrNull() ?: return null
        if (v.size < 26) return null
        fun opt(i: Int): Int? = v[i].takeIf { it >= 0 }
        return RpuDmInfo(
            cmVersion = opt(0),
            levels = v[1].coerceAtLeast(0),
            l1MinPq = opt(2),
            l1MaxPq = opt(3),
            l1AvgPq = opt(4),
            l5Left = opt(5),
            l5Right = opt(6),
            l5Top = opt(7),
            l5Bottom = opt(8),
            guessedProfile = opt(15),
            elType = opt(16),
            blBitDepth = opt(17),
            elBitDepth = opt(18),
            elHalfResolution = opt(20)?.let { it == 1 },
            level2Count = v[21].coerceAtLeast(0),
            level8Count = v[22].coerceAtLeast(0),
            level10Count = v[23].coerceAtLeast(0)
        )
    }

    /**
     * Reads [RpuStaticMetadata] from an RPU NAL via the bundled libdovi 3.3.2
     * readers (no library bump). Returns null on a stub build, when the bridge
     * is unavailable, on parse failure, or when the RPU carries no DM metadata.
     * Intended to run once per stream on the first RPU, like [detectRpuElType].
     */
    fun getRpuStaticMetadata(sample: ByteArray, offset: Int, len: Int): RpuStaticMetadata? {
        if (!isAvailable() || len <= 0) return null
        val v = runCatching { nativeGetRpuStaticMetadata(sample, offset, len) }
            .onFailure { Log.w(TAG, "RPU metadata read failed: ${it.message}") }
            .getOrNull() ?: return null
        if (v.size < 6) return null
        fun opt(i: Int): Int? = v[i].takeIf { it >= 0 }
        return RpuStaticMetadata(
            sourceMinPq = v[0],
            sourceMaxPq = v[1],
            l6MinMasteringLuminance = opt(2),
            l6MaxMasteringLuminance = opt(3),
            maxCll = opt(4),
            maxFall = opt(5)
        )
    }

    fun convertDv7RpuToDv81NonAllocating(
        sample: ByteArray,
        offset: Int,
        len: Int,
        mode: Int = 1
    ): Int {        if (!isAvailable() || len <= 0) return 0
        conversionCallCount.incrementAndGet()
        var written = runCatching {
            nativeConvertDv7RpuToDv81NonAllocating(sample, offset, len, rpuOutBuffer, mode)
        }.onFailure { Log.w(TAG, "Non-allocating conversion failed: ${it.message}") }
            .getOrDefault(0)
        if (written < 0) {
            // Output didn't fit: grow the reusable buffer to the required size and retry once.
            val required = -written
            rpuOutBuffer = ByteArray(maxOf(required, rpuOutBuffer.size * 2))
            written = runCatching {
                nativeConvertDv7RpuToDv81NonAllocating(sample, offset, len, rpuOutBuffer, mode)
            }.onFailure { Log.w(TAG, "Non-allocating retry failed: ${it.message}") }
                .getOrDefault(0)
        }
        if (written > 0) {
            conversionSuccessCount.incrementAndGet()
        }
        return written
    }

    /**
     * Processes an HEVC video sample in native C++ layer.
     * Optionally converts or strips Dolby Vision RPUs, and strips HDR10+ SEIs.
     * Returns the size of the rewritten sample, or 0 if no changes were made.
     * If the output buffer was too small, grows the buffer and retries once.
     */
    fun processVideoSampleNonAllocating(
        sample: ByteArray,
        sampleLen: Int,
        nalFormat: Int, // 0 for Annex-B, 1 for Length-Delimited
        nalLengthFieldLength: Int,
        convertDovi: Boolean,
        doviMode: Int,
        doviProfile: Int,
        stripDoviRpu: Boolean,
        stripHdr10Plus: Boolean
    ): Int {
        if (!isAvailable() || sampleLen <= 0) return 0

        var written = runCatching {
            nativeProcessVideoSample(
                sample = sample,
                sampleLen = sampleLen,
                nalFormat = nalFormat,
                nalLengthFieldLength = nalLengthFieldLength,
                outBuffer = rpuOutBuffer,
                convertDovi = convertDovi,
                doviMode = doviMode,
                doviProfile = doviProfile,
                stripDoviRpu = stripDoviRpu,
                stripHdr10Plus = stripHdr10Plus
            )
        }.onFailure { Log.w(TAG, "nativeProcessVideoSample failed: ${it.message}") }
            .getOrDefault(0)

        if (written < 0) {
            val required = -written
            rpuOutBuffer = ByteArray(maxOf(required, rpuOutBuffer.size * 2))
            written = runCatching {
                nativeProcessVideoSample(
                    sample = sample,
                    sampleLen = sampleLen,
                    nalFormat = nalFormat,
                    nalLengthFieldLength = nalLengthFieldLength,
                    outBuffer = rpuOutBuffer,
                    convertDovi = convertDovi,
                    doviMode = doviMode,
                    doviProfile = doviProfile,
                    stripDoviRpu = stripDoviRpu,
                    stripHdr10Plus = stripHdr10Plus
                )
            }.onFailure { Log.w(TAG, "nativeProcessVideoSample retry failed: ${it.message}") }
                .getOrDefault(0)
        }
        return written
    }

    private fun loadNativeLibrary(): Boolean {
        if (!isNativeEnabledInBuild) {
            return false
        }
        return try {
            System.loadLibrary(LIB_NAME)
            Log.i(TAG, "Loaded native library: $LIB_NAME")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load native library $LIB_NAME: ${t.message}")
            false
        }
    }

    private fun String.safeHost(): String {
        return runCatching { android.net.Uri.parse(this).host ?: "unknown" }.getOrDefault("unknown")
    }

    @JvmStatic
    private external fun nativeGetBridgeVersion(): String

    @JvmStatic
    private external fun nativeIsConversionPathReady(): Boolean

    @JvmStatic
    private external fun nativeConvertDv7RpuToDv81(payload: ByteArray, mode: Int): ByteArray?

    // FEL/MEL detection.
    private external fun nativeDetectRpuElType(sample: ByteArray, offset: Int, length: Int): Int

    // RPU static HDR metadata read (MDL + MaxCLL/MaxFALL). Returns
    // int[6] or null; see [getRpuStaticMetadata].
    private external fun nativeGetRpuStaticMetadata(
        sample: ByteArray,
        offset: Int,
        length: Int
    ): IntArray?

    // RPU display-management summary for the HUD. Returns int[26] or null; see [getRpuDmInfo].
    private external fun nativeGetRpuDmInfo(
        sample: ByteArray,
        offset: Int,
        length: Int
    ): IntArray?

    @JvmStatic
    private external fun nativeConvertDv7RpuToDv81NonAllocating(
        sample: ByteArray,
        offset: Int,
        len: Int,
        outBuffer: ByteArray,
        mode: Int
    ): Int

    @JvmStatic
    private external fun nativeProcessVideoSample(
        sample: ByteArray,
        sampleLen: Int,
        nalFormat: Int,
        nalLengthFieldLength: Int,
        outBuffer: ByteArray,
        convertDovi: Boolean,
        doviMode: Int,
        doviProfile: Int,
        stripDoviRpu: Boolean,
        stripHdr10Plus: Boolean
    ): Int
}

