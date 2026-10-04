package com.nuvio.tv.core.iptv

/** Intent must be explicit: URL suffix/MIME cannot establish live/file semantics. */
enum class PlaybackPurpose {
    VOD, LIVE_CHANNEL, PROVIDER_ARCHIVE, TIMESHIFT_READER, RECORDING;

    // Archive remains conservative until its adapter proves file/range semantics.
    val allowsVodNetworkOptimizations: Boolean get() = this == VOD
    val allowsUpstreamThumbnails: Boolean get() = this == VOD
}
