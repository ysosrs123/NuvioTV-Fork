package com.nuvio.tv.ui.screens.player

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.MpvDirectDecodeMode

/**
 * With the copy route each decoded frame goes into memory and back to the graphics chip: about 600 MB a second for 4K
 * HDR. On the AM9 that halves even 1080p, and at 4K the Fire Stick runs out of memory. MPV can hand the decoder's
 * frames straight to the graphics chip instead. Only files with a video track take part.
 */
internal fun hasVideoTrack(width: Int?, height: Int?): Boolean = (width ?: 0) > 0 || (height ?: 0) > 0

/** The decoder to ask mpv for, or null to keep the "Hardware decoding" setting. */
internal fun mpvHwdecForDirectMode(
    mode: MpvDirectDecodeMode,
    directRefusedOnThisBox: Boolean,
    softwareDecodeForced: Boolean
): String? = when {
    softwareDecodeForced -> null
    mode == MpvDirectDecodeMode.OFF -> null
    mode == MpvDirectDecodeMode.AUTOMATIC && directRefusedOnThisBox -> null
    else -> MPV_HWDEC_DIRECT
}

internal enum class DirectDecodeOutcome { WORKED, REFUSED, WAITING }

/**
 * A refusal says something about the box only when the same file was running on the copy decoder before: the chip
 * decodes it, just not straight to the graphics chip. A file no hardware decoder takes (H.264 level 6.2 on Amlogic,
 * AV1 without a decoder) was on software already and says nothing about the direct route.
 */
internal fun isCountableDirectRefusal(valueBeforeRequest: String?): Boolean = valueBeforeRequest?.trim() == MPV_HWDEC_COPY

/**
 * Reads mpv's `hwdec-current` after the direct decoder was asked for. With only the direct decoder allowed mpv
 * cannot pick the copy decoder, so the copy decoder or the value from before the request is left over from the
 * previous decoder; software (or anything else) means the box refused the direct one.
 */
internal fun directDecodeOutcome(hwdecCurrent: String?, valueBeforeRequest: String?): DirectDecodeOutcome {
    val now = hwdecCurrent?.trim().orEmpty()
    return when {
        now == MPV_HWDEC_DIRECT -> DirectDecodeOutcome.WORKED
        now.isEmpty() || now == MPV_HWDEC_COPY || now == valueBeforeRequest?.trim() -> DirectDecodeOutcome.WAITING
        else -> DirectDecodeOutcome.REFUSED
    }
}

/** After this many refusals on files the chip decodes, Automatic keeps video on the copy route on this box. */
internal const val DIRECT_REFUSALS_TO_REMEMBER = 2

internal fun isDirectRefusedOnBox(refusals: Int): Boolean = refusals >= DIRECT_REFUSALS_TO_REMEMBER

/** Refusals count once per file, so reopening the same file does not mark the box. */
internal fun nextDirectRefusalCount(storedCount: Int, lastCountedFile: String?, file: String?): Int =
    if (file != null && file == lastCountedFile) storedCount else storedCount + 1

/**
 * Counts on this box, for every profile, how often the direct decoder was refused, so Automatic starts video on the copy
 * route at once after the second time. A new Arc version starts again from zero, since its MPV may behave
 * differently.
 */
internal object MpvDirectDecodeMemory {
    private const val PREFS = "mpv_direct_decoding"
    private const val KEY_VERSION = "direct_refused_version"
    private const val KEY_COUNT = "direct_refused_count"
    private const val KEY_LAST_FILE = "direct_refused_last_file"

    fun isRefused(context: Context): Boolean = isDirectRefusedOnBox(refusals(context))

    /** Returns true once the box counts as refusing the direct decoder. */
    fun recordRefusal(context: Context, file: String?): Boolean = runCatching {
        val prefs = prefs(context)
        val sameVersion = prefs.getInt(KEY_VERSION, -1) == BuildConfig.VERSION_CODE
        val lastFile = if (sameVersion) prefs.getString(KEY_LAST_FILE, null) else null
        val count = nextDirectRefusalCount(refusals(context), lastFile, file)
        prefs.edit()
            .putInt(KEY_VERSION, BuildConfig.VERSION_CODE)
            .putInt(KEY_COUNT, count)
            .putString(KEY_LAST_FILE, file)
            .apply()
        isDirectRefusedOnBox(count)
    }.getOrDefault(false)

    /** The direct decoder worked on this box: earlier refusals were about those files, not the box. */
    fun recordWorked(context: Context) {
        runCatching { prefs(context).edit().remove(KEY_COUNT).remove(KEY_LAST_FILE).apply() }
    }

    private fun refusals(context: Context): Int = runCatching {
        val prefs = prefs(context)
        if (prefs.getInt(KEY_VERSION, -1) == BuildConfig.VERSION_CODE) prefs.getInt(KEY_COUNT, 0) else 0
    }.getOrDefault(0)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

internal const val MPV_HWDEC_DIRECT = "mediacodec"
private const val MPV_HWDEC_COPY = "mediacodec-copy"

internal fun PlayerRuntimeController.applyMpvDirectDecodeMode(view: NuvioMpvSurfaceView) {
    view.applyDirectDecodeMode(mpvDirectDecodeModeSetting, MpvDirectDecodeMemory.isRefused(context))
    view.onDirectDecodeRefused = { file -> MpvDirectDecodeMemory.recordRefusal(context, file) }
    view.onDirectDecodeWorked = { MpvDirectDecodeMemory.recordWorked(context) }
}
