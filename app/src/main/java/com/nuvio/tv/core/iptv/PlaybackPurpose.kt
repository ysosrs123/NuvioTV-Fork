package com.nuvio.tv.core.iptv

enum class PlaybackPurpose {
    VOD, LIVE_CHANNEL, PROVIDER_ARCHIVE, TIMESHIFT_READER, RECORDING;

    val allowsVodNetworkOptimizations: Boolean get() = this == VOD
    val allowsUpstreamThumbnails: Boolean get() = this == VOD
}
