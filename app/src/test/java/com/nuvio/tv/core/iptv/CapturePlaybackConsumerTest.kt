package com.nuvio.tv.core.iptv

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CapturePlaybackConsumerTest {
    private class Owner(val label: String, val events: MutableList<String>, val decoder: Int = 0) : OwnedCaptureConsumer {
        var confirms = true; var failStart = false; var failClose = false
        override val minimumMemoryReservationBytes = 100L
        override val minimumDecoderReservationCount get() = decoder
        override fun start() { events += "$label.start"; if (failStart) error("fixture") }
        override suspend fun close(): Boolean { events += "$label.close"; if (failClose) error("fixture"); return confirms }
    }
    @Test fun uncertainPlayerCannotCloseSourceAndConfirmedHalvesAreNotRepeated() = runBlocking {
        val events=mutableListOf<String>(); val source=Owner("source",events); val player=Owner("player",events,1)
        val owner=CapturePlaybackConsumer(source,player)
        assertEquals(200L,owner.minimumMemoryReservationBytes); assertEquals(1,owner.minimumDecoderReservationCount)
        owner.start(); player.confirms=false
        assertFalse(owner.close()); assertEquals(listOf("source.start","player.start","player.close"),events)
        player.confirms=true; source.confirms=false; assertFalse(owner.close())
        source.confirms=true; assertTrue(owner.close()); assertTrue(owner.close())
        assertEquals(listOf("source.start","player.start","player.close","player.close","source.close","source.close"),events)
    }
    @Test fun failedPlayerStartStillRetainsBothOwnersUntilPlayerClosure() = runBlocking {
        val events=mutableListOf<String>(); val source=Owner("source",events); val player=Owner("player",events,1).apply { failStart=true; failClose=true }
        val owner=CapturePlaybackConsumer(source,player)
        try { owner.start(); fail() } catch (_:IllegalStateException) { }
        assertFalse(owner.close()); assertFalse(events.contains("source.close"))
        player.failClose=false; assertTrue(owner.close())
    }
    @Test fun closeBeforeStartFencesStartupAndReleasesInRendererFirstOrder() = runBlocking {
        val events=mutableListOf<String>(); val owner=CapturePlaybackConsumer(Owner("source",events),Owner("player",events,1))
        assertTrue(owner.close()); assertEquals(listOf("player.close","source.close"),events)
        try { owner.start(); fail() } catch (_:IllegalStateException) { }
    }
}
