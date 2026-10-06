package com.nuvio.tv.core.iptv

data class IptvDeviceProfile(val maxTiles: Int, val tileBufferBytes: Int, val backgroundRows: Int, val decodeBudget: Long? = null) {
    init { require(maxTiles in 1..4 && tileBufferBytes > 0 && backgroundRows > 0 && (decodeBudget == null || decodeBudget > 0)) }
}

fun iptvDeviceProfile(totalMemoryBytes: Long, lowRamDevice: Boolean, videoDecoderInstances: Int?, decodeBudget: Long? = null): IptvDeviceProfile {
    val gib = totalMemoryBytes / (1024.0 * 1024 * 1024)
    val byMemory = when {
        lowRamDevice || gib < 1.6 -> 1
        gib < 2.6 -> 2
        gib < 3.6 -> 3
        else -> 4
    }
    val byBudget = decodeBudget?.let { (it / multiviewPixelRate(MULTIVIEW_RUNGS.first())).toInt().coerceAtLeast(1) } ?: byMemory
    val tiles = minOf(byMemory, videoDecoderInstances?.takeIf { it > 0 } ?: byMemory, byBudget).coerceIn(1, 4)
    return IptvDeviceProfile(
        maxTiles = tiles,
        tileBufferBytes = if (gib < 2.6) 4 * 1024 * 1024 else 6 * 1024 * 1024,
        backgroundRows = when {
            lowRamDevice || gib < 1.6 -> 300
            gib < 2.6 -> 500
            else -> 1_000
        },
        decodeBudget = decodeBudget,
    )
}
