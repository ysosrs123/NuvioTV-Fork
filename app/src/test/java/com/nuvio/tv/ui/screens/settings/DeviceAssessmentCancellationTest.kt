package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.core.network.DiagnosticRunCoordinator
import io.mockk.every
import io.mockk.coVerify
import com.nuvio.tv.core.network.StreamSweepEngine
import android.content.Context
import com.nuvio.tv.core.assessment.DeviceAssessmentEngine
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.data.local.PlayerSettings
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceAssessmentCancellationTest {
    @Test fun `cancelled assessment clears running state and leaves no result`() = runTest {
        mockkObject(DeviceAssessmentEngine)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            coEvery { DeviceAssessmentEngine.run(any(), any(), any(), any(), any(), any(), any()) } coAnswers { awaitCancellation() }
            val state = DeviceAssessmentState()
            runDeviceAssessment(scope, mockk<Context>(), state, mockk<PlayerSettings>(), mockk<LastPlaybackDiagnostics>())
            runCurrent(); assertTrue(state.running)
            scope.cancel(); runCurrent()
            assertFalse(state.running); assertNull(state.result); assertFalse(state.applyArmed)
        } finally { scope.cancel(); unmockkObject(DeviceAssessmentEngine) }
    }

    @Test fun `failed assessment clears running state while preserving error propagation`() = runTest {
        mockkObject(DeviceAssessmentEngine)
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, failure -> errors += failure })
        try {
            coEvery { DeviceAssessmentEngine.run(any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("synthetic assessment failure")
            val state = DeviceAssessmentState()
            runDeviceAssessment(scope, mockk<Context>(), state, mockk<PlayerSettings>(), mockk<LastPlaybackDiagnostics>())
            runCurrent()
            assertFalse(state.running); assertNull(state.result)
            assertEquals(1, errors.size); assertEquals("synthetic assessment failure", errors.single().message)
        } finally { scope.cancel(); unmockkObject(DeviceAssessmentEngine) }
    }

    @Test fun `run limit clears running state and exposes no applyable recommendation`()=runTest {
        mockkObject(DeviceAssessmentEngine)
        val scope=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler))
        try {
            coEvery { DeviceAssessmentEngine.run(any(),any(),any(),any(),any(),any(),any()) } throws
                DiagnosticRunCoordinator.Unavailable(DiagnosticRunCoordinator.Reason.TIMED_OUT)
            val context=mockk<Context>();every { context.getString(any()) } returns "Comparison incomplete"
            val state=DeviceAssessmentState().apply { applyArmed=true;runError="old error" }
            runDeviceAssessment(scope,context,state,mockk<PlayerSettings>(),mockk<LastPlaybackDiagnostics>())
            runCurrent()
            assertFalse(state.running);assertNull(state.result);assertFalse(state.applyArmed)
            assertNull(state.selectedProfile);assertEquals("Comparison incomplete",state.runError)
        } finally { scope.cancel();unmockkObject(DeviceAssessmentEngine) }
    }

    @Test fun `queued repeated assessment click starts one run`() = runTest {
        mockkObject(DeviceAssessmentEngine)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            coEvery { DeviceAssessmentEngine.run(any(), any(), any(), any(), any(), any(), any()) } coAnswers { awaitCancellation() }
            val state = DeviceAssessmentState(); val context = mockk<Context>()
            repeat(2) { runDeviceAssessment(scope, context, state, mockk<PlayerSettings>(), mockk<LastPlaybackDiagnostics>()) }
            assertTrue(state.running); runCurrent()
            coVerify(exactly = 1) { DeviceAssessmentEngine.run(any(), any(), any(), any(), any(), any(), any()) }
            state.cancel(); runCurrent(); assertFalse(state.running)
        } finally { scope.cancel(); unmockkObject(DeviceAssessmentEngine) }
    }
    @Test fun `explicit assessment cancel rejects late pass callbacks`() = runTest {
        mockkObject(DeviceAssessmentEngine)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            lateinit var late: (String) -> Unit
            coEvery { DeviceAssessmentEngine.run(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                late = arg(5); awaitCancellation()
            }
            val state = DeviceAssessmentState()
            runDeviceAssessment(scope, mockk<Context>(), state, mockk<PlayerSettings>(), mockk<LastPlaybackDiagnostics>())
            runCurrent(); state.cancel(); late("late pass"); runCurrent()
            assertFalse(state.running); assertTrue(state.passRows.isEmpty()); assertNull(state.result); assertFalse(state.applyArmed)
        } finally { scope.cancel(); unmockkObject(DeviceAssessmentEngine) }
    }
}
