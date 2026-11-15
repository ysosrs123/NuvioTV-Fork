package com.nuvio.tv.core.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.view.Display

/**
 * Decides what to do with a Dolby Vision stream at the codec selector. It only intervenes
 * where there's a concrete fix; everything else falls through to default ExoPlayer behavior.
 *
 * Convert DV7 to DV8.1 when:
 *  - the display is DV-capable (any device, whatever DV profiles its decoder lists);
 *  - the display is HDR10-only but a DV8.1 decoder exists — convert and let it emit HDR10.
 *
 * Other HDR10-only displays strip to the HEVC base layer. This policy never passes DV7
 * through untouched; only the Off (native) and Amlogic native FEL settings do.
 */
object DolbyVisionBaseLayerPolicy {

    enum class Decision {
        /** Pass DV7 through untouched. Not chosen by [resolveFromCapabilities]. */
        NATIVE_DV7,
        /** Convert DV7 RPU to DV8.1 with libdovi, then feed to a DV-aware decoder.
         *  Used on every DV display, and on Samsung / Fire TV / Xiaomi with HDR10 displays. */
        CONVERT_TO_DV81,
        /** Display supports HDR10/HDR10+. Strip DV, play HEVC base layer. */
        STRIP_TO_HDR10,
        /** Display capabilities unknown (pre-N or null caps). Strip defensively. */
        STRIP_BEST_EFFORT,
        /** Display known to be SDR-only or HLG-only. Strip and let renderer tonemap. */
        STRIP_AND_TONEMAP
    }

    data class Result(
        val decision: Decision,
        val hdrCapsKnown: Boolean,
        val displayDv: Boolean,
        val displayHdr10: Boolean,
        val displayHdr10Plus: Boolean,
        val displayHlg: Boolean,
        val codecSupportsDvheDtb: Boolean,
        val codecSupportsDvheStn: Boolean,
        val codecSupportsDvheSt: Boolean,
        val isAmazonFireTv: Boolean,
        val isSamsung: Boolean,
        val isXiaomi: Boolean,
        val bridgeReady: Boolean,
        val apiLevel: Int
    ) {
        /** True when DV7 streams should be diverted away from the native DV7 decoder path. */
        val divertsFromNativeDv7: Boolean
            get() = decision != Decision.NATIVE_DV7

        /**
         * True when a DV display converts on a device whose decoder does not list Profile 8
         * alone (Xiaomi without Profile 7 excepted). These convert the way manual Convert to
         * DV8.1 does (mode 2 with fallback to mode 1); the rest keep Auto's mode 1.
         */
        val usesManualConversion: Boolean
            get() = decision == Decision.CONVERT_TO_DV81 && displayDv &&
                (codecSupportsDvheDtb || (!codecSupportsDvheSt && !isXiaomi))

        /** True when DV7 should be mapped to its HEVC base layer at the codec selector. */
        val mapToHevc: Boolean
            get() = when (decision) {
                Decision.STRIP_TO_HDR10,
                Decision.STRIP_BEST_EFFORT,
                Decision.STRIP_AND_TONEMAP -> true
                else -> false
            }
    }

    fun resolveFromCapabilities(
        hdrCapsKnown: Boolean,
        displayDv: Boolean,
        displayHdr10: Boolean,
        displayHdr10Plus: Boolean,
        displayHlg: Boolean,
        codecSupportsDvheDtb: Boolean,
        codecSupportsDvheStn: Boolean,
        codecSupportsDvheSt: Boolean,
        isAmazonFireTv: Boolean,
        isSamsung: Boolean,
        isXiaomi: Boolean,
        bridgeReady: Boolean,
        apiLevel: Int
    ): Result {
        val displayHdr10Family = displayHdr10 || displayHdr10Plus

        val decision = when {
            !hdrCapsKnown -> Decision.STRIP_BEST_EFFORT

            // No Android decoder plays both DV7 layers, and a decoder that lists Profile 7
            // can still stall on a single-track DV7 file, so a DV display always converts.
            // A DV decoder that does not list Profile 8 is ranked after the HEVC decoder
            // for the converted stream, which then plays as HDR10.
            displayDv && bridgeReady -> Decision.CONVERT_TO_DV81

            displayDv -> Decision.STRIP_TO_HDR10

            // Samsung HDR10 fallback (User 3): no DV display but a DV81 decoder is
            // available. Convert DV7 to DV81 so the decoder emits HDR10. Without this
            // branch the HEVC base layer fallback fails on some MTK SoCs.
            // Also fires for Amazon devices on HDR10-only TVs (Karat + HDR10 TV).
            displayHdr10Family && bridgeReady && codecSupportsDvheSt && (isSamsung || isAmazonFireTv) ->
                Decision.CONVERT_TO_DV81

            // Xiaomi box on HDR10-only TV: convert so the hidden decoder can emit HDR10.
            // Without this, STRIP_TO_HDR10 fires and the HEVC fallback may fail on some
            // Amlogic firmware (same class of issue as Samsung HDR10 path).
            displayHdr10Family && isXiaomi && bridgeReady -> Decision.CONVERT_TO_DV81

            // HDR10/HDR10+ display on a non-Amazon, non-Samsung, non-Xiaomi device.
            // Includes Google TV Streamer on HDR10 TV. Strip DV, play HEVC base layer.
            displayHdr10Family -> Decision.STRIP_TO_HDR10

            else -> Decision.STRIP_AND_TONEMAP
        }

        return Result(
            decision = decision,
            hdrCapsKnown = hdrCapsKnown,
            displayDv = displayDv,
            displayHdr10 = displayHdr10,
            displayHdr10Plus = displayHdr10Plus,
            displayHlg = displayHlg,
            codecSupportsDvheDtb = codecSupportsDvheDtb,
            codecSupportsDvheStn = codecSupportsDvheStn,
            codecSupportsDvheSt = codecSupportsDvheSt,
            isAmazonFireTv = isAmazonFireTv,
            isSamsung = isSamsung,
            isXiaomi = isXiaomi,
            bridgeReady = bridgeReady,
            apiLevel = apiLevel
        )
    }

    fun resolve(context: Context, bridgeReady: Boolean): Result {
        val apiLevel = Build.VERSION.SDK_INT
        val manufacturer = Build.MANUFACTURER
        val isAmazonFireTv = manufacturer.equals("Amazon", ignoreCase = true)
        val isSamsung = manufacturer.equals("Samsung", ignoreCase = true)
        val isXiaomi = manufacturer.equals("Xiaomi", ignoreCase = true)

        if (apiLevel < Build.VERSION_CODES.N) {
            return resolveFromCapabilities(
                hdrCapsKnown = false,
                displayDv = false,
                displayHdr10 = false,
                displayHdr10Plus = false,
                displayHlg = false,
                codecSupportsDvheDtb = false,
                codecSupportsDvheStn = false,
                codecSupportsDvheSt = false,
                isAmazonFireTv = isAmazonFireTv,
                isSamsung = isSamsung,
                isXiaomi = isXiaomi,
                bridgeReady = bridgeReady,
                apiLevel = apiLevel
            )
        }

        @Suppress("DEPRECATION")
        val hdrTypes: IntArray? = runCatching {
            val dm = context.getSystemService(DisplayManager::class.java)
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            display?.hdrCapabilities?.supportedHdrTypes
        }.getOrNull()

        val hdrCapsKnown = hdrTypes != null
        val displayDv = hdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) == true
        val displayHdr10 = hdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) == true
        val displayHdr10Plus =
            hdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS) == true
        val displayHlg = hdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HLG) == true

        val decoderProfiles = queryDvDecoderProfileSupport()

        return resolveFromCapabilities(
            hdrCapsKnown = hdrCapsKnown,
            displayDv = displayDv,
            displayHdr10 = displayHdr10,
            displayHdr10Plus = displayHdr10Plus,
            displayHlg = displayHlg,
            codecSupportsDvheDtb = decoderProfiles.dvheDtb,
            codecSupportsDvheStn = decoderProfiles.dvheStn,
            codecSupportsDvheSt = decoderProfiles.dvheSt,
            isAmazonFireTv = isAmazonFireTv,
            isSamsung = isSamsung,
            isXiaomi = isXiaomi,
            bridgeReady = bridgeReady,
            apiLevel = apiLevel
        )
    }

    private data class DvDecoderProfileSupport(
        val dvheDtb: Boolean,   // P7
        val dvheStn: Boolean,   // P5
        val dvheSt: Boolean     // P8
    )

    /**
     * Enumerates the platform's decoders and reports which DV profiles they advertise.
     * Returns all-false on any error or pre-N devices.
     */
    private fun queryDvDecoderProfileSupport(): DvDecoderProfileSupport {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return DvDecoderProfileSupport(false, false, false)
        }
        return runCatching {
            var dvheDtb = false
            var dvheStn = false
            var dvheSt = false
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                val supportsDvMime = info.supportedTypes.any { type ->
                    type.equals(DOLBY_VISION_MIME, ignoreCase = true)
                }
                if (!supportsDvMime) continue
                val caps: MediaCodecInfo.CodecCapabilities = runCatching {
                    info.getCapabilitiesForType(DOLBY_VISION_MIME)
                }.getOrNull() ?: continue
                val profileLevels = caps.profileLevels ?: continue
                for (profileLevel in profileLevels) {
                    when (profileLevel.profile) {
                        DvheDtbProfile -> dvheDtb = true
                        DvheStnProfile -> dvheStn = true
                        DvheStProfile -> dvheSt = true
                    }
                }
            }
            DvDecoderProfileSupport(dvheDtb, dvheStn, dvheSt)
        }.getOrDefault(DvDecoderProfileSupport(false, false, false))
    }

    private val profile5Decoder: Boolean by lazy { queryDvDecoderProfileSupport().dvheStn }

    /** True when the box has a decoder for Dolby Vision Profile 5. Looked up once. */
    fun decodesProfile5(): Boolean = profile5Decoder

    private const val DOLBY_VISION_MIME = "video/dolby-vision"

    /** Android constant for DV Profile 7 (dual-layer FEL/MEL). Added in API 24. */
    private const val DvheDtbProfile = CodecProfileLevel.DolbyVisionProfileDvheDtb

    /** Android constant for DV Profile 5 (single-layer DV-only HEVC). Added in API 24. */
    private const val DvheStnProfile = CodecProfileLevel.DolbyVisionProfileDvheStn

    /** Android constant for DV Profile 8 (single-layer HDR10-compatible HEVC). Added in API 24. */
    private const val DvheStProfile = CodecProfileLevel.DolbyVisionProfileDvheSt
}