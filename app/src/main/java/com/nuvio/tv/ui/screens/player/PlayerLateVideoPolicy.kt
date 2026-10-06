package com.nuvio.tv.ui.screens.player

/**
 * Watches the picture for a few seconds after a seek. On some boxes a second seek close behind the
 * first leaves the picture at a few frames per second until the next seek; one more seek to the
 * same spot brings it back.
 */
internal object PlayerLateVideoPolicy {
    const val CHECK_MS = 1_000L
    const val GRACE_MS = 2_000L
    const val BAD_SAMPLES = 3
    const val GOOD_SAMPLES = 3
    const val MAX_SAMPLES = 12
    const val MAX_IDLE_SAMPLES = 120
    const val MIN_DROPPED_PER_SAMPLE = 5
    const val MIN_BASELINE_FPS = 15f
    const val SLOW_FRACTION = 0.4f

    data class State(
        val bad: Int = 0,
        val good: Int = 0,
        val samples: Int = 0,
        val goodFrames: Int = 0,
        val idle: Int = 0,
    )

    /** Frames shown and dropped since the previous sample, one [CHECK_MS] apart. */
    data class Sample(
        val playing: Boolean,
        val rendered: Int,
        val dropped: Int,
        val baselineFps: Float?,
    )

    sealed class Decision {
        data object Continue : Decision()
        data class Healthy(val fps: Float) : Decision()
        data object Resync : Decision()
        data object Stop : Decision()
    }

    data class Step(val state: State, val decision: Decision)

    fun isBad(sample: Sample): Boolean {
        val mostlyDropped = sample.dropped >= MIN_DROPPED_PER_SAMPLE && sample.dropped >= sample.rendered
        val baseline = sample.baselineFps
        val slow = baseline != null && baseline >= MIN_BASELINE_FPS &&
            sample.rendered < baseline * SLOW_FRACTION * CHECK_MS / 1000f
        return mostlyDropped || slow
    }

    fun step(state: State, sample: Sample): Step {
        if (!sample.playing) {
            val idle = state.idle + 1
            val next = State(samples = state.samples, idle = idle)
            return Step(next, if (idle >= MAX_IDLE_SAMPLES) Decision.Stop else Decision.Continue)
        }
        val samples = state.samples + 1
        if (isBad(sample)) {
            val bad = state.bad + 1
            val next = State(bad = bad, samples = samples, idle = state.idle)
            return Step(next, if (bad >= BAD_SAMPLES) Decision.Resync else continueOrStop(samples))
        }
        val good = state.good + 1
        val goodFrames = state.goodFrames + sample.rendered
        val next = State(good = good, samples = samples, goodFrames = goodFrames, idle = state.idle)
        if (good >= GOOD_SAMPLES) {
            return Step(next, Decision.Healthy(goodFrames * 1000f / (good * CHECK_MS)))
        }
        return Step(next, continueOrStop(samples))
    }

    fun isHardwareDecoder(decoderName: String?): Boolean {
        val name = decoderName?.lowercase() ?: return false
        if (name.startsWith("omx.google.") || name.startsWith("c2.android.")) return false
        return name.startsWith("omx.") || name.startsWith("c2.")
    }

    private fun continueOrStop(samples: Int): Decision =
        if (samples >= MAX_SAMPLES) Decision.Stop else Decision.Continue
}
