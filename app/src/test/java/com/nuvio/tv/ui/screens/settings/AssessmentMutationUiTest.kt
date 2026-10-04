package com.nuvio.tv.ui.screens.settings

import android.content.Context
import com.nuvio.tv.core.assessment.*
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssessmentMutationUiTest {
    private val context = mockk<Context>()
    private val store = mockk<PlayerSettingsDataStore>()
    private var active = 1
    @Before fun setup() {
        mockkObject(DeviceAssessmentApplier)
        every { context.getString(any()) } answers { "message-${firstArg<Int>()}" }
        every { store.activeProfileId } answers { active }
    }
    @After fun cleanup() { unmockkObject(DeviceAssessmentApplier) }
    private fun state(): DeviceAssessmentState {
        val result = mockk<AssessmentResult>()
        every { result.profiles } returns emptyList()
        every { result.applyPlan } returns AssessmentApplyPlan(enableHttp2 = true)
        every { result.header } returns mockk { every { safeLimitMb } returns 2_048 }
        return DeviceAssessmentState().apply { this.result = result; assessedProfileId = 1; applyArmed = true }
    }
    @Test fun `changed profile rejects apply before any mutation`() = runTest {
        active = 2; val state = state()
        runApplyAssessment(this, context, state, store); runCurrent()
        assertFalse(state.applying); assertFalse(state.applyArmed); assertNotNull(state.runError)
        coVerify(exactly = 0) { DeviceAssessmentApplier.apply(any(), any(), any(), any(), any()) }
    }
    @Test fun `queued apply rechecks profile after dispatch`() = runTest {
        val state = state(); runApplyAssessment(this, context, state, store); active = 2; runCurrent()
        assertFalse(state.applying); assertNotNull(state.runError)
        coVerify(exactly = 0) { DeviceAssessmentApplier.apply(any(), any(), any(), any(), any()) }
    }
    @Test fun `repeat apply click claims once and passes explicit profile`() = runTest {
        coEvery { DeviceAssessmentApplier.apply(any(), any(), any(), any(), any()) } returns DeviceAssessmentApplier.ApplyOutcome(1)
        val state = state(); repeat(2) { runApplyAssessment(this, context, state, store) }
        assertTrue(state.applying); runCurrent(); assertFalse(state.applying); assertEquals(1, state.appliedCount)
        coVerify(exactly = 1) { DeviceAssessmentApplier.apply(store, any(), null, 2_048, 1) }
    }
    @Test fun `apply failure releases busy state and disarms confirmation`() = runTest {
        coEvery { DeviceAssessmentApplier.apply(any(), any(), any(), any(), any()) } throws java.io.IOException("synthetic")
        val state = state().apply { appliedCount = 9 }; runApplyAssessment(this, context, state, store); runCurrent()
        assertFalse(state.applying); assertFalse(state.applyArmed); assertNull(state.appliedCount); assertNotNull(state.runError)
    }
    @Test fun `apply cancellation releases busy state`() = runTest {
        coEvery { DeviceAssessmentApplier.apply(any(), any(), any(), any(), any()) } coAnswers { awaitCancellation() }
        for (dispatchFirst in listOf(false, true)) {
            val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
            val state = state().apply { appliedCount = 9 }
            runApplyAssessment(scope, context, state, store)
            if (dispatchFirst) runCurrent()
            scope.cancel(); runCurrent()
            assertFalse(state.applying); assertFalse(state.applyArmed); assertNull(state.appliedCount)
        }
    }
    @Test fun `revert failure releases busy state`() = runTest {
        coEvery { DeviceAssessmentApplier.revert(any(), any()) } throws java.io.IOException("synthetic")
        val state = state(); runRevertAssessment(this, context, state, store); runCurrent()
        assertFalse(state.applying); assertFalse(state.applyArmed); assertNotNull(state.runError)
    }
    @Test fun `queued revert refuses changed profile`() = runTest {
        val state = state(); runRevertAssessment(this, context, state, store); active = 2; runCurrent()
        assertFalse(state.applying); assertNotNull(state.runError)
        coVerify(exactly = 0) { DeviceAssessmentApplier.revert(any(), any()) }
    }
    @Test fun `running assessment blocks revert`() = runTest {
        val state = state(); state.run.start(this) { awaitCancellation() }
        runRevertAssessment(this, context, state, store); runCurrent()
        coVerify(exactly = 0) { DeviceAssessmentApplier.revert(any(), any()) }
        state.cancel(); runCurrent()
    }
    @Test fun `repeat revert click claims once`() = runTest {
        coEvery { DeviceAssessmentApplier.revert(any(), any()) } returns true
        val state = state(); repeat(2) { runRevertAssessment(this, context, state, store) }; runCurrent()
        assertFalse(state.applying); assertNull(state.appliedCount)
        coVerify(exactly = 1) { DeviceAssessmentApplier.revert(store, 1) }
    }

    @Test fun `changed network rejects assessment before queuing apply`() = runTest {
        val s = state().apply { appliedCount = 7; assessedNetwork = "old"; networkIdentity = { "new" } }
        runApplyAssessment(this, context, s, store); runCurrent()
        assertNull(s.result); assertFalse(s.applying); assertNotNull(s.runError); assertNull(s.appliedCount)
        coVerify(exactly = 0) { DeviceAssessmentApplier.apply(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { DeviceAssessmentApplier.applyIfCurrent(any(), any(), any(), any(), any(), any()) }
    }
    @Test fun `queued apply retains network guard after UI result invalidation`() = runTest {
        var route = "old"; val s = state().apply { assessedNetwork = route; networkIdentity = { route } }
        runApplyAssessment(this, context, s, store); route = "new"; s.invalidateNetwork(context); runCurrent()
        assertFalse(s.applying); assertNotNull(s.runError)
        coVerify(exactly = 0) { DeviceAssessmentApplier.apply(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { DeviceAssessmentApplier.applyIfCurrent(any(), any(), any(), any(), any(), any()) }
    }
    @Test fun `valid observed network uses guarded mutation`() = runTest {
        coEvery { DeviceAssessmentApplier.applyIfCurrent(any(), any(), any(), any(), any(), any()) } coAnswers {
            assertTrue(arg<() -> Boolean>(5)()); DeviceAssessmentApplier.ApplyOutcome(1)
        }
        val s = state().apply { assessedNetwork = "same"; networkIdentity = { "same" } }
        runApplyAssessment(this, context, s, store); runCurrent()
        assertEquals(1, s.appliedCount)
        coVerify(exactly = 1) { DeviceAssessmentApplier.applyIfCurrent(store, any(), null, 2_048, 1, any()) }
    }
    @Test fun `network guard failure inside mutation releases UI without success`() = runTest {
        var route = "old"
        coEvery { DeviceAssessmentApplier.applyIfCurrent(any(), any(), any(), any(), any(), any()) } coAnswers {
            route = "new"; assertFalse(arg<() -> Boolean>(5)()); throw DeviceAssessmentApplier.StaleAssessment()
        }
        val s = state().apply { assessedNetwork = route; networkIdentity = { route } }
        runApplyAssessment(this, context, s, store); runCurrent()
        assertFalse(s.applying); assertNull(s.appliedCount); assertNull(s.result); assertNotNull(s.runError)
    }

}
