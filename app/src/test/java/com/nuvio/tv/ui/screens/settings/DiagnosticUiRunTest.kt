package com.nuvio.tv.ui.screens.settings

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticUiRunTest {
    @Test fun `claims slot before dispatch and rejects repeated click`() = runTest {
        val run = DiagnosticUiRun(); var calls = 0
        assertTrue(run.start(this) { calls++; awaitCancellation() })
        assertTrue(run.running)
        assertFalse(run.start(this) { calls++ })
        runCurrent(); assertEquals(1, calls); run.cancel(); runCurrent()
    }
    @Test fun `cancel before dispatch starts no work`() = runTest {
        val run = DiagnosticUiRun(); var calls = 0
        run.start(this) { calls++ }; run.cancel(); runCurrent()
        assertFalse(run.running); assertEquals(0, calls)
    }
    @Test fun `cancelled parent cannot leave running latch set`() = runTest {
        val scope = CoroutineScope(Job().apply { cancel() } + StandardTestDispatcher(testScheduler))
        val run = DiagnosticUiRun(); var finished = 0
        run.start(scope, onFinished = { finished++ }) { fail("must not run") }
        runCurrent(); assertFalse(run.running); assertEquals(1, finished)
    }
    @Test fun `cancel invalidates old callback during replacement run`() = runTest {
        val run = DiagnosticUiRun(); lateinit var old: () -> Boolean
        run.start(this) { old = it; awaitCancellation() }; runCurrent(); assertTrue(old())
        run.cancel(); run.start(this) { awaitCancellation() }; runCurrent()
        assertFalse(old()); assertTrue(run.running); run.cancel(); runCurrent()
    }
    @Test fun `late cleanup cannot finish a replacement run`() = runTest {
        val run = DiagnosticUiRun(); val exit = CompletableDeferred<Unit>(); var oldFinished = 0
        run.start(this, onFinished = { oldFinished++ }) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { exit.await() } }
        }
        runCurrent(); run.cancel(); run.start(this) { awaitCancellation() }; runCurrent()
        exit.complete(Unit); runCurrent()
        assertTrue(run.running); assertEquals(0, oldFinished); run.cancel(); runCurrent()
    }
    @Test fun `scope cancellation clears active UI slot`() = runTest {
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler)); val run = DiagnosticUiRun()
        run.start(scope) { awaitCancellation() }; runCurrent(); scope.cancel(); runCurrent()
        assertFalse(run.running)
    }
    @Test fun `failure releases UI slot without hiding the failure`() = runTest {
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> errors += e })
        val run = DiagnosticUiRun(); run.start(scope) { error("failure") }; runCurrent()
        assertFalse(run.running); assertEquals("failure", errors.single().message); scope.cancel()
    }
    @Test fun `completed run permits a new one`() = runTest {
        val run = DiagnosticUiRun(); var calls = 0
        run.start(this) { calls++ }; runCurrent(); assertFalse(run.running)
        run.start(this) { calls++ }; runCurrent(); assertEquals(2, calls)
    }
}
