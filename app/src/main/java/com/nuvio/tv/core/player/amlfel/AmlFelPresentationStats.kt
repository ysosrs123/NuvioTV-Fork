package com.nuvio.tv.core.player.amlfel

/**
 * What the native FEL path can tell about the picture it shows. Frames go straight to the video layer there, so no
 * per-frame times or drop counts reach the app; the video clock (VPTS) is polled instead. Written on the playback
 * thread only, read by the report recorder.
 */
internal object AmlFelPresentationStats {
    const val NO_OFFSET = Int.MIN_VALUE

    /** Polls in which the shown picture did not move while playing, i.e. it stood still for at least one poll. */
    @Volatile var holds = 0
        private set

    /** Corrections of the hardware clock to the sound clock; each one can repeat or skip a frame. */
    @Volatile var clockCorrections = 0
        private set

    /** Shown picture minus the sound clock at the last poll, in ms; [NO_OFFSET] when not playing. */
    @Volatile var pictureOffsetMs = NO_OFFSET
        private set

    fun reset() {
        holds = 0
        clockCorrections = 0
        pictureOffsetMs = NO_OFFSET
    }

    fun noteHold() {
        holds++
    }

    fun noteClockCorrection() {
        clockCorrections++
    }

    fun notePictureOffset(ms: Int) {
        pictureOffsetMs = ms
    }

    fun clearPictureOffset() {
        pictureOffsetMs = NO_OFFSET
    }

    /** Shown picture minus the target position, both 90 kHz u32 clock values, in ms. */
    fun offsetMs(vpts90k: Long, target90k: Int): Int = (vpts90k.toInt() - target90k) / 90
}

/** Tells a poll whose picture stood still since the previous poll, counting only polls made while playing. */
internal class FelPictureHoldDetector {
    private var lastVpts = -1L
    private var lastShowing = false

    fun poll(vpts: Long, showing: Boolean): Boolean {
        if (vpts < 0L) return false
        val held = showing && lastShowing && vpts == lastVpts
        lastVpts = vpts
        lastShowing = showing
        return held
    }

    fun reset() {
        lastVpts = -1L
        lastShowing = false
    }
}
