package com.nuvio.tv.core.player

/** Which native Dolby Vision Profile 7 FEL backend drives the current playback. */
internal enum class NativeFelBackend {
    NONE,
    /** AM9 test kernel: amstream dual-layer port (core/player/amlfel). */
    AM9_AMSTREAM,
    /** Rooted Fire TV Cube 3 + Fire TV AV module: libdvfelplay + /dev/dvfel (core/player/dvfel). */
    GAZELLE_DVFEL,
}
