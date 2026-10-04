package com.nuvio.tv.data.trailer

/** Why YouTube refuses a video. [SIGN_IN_REQUIRED] can also be a passing bot check, so it is worth a retry. */
enum class YouTubeUnplayableReason {
    AGE_RESTRICTED,
    UNAVAILABLE,
    SIGN_IN_REQUIRED;

    val isDefinite: Boolean get() = this != SIGN_IN_REQUIRED
}

private val AGE_REASON_REGEX = Regex("\\bage\\b|inappropriate")

internal fun youTubeUnplayableReasonOf(status: String?, reason: String?): YouTubeUnplayableReason? {
    val text = reason.orEmpty().lowercase()
    val mentionsAge = AGE_REASON_REGEX.containsMatchIn(text)
    return when (status) {
        "AGE_VERIFICATION_REQUIRED", "AGE_CHECK_REQUIRED" -> YouTubeUnplayableReason.AGE_RESTRICTED
        "ERROR", "UNPLAYABLE" ->
            if (mentionsAge) YouTubeUnplayableReason.AGE_RESTRICTED else YouTubeUnplayableReason.UNAVAILABLE
        "LOGIN_REQUIRED" -> when {
            mentionsAge -> YouTubeUnplayableReason.AGE_RESTRICTED
            "private" in text -> YouTubeUnplayableReason.UNAVAILABLE
            else -> YouTubeUnplayableReason.SIGN_IN_REQUIRED
        }
        else -> null
    }
}

/** One answer for a video from what each client said; a single sign-in answer keeps the result retryable. */
internal fun combineYouTubeUnplayableReasons(reasons: List<YouTubeUnplayableReason>): YouTubeUnplayableReason? =
    when {
        reasons.isEmpty() -> null
        YouTubeUnplayableReason.SIGN_IN_REQUIRED in reasons -> YouTubeUnplayableReason.SIGN_IN_REQUIRED
        YouTubeUnplayableReason.AGE_RESTRICTED in reasons -> YouTubeUnplayableReason.AGE_RESTRICTED
        else -> YouTubeUnplayableReason.UNAVAILABLE
    }
