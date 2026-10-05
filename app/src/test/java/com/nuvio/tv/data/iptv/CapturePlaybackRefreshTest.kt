package com.nuvio.tv.data.iptv

import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class CapturePlaybackRefreshTest {
    @Test fun hundredNotificationsQueueOneLatestCallback() {
        val queue=ConcurrentLinkedQueue<Runnable>(); var calls=0
        val b=CapturePlaybackRefresh({ queue.add(it) },{queue.remove(it)}, { calls++ },{throw it})
        repeat(100) { assertTrue(b.request()) }; assertEquals(1,queue.size); queue.remove().run(); assertEquals(1,calls); assertTrue(queue.isEmpty()); assertTrue(b.close())
    }
    @Test fun notificationsDuringCallbackCoalesceIntoExactlyOneFollowup() {
        val queue=ConcurrentLinkedQueue<Runnable>(); var calls=0; lateinit var b:CapturePlaybackRefresh
        b=CapturePlaybackRefresh({queue.add(it)},{queue.remove(it)},{ if(++calls==1) repeat(100) { b.request() } },{throw it})
        b.request(); queue.remove().run(); assertEquals(1,queue.size); queue.remove().run(); assertEquals(2,calls); assertTrue(queue.isEmpty()); assertTrue(b.close())
    }
    @Test fun blockedCallbackPreventsConfirmedCloseUntilItReturns() {
        val queue=ConcurrentLinkedQueue<Runnable>(); val entered=CountDownLatch(1); val finish=CountDownLatch(1); val calls=AtomicInteger()
        val b=CapturePlaybackRefresh({queue.add(it)},{queue.remove(it)},{ calls.incrementAndGet(); entered.countDown(); assertTrue(finish.await(5,TimeUnit.SECONDS)) },{throw it})
        val executor=Executors.newSingleThreadExecutor()
        try { b.request(); val job=executor.submit { queue.remove().run() }; assertTrue(entered.await(5,TimeUnit.SECONDS)); assertFalse(b.close()); assertFalse(b.request()); finish.countDown(); job.get(5,TimeUnit.SECONDS); assertTrue(b.close()); assertEquals(1,calls.get()) }
        finally { finish.countDown(); executor.shutdownNow() }
    }
    @Test fun callbackAlreadyDequeuedBeforeCloseCannotPublishLater() {
        val queue=ConcurrentLinkedQueue<Runnable>(); var calls=0
        val b=CapturePlaybackRefresh({queue.add(it)},{queue.remove(it)},{calls++},{throw it})
        b.request(); val late=queue.remove(); assertTrue(b.close()); late.run(); assertEquals(0,calls); assertFalse(b.request())
    }
    @Test fun rejectedPostFencesRetryAndReportsFailure() {
        var failures=0; val b=CapturePlaybackRefresh({false},{},{fail("Rejected callback")},{failures++})
        assertFalse(b.request()); assertFalse(b.request()); assertEquals(1,failures); assertTrue(b.close())
    }
    @Test fun publicationFailureCannotStartAutomaticRetry() {
        val queue=ConcurrentLinkedQueue<Runnable>(); var failures=0
        val b=CapturePlaybackRefresh({queue.add(it)},{queue.remove(it)},{throw IOException("fixture")},{failures++})
        b.request(); queue.remove().run(); assertEquals(1,failures); assertTrue(queue.isEmpty()); assertFalse(b.request()); assertTrue(b.close())
    }
    @Test fun failedRemovalRetainsUncertaintyWhileLateCallbackIsFenced() {
        val queue=ConcurrentLinkedQueue<Runnable>(); var removes=0
        val b=CapturePlaybackRefresh({queue.add(it)},{if(++removes==1) throw IOException("fixture"); queue.remove(it)},{fail("Fenced callback")},{throw it})
        b.request(); assertFalse(b.close()); queue.remove().run(); assertTrue(b.close()); assertEquals(2,removes)
    }
}
