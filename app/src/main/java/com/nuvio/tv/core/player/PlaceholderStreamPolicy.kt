package com.nuvio.tv.core.player

/**
 * Rejects a "stream" that is really a provider error card: debrid services and
 * scrapers sometimes answer with HTTP 200 and a small playable MP4 whose
 * content is an error message, so the only tell is the shape of the file.
 *
 * An absolute size floor is used rather than a ratio against the advertised
 * size, because a season pack that serves one episode is a legitimate small
 * ratio, and some sources declare no size at all.
 *
 * The floor only applies when the title's metadata says it should be long, so
 * clips and extras are never judged. With no runtime known, no verdict.
 *
 * Upstream: NuvioMedia/NuvioTV. Licensed under GPL-3.0.
 */
object PlaceholderStreamPolicy {

    /**
     * Files at or below this are not video anyone asked for. The largest placeholder
     * observed is 406 KB; the smallest plausible real episode at watchable quality is
     * tens of MB. 8 MB sits in the empty space between, ~20x above the observed
     * placeholder and well under any real content.
     */
    const val MIN_PLAUSIBLE_BYTES = 8L * 1024L * 1024L

    /**
     * The runtime guard. Only titles the metadata says run longer than this are
     * subject to the floor, so clips and extras are never judged.
     */
    const val MIN_GUARDED_RUNTIME_MS = 20L * 60L * 1000L

    /**
     * A file shorter than this, for a title whose metadata says it is feature length,
     * is an error card rather than the feature. Deliberately far below any real
     * content so a mis-scraped runtime cannot cause a rejection on its own.
     */
    const val MIN_PLAUSIBLE_DURATION_MS = 3L * 60L * 1000L

    /**
     * Fraction of the expected runtime below which a file is not the title, used only
     * alongside [MIN_PLAUSIBLE_DURATION_MS]; both must agree before a duration verdict
     * is returned.
     */
    const val MAX_IMPLAUSIBLE_DURATION_RATIO = 0.33

    sealed interface Verdict {
        /** Nothing suspicious, or not enough information to judge. Play it. */
        data object Accept : Verdict

        /** [reason] is logged and shown; a silent rejection would be worse than none. */
        data class Reject(val reason: Reason, val detail: String) : Verdict
    }

    enum class Reason {
        /** Content-Length is implausibly small for a feature-length title. */
        ImplausibleSize,

        /** Decoded duration is a small fraction of the expected runtime. */
        ImplausibleDuration
    }

    /**
     * @param contentLengthBytes actual bytes the server declared for the file being
     *   played, or null when unknown. NOT the advertised/label size.
     * @param durationMs decoded duration once known, or null before prepare.
     * @param expectedRuntimeMs runtime from the title's metadata, or null when unknown.
     */
    fun evaluate(
        contentLengthBytes: Long?,
        durationMs: Long?,
        expectedRuntimeMs: Long?
    ): Verdict {
        // No runtime, no verdict: without it we cannot tell a clip from an error card.
        if (expectedRuntimeMs == null || expectedRuntimeMs < MIN_GUARDED_RUNTIME_MS) {
            return Verdict.Accept
        }

        if (contentLengthBytes != null &&
            contentLengthBytes > 0L &&
            contentLengthBytes <= MIN_PLAUSIBLE_BYTES
        ) {
            return Verdict.Reject(
                reason = Reason.ImplausibleSize,
                detail = "${contentLengthBytes / 1024L} KB for a " +
                    "${expectedRuntimeMs / 60000L} min title"
            )
        }

        if (durationMs != null && durationMs > 0L) {
            val tooShortAbsolute = durationMs < MIN_PLAUSIBLE_DURATION_MS
            val tooShortRelative = durationMs < expectedRuntimeMs * MAX_IMPLAUSIBLE_DURATION_RATIO
            if (tooShortAbsolute && tooShortRelative) {
                return Verdict.Reject(
                    reason = Reason.ImplausibleDuration,
                    detail = "${durationMs / 1000L}s of a " +
                        "${expectedRuntimeMs / 60000L} min title"
                )
            }
        }

        return Verdict.Accept
    }
}
