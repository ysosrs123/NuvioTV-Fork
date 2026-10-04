package com.nuvio.tv.ui.screens.player

import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.junit.Assert.*
import org.junit.Test

class PlaybackPrewarmNetworkPolicyTest {
    @Test fun `selected playback HTTP2 choice overrides previous session without changing shared state`() {
        val previous = NuvioExoPlayerPerformanceHelper.enableHttp2
        try {
            for (lastSession in listOf(false, true)) {
                NuvioExoPlayerPerformanceHelper.enableHttp2 = lastSession
                for (selected in listOf(false, true)) {
                    val client = NuvioExoPlayerPerformanceHelper.applyNetworkOptimizations(
                        OkHttpClient.Builder(), http2Enabled = selected
                    ).build()
                    assertEquals(selected, Protocol.HTTP_2 in client.protocols)
                    assertTrue(Protocol.HTTP_1_1 in client.protocols)
                    assertSame(NuvioExoPlayerPerformanceHelper.sharedConnectionPool, client.connectionPool)
                    assertEquals(lastSession, NuvioExoPlayerPerformanceHelper.enableHttp2)
                }
            }
        } finally { NuvioExoPlayerPerformanceHelper.enableHttp2 = previous }
    }
}
