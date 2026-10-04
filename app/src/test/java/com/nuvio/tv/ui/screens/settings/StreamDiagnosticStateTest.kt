package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.network.BoundedStreamSpeedTest
import com.nuvio.tv.core.network.StreamSpeedTester
import com.nuvio.tv.core.network.StreamSweepEngine
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@UnstableApi
class StreamDiagnosticStateTest {
    private val context = mockk<Context>()
    private val success = BoundedStreamSpeedTest.Result(mbps = 40.0, measuredBytes = 1024, measuredNanos = 1_000_000, servingEndpoint = "media.example")
    private fun outcome() = StreamSweepEngine.SweepOutcome(StreamSweepEngine.VerdictKind.LEAVE_PARALLEL_OFF,
        "qualified verdict", null, 40.0, 20.0, emptyList(), null, null, 0)
    @Before fun setup() {
        mockkObject(StreamSpeedTester, StreamSweepEngine)
        every { context.getString(any()) } answers { "string-${firstArg<Int>()}" }
        every { context.getString(any(), *anyVararg()) } returns "observation"
    }
    @After fun cleanup() { unmockkObject(StreamSpeedTester, StreamSweepEngine) }
    private fun StreamDiagnosticState.begin(scope: CoroutineScope, mode: StreamDiagnosticMode = StreamDiagnosticMode.QUICK,
        headers: Map<String,String> = emptyMap(), network: () -> Any? = { "network" }, durationMs: Long = 0, bitrate: Long? = 10_000_000) =
        start(scope, context, mode, "https://media.example/video", headers, bitrate, network, "Wi-Fi", now = { 1000 }, durationMs = durationMs)

    @Test fun `quick uses one baseline and never recommends settings`() = runTest {
        coEvery { StreamSpeedTester.runBaselineTest(any(), any()) } returns success
        val state = StreamDiagnosticState(); state.begin(this); runCurrent()
        assertEquals("Done", state.status); assertEquals(40.0, state.rows.single().second!!, 0.0)
        assertEquals("media.example", state.endpoint); assertNotNull(state.resultScope); assertNull(state.verdict)
        coVerify(exactly = 1) { StreamSpeedTester.runBaselineTest(any(), any()) }
        coVerify(exactly = 0) { StreamSweepEngine.run(any(), any(), any(), any(), any(), any(), any(), any()) }
    }
    @Test fun `failed quick check is unavailable rather than zero capacity`() = runTest {
        coEvery { StreamSpeedTester.runBaselineTest(any(), any()) } returns BoundedStreamSpeedTest.Result(failure = BoundedStreamSpeedTest.Failure.BUSY)
        val state = StreamDiagnosticState(); state.begin(this); runCurrent()
        assertEquals("Error", state.status); assertNull(state.rows.single().second); assertNotNull(state.error); assertNull(state.verdict)
    }
    @Test fun `advanced preserves rows and its qualified verdict`() = runTest {
        coEvery { StreamSweepEngine.run(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            arg<(String) -> Unit>(4)("pass"); arg<(String) -> Unit>(5)("pass")
            arg<(String,Double?,StreamSweepEngine.PassNote?) -> Unit>(6)("pass", 40.0, null)
            arg<(String?) -> Unit>(7)("cdn.example"); outcome()
        }
        val state = StreamDiagnosticState(); state.begin(this, StreamDiagnosticMode.ADVANCED); runCurrent()
        assertEquals("Done", state.status); assertEquals("qualified verdict", state.verdict)
        assertEquals("cdn.example", state.endpoint); assertEquals(1, state.rows.size); assertNotNull(state.resultScope)
        coVerify(exactly = 0) { StreamSpeedTester.runBaselineTest(any(), any()) }
    }
    @Test fun `repeat click before dispatch creates one test`() = runTest {
        coEvery { StreamSpeedTester.runBaselineTest(any(), any()) } coAnswers { awaitCancellation() }
        val state = StreamDiagnosticState(); state.begin(this); state.begin(this); runCurrent()
        coVerify(exactly = 1) { StreamSpeedTester.runBaselineTest(any(), any()) }
        state.cancel(); runCurrent(); assertFalse(state.run.running); assertEquals("Idle", state.status)
    }
    @Test fun `explicit cancel clears pending source results`() = runTest {
        coEvery { StreamSpeedTester.runBaselineTest(any(), any()) } coAnswers { awaitCancellation() }
        val state = StreamDiagnosticState(); state.begin(this); runCurrent(); state.cancel(); runCurrent()
        assertEquals("Idle", state.status); assertTrue(state.rows.isEmpty()); assertNull(state.endpoint); assertNull(state.resultScope)
    }
    @Test fun `old advanced callbacks cannot overwrite new quick result`() = runTest {
        lateinit var late: (String) -> Unit
        coEvery { StreamSweepEngine.run(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            late = arg(4); awaitCancellation()
        }
        coEvery { StreamSpeedTester.runBaselineTest(any(), any()) } returns success
        val state = StreamDiagnosticState(); state.begin(this, StreamDiagnosticMode.ADVANCED); runCurrent()
        state.cancel(); state.begin(this); runCurrent(); late("stale pass")
        assertEquals("Done", state.status); assertNull(state.verdict); assertEquals("media.example", state.endpoint)
    }
    @Test fun `changed network withholds measured rows and recommendation`() = runTest {
        var network = "one"
        coEvery { StreamSweepEngine.run(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers { network = "two"; outcome() }
        val state = StreamDiagnosticState(); state.begin(this, StreamDiagnosticMode.ADVANCED, network = { network }); runCurrent()
        assertEquals("Error", state.status); assertNull(state.verdict); assertTrue(state.rows.isEmpty()); assertNull(state.resultScope)
    }
    @Test fun `unknown network cannot produce qualified result`() = runTest {
        coEvery { StreamSpeedTester.runBaselineTest(any(), any()) } returns success
        val state = StreamDiagnosticState(); state.begin(this, network = { null }); runCurrent()
        assertEquals("Error", state.status); assertNull(state.endpoint); assertTrue(state.rows.isEmpty())
    }
    @Test fun `headers are copied before queued work starts`() = runTest {
        val headers = mutableMapOf("Token" to "original")
        coEvery { StreamSpeedTester.runBaselineTest(any(), any()) } returns success
        val state = StreamDiagnosticState(); state.begin(this, headers = headers); headers["Token"] = "changed"; runCurrent()
        coVerify { StreamSpeedTester.runBaselineTest(any(), mapOf("Token" to "original")) }
    }
    @Test fun `parent cancellation returns UI to idle`() = runTest {
        coEvery { StreamSpeedTester.runBaselineTest(any(), any()) } coAnswers { awaitCancellation() }
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler)); val state = StreamDiagnosticState()
        state.begin(scope); runCurrent(); scope.cancel(); runCurrent()
        assertFalse(state.run.running); assertEquals("Idle", state.status); assertTrue(state.rows.isEmpty())
    }

    @Test fun `advanced resolves missing bitrate inside the explicit diagnostic`() = runTest {
        coEvery { StreamSpeedTester.getStreamContentLength(any(), any()) } returns 1_000_000L
        coEvery { StreamSweepEngine.run(any(), any(), any(), any(), any(), any(), any(), any()) } returns outcome()
        val state = StreamDiagnosticState()
        state.begin(this, StreamDiagnosticMode.ADVANCED, durationMs = 10_000, bitrate = null); runCurrent()
        assertTrue(state.bitrateIsMux); assertEquals(800_000L, state.bitrate)
        coVerify { StreamSweepEngine.run(any(), any(), any(), 800_000L, any(), any(), any(), any()) }
    }
    @Test fun `quick skips metadata even when bitrate is missing`() = runTest {
        coEvery { StreamSpeedTester.runBaselineTest(any(), any()) } returns success
        val state = StreamDiagnosticState(); state.begin(this, durationMs = 10_000, bitrate = null); runCurrent()
        assertNull(state.bitrate); assertFalse(state.bitrateIsMux)
        coVerify(exactly = 0) { StreamSpeedTester.getStreamContentLength(any(), any()) }
    }

    @Test fun `route switch away and back rejects advanced sample`() = runTest {
        val tracker = com.nuvio.tv.core.network.DiagnosticNetworkTracker(); tracker.start()
        fun ready(n: String) { tracker.available(n); tracker.capabilities(n, "wifi"); tracker.links(n, "dns") }
        ready("a")
        coEvery { StreamSweepEngine.run(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            ready("b"); ready("a"); outcome()
        }
        val s = StreamDiagnosticState(); s.begin(this, StreamDiagnosticMode.ADVANCED, network = { tracker.token("a") }); runCurrent()
        assertEquals("Error", s.status); assertNull(s.verdict); assertTrue(s.rows.isEmpty())
    }
    @Test fun `post result network change clears quick observation`() = runTest {
        coEvery { StreamSpeedTester.runBaselineTest(any(), any()) } returns success
        val s = StreamDiagnosticState(); s.begin(this); runCurrent(); s.invalidateNetwork(context, "changed")
        assertEquals("Error", s.status); assertTrue(s.rows.isEmpty()); assertNull(s.endpoint); assertNull(s.resultScope)
    }
    @Test fun `route invalidation cancels active comparison and rejects late callback`() = runTest {
        lateinit var late: (String) -> Unit
        coEvery { StreamSweepEngine.run(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            late = arg(4); awaitCancellation()
        }
        val s = StreamDiagnosticState(); s.begin(this, StreamDiagnosticMode.ADVANCED); runCurrent()
        s.invalidateNetwork(context, "changed"); runCurrent(); late("obsolete")
        assertFalse(s.run.running); assertEquals("Error", s.status); assertTrue(s.rows.isEmpty()); assertNull(s.verdict)
    }

}
