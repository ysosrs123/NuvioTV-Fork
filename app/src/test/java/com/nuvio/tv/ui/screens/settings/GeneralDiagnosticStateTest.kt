package com.nuvio.tv.ui.screens.settings

import android.content.Context
import com.nuvio.tv.core.network.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class GeneralDiagnosticStateTest {
    private val context = mockk<Context>()
    private val success = BoundedNetworkSpeedTest.Result(mbps = 32.0, latencyMs = 8, servingHosts = setOf("media.example"))
    @Before fun setup() {
        mockkObject(GeneralNetworkSpeedTest)
        every { context.getString(any()) } returns "unavailable"
        every { context.getString(any(), *anyVararg()) } returns "qualified observation"
    }
    @After fun cleanup() { unmockkObject(GeneralNetworkSpeedTest) }
    private fun GeneralDiagnosticState.begin(scope: CoroutineScope, identity: () -> Any? = { "network" }) =
        start(scope, context, identity, "Ethernet")
    @Test fun `valid general sample retains scoped numbers`() = runTest {
        coEvery { GeneralNetworkSpeedTest.run(any(), any()) } coAnswers {
            arg<(Long) -> Unit>(0)(8); arg<() -> Unit>(1)(); success
        }
        val s = GeneralDiagnosticState(); s.begin(this); runCurrent()
        assertEquals(NetworkTestState.Done, s.status); assertEquals(32.0, s.downloadMbps!!, 0.0)
        assertEquals(8L, s.latencyMs); assertNotNull(s.resultScope)
    }
    @Test fun `unknown observation starts no transfer`() = runTest {
        val s = GeneralDiagnosticState(); s.begin(this) { null }; runCurrent()
        assertEquals(NetworkTestState.Error, s.status)
        coVerify(exactly = 0) { GeneralNetworkSpeedTest.run(any(), any()) }
    }
    @Test fun `repeated click before dispatch owns one transfer`() = runTest {
        coEvery { GeneralNetworkSpeedTest.run(any(), any()) } coAnswers { awaitCancellation() }
        val s = GeneralDiagnosticState(); repeat(2) { s.begin(this) }; runCurrent()
        coVerify(exactly = 1) { GeneralNetworkSpeedTest.run(any(), any()) }; s.cancel(); runCurrent()
    }
    @Test fun `changed generation with same route rejects sample`() = runTest {
        var epoch = 1
        coEvery { GeneralNetworkSpeedTest.run(any(), any()) } coAnswers { epoch = 3; success }
        val s = GeneralDiagnosticState(); s.begin(this) { "network:$epoch" }; runCurrent()
        assertEquals(NetworkTestState.Error, s.status); assertNull(s.downloadMbps); assertNull(s.latencyMs); assertNull(s.resultScope)
    }
    @Test fun `change after completion clears old result`() = runTest {
        coEvery { GeneralNetworkSpeedTest.run(any(), any()) } returns success
        val s = GeneralDiagnosticState(); s.begin(this); runCurrent(); s.invalidateNetwork(context, "other")
        assertEquals(NetworkTestState.Error, s.status); assertNull(s.downloadMbps); assertNull(s.resultScope)
    }
    @Test fun `parent cancelled before dispatch releases UI claim`() = runTest {
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler)); val s = GeneralDiagnosticState()
        s.begin(scope); scope.cancel(); runCurrent()
        assertFalse(s.run.running); assertEquals(NetworkTestState.Idle, s.status)
        coVerify(exactly = 0) { GeneralNetworkSpeedTest.run(any(), any()) }
    }
    @Test fun `route invalidation cancels transfer and old callback cannot update replacement`() = runTest {
        lateinit var late: (Long) -> Unit
        coEvery { GeneralNetworkSpeedTest.run(any(), any()) } coAnswers { late = arg(0); awaitCancellation() }
        val s = GeneralDiagnosticState(); s.begin(this); runCurrent(); s.invalidateNetwork(context, "other"); runCurrent()
        assertFalse(s.run.running); assertEquals(NetworkTestState.Error, s.status)
        coEvery { GeneralNetworkSpeedTest.run(any(), any()) } returns success
        s.begin(this); runCurrent(); late(999)
        assertEquals(NetworkTestState.Done, s.status); assertNull(s.latencyMs); assertEquals(32.0, s.downloadMbps!!, 0.0)
    }

    @Test fun `initial observation failure has specific unavailable reason`() = runTest {
        val s = GeneralDiagnosticState(); s.begin(this) { null }; runCurrent()
        verify { context.getString(com.nuvio.tv.R.string.network_test_route_unavailable) }
        verify(exactly = 0) { context.getString(com.nuvio.tv.R.string.network_test_network_changed) }
    }
    @Test fun `later observed change retains changed reason`() = runTest {
        coEvery { GeneralNetworkSpeedTest.run(any(), any()) } returns success
        val s = GeneralDiagnosticState(); s.begin(this); runCurrent(); s.invalidateNetwork(context, "new")
        verify { context.getString(com.nuvio.tv.R.string.network_test_network_changed) }
        verify(exactly = 0) { context.getString(com.nuvio.tv.R.string.network_test_route_unavailable) }
    }

}
