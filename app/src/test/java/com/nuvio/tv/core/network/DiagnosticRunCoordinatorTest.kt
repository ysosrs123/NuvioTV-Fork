package com.nuvio.tv.core.network

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticRunCoordinatorTest {
    private val limits=DiagnosticRunCoordinator.Limits(1000,100)
    private suspend fun unavailable(reason:DiagnosticRunCoordinator.Reason, action:suspend () -> Unit) {
        try { action();fail("Expected $reason") } catch (e:DiagnosticRunCoordinator.Unavailable) { assertEquals(reason,e.reason) }
    }
    @Test fun `unrelated diagnostics cannot enter an active root`()=runTest {
        val gate=DiagnosticRunCoordinator(DiagnosticPlaybackGuard())
        val first=async { gate.run(limits) { awaitCancellation() } };runCurrent()
        unavailable(DiagnosticRunCoordinator.Reason.BUSY) { gate.run(limits) { fail() } }
        first.cancelAndJoin();assertEquals(7,gate.run(limits) { 7 })
    }
    @Test fun `nested phases inherit ownership and cannot release the outer run`()=runTest {
        val gate=DiagnosticRunCoordinator(DiagnosticPlaybackGuard());val pause=CompletableDeferred<Unit>()
        val first=async { gate.run(limits) { outer ->
            gate.run(limits) { inner -> assertSame(outer,inner);inner.acquire(10).close() }
            pause.await()
        } };runCurrent()
        unavailable(DiagnosticRunCoordinator.Reason.BUSY) { gate.run(limits) { fail() } }
        pause.complete(Unit);first.await();gate.run(limits) {}
    }
    @Test fun `returned caller retains admission until its work closes`()=runTest {
        val gate=DiagnosticRunCoordinator(DiagnosticPlaybackGuard())
        val work=gate.run(limits) { it.acquire(10) }
        unavailable(DiagnosticRunCoordinator.Reason.BUSY) { gate.run(limits) { fail() } }
        work.close();gate.run(limits) {}
    }
    @Test fun `cancelled caller retains its unfinished transport lease`()=runTest {
        val gate=DiagnosticRunCoordinator(DiagnosticPlaybackGuard());lateinit var work:AutoCloseable
        val task=async { gate.run(limits) { work=it.acquire(10);awaitCancellation() } };runCurrent()
        task.cancelAndJoin();unavailable(DiagnosticRunCoordinator.Reason.BUSY) { gate.run(limits) {} }
        work.close();gate.run(limits) {}
    }
    @Test fun `whole run timeout includes phases and pauses without resetting`()=runTest {
        val gate=DiagnosticRunCoordinator(DiagnosticPlaybackGuard())
        val task=async { unavailable(DiagnosticRunCoordinator.Reason.TIMED_OUT) {
            gate.run(limits) {
                delay(700)
                gate.run(limits) { delay(700) }
            }
        } }
        runCurrent();advanceTimeBy(1000);runCurrent();assertTrue(task.isCompleted);task.await()
        gate.run(limits) {}
    }
    @Test fun `timeout still holds admission for late workers`()=runTest {
        val gate=DiagnosticRunCoordinator(DiagnosticPlaybackGuard());lateinit var work:AutoCloseable
        val task=async { unavailable(DiagnosticRunCoordinator.Reason.TIMED_OUT) {
            gate.run(limits) { work=it.acquire(10);awaitCancellation() }
        } };runCurrent();advanceTimeBy(1000);runCurrent();task.await()
        unavailable(DiagnosticRunCoordinator.Reason.BUSY) { gate.run(limits) {} }
        work.close();gate.run(limits) {}
    }
    @Test fun `next phase waits for preceding cleanup instead of overlapping`()=runTest {
        val gate=DiagnosticRunCoordinator(DiagnosticPlaybackGuard());lateinit var first:AutoCloseable
        var second=false
        val task=async { gate.run(limits) { run ->
            first=run.acquire(10)
            run.acquire(10).use { second=true }
        } };runCurrent();assertFalse(second)
        first.close();runCurrent();assertTrue(second);task.await()
    }
    @Test fun `nested cell deadline includes waiting for previous cleanup`()=runTest {
        val gate=DiagnosticRunCoordinator(DiagnosticPlaybackGuard());lateinit var first:AutoCloseable
        val task=async { gate.run(limits) { run ->
            first=run.acquire(10)
            unavailable(DiagnosticRunCoordinator.Reason.TIMED_OUT) {
                gate.run(DiagnosticRunCoordinator.Limits(100,100)) { it.acquire(10).close();fail() }
            }
            assertEquals(10,run.reservedPayloadBytes())
            first.close();run.acquire(10).close()
        } };runCurrent();advanceTimeBy(100);runCurrent();task.await()
        gate.run(limits) {}
    }
    @Test fun `metadata needs no payload allowance and attempts are charged conservatively`()=runTest {
        val gate=DiagnosticRunCoordinator(DiagnosticPlaybackGuard())
        gate.run(limits) { run ->
            run.acquire(0).close();run.acquire(40).close();run.acquire(60).close()
            assertEquals(100,run.reservedPayloadBytes())
        }
    }
    @Test fun `finished and failed attempts do not refund their ceilings`()=runTest {
        val gate=DiagnosticRunCoordinator(DiagnosticPlaybackGuard())
        unavailable(DiagnosticRunCoordinator.Reason.PAYLOAD_LIMIT) {
            gate.run(limits) { run ->
                run.acquire(72).close()
                unavailable(DiagnosticRunCoordinator.Reason.PAYLOAD_LIMIT) { run.acquire(72).close() }
                assertEquals(72,run.reservedPayloadBytes())
                // Catching a phase failure must not let the parent publish a complete recommendation.
                "pretend success"
            }
        }
        gate.run(limits) {}
    }
    @Test fun `playback blocks a metadata only root`()=runTest {
        val playback=DiagnosticPlaybackGuard();val gate=DiagnosticRunCoordinator(playback)
        playback.enterPlayback().use {
            unavailable(DiagnosticRunCoordinator.Reason.PLAYBACK_ACTIVE) { gate.run(DiagnosticRunCoordinator.Limits(100,0)) { fail() } }
        }
        gate.run(limits) {}
    }
    @Test fun `playback cancels the run during an interphase pause`()=runTest {
        val playback=DiagnosticPlaybackGuard();val gate=DiagnosticRunCoordinator(playback)
        val task=async { gate.run(limits) { it.acquire(1).close();delay(900);fail("must not start another phase") } }
        runCurrent();playback.enterPlayback().use { runCurrent();assertTrue(task.isCancelled) }
        gate.run(limits) {}
    }
    @Test fun `duplicate lease close cannot unlock someone elses run`()=runTest {
        val gate=DiagnosticRunCoordinator(DiagnosticPlaybackGuard())
        val old=gate.run(limits) { it.acquire(1) };old.close()
        val current=gate.run(limits) { it.acquire(1) };old.close()
        unavailable(DiagnosticRunCoordinator.Reason.BUSY) { gate.run(limits) {} }
        current.close();gate.run(limits) {}
    }
    @Test fun `escaped finished run context cannot reacquire work`()=runTest {
        val gate=DiagnosticRunCoordinator(DiagnosticPlaybackGuard());lateinit var context:CoroutineContext
        gate.run(limits) { context=currentCoroutineContext().minusKey(Job) }
        withContext(context) { unavailable(DiagnosticRunCoordinator.Reason.BUSY) { gate.run(limits) { it.acquire(1) } } }
        gate.run(limits) {}
    }
    @Test fun `exception before transport leaves admission available`()=runTest {
        val gate=DiagnosticRunCoordinator(DiagnosticPlaybackGuard())
        try { gate.run(limits) { throw IllegalArgumentException("synthetic") } } catch (_:IllegalArgumentException) {}
        gate.run(limits) {}
    }
}
