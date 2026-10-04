/*
 * Copyright (C) 2024-2026 NuvioTV contributors
 *
 * This file is part of a fork of NuvioTV (https://github.com/NuvioMedia/NuvioTV)
 * and is licensed under the GNU General Public License v3.0.
 */
package com.nuvio.tv.ui.screens.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/**
 * What the platform says the audio chain can take, read once per sink build
 * and shown on the diagnostics page. A chain can advertise a format in its
 * EDID that the receiver cannot decode, so the claim is worth seeing.
 *
 * Read-only: it never opens an AudioTrack and never writes a setting.
 * [AudioTrack.isDirectPlaybackSupported] is the same question media3's
 * AudioCapabilities asks. The encoded-surround mode is included because in
 * AUTO the enabled set comes from EDID, in MANUAL the user chose it.
 */
object AudioCapabilityReport {

    /** Label to AudioFormat encoding, in the order they appear in the report. */
    private val PROBES: List<Pair<String, Int>> = buildList {
        add("AC3" to AudioFormat.ENCODING_AC3)
        add("EAC3" to AudioFormat.ENCODING_E_AC3)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add("EAC3-JOC" to AudioFormat.ENCODING_E_AC3_JOC)
        }
        add("TrueHD" to AudioFormat.ENCODING_DOLBY_TRUEHD)
        add("DTS" to AudioFormat.ENCODING_DTS)
        add("DTS-HD" to AudioFormat.ENCODING_DTS_HD)
    }

    @Volatile
    private var latestReport: String? = null

    /** The most recent report, or null if none has been captured yet. */
    val latest: String?
        get() = latestReport

    /**
     * Probes the platform and stores the result. Call from the sink build, where the same
     * MEDIA/MOVIE attributes the sink uses are in force.
     *
     * Any failure is swallowed into a short marker rather than propagated: a diagnostic
     * must never be able to break playback.
     */
    fun capture(context: Context) {
        latestReport = try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                "unavailable (API ${Build.VERSION.SDK_INT} < 29)"
            } else {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
                val supported = mutableListOf<String>()
                val absent = mutableListOf<String>()
                for ((label, ok) in probeDirectLabels(attributes)) {
                    (if (ok) supported else absent).add(label)
                }
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val maxPcmLabel = maxPcmChannelCount(audioManager)?.let { "$it ch" } ?: "unknown"
                format(supported, absent, negotiatedEncodings(audioManager), surroundModeName(audioManager), maxPcmLabel)
            }
        } catch (t: Throwable) {
            "unavailable (${t.javaClass.simpleName})"
        }
    }

    /** Clears the report. Used by tests. */
    internal fun reset() {
        latestReport = null
    }

    /**
     * What the platform claims the chain can take directly, one flag per format group
     * the per-format passthrough switches can act on. Same probe as [capture]; exposed
     * structurally so the Device Assessment can key its per-format rows on it rather
     * than parsing the display string.
     *
     * Null below API 29 (no isDirectPlaybackSupported) or when the probe throws. A
     * false here is the same answer media3's AudioCapabilities will give the sink, so
     * "absent" can never be wrong in the direction that loses working passthrough.
     */
    data class DirectSupport(
        val ac3: Boolean,
        val eac3: Boolean,
        val trueHd: Boolean,
        val dts: Boolean,
        val dtsHd: Boolean
    )

    fun probeDirectSupport(): DirectSupport? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            val m = probeDirectLabels(attributes).toMap()
            DirectSupport(
                ac3 = m["AC3"] == true,
                eac3 = m["EAC3"] == true,
                trueHd = m["TrueHD"] == true,
                dts = m["DTS"] == true,
                dtsHd = m["DTS-HD"] == true
            )
        }.getOrNull()
    }

    /** One isDirectPlaybackSupported call per [PROBES] entry, 48 kHz / 5.1, in order. */
    private fun probeDirectLabels(attributes: AudioAttributes): List<Pair<String, Boolean>> =
        PROBES.map { (label, encoding) ->
            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                .build()
            val ok = runCatching {
                AudioTrack.isDirectPlaybackSupported(format, attributes)
            }.getOrDefault(false)
            label to ok
        }

    /** Pure formatter, split out so the wording is unit-testable without a device. */
    internal fun format(
        supported: List<String>,
        absent: List<String>,
        negotiated: String,
        surroundMode: String,
        maxPcm: String
    ): String {
        val direct = if (supported.isEmpty()) "none" else supported.joinToString(" ")
        val missing = if (absent.isEmpty()) "none" else absent.joinToString(" ")
        return "Direct: $direct\nAbsent: $missing\nNegotiated: $negotiated\nSurround: $surroundMode\nMax PCM: $maxPcm"
    }

    /** HDMI/ARC/eARC output device types whose negotiated encodings we report. */
    private val HDMI_OUTPUT_TYPES = setOf(
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC
    )

    /**
     * The encodings the connected HDMI/ARC/eARC output actually negotiated, read from
     * [AudioDeviceInfo.getEncodings]. A different oracle from isDirectPlaybackSupported
     * above: that reads the vendor audio-policy profiles (static, and on some TVs still
     * advertising formats the licence-stripped HAL will not open); this reflects what the
     * HDMI link reported after EDID negotiation. When the two disagree - a TV that claims
     * DTS it cannot open - this is the row that tends to be honest. PCM16 is included
     * deliberately: its presence is what makes the line legible at a glance.
     *
     * Best-effort. getEncodings() returns an empty array when the platform does not
     * expose it, which is common on TVs; we say "unknown" then rather than imply nothing
     * is accepted.
     */
    private fun negotiatedEncodings(audioManager: AudioManager?): String {
        if (audioManager == null) return "unknown"
        val labels = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type in HDMI_OUTPUT_TYPES }
                .flatMap { it.encodings.asList() }
                .distinct()
                .mapNotNull(::encodingLabel)
        }.getOrNull().orEmpty()
        return if (labels.isEmpty()) "unknown" else labels.joinToString(" ")
    }

    /**
     * Highest PCM output channel count the connected HDMI/ARC/eARC chain
     * negotiates, read per encoding from [AudioDeviceInfo.getAudioProfiles]
     * and filtered to PCM formats. A 2 ch LPCM chain plays multichannel PCM
     * collapsed to stereo without any error, so only this read can tell the
     * assessment that Transcode-to-AC-3 suits the chain better.
     *
     * [AudioDeviceInfo.getChannelCounts] is not used: it returns the union
     * across every encoding, which reports 6 on a chain that takes 2 ch LPCM
     * but 5.1 compressed.
     *
     * API 31+; below that this returns null.
     */
    fun readMaxPcmChannelCount(context: Context): Int? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return maxPcmChannelCount(audioManager)
    }

    @SuppressLint("NewApi", "InlinedApi")
    private fun maxPcmChannelCount(audioManager: AudioManager?): Int? {
        if (audioManager == null) return null
        // getAudioProfiles() and the AudioProfile channel-mask getters are S+.
        // Below that there is no PCM-specific read (getChannelCounts unions
        // across encodings), so report unknown and let the row fall to VERIFY.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val pcmEncodings = setOf(
            AudioFormat.ENCODING_PCM_8BIT,
            AudioFormat.ENCODING_PCM_16BIT,
            AudioFormat.ENCODING_PCM_24BIT_PACKED,
            AudioFormat.ENCODING_PCM_32BIT,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        return runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type in HDMI_OUTPUT_TYPES }
                .flatMap { it.audioProfiles }
                .filter { it.format in pcmEncodings }
                // Both positional and index masks resolve to a channel count via
                // popcount; take the largest across every PCM profile's masks.
                .flatMap { profile ->
                    (profile.channelMasks.asList() + profile.channelIndexMasks.asList())
                        .map { mask -> Integer.bitCount(mask) }
                }
                .filter { it > 0 }
                .maxOrNull()
        }.getOrNull()
    }

    /** AudioFormat.ENCODING_* to the same short labels used in the direct/absent lists. */
    private fun encodingLabel(encoding: Int): String? = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM16"
        AudioFormat.ENCODING_AC3 -> "AC3"
        AudioFormat.ENCODING_E_AC3 -> "EAC3"
        AudioFormat.ENCODING_E_AC3_JOC -> "EAC3-JOC"
        AudioFormat.ENCODING_DOLBY_TRUEHD -> "TrueHD"
        AudioFormat.ENCODING_DTS -> "DTS"
        AudioFormat.ENCODING_DTS_HD -> "DTS-HD"
        else -> null
    }

    /**
     * The Android TV "surround sound" mode. NEVER/ALWAYS/AUTO/MANUAL are the platform's
     * own names; MANUAL means the user has ticked formats by hand, which is the only case
     * where the enabled set carries information the EDID does not.
     */
    private fun surroundModeName(audioManager: AudioManager?): String {
        if (audioManager == null) return "unknown"
        // getEncodedSurroundMode() is API 31+ (Android 12). On Android 10/11 it throws,
        // which would read as "unknown" and look like a genuinely unreadable value.
        // Attempt only where the getter exists, wrap it so an OEM off-by-one still
        // degrades cleanly, and say "n/a" when the platform cannot report the mode
        // rather than "unknown".
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "n/a (<API 31)"
        val mode = runCatching { audioManager.encodedSurroundMode }.getOrNull()
            ?: return "n/a"
        return when (mode) {
            AudioManager.ENCODED_SURROUND_OUTPUT_NEVER -> "NEVER"
            AudioManager.ENCODED_SURROUND_OUTPUT_ALWAYS -> "ALWAYS"
            AudioManager.ENCODED_SURROUND_OUTPUT_AUTO -> "AUTO"
            AudioManager.ENCODED_SURROUND_OUTPUT_MANUAL -> "MANUAL"
            else -> "unknown"
        }
    }
}
