package com.nuvio.tv.core.player.dvfel

/** Process-wide state of the Gazelle FEL session, written on the playback thread and read by the UI. */
internal object GazelleFelState {
    /** [DvFelPlay.MODE_FEL], [DvFelPlay.MODE_CONVERT], or -1 when no session is open. */
    @Volatile var mode: Int = -1
        private set
    @Volatile var elType: Int = DvFelPlay.EL_NONE
        private set
    /**
     * Last snapshot of the dvfp_stats counters: 0 composed, 1 mapped_only, 2 passthrough, 3 no_el_skipped,
     * 4 el_decoded, 5 el_inputs, 6 el_outputs, 7 el_overflow, 8 no_crc_match, 9 stale.
     */
    @Volatile var stats: LongArray? = null

    val isFelRunning: Boolean get() = mode == DvFelPlay.MODE_FEL

    fun onOpened(mode: Int, elType: Int) {
        this.mode = mode
        this.elType = elType
        stats = null
    }

    fun onClosed() {
        mode = -1
    }
}
