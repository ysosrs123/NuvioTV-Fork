package com.nuvio.tv.ui.screens.settings
import android.content.Context
import androidx.compose.foundation.lazy.LazyListScope
import com.nuvio.tv.core.assessment.*
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.data.local.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class AssessmentFreshnessUiTest {
    private val context = mockk<Context>()
    private val store = mockk<PlayerSettingsDataStore>()
    private val settings = PlayerSettings()
    private val source = LastPlaybackDiagnostics(timestampMs = 100, streamUrl = "https://example.invalid/source")
    private var latest = AssessmentInputs.capture(settings, source)
    @Before fun setup() {
        mockkObject(DeviceAssessmentEngine, DeviceAssessmentApplier)
        every { context.getString(any()) } answers { "message-${firstArg<Int>()}" }
        every { store.activeProfileId } returns 1
    }
    @After fun cleanup() { unmockkObject(DeviceAssessmentEngine, DeviceAssessmentApplier) }
    private fun result(): AssessmentResult = mockk {
        every { profiles } returns emptyList(); every { applyPlan } returns AssessmentApplyPlan(enableHttp2 = true)
        every { header } returns mockk { every { safeLimitMb } returns 250 }
    }
    private fun state() = DeviceAssessmentState().apply {
        result = result(); assessedProfileId = 1
        assessedInputs = latest; inputIdentity = { latest }; applyArmed = true
    }
    @Test fun `queued run rejects changed source before any engine work`() = runTest {
        val s = DeviceAssessmentState()
        runDeviceAssessment(this, context, s, settings, source, 1, inputIdentity = { latest })
        latest = AssessmentInputs.capture(settings, source.copy(timestampMs = 101)); runCurrent()
        assertFalse(s.running); assertNull(s.result); assertNotNull(s.runError)
        coVerify(exactly = 0) { DeviceAssessmentEngine.run(any(),any(),any(),any(),any(),any(),any()) }
    }
    @Test fun `settings changes clear completed result and confirmation`() = runTest {
        val s = state(); latest = AssessmentInputs.capture(settings.copy(enableHttp2 = !settings.enableHttp2), source)
        s.invalidateInputs(context); assertNull(s.result); assertFalse(s.applyArmed); assertNotNull(s.runError)
    }
    @Test fun `runtime fact change during run withholds completed recommendation`() = runTest {
        val outcome = result(); every { outcome.deviceFacts } returns mockk()
        coEvery { DeviceAssessmentEngine.run(any(),any(),any(),any(),any(),any(),any()) } returns outcome
        val s = DeviceAssessmentState()
        runDeviceAssessment(this, context, s, settings, source, 1, inputIdentity = { latest }, deviceFactsCurrent = { false })
        runCurrent(); assertNull(s.result); assertNotNull(s.runError); assertFalse(s.running)
    }
    @Test fun `runtime fact change rejects Apply before mutation`() = runTest {
        val s = state(); every { s.result!!.deviceFacts } returns mockk(); s.deviceFactsCurrent = { false }
        runApplyAssessment(this, context, s, store); runCurrent(); assertNull(s.result); assertFalse(s.applying)
        coVerify(exactly = 0) { DeviceAssessmentApplier.applyValidated(any(),any(),any(),any(),any(),any(),any()) }
    }
    @Test fun `queued Apply retains source guard after UI cancellation`() = runTest {
        val s = state(); runApplyAssessment(this, context, s, store)
        latest = AssessmentInputs.capture(settings, source.copy(headersJson = "renewed")); s.cancel(); runCurrent()
        assertFalse(s.applying); assertNull(s.appliedCount); assertNotNull(s.runError)
        coVerify(exactly = 0) { DeviceAssessmentApplier.applyValidated(any(),any(),any(),any(),any(),any(),any()) }
    }
    @Test fun `successful validated Apply retires recommendation and preserves success count`() = runTest {
        coEvery { DeviceAssessmentApplier.applyValidated(any(),any(),any(),any(),any(),any(),any()) } coAnswers {
            assertTrue(arg<() -> Boolean>(6)()); latest = AssessmentInputs.capture(settings.copy(enableHttp2 = !settings.enableHttp2), source)
            DeviceAssessmentApplier.ApplyOutcome(1)
        }
        val s = state(); runApplyAssessment(this, context, s, store); runCurrent()
        assertEquals(1, s.appliedCount); assertNull(s.result); assertFalse(s.applying); assertFalse(s.applyArmed)
        s.invalidateInputs(context); assertEquals(1, s.appliedCount); assertNull(s.runError)
    }
    @Test fun `invalidated running inputs reject late pass callbacks`() = runTest {
        lateinit var late: (String) -> Unit
        coEvery { DeviceAssessmentEngine.run(any(),any(),any(),any(),any(),any(),any()) } coAnswers { late = arg(5); awaitCancellation() }
        val s = DeviceAssessmentState()
        runDeviceAssessment(this, context, s, settings, source, 1, inputIdentity = { latest }); runCurrent()
        latest = AssessmentInputs.capture(settings, source.copy(timestampMs = 101)); s.invalidateInputs(context)
        late("old pass"); runCurrent(); assertFalse(s.running); assertTrue(s.passRows.isEmpty()); assertNull(s.result)
    }
    @Test fun `unavailable initial network reports unavailable rather than changed`() = runTest {
        val s = DeviceAssessmentState()
        runDeviceAssessment(this, context, s, settings, source, 1, networkIdentity = { null }); runCurrent()
        verify { context.getString(com.nuvio.tv.R.string.network_test_route_unavailable) }
        verify(exactly = 0) { context.getString(com.nuvio.tv.R.string.network_test_network_changed) }
    }
    @Test fun `mutation controls are registered with no current result`() {
        val list = mockk<LazyListScope>(relaxed = true)
        list.deviceAssessmentItems(DeviceAssessmentState(), source, {}, {}, {}, {})
        verify(exactly = 1) { list.item("assessment_apply", any(), any()) }
    }
}
