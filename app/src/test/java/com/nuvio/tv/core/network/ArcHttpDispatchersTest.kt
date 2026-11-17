package com.nuvio.tv.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcHttpDispatchersTest {

    @Test
    fun `a 64-bit process keeps the normal request limit`() {
        assertEquals(32, ArcHttpDispatchers.maxRequestsFor(is64Bit = true, normal = 32, small = 12))
        assertEquals(64, ArcHttpDispatchers.maxRequestsFor(is64Bit = true, normal = 64, small = 16))
    }

    @Test
    fun `a 32-bit process gets the small request limit`() {
        assertEquals(12, ArcHttpDispatchers.maxRequestsFor(is64Bit = false, normal = 32, small = ArcHttpDispatchers.SMALL_IMAGE_REQUESTS))
        assertEquals(16, ArcHttpDispatchers.maxRequestsFor(is64Bit = false, normal = 64, small = ArcHttpDispatchers.SMALL_SHARED_REQUESTS))
    }

    @Test
    fun `the shared 32-bit dispatcher lets more than OkHttp's five calls reach one host`() {
        assertEquals(8, ArcHttpDispatchers.SMALL_SHARED_REQUESTS_PER_HOST)
        assertTrue(ArcHttpDispatchers.SMALL_SHARED_REQUESTS_PER_HOST < ArcHttpDispatchers.SMALL_SHARED_REQUESTS)
    }

    @Test
    fun `the small limit never raises a lower normal one`() {
        assertEquals(4, ArcHttpDispatchers.maxRequestsFor(is64Bit = false, normal = 4, small = 12))
    }
}
