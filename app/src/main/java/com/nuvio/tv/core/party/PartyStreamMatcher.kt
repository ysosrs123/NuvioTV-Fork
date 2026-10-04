package com.nuvio.tv.core.party

import kotlin.math.abs

enum class PartyMatch { SAME_FILE, LIKELY_SAME, DIFFERENT, UNKNOWN }

object PartyStreamMatcher {
    private const val MIN_TELLING_SIZE_BYTES = 50L * 1024 * 1024
    const val DURATION_TOLERANCE_MS = 1_000L

    fun match(host: PartyFingerprint, candidate: PartyFingerprint): PartyMatch {
        if (host.isEmpty) return PartyMatch.UNKNOWN
        val hostHash = host.infoHash?.takeIf { it.isNotBlank() }
        val candidateHash = candidate.infoHash?.takeIf { it.isNotBlank() }
        if (hostHash != null && candidateHash != null) {
            if (!hostHash.equals(candidateHash, ignoreCase = true)) return otherEvidence(host, candidate, PartyMatch.DIFFERENT)
            return when {
                host.fileIdx == null || candidate.fileIdx == null -> otherEvidence(host, candidate, PartyMatch.LIKELY_SAME)
                host.fileIdx == candidate.fileIdx -> PartyMatch.SAME_FILE
                else -> PartyMatch.DIFFERENT
            }
        }
        return otherEvidence(host, candidate, PartyMatch.DIFFERENT)
    }

    private fun otherEvidence(host: PartyFingerprint, candidate: PartyFingerprint, fallback: PartyMatch): PartyMatch {
        val sameSize = host.sizeBytes != null && host.sizeBytes == candidate.sizeBytes
        val sameVideoHash = !host.videoHash.isNullOrBlank() && host.videoHash.equals(candidate.videoHash, ignoreCase = true)
        val sameName = !host.filename.isNullOrBlank() && host.filename.equals(candidate.filename, ignoreCase = true)
        return when {
            sameVideoHash && sameSize -> PartyMatch.SAME_FILE
            sameName && sameSize -> PartyMatch.SAME_FILE
            sameName || sameVideoHash -> PartyMatch.LIKELY_SAME
            sameSize && (host.sizeBytes ?: 0L) >= MIN_TELLING_SIZE_BYTES -> PartyMatch.LIKELY_SAME
            else -> fallback
        }
    }

    /** The index of the candidate to open without asking, or null when the guest should choose. */
    fun <T> pick(host: PartyFingerprint, candidates: List<T>, fingerprint: (T) -> PartyFingerprint): Int? {
        val index = candidates.indexOfFirst { match(host, fingerprint(it)) == PartyMatch.SAME_FILE }
        return index.takeIf { it >= 0 }
    }

    /** How much longer (positive) or shorter our copy runs than the host's, when that is worth a warning. */
    fun durationMismatchMs(hostDurationMs: Long?, ownDurationMs: Long?): Long? {
        if (hostDurationMs == null || ownDurationMs == null || hostDurationMs <= 0 || ownDurationMs <= 0) return null
        val difference = ownDurationMs - hostDurationMs
        return difference.takeIf { abs(it) > DURATION_TOLERANCE_MS }
    }
}
