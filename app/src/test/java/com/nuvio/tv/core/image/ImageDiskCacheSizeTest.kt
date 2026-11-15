package com.nuvio.tv.core.image

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageDiskCacheSizeTest {

    private val mb = 1024L * 1024
    private val gb = 1024L * mb

    @Test
    fun plentyOfFreeStorage_getsOneGigabyte() {
        assertEquals(1 * gb, imageDiskCacheBytes(totalRamBytes = 3 * gb, freeStorageBytes = 64 * gb))
        assertEquals(1 * gb, imageDiskCacheBytes(totalRamBytes = 2 * gb, freeStorageBytes = 10 * gb + 1))
    }

    @Test
    fun fourToTenGigabytesFree_getsFiveHundredMegabytes() {
        assertEquals(500 * mb, imageDiskCacheBytes(totalRamBytes = 2 * gb, freeStorageBytes = 8_269L * mb))
        assertEquals(500 * mb, imageDiskCacheBytes(totalRamBytes = 4 * gb, freeStorageBytes = 10 * gb))
        assertEquals(500 * mb, imageDiskCacheBytes(totalRamBytes = 1 * gb, freeStorageBytes = 4 * gb))
    }

    @Test
    fun underFourGigabytesFree_staysAsBefore() {
        assertEquals(200 * mb, imageDiskCacheBytes(totalRamBytes = 2 * gb, freeStorageBytes = 4 * gb - 1))
        assertEquals(200 * mb, imageDiskCacheBytes(totalRamBytes = 3_299L * mb, freeStorageBytes = 0L))
        assertEquals(300 * mb, imageDiskCacheBytes(totalRamBytes = 3_300L * mb, freeStorageBytes = 0L))
    }
}
