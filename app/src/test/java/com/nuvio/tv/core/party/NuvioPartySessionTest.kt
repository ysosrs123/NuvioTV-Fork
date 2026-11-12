package com.nuvio.tv.core.party

import com.nuvio.tv.core.party.nuvio.ActiveParty
import com.nuvio.tv.core.party.nuvio.PartyError
import com.nuvio.tv.core.party.nuvio.PartyMember
import com.nuvio.tv.core.party.nuvio.PartyPlayback
import com.nuvio.tv.core.party.nuvio.PartyPresence
import com.nuvio.tv.core.party.nuvio.PartySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.nuvio.tv.core.party.nuvio.PartyMedia as NuvioMedia
import com.nuvio.tv.core.party.nuvio.PartyMemberStatus as NuvioStatus

class NuvioPartySessionTest {

    private var now = 1_000_000L

    private val film = NuvioMedia(
        type = "movie",
        metaId = "tt1745960",
        metaType = "movie",
        title = "Top Gun: Maverick",
        source = PartySource(infoHash = "ABCDEF", fileIdx = 2, filename = "testfilm.mkv", videoSize = 42L, name = "TB 4K"),
    )

    private val arcFilm = PartyMedia(contentId = "tt1745960", contentType = "movie", title = "Top Gun: Maverick")

    private class FakeLink(private val now: () -> Long) : NuvioPartyLink {
        override var party: ActiveParty? = null
        override var playback: PartyPlayback? = null
        val published = mutableListOf<PartyPlayback>()
        val media = mutableListOf<NuvioMedia>()
        val statuses = mutableListOf<Triple<NuvioStatus, String?, Long?>>()
        var created: NuvioMedia? = null
        var joined: String? = null
        var left = false
        var ended = false
        var hostOnlySet: Boolean? = null

        override fun nowMs(): Long = now()

        override fun create(media: NuvioMedia, done: (PartyError?) -> Unit) {
            created = media
            party = party(memberId = "me", hostMemberId = "me", media = media)
            done(null)
        }

        override fun join(code: String, done: (PartyError?) -> Unit) {
            joined = code
            done(null)
        }

        override fun leave() {
            left = true
            party = null
        }

        override fun end() {
            ended = true
            party = null
        }

        override fun setHostOnly(enabled: Boolean) {
            hostOnlySet = enabled
        }

        override fun publishPlayback(paused: Boolean, positionMs: Long, rate: Float) {
            val next = PartyPlayback(
                seq = (playback?.seq ?: 0L) + 1,
                memberId = party?.memberId,
                paused = paused,
                positionMs = positionMs,
                rate = rate,
                at = now(),
                mediaKey = party?.media?.key,
            )
            published += next
            playback = next
        }

        override fun publishMedia(media: NuvioMedia, positionMs: Long) {
            this.media += media
            party = party?.copy(media = media)
        }

        override fun setLocalStatus(status: NuvioStatus, mediaKey: String?, behindMs: Long?) {
            statuses += Triple(status, mediaKey, behindMs)
        }

        fun hostPlays(positionMs: Long, at: Long, paused: Boolean = false, seq: Long = (playback?.seq ?: 0L) + 1) {
            playback = PartyPlayback(seq = seq, memberId = "host", paused = paused, positionMs = positionMs, at = at, mediaKey = party?.media?.key)
        }

        fun party(memberId: String, hostMemberId: String, media: NuvioMedia, hostOnly: Boolean = false) = ActiveParty(
            id = "p1",
            code = "H7KQ2L",
            memberId = memberId,
            hostMemberId = hostMemberId,
            members = listOf(PartyMember("host", "Jo"), PartyMember("me", "Alex")).distinctBy { it.id },
            presence = mapOf("host" to PartyPresence("host", NuvioStatus.Ready, media.key)),
            media = media,
            maxMembers = 3,
            connected = true,
            hostOnly = hostOnly,
            inviteLink = "https://nuvio.tv/party/H7KQ2L",
        )
    }

    private inner class FakePlayer(start: Long, playing: Boolean = true) : PartyPlayer {
        var position = start
        override val positionMs: Long get() = position
        override var durationMs: Long = 7_200_000L
        override var wantsToPlay: Boolean = playing
        override var isBuffering: Boolean = false
        override var speedAllowed: Boolean = true
        override var isAway: Boolean = false
        val seeks = mutableListOf<Long>()
        val speeds = mutableListOf<Float>()

        override fun play() {
            wantsToPlay = true
        }

        override fun pause() {
            wantsToPlay = false
        }

        override fun seekTo(positionMs: Long) {
            seeks += positionMs
            position = positionMs
        }

        override fun setSpeed(speed: Float) {
            speeds += speed
        }
    }

    private fun run(session: NuvioPartySession, player: FakePlayer?, ms: Long) {
        var left = ms
        while (left > 0) {
            now += 100
            if (player != null && player.wantsToPlay && !player.isBuffering) player.position += 100
            session.tick()
            left -= 100
        }
    }

    private fun guest(hostOnly: Boolean = false): Triple<NuvioPartySession, FakeLink, FakePlayer> {
        val link = FakeLink { now }
        val session = NuvioPartySession(link, clock = { now })
        session.joinParty("H7KQ2L")
        link.party = link.party(memberId = "me", hostMemberId = "host", media = film, hostOnly = hostOnly)
        session.tick()
        val player = FakePlayer(start = 0L, playing = false)
        session.attachPlayer(player, arcFilm)
        return Triple(session, link, player)
    }

    @Test
    fun `a guest is sent to the host's title with the host's file details`() {
        val link = FakeLink { now }
        val session = NuvioPartySession(link, clock = { now })
        session.joinParty("H7KQ2L")
        assertEquals("H7KQ2L", link.joined)
        link.party = link.party(memberId = "me", hostMemberId = "host", media = film)
        session.tick()

        val request = session.mediaRequest.value
        assertNotNull(request)
        assertEquals("tt1745960", request!!.contentId)
        assertEquals("movie", request.contentType)
        assertEquals("abcdef", request.fingerprint.infoHash)
        assertEquals(2, request.fingerprint.fileIdx)
        assertEquals(42L, request.fingerprint.sizeBytes)
        val state = session.state.value
        assertEquals(PartyRole.GUEST, state.role)
        assertTrue(state.withNuvioApps)
        assertEquals(3, state.maxMembers)
        assertEquals("https://nuvio.tv/party/H7KQ2L", state.inviteLink)
    }

    @Test
    fun `a guest lines up with the host and plays`() {
        val (session, link, player) = guest()
        link.hostPlays(positionMs = 600_000L, at = now)
        run(session, player, 300)

        assertTrue(player.wantsToPlay)
        assertTrue(player.seeks.isNotEmpty())
        assertTrue(kotlin.math.abs(player.position - (600_000L + 300)) < 300)
    }

    @Test
    fun `a bitstream guest never changes speed and seeks at most once a minute for small drift`() {
        val (session, link, player) = guest()
        player.speedAllowed = false
        link.hostPlays(positionMs = 600_000L, at = now)
        run(session, player, 3_000)
        val seeksAfterJoin = player.seeks.size

        player.position -= 800
        run(session, player, 5_000)
        assertEquals("0.8 s is tolerated", seeksAfterJoin, player.seeks.size)

        player.position -= 1_200
        run(session, player, 5_000)
        assertEquals("2 s is corrected once", seeksAfterJoin + 1, player.seeks.size)

        player.position -= 2_000
        run(session, player, 5_000)
        assertEquals("not again within a minute", seeksAfterJoin + 1, player.seeks.size)

        player.position -= 4_000
        run(session, player, 3_000)
        assertEquals("far out is corrected at once", seeksAfterJoin + 2, player.seeks.size)
        assertTrue(player.speeds.isEmpty())
    }

    @Test
    fun `a guest that may change speed is nudged for small drift`() {
        val (session, link, player) = guest()
        link.hostPlays(positionMs = 600_000L, at = now)
        run(session, player, 3_000)
        player.position -= 600
        run(session, player, 1_000)
        assertTrue(player.speeds.any { it > 1f })
    }

    @Test
    fun `a guest that may not control pauses only itself and catches up on play`() {
        val (session, link, player) = guest(hostOnly = true)
        link.hostPlays(positionMs = 600_000L, at = now)
        run(session, player, 3_000)

        player.pause()
        run(session, player, 2_000)
        assertTrue(session.state.value.selfPaused)
        assertTrue(link.published.isEmpty())
        assertEquals(NuvioStatus.Behind, link.statuses.last().first)

        run(session, player, 20_000)
        player.play()
        run(session, player, 300)
        assertFalse(session.state.value.selfPaused)
        val expected = 600_000L + (now - (link.playback!!.at))
        assertTrue(kotlin.math.abs(player.position - expected) < 500)
    }

    @Test
    fun `a guest that may control shares its pause and its jumps`() {
        val (session, link, player) = guest()
        link.hostPlays(positionMs = 600_000L, at = now)
        run(session, player, 3_000)

        player.pause()
        run(session, player, 2_000)
        assertTrue(link.published.last().paused)

        session.noteLocalSeek()
        player.position += 120_000
        run(session, player, 200)
        assertTrue(kotlin.math.abs(link.published.last().positionMs - player.position) <= 200)
        assertTrue(link.published.last().paused)
    }

    @Test
    fun `the host's title follows the host's player`() {
        val link = FakeLink { now }
        val session = NuvioPartySession(link, clock = { now })
        val player = FakePlayer(start = 0L)
        session.attachPlayer(player, arcFilm)
        session.createParty()
        assertEquals("tt1745960", link.created?.metaId)
        session.tick()
        assertEquals(PartyRole.HOST, session.state.value.role)

        val episode = PartyMedia(contentId = "tt0903747", contentType = "series", season = 1, episode = 2, title = "Breaking Bad")
        session.attachPlayer(player, episode)
        session.tick()
        assertEquals("tt0903747", link.media.single().metaId)
        assertEquals(2, link.media.single().episode)
    }

    @Test
    fun `starting with nothing playing waits for the first title`() {
        val link = FakeLink { now }
        val session = NuvioPartySession(link, clock = { now })
        session.createParty()
        assertTrue(session.state.value.waitingForTitle)
        assertTrue(session.state.value.isActive)
        assertNull(link.created)

        session.attachPlayer(FakePlayer(start = 0L), arcFilm)
        assertEquals("tt1745960", link.created?.metaId)
        session.tick()
        assertFalse(session.state.value.waitingForTitle)
        assertEquals("H7KQ2L", session.state.value.code)
    }

    @Test
    fun `a party closed by the server shows as ended, leaving does not`() {
        val (session, link, player) = guest()
        run(session, player, 200)
        link.party = null
        session.tick()
        assertTrue(session.state.value.endedByHost)
        assertFalse(session.state.value.isActive)

        val (second, secondLink, secondPlayer) = guest()
        run(second, secondPlayer, 200)
        second.leaveParty()
        assertTrue(secondLink.left)
        second.tick()
        assertFalse(second.state.value.endedByHost)
    }

    @Test
    fun `a bitstream guest does not seek for a small gap when someone else resumes`() {
        val (session, link, player) = guest()
        player.speedAllowed = false
        link.hostPlays(positionMs = 600_000L, at = now)
        run(session, player, 3_000)
        val seeks = player.seeks.size

        link.hostPlays(positionMs = player.position + 900, at = now, paused = true)
        run(session, player, 1_000)
        link.hostPlays(positionMs = player.position, at = now)
        run(session, player, 1_000)
        assertTrue("paused alignment is allowed", player.seeks.size <= seeks + 1)

        val beforeResume = player.seeks.size
        link.hostPlays(positionMs = player.position + 1_000, at = now)
        run(session, player, 1_000)
        assertEquals("1 s on a resume is left alone", beforeResume, player.seeks.size)

        link.hostPlays(positionMs = player.position + 600_000, at = now)
        run(session, player, 500)
        assertEquals("a real jump is followed", beforeResume + 1, player.seeks.size)
        assertTrue(player.speeds.all { it == 1f })
    }

    @Test
    fun `leaving while a join is still on its way leaves once it lands`() {
        val link = object : NuvioPartySessionTestLink(now = { now }) {}
        val session = NuvioPartySession(link, clock = { now })
        session.joinParty("H7KQ2L")
        session.leaveParty()
        link.finishJoin()
        assertTrue(link.left)
        session.tick()
        assertFalse(session.state.value.isActive)
        assertFalse(session.state.value.endedByHost)
    }

    @Test
    fun `a join left on its way stays ignored until the leave lands`() {
        val link = object : NuvioPartySessionTestLink(now = { now }) {
            override fun leave() {
                left = true
            }
        }
        val session = NuvioPartySession(link, clock = { now })
        session.attachPlayer(FakePlayer(start = 0L), arcFilm)
        session.joinParty("H7KQ2L")
        session.leaveParty()
        link.finishJoin()
        repeat(3) { session.tick() }
        assertTrue(link.left)
        assertNull(session.mediaRequest.value)
        assertNull(session.partyMedia)
        assertFalse(session.state.value.isActive)

        link.party = null
        session.tick()
        assertFalse(session.state.value.isActive)
        assertFalse(session.state.value.endedByHost)
    }

    @Test
    fun `pictures from a Nuvio host are kept only when they are short https links`() {
        val media = film.copy(
            poster = "https://img.example/p.jpg",
            background = "http://img.example/b.jpg",
            logo = "https://img.example/" + "x".repeat(600),
        )
        val arc = NuvioPartySession.toArc(media)
        assertEquals("https://img.example/p.jpg", arc.poster)
        assertNull(arc.backdrop)
        assertNull(arc.logo)
    }

    @Test
    fun `the host turns guest control off through the server`() {
        val link = FakeLink { now }
        val session = NuvioPartySession(link, clock = { now })
        session.attachPlayer(FakePlayer(start = 0L), arcFilm)
        session.createParty()
        session.tick()
        session.setGuestsControl(false)
        assertEquals(true, link.hostOnlySet)
    }
}

/** A link whose join answers later, as the server does. */
open class NuvioPartySessionTestLink(private val now: () -> Long) : NuvioPartyLink {
    override var party: ActiveParty? = null
    override var playback: PartyPlayback? = null
    var left = false
    private var pending: ((PartyError?) -> Unit)? = null

    fun finishJoin() {
        party = ActiveParty(
            id = "p1", code = "H7KQ2L", memberId = "me", hostMemberId = "host",
            members = listOf(PartyMember("host", "Jo"), PartyMember("me", "Alex")), presence = emptyMap(),
            media = NuvioMedia(type = "movie", metaId = "tt1", metaType = "movie"), maxMembers = 3, connected = true,
        )
        pending?.invoke(null)
        pending = null
    }

    override fun nowMs(): Long = now()
    override fun create(media: NuvioMedia, done: (PartyError?) -> Unit) = Unit
    override fun join(code: String, done: (PartyError?) -> Unit) {
        pending = done
    }
    override fun leave() {
        left = true
        party = null
    }
    override fun end() = Unit
    override fun setHostOnly(enabled: Boolean) = Unit
    override fun publishPlayback(paused: Boolean, positionMs: Long, rate: Float) = Unit
    override fun publishMedia(media: NuvioMedia, positionMs: Long) = Unit
    override fun setLocalStatus(status: NuvioStatus, mediaKey: String?, behindMs: Long?) = Unit
}
