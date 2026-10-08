package com.nuvio.tv.data.trailer

data class TrailerPlaybackSource(
    val videoUrl: String,
    val audioUrl: String? = null,
    val validUntilMs: Long? = null
)
