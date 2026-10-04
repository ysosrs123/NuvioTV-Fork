package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.player.PlaceholderStreamPolicy

/**
 * File-local. PlayerRuntimeController.TAG lives in a companion object and is not
 * in scope for a top-level extension function, while a top-level private TAG in
 * PlayerRuntimeControllerTorrent.kt is a resolution candidate for this package and
 * then fails the visibility check. Declaring our own is the pattern that file uses.
 * The string is deliberately unchanged so existing logcat filters keep working.
 */
private const val TAG = "PlayerViewModel"

/**
 * Evaluates the placeholder gate once per play and logs its inputs and verdict.
 *
 * PlaceholderStreamPolicy needs an expected runtime, and runtime is only present
 * when the stream screen resolved one before the press. On the direct-autoplay
 * path the overlay may hand off before loadMetadataIfNeeded completes, which
 * leaves the policy inert, silently, since a null runtime means "do not judge".
 * The log line shows, for every entry path (direct autoplay, cached link reuse,
 * manual pick, binge prefetch), whether the runtime arrived.
 *
 * Nothing here changes playback; the caller acts on a Reject through
 * [rejectPlaceholderStream].
 *
 * Upstream: NuvioMedia/NuvioTV. Licensed under GPL-3.0.
 */
internal fun PlayerRuntimeController.probePlaceholderStream(player: ExoPlayer): PlaceholderStreamPolicy.Verdict {
    if (placeholderProbeDone) return PlaceholderStreamPolicy.Verdict.Accept
    placeholderProbeDone = true

    val contentLengthBytes = PlaybackByteCounter.contentLengthFor(currentStreamUrl)
    val durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
    val expectedRuntimeMs = expectedRuntimeMinutes?.let { it * 60_000L }

    val verdict = PlaceholderStreamPolicy.evaluate(
        contentLengthBytes = contentLengthBytes,
        durationMs = durationMs,
        expectedRuntimeMs = expectedRuntimeMs
    )

    val outcome = when (verdict) {
        is PlaceholderStreamPolicy.Verdict.Accept ->
            if (expectedRuntimeMs == null) "ACCEPT(no-runtime: gate inert)" else "ACCEPT"
        is PlaceholderStreamPolicy.Verdict.Reject ->
            "WOULD_REJECT(${verdict.reason}: ${verdict.detail})"
    }

    Log.i(
        TAG,
        "PLACEHOLDER_PROBE verdict=$outcome " +
            "contentLength=${contentLengthBytes ?: -1} " +
            "durationMs=${durationMs ?: -1} " +
            "expectedRuntimeMin=${expectedRuntimeMinutes ?: -1}"
    )

    return verdict
}
