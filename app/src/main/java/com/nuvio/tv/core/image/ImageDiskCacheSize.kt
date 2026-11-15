package com.nuvio.tv.core.image

private const val MB = 1024L * 1024
private const val GB = 1024L * MB

/**
 * Disk room for posters and backdrops: 1 GB with more than 10 GB free, 500 MB with 4 GB or more free, otherwise
 * 300 MB, or 200 MB under ~3.3 GB of RAM.
 */
fun imageDiskCacheBytes(totalRamBytes: Long, freeStorageBytes: Long): Long = when {
    freeStorageBytes > 10 * GB -> 1 * GB
    freeStorageBytes >= 4 * GB -> 500 * MB
    totalRamBytes >= 3_300L * MB -> 300 * MB
    else -> 200 * MB
}
