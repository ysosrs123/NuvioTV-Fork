package com.nuvio.tv.core.network

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SharedDiagnosticIsolationTest {
    private class Pending(request:Request) : Call by client.newCall(request) {
        lateinit var callback:Callback
        var cancelled=false
        override fun enqueue(responseCallback:Callback) { callback=responseCallback }
        override fun cancel() { cancelled=true }
        fun finish()=callback.onFailure(this,IOException("synthetic cancelled transport"))
    }
    private class Harness {
        val playback=DiagnosticPlaybackGuard()
        val gate=DiagnosticRunCoordinator(playback)
        val calls=mutableListOf<Pending>()
        val factory=Call.Factory { Pending(it).also(calls::add) }
        val baseline=BoundedStreamSpeedTest(factory,playback,
            BoundedStreamSpeedTest.Limits(warmupBytes=1,measuredBytes=1,warmupMs=10,measuredMs=10,totalMs=100),coordinator=gate)
        val probe=StreamContentLengthProbe(factory,timeoutMs=100,coordinator=gate)
        val general=BoundedNetworkSpeedTest(factory,playback,
            BoundedNetworkSpeedTest.Limits(totalBytes=10,totalMs=100,downloadMs=100),coordinator=gate)
        val parallel=BoundedParallelSpeedTest(playback,
            BoundedParallelSpeedTest.Limits(totalBytes=10,warmupBytes=1,warmupMs=10,measuredMs=50,minimumMeasuredMs=0,totalMs=100),coordinator=gate)
    }
    @Test fun `timed out baseline blocks metadata general and parallel until callback ends`()=runTest {
        val h=Harness();val first=async { h.baseline.run(URL,emptyMap()) };runCurrent()
        val call=h.calls.single();advanceTimeBy(100);runCurrent()
        assertEquals(BoundedStreamSpeedTest.Failure.TIMED_OUT,first.await().failure);assertTrue(call.cancelled)
        assertEquals(0,h.probe.probe(URL,emptyMap()))
        assertEquals(BoundedNetworkSpeedTest.Failure.BUSY,h.general.run().failure)
        assertTrue(h.parallel.run { error("busy must not create workers") }.failure!!.contains("finishing"))
        assertEquals(1,h.calls.size)
        call.finish()
        val next=async { h.probe.probe(URL,emptyMap()) };runCurrent();assertEquals(2,h.calls.size)
        h.calls.last().finish();assertEquals(0,next.await())
    }
    @Test fun `metadata cancellation retains cross diagnostic exclusion`()=runTest {
        val h=Harness();val first=async { h.probe.probe(URL,emptyMap()) };runCurrent()
        first.cancelAndJoin();assertTrue(h.calls.single().cancelled)
        assertEquals(BoundedStreamSpeedTest.Failure.BUSY,h.baseline.run(URL,emptyMap()).failure)
        h.calls.single().finish()
        val next=async { h.baseline.run(URL,emptyMap()) };runCurrent();assertEquals(2,h.calls.size)
        h.calls.last().finish();assertEquals(BoundedStreamSpeedTest.Failure.UNAVAILABLE,next.await().failure)
    }
    @Test fun `active playback creates no requests from any diagnostic type`()=runTest {
        val h=Harness()
        h.playback.enterPlayback().use {
            assertEquals(0,h.probe.probe(URL,emptyMap()))
            assertEquals(BoundedStreamSpeedTest.Failure.PLAYBACK_ACTIVE,h.baseline.run(URL,emptyMap()).failure)
            assertEquals(BoundedNetworkSpeedTest.Failure.PLAYBACK_ACTIVE,h.general.run().failure)
            assertEquals("Playback is active",h.parallel.run { error("must not create") }.failure)
        }
        assertTrue(h.calls.isEmpty())
    }
    @Test fun `exhausted parent allowance cannot start another HTTP phase`()=runTest {
        val h=Harness()
        try {
            h.gate.run(DiagnosticRunCoordinator.Limits(1000,1)) {
                assertEquals(BoundedStreamSpeedTest.Failure.UNAVAILABLE,h.baseline.run(URL,emptyMap()).failure)
            }
            fail("incomplete run must not succeed")
        } catch(e:DiagnosticRunCoordinator.Unavailable) { assertEquals(DiagnosticRunCoordinator.Reason.PAYLOAD_LIMIT,e.reason) }
        assertTrue(h.calls.isEmpty())
    }
    companion object { private val client=OkHttpClient();private const val URL="https://media.invalid/title" }
}
