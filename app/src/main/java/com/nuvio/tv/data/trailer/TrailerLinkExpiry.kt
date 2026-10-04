package com.nuvio.tv.data.trailer

import java.time.Duration
import java.time.Instant

internal val TRAILER_LINK_TTL: Duration = Duration.ofHours(3)
internal val TRAILER_LINK_EXPIRY_MARGIN: Duration = Duration.ofMinutes(15)

private val QUERY_EXPIRE_REGEX = Regex("[?&]expires?=(\\d+)", RegexOption.IGNORE_CASE)
private val PATH_EXPIRE_REGEX = Regex("/expire/(\\d+)(?:/|$)")

/** YouTube links carry `expire=` (or `/expire/N/`), IMDb CloudFront links carry `Expires=`. */
internal fun trailerUrlExpiresAt(url: String): Instant? {
    val match = QUERY_EXPIRE_REGEX.find(url) ?: PATH_EXPIRE_REGEX.find(url) ?: return null
    val value = match.groupValues[1].toLongOrNull() ?: return null
    return if (value > 100_000_000_000L) Instant.ofEpochMilli(value) else Instant.ofEpochSecond(value)
}

internal fun TrailerPlaybackSource.expiresAt(): Instant? =
    listOfNotNull(trailerUrlExpiresAt(videoUrl), audioUrl?.let(::trailerUrlExpiresAt)).minOrNull()

internal fun isTrailerUrlExpired(url: String, now: Instant): Boolean {
    val expiresAt = trailerUrlExpiresAt(url) ?: return false
    return !now.isBefore(expiresAt.minus(TRAILER_LINK_EXPIRY_MARGIN))
}

internal fun isTrailerSourceStale(source: TrailerPlaybackSource, resolvedAt: Instant, now: Instant): Boolean {
    val expiresAt = source.expiresAt()
    return if (expiresAt != null) {
        !now.isBefore(expiresAt.minus(TRAILER_LINK_EXPIRY_MARGIN))
    } else {
        Duration.between(resolvedAt, now) >= TRAILER_LINK_TTL
    }
}
