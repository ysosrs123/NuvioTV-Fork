package com.nuvio.tv.core.party

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PartySessionTest {

    private class Sim(val latencyMs: Long = 120L) {
        var now = 1_000_000L
        private class Envelope(val from: String, val payload: String, val at: Long)

        private val queue = ArrayDeque<Envelope>()
        val transports = mutableListOf<BusTransport>()
        val devices = mutableListOf<Device>()
        private var ids = 0

        inner class BusTransport : PartyTransport {
            override val localId: String = "id" + (ids++).toString().padStart(2, '0')
            var listener: PartyTransport.Listener? = null
            var online = true
            private var announced = false

            override fun open(code: String, listener: PartyTransport.Listener) {
                this.listener = listener
                this.code = code
                transports += this
            }

            var code: String = ""
            val sent = mutableListOf<String>()

            override fun send(payload: String) {
                sent += payload
                if (online) queue.addLast(Envelope(localId, payload, now + latencyMs))
            }

            override fun close() {
                transports.remove(this)
                listener = null
            }

            fun pump() {
                if (!announced && online) {
                    announced = true
                    listener?.onConnected()
                }
            }
        }

        fun device(name: String, clockOffset: Long = 0L): Device = Device(this, name, clockOffset).also { devices += it }

        fun advance(ms: Long, step: Long = 100L) {
            var left = ms
            while (left > 0) {
                now += step
                left -= step
                transports.toList().forEach { it.pump() }
                while (queue.isNotEmpty() && queue.first().at <= now) {
                    val envelope = queue.removeFirst()
                    val sender = transports.firstOrNull { it.localId == envelope.from }
                    transports.toList().forEach { target ->
                        if (target.online && target.localId != envelope.from && (sender == null || sender.code == target.code)) {
                            target.listener?.onMessage(envelope.from, envelope.payload)
                        }
                    }
                }
                devices.forEach { device ->
                    device.player?.step(step)
                    if (device.running) device.session.tick()
                }
            }
        }
    }

    private class FakePlayer(private val sim: Sim, start: Long, playing: Boolean = true) : PartyPlayer {
        private var position = start.toDouble()
        private var speed = 1f
        private var seekBufferUntil = 0L
        var stalled = false
        var seekLatencyMs = 300L
        var speedOk = true
        val speeds = mutableListOf<Float>()
        var seeks = 0

        override val positionMs: Long get() = position.toLong()
        override var durationMs: Long = 7_200_000L
        override var wantsToPlay: Boolean = playing
        override val isBuffering: Boolean get() = stalled || sim.now < seekBufferUntil
        override val speedAllowed: Boolean get() = speedOk
        override var isAway: Boolean = false

        fun goAway() {
            isAway = true
            wantsToPlay = false
        }

        override fun play() {
            wantsToPlay = true
        }

        override fun pause() {
            wantsToPlay = false
        }

        override fun seekTo(positionMs: Long) {
            seeks++
            userSeek(positionMs)
        }

        override fun setSpeed(speed: Float) {
            speeds += speed
            this.speed = speed
        }

        fun userSeek(positionMs: Long) {
            position = positionMs.toDouble()
            seekBufferUntil = sim.now + seekLatencyMs
        }

        fun step(ms: Long) {
            if (wantsToPlay && !isBuffering) position += ms * speed
        }
    }

    private class Device(val sim: Sim, var name: String, val clockOffset: Long) {
        var transport: Sim.BusTransport? = null
        var identity: PartyIdentity? = null
        var avatar: String? = null
        val friends = mutableListOf<PartyFriendRequest>()
        val session = PartySession(
            transportFactory = { sim.BusTransport().also { transport = it } },
            clock = { sim.now + clockOffset },
            deviceName = { name },
            codeFactory = { "H7KQ29" },
            profile = { PartyProfile(avatar, "#1E88E5") },
            friendIdentity = { create -> identity ?: if (create) PartyIdentity.random().also { identity = it } else null },
            isFriend = { key -> friends.any { it.key == key } },
            onFriendAdded = { request -> friends.removeAll { it.key == request.key }; friends += request },
        )
        val id: String get() = transport!!.localId
        var player: FakePlayer? = null
        var running = true
        val state: PartyState get() = session.state.value

        fun userSeek(positionMs: Long) {
            session.noteLocalSeek()
            player!!.userSeek(positionMs)
        }

        fun attach(media: PartyMedia, start: Long = 0L, playing: Boolean = true, speedOk: Boolean = true): FakePlayer {
            val created = FakePlayer(sim, start, playing).also { it.speedOk = speedOk }
            player = created
            session.attachPlayer(created, media)
            return created
        }
    }

    private val film = PartyMedia(
        contentId = "tt0133093",
        contentType = "movie",
        videoId = "tt0133093",
        title = "The Matrix",
        fingerprint = PartyFingerprint("abc", 0, "The.Matrix.mkv", 8_000_000_000, null, 7_200_000),
    )

    private fun gap(a: FakePlayer, b: FakePlayer): Long = abs(a.positionMs - b.positionMs)

    private fun party(sim: Sim, guestSpeedOk: Boolean = true, guestOffset: Long = 0L): Triple<Device, Device, FakePlayer> {
        val host = sim.device("Lounge")
        host.attach(film, start = 600_000)
        host.session.createParty()
        sim.advance(1_000)
        val guest = sim.device("Fire TV", guestOffset)
        assertTrue(guest.session.joinParty("h7k q29"))
        sim.advance(1_500)
        assertEquals(film.copy(sharedUrl = null), guest.session.mediaRequest.value)
        guest.session.consumeMediaRequest()
        val guestPlayer = guest.attach(film, start = 0, playing = true, speedOk = guestSpeedOk)
        return Triple(host, guest, guestPlayer)
    }

    @Test
    fun `a guest that may change speed joins and settles on the host position`() {
        val sim = Sim()
        val (host, guest, guestPlayer) = party(sim, guestOffset = 5_000_000)
        sim.advance(30_000)
        assertTrue("gap ${gap(host.player!!, guestPlayer)}", gap(host.player!!, guestPlayer) <= 150)
        assertTrue(guestPlayer.speeds.all { it in 0.95f..1.05f })
        assertEquals(PartyStatus.ACTIVE, guest.state.status)
        assertEquals(PartyRole.GUEST, guest.state.role)
        assertTrue(guest.state.hostPresent)
        assertEquals(2, host.state.members.size)
        assertEquals(listOf("Lounge", "Fire TV"), host.state.members.map { it.name })
        assertEquals(PartyMemberStatus.IN_SYNC, host.state.members[1].status)
        assertTrue(host.player!!.speeds.isEmpty())
        assertEquals(0, host.player!!.seeks)
    }

    @Test
    fun `a guest that must not change speed never gets a speed command`() {
        val sim = Sim()
        val (host, _, guestPlayer) = party(sim, guestSpeedOk = false)
        sim.advance(20_000)
        assertTrue(guestPlayer.speeds.isEmpty())
        assertTrue("gap ${gap(host.player!!, guestPlayer)}", gap(host.player!!, guestPlayer) <= 1_500)
        val seeksAfterJoin = guestPlayer.seeks
        guestPlayer.userSeekQuietly(guestPlayer.positionMs - 900)
        sim.advance(90_000)
        assertTrue(guestPlayer.speeds.isEmpty())
        assertEquals("a drift inside the dead band must be left alone", seeksAfterJoin, guestPlayer.seeks)
    }

    private fun FakePlayer.userSeekQuietly(positionMs: Long) {
        // A slip the party did not cause and cannot see as a seek: under the seek-detection threshold.
        userSeek(positionMs)
        seekLatencyMs = 300
    }

    @Test
    fun `host pause and play carry over and the pause lines everyone up`() {
        val sim = Sim()
        val (host, _, guestPlayer) = party(sim, guestSpeedOk = false)
        sim.advance(10_000)
        host.player!!.pause()
        sim.advance(3_000)
        assertFalse(guestPlayer.wantsToPlay)
        assertTrue("gap ${gap(host.player!!, guestPlayer)}", gap(host.player!!, guestPlayer) <= 100)
        host.player!!.play()
        sim.advance(3_000)
        assertTrue(guestPlayer.wantsToPlay)
        assertTrue(gap(host.player!!, guestPlayer) <= 600)
        assertTrue(guestPlayer.speeds.isEmpty())
    }

    @Test
    fun `an older room state that arrives late does not undo a pause`() {
        val sim = Sim()
        val (host, guest, guestPlayer) = party(sim)
        sim.advance(10_000)
        val hostTransport = sim.transports.first { it.localId == host.id }
        val playing = hostTransport.sent.last { (PartyCodec.decode(it) as? PartyMessage.State)?.playing == true }
        host.player!!.pause()
        sim.advance(3_000)
        assertFalse(guestPlayer.wantsToPlay)
        sim.transports.first { it.localId == guest.id }.listener?.onMessage(host.id, playing)
        sim.advance(2_000)
        assertFalse(guestPlayer.wantsToPlay)
    }

    @Test
    fun `a guest with control pauses and resumes the room`() {
        val sim = Sim()
        val (host, _, guestPlayer) = party(sim)
        sim.advance(10_000)
        guestPlayer.pause()
        sim.advance(2_000)
        assertFalse(host.player!!.wantsToPlay)
        assertFalse(guestPlayer.wantsToPlay)
        guestPlayer.play()
        sim.advance(3_000)
        assertTrue(host.player!!.wantsToPlay)
        assertTrue(guestPlayer.wantsToPlay)
        sim.advance(10_000)
        assertTrue(gap(host.player!!, guestPlayer) <= 150)
    }

    @Test
    fun `a guest without control only pauses itself and catches up on play`() {
        val sim = Sim()
        val (host, guest, guestPlayer) = party(sim)
        host.session.setGuestsControl(false)
        sim.advance(10_000)
        guestPlayer.pause()
        sim.advance(5_000)
        assertTrue(host.player!!.wantsToPlay)
        assertFalse(guestPlayer.wantsToPlay)
        assertTrue(guest.state.selfPaused)
        guestPlayer.play()
        sim.advance(8_000)
        assertFalse(guest.state.selfPaused)
        assertTrue("gap ${gap(host.player!!, guestPlayer)}", gap(host.player!!, guestPlayer) <= 400)
    }

    @Test
    fun `the room waits once for a guest that buffers and resumes together`() {
        val sim = Sim()
        val (host, guest, guestPlayer) = party(sim, guestSpeedOk = false)
        sim.advance(15_000)
        guestPlayer.stalled = true
        sim.advance(3_500)
        assertFalse("host should be held", host.player!!.wantsToPlay)
        assertEquals(PartyHoldReason.BUFFERING, host.state.hold?.reason)
        assertEquals("Fire TV", host.state.hold?.memberName)
        assertEquals(PartyHoldReason.BUFFERING, guest.state.hold?.reason)
        assertTrue(guest.state.hold?.isSelf == true)
        guestPlayer.stalled = false
        sim.advance(4_000)
        assertNull(host.state.hold)
        assertTrue(host.player!!.wantsToPlay)
        assertTrue(guestPlayer.wantsToPlay)
        sim.advance(5_000)
        assertTrue("gap ${gap(host.player!!, guestPlayer)}", gap(host.player!!, guestPlayer) <= 600)
        assertTrue(guestPlayer.speeds.isEmpty())
    }

    @Test
    fun `a second stall within five minutes does not hold the room again`() {
        val sim = Sim()
        val (host, guest, guestPlayer) = party(sim)
        sim.advance(15_000)
        guestPlayer.stalled = true
        sim.advance(4_000)
        guestPlayer.stalled = false
        sim.advance(70_000)
        assertTrue(host.player!!.wantsToPlay)
        guestPlayer.stalled = true
        sim.advance(6_000)
        assertTrue("host keeps playing", host.player!!.wantsToPlay)
        assertNull(host.state.hold)
        guestPlayer.stalled = false
        sim.advance(15_000)
        assertTrue(guest.state.catchingUp)
        assertTrue("gap ${gap(host.player!!, guestPlayer)}", gap(host.player!!, guestPlayer) <= 400)
    }

    @Test
    fun `in a party of three the room moves on after ten seconds`() {
        val sim = Sim()
        val (host, _, guestPlayer) = party(sim)
        val third = sim.device("Bedroom")
        third.session.joinParty("H7KQ29")
        sim.advance(1_500)
        third.session.consumeMediaRequest()
        val thirdPlayer = third.attach(film)
        sim.advance(15_000)
        assertEquals(3, host.state.members.size)
        guestPlayer.stalled = true
        sim.advance(4_000)
        assertFalse(host.player!!.wantsToPlay)
        assertFalse(thirdPlayer.wantsToPlay)
        sim.advance(11_000)
        assertTrue(host.player!!.wantsToPlay)
        assertTrue(thirdPlayer.wantsToPlay)
        assertEquals(PartyMemberStatus.BUFFERING, host.state.members.first { it.name == "Fire TV" }.status)
        guestPlayer.stalled = false
        sim.advance(22_000)
        assertTrue("gap ${gap(host.player!!, guestPlayer)}", gap(host.player!!, guestPlayer) <= 400)
        assertTrue("gap ${gap(host.player!!, thirdPlayer)}", gap(host.player!!, thirdPlayer) <= 400)
    }

    @Test
    fun `the host buffering holds the guests where the host is`() {
        val sim = Sim()
        val (host, guest, guestPlayer) = party(sim)
        sim.advance(15_000)
        host.player!!.stalled = true
        sim.advance(5_000)
        assertFalse(guestPlayer.wantsToPlay)
        assertTrue(gap(host.player!!, guestPlayer) <= 500)
        assertEquals("Lounge", guest.state.hold?.memberName)
        host.player!!.stalled = false
        sim.advance(4_000)
        assertTrue(host.player!!.wantsToPlay && guestPlayer.wantsToPlay)
        sim.advance(8_000)
        assertTrue(gap(host.player!!, guestPlayer) <= 150)
    }

    @Test
    fun `a host seek pauses, lines everyone up and resumes`() {
        val sim = Sim()
        val (host, _, guestPlayer) = party(sim, guestSpeedOk = false)
        sim.advance(10_000)
        host.userSeek(3_000_000)
        sim.advance(6_000)
        assertTrue(host.player!!.wantsToPlay && guestPlayer.wantsToPlay)
        assertTrue(host.player!!.positionMs in 3_000_000..3_006_000)
        assertTrue("gap ${gap(host.player!!, guestPlayer)}", gap(host.player!!, guestPlayer) <= 600)
        assertTrue(guestPlayer.speeds.isEmpty())
    }

    @Test
    fun `a jump nobody asked for is not announced as a seek`() {
        val sim = Sim()
        val (host, guest, guestPlayer) = party(sim, guestSpeedOk = false)
        sim.advance(10_000)
        guestPlayer.userSeek(guestPlayer.positionMs - 20_000)
        sim.advance(4_000)
        assertTrue("the room is not dragged back", host.player!!.positionMs > 610_000)
        assertNull(host.state.hold)
        host.player!!.userSeek(host.player!!.positionMs + 30_000)
        sim.advance(1_000)
        assertNull("no hold for the host's own recovery jump", host.state.hold)
        assertTrue(host.player!!.wantsToPlay)
        sim.advance(20_000)
        assertTrue("gap ${gap(host.player!!, guestPlayer)}", gap(host.player!!, guestPlayer) <= 1_500)
        assertTrue(guestPlayer.speeds.isEmpty())
        assertEquals(PartyRole.GUEST, guest.state.role)
    }

    @Test
    fun `a guest seek moves the room when guests have control`() {
        val sim = Sim()
        val (host, guest, guestPlayer) = party(sim)
        sim.advance(10_000)
        guest.userSeek(1_200_000)
        sim.advance(8_000)
        assertTrue(host.player!!.positionMs in 1_200_000..1_208_000)
        assertTrue(host.player!!.wantsToPlay && guestPlayer.wantsToPlay)
        sim.advance(8_000)
        assertTrue(gap(host.player!!, guestPlayer) <= 150)
    }

    @Test
    fun `when the host disappears the earliest guest takes over and the old host steps down on return`() {
        val sim = Sim()
        val (host, guest, guestPlayer) = party(sim)
        val third = sim.device("Bedroom")
        third.session.joinParty("H7KQ29")
        sim.advance(1_500)
        third.session.consumeMediaRequest()
        val thirdPlayer = third.attach(film)
        sim.advance(20_000)
        host.running = false
        host.transport!!.online = false
        sim.advance(40_000)
        assertEquals(PartyRole.HOST, guest.state.role)
        assertEquals(PartyRole.GUEST, third.state.role)
        assertTrue(third.state.hostPresent)
        sim.advance(10_000)
        assertTrue("gap ${gap(guestPlayer, thirdPlayer)}", gap(guestPlayer, thirdPlayer) <= 150)
        host.running = true
        host.transport!!.online = true
        sim.advance(15_000)
        assertEquals(PartyRole.GUEST, host.state.role)
        assertEquals(PartyRole.HOST, guest.state.role)
        sim.advance(20_000)
        assertTrue("gap ${gap(guestPlayer, host.player!!)}", gap(guestPlayer, host.player!!) <= 150)
    }

    @Test
    fun `a guest leaving the app is away, not a pause, and lines up again on return`() {
        val sim = Sim()
        val (host, guest, guestPlayer) = party(sim)
        sim.advance(10_000)
        guestPlayer.goAway()
        sim.advance(5_000)
        assertTrue("the room keeps playing", host.player!!.wantsToPlay)
        assertEquals(PartyMemberStatus.AWAY, host.state.members.first { it.name == "Fire TV" }.status)
        host.userSeek(2_000_000)
        sim.advance(3_000)
        assertTrue("a seek does not wait for someone away", host.player!!.wantsToPlay)
        assertNull(host.state.hold)
        sim.advance(20_000)
        guestPlayer.isAway = false
        sim.advance(10_000)
        assertTrue(guestPlayer.wantsToPlay)
        assertEquals(PartyRole.GUEST, guest.state.role)
        assertTrue("gap ${gap(host.player!!, guestPlayer)}", gap(host.player!!, guestPlayer) <= 600)
        sim.advance(20_000)
        assertTrue("gap ${gap(host.player!!, guestPlayer)}", gap(host.player!!, guestPlayer) <= 150)
        assertEquals(PartyMemberStatus.IN_SYNC, host.state.members.first { it.name == "Fire TV" }.status)
    }

    @Test
    fun `a host leaving the app hands the room over and follows on return`() {
        val sim = Sim()
        val (host, guest, guestPlayer) = party(sim)
        sim.advance(10_000)
        host.player!!.goAway()
        sim.advance(3_000)
        assertEquals(PartyRole.HOST, guest.state.role)
        assertEquals(PartyRole.GUEST, host.state.role)
        assertTrue("the room keeps playing", guestPlayer.wantsToPlay)
        sim.advance(20_000)
        assertEquals(PartyMemberStatus.AWAY, guest.state.members.first { it.name == "Lounge" }.status)
        host.player!!.isAway = false
        sim.advance(10_000)
        assertEquals(PartyRole.GUEST, host.state.role)
        assertEquals(PartyRole.HOST, guest.state.role)
        assertTrue(host.player!!.wantsToPlay)
        assertTrue("gap ${gap(host.player!!, guestPlayer)}", gap(host.player!!, guestPlayer) <= 600)
        sim.advance(20_000)
        assertTrue("gap ${gap(host.player!!, guestPlayer)}", gap(host.player!!, guestPlayer) <= 150)
    }

    @Test
    fun `a guest joining an away host takes over from where the room was`() {
        val sim = Sim()
        val host = sim.device("Lounge")
        host.attach(film, start = 600_000)
        host.session.createParty()
        sim.advance(1_000)
        host.player!!.goAway()
        sim.advance(2_000)
        val guest = sim.device("Fire TV")
        guest.session.joinParty("H7KQ29")
        sim.advance(3_000)
        assertEquals(PartyRole.HOST, guest.state.role)
        assertEquals(PartyRole.GUEST, host.state.role)
        guest.session.consumeMediaRequest()
        val guestPlayer = guest.attach(film, start = 100_000, playing = true)
        sim.advance(2_000)
        assertTrue("starts near the room position, at ${guestPlayer.positionMs}", guestPlayer.positionMs in 600_000..604_000)
        host.player!!.isAway = false
        sim.advance(30_000)
        assertEquals(PartyRole.GUEST, host.state.role)
        assertTrue("gap ${gap(host.player!!, guestPlayer)}", gap(host.player!!, guestPlayer) <= 150)
    }

    @Test
    fun `a speed-locked guest does not jump straight after buffering`() {
        val sim = Sim()
        val (host, _, guestPlayer) = party(sim, guestSpeedOk = false)
        sim.advance(15_000)
        guestPlayer.stalled = true
        sim.advance(4_000)
        guestPlayer.stalled = false
        sim.advance(70_000)
        val seeks = guestPlayer.seeks
        guestPlayer.stalled = true
        sim.advance(4_000)
        guestPlayer.stalled = false
        sim.advance(8_000)
        assertEquals("no jump while the buffer rebuilds", seeks, guestPlayer.seeks)
        sim.advance(15_000)
        assertTrue("gap ${gap(host.player!!, guestPlayer)}", gap(host.player!!, guestPlayer) <= 1_500)
    }

    @Test
    fun `when everyone is away the first box back becomes host`() {
        val sim = Sim()
        val (host, guest, guestPlayer) = party(sim)
        sim.advance(10_000)
        guestPlayer.goAway()
        sim.advance(2_000)
        host.player!!.goAway()
        sim.advance(10_000)
        host.player!!.isAway = false
        host.player!!.play()
        sim.advance(30_000)
        assertEquals(PartyRole.HOST, host.state.role)
        assertTrue(host.player!!.wantsToPlay)
        guestPlayer.isAway = false
        sim.advance(30_000)
        assertEquals(PartyRole.GUEST, guest.state.role)
        assertTrue(guest.state.hostPresent)
        assertTrue("gap ${gap(host.player!!, guestPlayer)}", gap(host.player!!, guestPlayer) <= 150)
    }

    @Test
    fun `a host leaving during a seek hold still lets the room play on`() {
        val sim = Sim()
        val (host, guest, guestPlayer) = party(sim)
        sim.advance(10_000)
        host.userSeek(2_000_000)
        sim.advance(300)
        host.player!!.goAway()
        sim.advance(10_000)
        assertEquals(PartyRole.HOST, guest.state.role)
        assertTrue("the room plays on", guestPlayer.wantsToPlay)
        assertTrue("at ${guestPlayer.positionMs}", guestPlayer.positionMs > 2_000_000)
    }

    @Test
    fun `a new host without a player keeps the room clock running`() {
        val sim = Sim()
        val (host, guest, guestPlayer) = party(sim)
        val third = sim.device("Bedroom")
        third.session.joinParty("H7KQ29")
        sim.advance(1_500)
        third.session.consumeMediaRequest()
        val thirdPlayer = third.attach(film)
        sim.advance(20_000)
        guest.session.detachPlayer(guestPlayer)
        sim.advance(2_000)
        host.player!!.goAway()
        sim.advance(15_000)
        assertEquals(PartyRole.HOST, guest.state.role)
        assertTrue("the others keep playing", thirdPlayer.wantsToPlay)
        val attached = guest.attach(film, start = 0, playing = true)
        sim.advance(10_000)
        assertTrue("gap ${gap(attached, thirdPlayer)}", gap(attached, thirdPlayer) <= 1_000)
    }

    @Test
    fun `a box that is away does not open the next episode until it is back`() {
        val sim = Sim()
        val (host, guest, guestPlayer) = party(sim)
        sim.advance(10_000)
        guestPlayer.goAway()
        sim.advance(2_000)
        val next = film.copy(videoId = "tt0133093:2", title = "Next")
        host.attach(next, start = 0)
        sim.advance(3_000)
        assertNull(guest.session.mediaRequest.value)
        guestPlayer.isAway = false
        sim.advance(1_000)
        assertEquals("tt0133093:2", guest.session.mediaRequest.value?.videoId)
    }

    @Test
    fun `a speed-locked guest parks ahead and starts on time`() {
        val sim = Sim()
        val (host, _, guestPlayer) = party(sim, guestSpeedOk = false)
        guestPlayer.seekLatencyMs = 1_200
        sim.advance(20_000)
        assertTrue(guestPlayer.speeds.isEmpty())
        assertTrue("gap ${gap(host.player!!, guestPlayer)}", gap(host.player!!, guestPlayer) <= 250)
        guestPlayer.userSeekQuietly(guestPlayer.positionMs - 6_000)
        sim.advance(25_000)
        assertTrue("gap ${gap(host.player!!, guestPlayer)}", gap(host.player!!, guestPlayer) <= 250)
    }

    @Test
    fun `everyone hears when the host changes`() {
        val sim = Sim()
        val (host, guest, _) = party(sim)
        sim.advance(10_000)
        val before = guest.state.hostChanges
        host.player!!.goAway()
        sim.advance(3_000)
        assertEquals(PartyRole.HOST, guest.state.role)
        assertTrue(guest.state.hostChanges > before)
        host.player!!.isAway = false
        sim.advance(5_000)
        assertTrue(host.state.hostChanges > 0)
    }

    @Test
    fun `a host alone in the room takes it back on return`() {
        val sim = Sim()
        val (host, guest, _) = party(sim)
        sim.advance(10_000)
        host.player!!.goAway()
        guest.session.leaveParty()
        sim.advance(3_000)
        host.player!!.isAway = false
        sim.advance(2_000)
        assertEquals(PartyRole.HOST, host.state.role)
        assertEquals(PartyStatus.ACTIVE, host.state.status)
    }

    @Test
    fun `a changed name reaches the others straight away`() {
        val sim = Sim()
        val (host, guest, _) = party(sim)
        sim.advance(5_000)
        guest.name = "Jo"
        guest.session.announceName()
        host.name = "Sam"
        host.session.announceName()
        sim.advance(1_000)
        assertEquals(listOf("Sam", "Jo"), host.state.members.map { it.name })
        assertEquals(listOf("Jo", "Sam"), guest.state.members.map { it.name })
    }

    @Test
    fun `ending the party sends everyone home and leaving does not`() {
        val sim = Sim()
        val (host, guest, _) = party(sim)
        val third = sim.device("Bedroom")
        third.session.joinParty("H7KQ29")
        sim.advance(5_000)
        third.session.leaveParty()
        sim.advance(2_000)
        assertEquals(2, host.state.members.size)
        assertEquals(PartyStatus.IDLE, third.state.status)
        assertFalse(third.state.endedByHost)
        host.session.endParty()
        sim.advance(2_000)
        assertEquals(PartyStatus.IDLE, host.state.status)
        assertEquals(PartyStatus.IDLE, guest.state.status)
        assertTrue(guest.state.endedByHost)
        guest.session.acknowledgeEnded()
        assertFalse(guest.state.endedByHost)
    }

    @Test
    fun `a new episode on the host asks guests to open it and waits for them`() {
        val sim = Sim()
        val (host, guest, _) = party(sim)
        sim.advance(10_000)
        val next = film.copy(videoId = "tt0133093:1:2", episode = 2, fingerprint = PartyFingerprint("def", 1))
        host.session.detachPlayer(host.player!!)
        val hostPlayer = host.attach(next, start = 0)
        sim.advance(1_000)
        assertEquals(next, guest.session.mediaRequest.value)
        assertFalse("host waits at the start", hostPlayer.wantsToPlay)
        assertEquals(PartyHoldReason.LOADING, host.state.hold?.reason)
        guest.session.consumeMediaRequest()
        guest.session.detachPlayer(guest.player!!)
        val guestPlayer = guest.attach(next, start = 0, playing = true)
        sim.advance(5_000)
        assertNull(host.state.hold)
        assertTrue(hostPlayer.wantsToPlay && guestPlayer.wantsToPlay)
        assertTrue(hostPlayer.positionMs < 5_000)
        assertTrue("gap ${gap(hostPlayer, guestPlayer)}", gap(hostPlayer, guestPlayer) <= 300)
    }

    @Test
    fun `the link is only passed on when the host allows it`() {
        val sim = Sim()
        val host = sim.device("Lounge")
        host.attach(film.copy(sharedUrl = "https://example.org/v.mkv"), start = 0)
        host.session.createParty()
        host.session.setShareLink(true)
        host.session.resendMedia()
        sim.advance(1_000)
        val guest = sim.device("Fire TV")
        guest.session.joinParty("H7KQ29")
        sim.advance(1_500)
        assertEquals("https://example.org/v.mkv", guest.session.mediaRequest.value?.sharedUrl)
        assertNotNull(guest.session.partyMedia)
    }

    @Test
    fun `an invalid code is refused and a wrong code finds nobody`() {
        val sim = Sim()
        val (_, _, _) = party(sim)
        val stranger = sim.device("Stranger")
        assertFalse(stranger.session.joinParty("12"))
        assertEquals(PartyStatus.IDLE, stranger.state.status)
        assertTrue(stranger.session.joinParty("ZZZZZZ"))
        sim.advance(40_000)
        assertFalse(stranger.state.hostPresent)
        assertEquals(PartyRole.GUEST, stranger.state.role)
        assertEquals(1, stranger.state.members.size)
    }
    @Test
    fun `adding a friend needs a yes on the other box and then both keep each other`() {
        val sim = Sim()
        val (host, guest, _) = party(sim)
        host.name = "Sam"
        host.avatar = "https://avatars/fox.png"
        guest.name = "Jo"
        host.session.announceName()
        sim.advance(1_000)
        assertEquals("https://avatars/fox.png", guest.state.members.first { it.isHost }.avatarUrl)
        assertEquals("#1E88E5", guest.state.members.first { it.isHost }.colour)

        host.session.addFriend(guest.id)
        assertEquals(setOf(guest.id), host.state.friendAsked)
        sim.advance(500)
        val request = guest.session.friendRequest.value!!
        assertEquals("Sam", request.name)
        assertEquals(host.identity!!.publicKeyHex, request.key)
        assertEquals("https://avatars/fox.png", request.avatarUrl)
        assertTrue(guest.friends.isEmpty())
        assertNull(guest.identity)

        guest.session.answerFriendRequest(accept = true)
        sim.advance(500)
        assertNull(guest.session.friendRequest.value)
        assertEquals(listOf(host.identity!!.publicKeyHex), guest.friends.map { it.key })
        assertEquals(listOf(guest.identity!!.publicKeyHex), host.friends.map { it.key })
        assertEquals("Jo", host.friends.single().name)
        assertEquals(emptySet<String>(), host.state.friendAsked)
        assertEquals(guest.identity!!.publicKeyHex, host.state.members.first { !it.isSelf }.friendKey)
        assertEquals(host.identity!!.publicKeyHex, guest.state.members.first { !it.isSelf }.friendKey)
    }

    @Test
    fun `not now stores nothing on either box`() {
        val sim = Sim()
        val (host, guest, _) = party(sim)
        sim.advance(2_000)
        host.session.addFriend(guest.id)
        sim.advance(500)
        guest.session.answerFriendRequest(accept = false)
        sim.advance(2_000)
        assertTrue(guest.friends.isEmpty())
        assertTrue(host.friends.isEmpty())
        assertNull(guest.identity)
    }

    @Test
    fun `a friend asking again is accepted without a question`() {
        val sim = Sim()
        val (host, guest, _) = party(sim)
        sim.advance(2_000)
        host.session.addFriend(guest.id)
        sim.advance(500)
        guest.session.answerFriendRequest(accept = true)
        sim.advance(500)
        host.friends.clear()
        host.session.addFriend(guest.id)
        sim.advance(500)
        assertNull(guest.session.friendRequest.value)
        assertEquals(1, host.friends.size)
    }

    @Test
    fun `a request with a borrowed key or for someone else is ignored`() {
        val sim = Sim()
        val (host, guest, _) = party(sim)
        sim.advance(2_000)
        val stranger = PartyIdentity.random()
        val borrowed = PartyIdentity.random()
        val random = java.security.SecureRandom()
        host.transport!!.send(
            PartyCodec.encode(PartyMessage.FriendRequest(guest.id, borrowed.publicKeyHex, PartyInvites.proof(stranger, host.id, guest.id, random), "Sam"))
        )
        host.transport!!.send(
            PartyCodec.encode(PartyMessage.FriendRequest(guest.id, stranger.publicKeyHex, PartyInvites.proof(stranger, "someone", guest.id, random), "Sam"))
        )
        host.transport!!.send(
            PartyCodec.encode(PartyMessage.FriendRequest("someone", stranger.publicKeyHex, PartyInvites.proof(stranger, host.id, "someone", random), "Sam"))
        )
        host.transport!!.send(
            PartyCodec.encode(PartyMessage.FriendAccept(guest.id, stranger.publicKeyHex, PartyInvites.proof(stranger, host.id, guest.id, random), "Sam"))
        )
        sim.advance(500)
        assertNull(guest.session.friendRequest.value)
        assertTrue(guest.friends.isEmpty())
    }

    @Test
    fun `a request is dropped when the person who asked leaves`() {
        val sim = Sim()
        val (host, guest, _) = party(sim)
        sim.advance(2_000)
        guest.session.addFriend(host.id)
        sim.advance(500)
        assertNotNull(host.session.friendRequest.value)
        guest.session.leaveParty()
        sim.advance(500)
        assertNull(host.session.friendRequest.value)
        host.session.answerFriendRequest(accept = true)
        assertTrue(host.friends.isEmpty())
    }
    @Test
    fun `a member cannot pass for a friend by repeating their key`() {
        val sim = Sim()
        val (host, guest, _) = party(sim)
        sim.advance(2_000)
        host.session.addFriend(guest.id)
        sim.advance(500)
        guest.session.answerFriendRequest(accept = true)
        sim.advance(4_000)
        val joKey = guest.identity!!.publicKeyHex
        val intruder = sim.device("Intruder")
        intruder.session.joinParty("H7KQ29")
        sim.advance(1_500)
        val random = java.security.SecureRandom()
        val copied = PartyMessage.Hello("Intruder", false, 0, true, friendKey = joKey, friendProof = PartyInvites.proof(guest.identity!!, guest.id, PartyInvites.ANYONE, random))
        intruder.transport!!.send(PartyCodec.encode(copied))
        sim.advance(1_000)
        val seen = host.state.members.first { it.id == intruder.id }
        assertNull(seen.friendKey)
        assertEquals(joKey, host.state.members.first { it.id == guest.id }.friendKey)
    }

    @Test
    fun `two people asking each other at once become friends without a question`() {
        val sim = Sim()
        val (host, guest, _) = party(sim)
        sim.advance(2_000)
        host.session.addFriend(guest.id)
        guest.session.addFriend(host.id)
        sim.advance(1_000)
        assertNull(host.session.friendRequest.value)
        assertNull(guest.session.friendRequest.value)
        assertEquals(1, host.friends.size)
        assertEquals(1, guest.friends.size)
        assertEquals(emptySet<String>(), host.state.friendAsked)
    }

    @Test
    fun `requests from two members wait their turn and an unanswered ask can be repeated`() {
        val sim = Sim()
        val (host, guest, _) = party(sim)
        val third = sim.device("Kitchen")
        third.session.joinParty("H7KQ29")
        sim.advance(2_000)
        guest.session.addFriend(host.id)
        third.session.addFriend(host.id)
        sim.advance(500)
        assertEquals(guest.id, host.session.friendRequest.value!!.memberId)
        host.session.answerFriendRequest(accept = false)
        assertEquals(third.id, host.session.friendRequest.value!!.memberId)
        host.session.answerFriendRequest(accept = true)
        assertNull(host.session.friendRequest.value)
        sim.advance(500)
        assertEquals(listOf(third.identity!!.publicKeyHex), host.friends.map { it.key })
        assertTrue(host.id in guest.state.friendAsked)
        sim.advance(121_000)
        assertFalse(host.id in guest.state.friendAsked)
    }
}
