package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaServerThumbsTest {
    @Test
    fun aServerAwayFromHomeOnlyGetsTheSpotASeekAsksFor() {
        assertTrue(MediaServerThumbs.onDemandOnly(true, "https://silo.friend.example.com/api/v2/stream/abc?token=x"))
        assertTrue(MediaServerThumbs.onDemandOnly(true, "https://203.0.113.7:8920/Videos/abc/stream?static=true"))
        assertTrue(MediaServerThumbs.onDemandOnly(true, "http://100.128.0.1:8096/Videos/abc/stream"))
    }

    @Test
    fun aServerOnTheHomeNetworkKeepsTheFullPass() {
        assertFalse(MediaServerThumbs.onDemandOnly(true, "http://192.168.1.20:8096/Videos/abc/stream?static=true"))
        assertFalse(MediaServerThumbs.onDemandOnly(true, "http://10.0.0.5:8096/Videos/abc/stream"))
        assertFalse(MediaServerThumbs.onDemandOnly(true, "http://172.20.1.2:8096/Videos/abc/stream"))
        assertFalse(MediaServerThumbs.onDemandOnly(true, "http://jellyfin.local:8096/Videos/abc/stream"))
        assertFalse(MediaServerThumbs.onDemandOnly(true, "https://nas.tail1234.ts.net/Videos/abc/stream"))
        assertFalse(MediaServerThumbs.onDemandOnly(true, "http://100.101.102.103:8096/Videos/abc/stream"))
        assertFalse(MediaServerThumbs.onDemandOnly(true, "http://[fd12:3456::1]:8096/Videos/abc/stream"))
        assertFalse(MediaServerThumbs.onDemandOnly(true, "http://127.0.0.1:8096/Videos/abc/stream"))
        assertFalse(MediaServerThumbs.onDemandOnly(true, "http://localhost:8096/Videos/abc/stream"))
    }

    @Test
    fun otherSourcesAreNeverOnDemand() {
        assertFalse(MediaServerThumbs.onDemandOnly(false, "https://cdn.debrid.example.com/dl/Film.2160p.mkv"))
        assertFalse(MediaServerThumbs.onDemandOnly(false, "https://aio.example.com/api/v1/usenet/stream/abc/file.mkv"))
        assertFalse(MediaServerThumbs.onDemandOnly(false, "http://192.168.1.10:3100/x.mkv"))
    }

    @Test
    fun anUnreadableLinkCountsAsAwayFromHome() {
        assertFalse(MediaServerThumbs.onHomeNetwork("not a link"))
        assertTrue(MediaServerThumbs.onDemandOnly(true, "not a link"))
    }

    @Test
    fun noMediaServerStreamIsReadAfterThePlayerCloses() {
        assertFalse(MediaServerThumbs.continuesAfterClose(mediaServer = true))
        assertTrue(MediaServerThumbs.continuesAfterClose(mediaServer = false))
    }

    @Test
    fun aServerAwayFromHomeGivesCopiesOneRequestAtATime() {
        assertEquals(Int.MAX_VALUE, MediaServerThumbs.copyLanes("http://192.168.1.20:8096"))
        assertEquals(Int.MAX_VALUE, MediaServerThumbs.copyLanes("https://nas.tail1234.ts.net"))
        assertEquals(1, MediaServerThumbs.copyLanes("https://silo.friend.example.com"))
        assertEquals(1, MediaServerThumbs.copyLanes("https://203.0.113.7:8920"))
        assertEquals(0, MediaServerThumbs.copyLanes(null))
        assertTrue(MediaServerThumbs.onHomeNetwork("http://192.168.1.20:8096/Videos/abc/stream?static=true&api_key=x"))
        assertFalse(MediaServerThumbs.onHomeNetwork("https://media.example.com/Videos/abc/stream?static=true&api_key=x"))
    }

    @Test
    fun onDemandFetchesOneAtATime() {
        assertEquals(1, MediaServerThumbs.fetchLimit(onDemand = true, limit = 8))
        assertEquals(1, MediaServerThumbs.fetchLimit(onDemand = true, limit = 1))
        assertEquals(3, MediaServerThumbs.fetchLimit(onDemand = false, limit = 3))
        assertEquals(8, MediaServerThumbs.fetchLimit(onDemand = false, limit = 8))
    }

    @Test
    fun aCopyFillsOnlyForAServerAwayFromHomeWithCopiesOnAndOneThatFits() {
        assertTrue(MediaServerThumbs.copyFills(onDemand = true, copiesOn = true, identityExact = true, fits = true))
        assertFalse(MediaServerThumbs.copyFills(onDemand = false, copiesOn = true, identityExact = true, fits = true))
        assertFalse(MediaServerThumbs.copyFills(onDemand = true, copiesOn = false, identityExact = true, fits = true))
        assertFalse(MediaServerThumbs.copyFills(onDemand = true, copiesOn = true, identityExact = false, fits = true))
        assertFalse(MediaServerThumbs.copyFills(onDemand = true, copiesOn = true, identityExact = true, fits = false))
    }

    @Test
    fun aCopyThatTurnsUpLateTakesOverTheServersBatchedPassWhileItRuns() {
        assertTrue(MediaServerThumbs.copyTakesOver(fits = true, remoteBatched = true, onDemand = false, passFinished = false))
        assertFalse(MediaServerThumbs.copyTakesOver(fits = false, remoteBatched = true, onDemand = false, passFinished = false))
        assertFalse(MediaServerThumbs.copyTakesOver(fits = true, remoteBatched = true, onDemand = false, passFinished = true))
        assertFalse(MediaServerThumbs.copyTakesOver(fits = true, remoteBatched = false, onDemand = true, passFinished = true))
        assertFalse(MediaServerThumbs.copyTakesOver(fits = true, remoteBatched = true, onDemand = true, passFinished = true))
    }
}
