package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceProfileTest {
    private val gib = 1024L * 1024 * 1024

    @Test fun memoryAndDecodersLimitPictures() {
        assertEquals(1, iptvDeviceProfile(2 * gib, lowRamDevice = true, videoDecoderInstances = 16).maxTiles)
        assertEquals(1, iptvDeviceProfile(gib * 3 / 2, lowRamDevice = false, videoDecoderInstances = null).maxTiles)
        assertEquals(2, iptvDeviceProfile(2 * gib - 100 * 1024 * 1024, lowRamDevice = false, videoDecoderInstances = 16).maxTiles)
        assertEquals(3, iptvDeviceProfile(3 * gib - 200 * 1024 * 1024, lowRamDevice = false, videoDecoderInstances = 16).maxTiles)
        assertEquals(4, iptvDeviceProfile(4 * gib, lowRamDevice = false, videoDecoderInstances = 16).maxTiles)
        assertEquals(2, iptvDeviceProfile(4 * gib, lowRamDevice = false, videoDecoderInstances = 2).maxTiles)
        assertEquals(4, iptvDeviceProfile(4 * gib, lowRamDevice = false, videoDecoderInstances = 0).maxTiles)
    }

    @Test fun aSmallDecodeBudgetLimitsPictures() {
        assertEquals(1, iptvDeviceProfile(4 * gib, false, 16, decodeBudget = multiviewPixelRate(360)).maxTiles)
        assertEquals(4, iptvDeviceProfile(4 * gib, false, 16, decodeBudget = multiviewPixelRate(2160, 60)).maxTiles)
        val profile = iptvDeviceProfile(4 * gib, false, 9, decodeBudget = multiviewPixelRate(1080) * 4, hevcBudget = 7680L * 4320 * 60)
        assertEquals(MultiviewDecode(multiviewPixelRate(1080) * 4, 7680L * 4320 * 60), profile.decode)
    }

    @Test fun smallerPicturesAndBuffersOnSmallerDevices() {
        val two = iptvDeviceProfile(2 * gib - 100 * 1024 * 1024, lowRamDevice = false, videoDecoderInstances = null)
        assertEquals(4 * 1024 * 1024, two.tileBufferBytes); assertEquals(500, two.backgroundRows)
        val four = iptvDeviceProfile(4 * gib, lowRamDevice = false, videoDecoderInstances = null)
        assertEquals(1_000, four.backgroundRows)
    }
}
