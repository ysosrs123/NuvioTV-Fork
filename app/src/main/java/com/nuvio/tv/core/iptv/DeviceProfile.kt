package com.nuvio.tv.core.iptv

data class IptvDeviceProfile(val maxTiles: Int, val tileMaxHeight: Int, val tileBufferBytes: Int, val backgroundRows: Int) {
    init { require(maxTiles in 1..4 && tileMaxHeight > 0 && tileBufferBytes > 0 && backgroundRows > 0) }
}

fun iptvDeviceProfile(totalMemoryBytes: Long, lowRamDevice: Boolean, videoDecoderInstances: Int?): IptvDeviceProfile {
    val gib = totalMemoryBytes / (1024.0 * 1024 * 1024)
    val byMemory = when {
        lowRamDevice || gib < 1.6 -> 1
        gib < 2.6 -> 2
        gib < 3.6 -> 3
        else -> 4
    }
    val tiles = minOf(byMemory, videoDecoderInstances?.takeIf { it > 0 } ?: byMemory).coerceIn(1, 4)
    return IptvDeviceProfile(
        maxTiles = tiles,
        tileMaxHeight = if (tiles <= 2) 720 else 540,
        tileBufferBytes = if (gib < 2.6) 4 * 1024 * 1024 else 6 * 1024 * 1024,
        backgroundRows = when {
            lowRamDevice || gib < 1.6 -> 300
            gib < 2.6 -> 500
            else -> 1_000
        },
    )
}
