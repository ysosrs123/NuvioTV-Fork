/*
 * Copyright (C) 2024-2026 NuvioTV contributors
 *
 * This file is part of a fork of NuvioTV (https://github.com/NuvioMedia/NuvioTV)
 * and is licensed under the GNU General Public License v3.0.
 */
package com.nuvio.tv.ui.screens.player

import android.os.Build

/**
 * One refusal of a passthrough AudioTrack by the platform for a bitstream encoding
 * it had claimed to support. isDirectPlaybackSupported and
 * AudioDeviceInfo.getEncodings() read the vendor policy and EDID, not the HAL's
 * open() result. Recorded when the audio-track-failure recovery engages on a
 * bitstream input.
 *
 * Used by Diagnostics (the Audio Chain Claims row), the passthrough policy
 * (auto-deny after enough refusals) and the device assessment. It carries the
 * encoding, the route it was refused on, a device fingerprint and a timestamp.
 */
internal data class AudioTrackRejection(
    /** Short label, e.g. "DTS-HD" - the same vocabulary as the Audio Chain Claims row. */
    val encoding: String,
    /** Output-route key at the time of refusal, e.g. "type:hdmi_arc|...", or null. */
    val routeKey: String?,
    /** "Manufacturer Model", so evidence never leaks across a device restore. */
    val deviceFingerprint: String,
    val atMs: Long
)

/**
 * Session-scoped in-memory store of [AudioTrackRejection]s. Session scope is enough for
 * the diagnostic (a failure, then opening the card, in the same session); the auto-deny
 * policy needs persistence on top.
 */
internal object AudioTrackRejectionLog {

    private val lock = Any()
    private val entries = mutableListOf<AudioTrackRejection>()

    fun deviceFingerprint(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /** Bitstream sample MIME to the short label used in the Audio Chain Claims row. */
    fun labelForMime(mime: String?): String? = when (mime) {
        "audio/ac3" -> "AC3"           // MimeTypes.AUDIO_AC3
        "audio/eac3" -> "EAC3"         // MimeTypes.AUDIO_E_AC3
        "audio/eac3-joc" -> "EAC3-JOC" // MimeTypes.AUDIO_E_AC3_JOC
        "audio/true-hd" -> "TrueHD"    // MimeTypes.AUDIO_TRUEHD
        "audio/vnd.dts" -> "DTS"       // MimeTypes.AUDIO_DTS
        "audio/vnd.dts.hd" -> "DTS-HD" // MimeTypes.AUDIO_DTS_HD
        else -> null
    }

    /** Records one refusal, de-duplicated on (encoding, routeKey), keeping the latest time. */
    fun record(encoding: String, routeKey: String?, atMs: Long) {
        synchronized(lock) {
            entries.removeAll { it.encoding == encoding && it.routeKey == routeKey }
            entries.add(AudioTrackRejection(encoding, routeKey, deviceFingerprint(), atMs))
        }
    }

    /** All refusals recorded this session, in insertion order. */
    fun snapshot(): List<AudioTrackRejection> = synchronized(lock) { entries.toList() }

    /** Distinct encodings refused on [routeKey], or on any route when [routeKey] is null. */
    fun encodingsRejectedOn(routeKey: String?): Set<String> = synchronized(lock) {
        entries.filter { routeKey == null || it.routeKey == routeKey }
            .map { it.encoding }
            .toSet()
    }

    /** Clears the log. Used by tests and by a future "re-detect chain" action. */
    fun reset() {
        synchronized(lock) {
            entries.clear()
        }
    }
}
