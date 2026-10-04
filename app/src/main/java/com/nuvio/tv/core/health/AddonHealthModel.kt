package com.nuvio.tv.core.health

/** Traffic-light level surfaced to the UI. */
enum class AddonHealthLevel { HEALTHY, DEGRADED, DOWN, UNKNOWN }

/**
 * Normalised per-request outcome. ADDON_MS ok/ok_inline -> SUCCESS, empty ->
 * EMPTY, timeout/error -> FAILURE; resolver Success -> SUCCESS, Error ->
 * FAILURE. Outcomes not the source's fault (cancelled, Stale) are not recorded.
 */
enum class HealthOutcome { SUCCESS, EMPTY, FAILURE }

data class HealthSample(val atMs: Long, val outcome: HealthOutcome, val latencyMs: Long)

data class HealthRecord(
    val samples: List<HealthSample> = emptyList(),
    val consecutiveFailures: Int = 0,
    val consecutiveSuccesses: Int = 0,
    val breakerOpenUntilMs: Long = 0L
)

/**
 * Passive add-on / resolver health model. Pure and Android-free. No background
 * polling: health is derived from a rolling window of the outcomes of requests
 * the app already makes, with a small hysteresis breaker.
 */
object AddonHealthModel {
    const val FAILURES_TO_OPEN = 2
    const val SUCCESSES_TO_CLOSE = 2
    const val COOLDOWN_MS = 5 * 60_000L
    const val MAX_SAMPLES = 20
    const val SLOW_LATENCY_MS = 8_000L

    /** Metadata pipeline: a details page slower than this reads DEGRADED. */
    const val METADATA_SLOW_LATENCY_MS = 4_000L
    const val DEGRADED_EMPTY_RATE = 0.5

    fun applySample(record: HealthRecord, sample: HealthSample): HealthRecord {
        val samples = (record.samples + sample).takeLast(MAX_SAMPLES)
        var failures = record.consecutiveFailures
        var successes = record.consecutiveSuccesses
        when (sample.outcome) {
            HealthOutcome.SUCCESS -> { successes += 1; failures = 0 }
            HealthOutcome.FAILURE -> { failures += 1; successes = 0 }
            HealthOutcome.EMPTY -> { failures = 0; successes = 0 }
        }
        var openUntil = record.breakerOpenUntilMs
        val now = sample.atMs
        when {
            openUntil > now -> Unit
            openUntil in 1..now -> if (successes >= SUCCESSES_TO_CLOSE) openUntil = 0L
            else -> if (failures >= FAILURES_TO_OPEN) openUntil = now + COOLDOWN_MS
        }
        return HealthRecord(samples, failures, successes, openUntil)
    }

    fun deriveLevel(
        record: HealthRecord,
        nowMs: Long,
        slowLatencyMs: Long = SLOW_LATENCY_MS
    ): AddonHealthLevel {
        if (record.samples.isEmpty()) return AddonHealthLevel.UNKNOWN
        if (record.breakerOpenUntilMs > nowMs) return AddonHealthLevel.DOWN
        if (record.breakerOpenUntilMs > 0L &&
            record.consecutiveSuccesses < SUCCESSES_TO_CLOSE
        ) {
            return AddonHealthLevel.DEGRADED
        }
        val last = record.samples.last()
        if (record.consecutiveFailures > 0 || last.outcome == HealthOutcome.FAILURE) {
            return AddonHealthLevel.DEGRADED
        }
        val emptyRate = record.samples.count { it.outcome == HealthOutcome.EMPTY }
            .toDouble() / record.samples.size
        if (emptyRate >= DEGRADED_EMPTY_RATE) return AddonHealthLevel.DEGRADED
        if (last.latencyMs > slowLatencyMs) return AddonHealthLevel.DEGRADED
        return AddonHealthLevel.HEALTHY
    }
}
