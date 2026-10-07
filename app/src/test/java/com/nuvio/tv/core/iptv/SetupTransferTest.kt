package com.nuvio.tv.core.iptv

import java.security.SecureRandom
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SetupTransferTest {
    private var clock = 1_000L
    private val receiver = SetupTransferReceiver(SecureRandom(), { clock })
    private val payload = "{\"sources\":[\"secret\"]}".toByteArray()

    private fun offer(code: String): Pair<SetupTransferOffer, SetupTransferReceiver.Offer> {
        val made = SetupTransferWire.offer(code, SetupTransferWire.hello(receiver.hello(), "Lounge TV"))
        val input = requireNotNull(SetupTransferWire.readOffer(made.body))
        return made to receiver.offer(input.revision, input.key, input.nonce, input.proof)
    }

    @Test fun matchingCodesDeliverTheSetupOnce() {
        val code = receiver.code
        val (sent, answer) = offer(code)
        assertFalse(sent.body.contains(code))
        val accepted = answer as SetupTransferReceiver.Offer.Accepted
        val ticket = requireNotNull(SetupTransferWire.confirm(sent, SetupTransferWire.accepted(accepted)))
        val opened = receiver.deliver(ticket, sent.session.seal(payload)) as SetupTransferReceiver.Delivery.Opened
        assertArrayEquals(payload, opened.payload)
        assertNotEquals(code, receiver.code)
        assertEquals(SetupTransferReceiver.Delivery.Unknown, receiver.deliver(ticket, sent.session.seal(payload)))
        assertEquals("Lounge TV", SetupTransferWire.name(SetupTransferWire.hello(receiver.hello(), "Lounge TV")))
    }

    @Test fun wrongCodesUseUpAttemptsAndThenRenewTheCode() {
        val code = receiver.code
        val wrong = if (code == "000000") "000001" else "000000"
        for (left in 4 downTo 1) assertEquals(SetupTransferReceiver.Offer.Refused(left), offer(wrong).second)
        val revision = receiver.hello().revision
        assertEquals(SetupTransferReceiver.Offer.Renewed, offer(wrong).second)
        assertNotEquals(code, receiver.code)
        assertEquals(revision + 1, receiver.hello().revision)
        assertTrue(offer(receiver.code).second is SetupTransferReceiver.Offer.Accepted)
    }

    @Test fun oldKeysForgedAnswersAndTamperedPayloadsAreRefused() {
        val stale = SetupTransferWire.offer(receiver.code, SetupTransferWire.hello(receiver.hello(), "TV"))
        receiver.renew()
        val input = requireNotNull(SetupTransferWire.readOffer(stale.body))
        assertEquals(SetupTransferReceiver.Offer.Renewed, receiver.offer(input.revision, input.key, input.nonce, input.proof))
        val (sent, answer) = offer(receiver.code)
        val accepted = answer as SetupTransferReceiver.Offer.Accepted
        val forged = JSONObject(SetupTransferWire.accepted(accepted)).put("proof", java.util.Base64.getEncoder().encodeToString(ByteArray(32))).toString()
        assertNull(SetupTransferWire.confirm(sent, forged))
        assertEquals(SetupTransferReceiver.Offer.Busy, offer(receiver.code).second)
        val box = sent.session.seal(payload).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertEquals(SetupTransferReceiver.Delivery.Damaged, receiver.deliver(accepted.ticket, box))
        assertNull(SetupTransferWire.readOffer("{\"revision\":1}"))
    }

    @Test fun ticketsExpire() {
        val (sent, answer) = offer(receiver.code)
        val accepted = answer as SetupTransferReceiver.Offer.Accepted
        clock += 120_001
        assertEquals(SetupTransferReceiver.Delivery.Unknown, receiver.deliver(accepted.ticket, sent.session.seal(payload)))
        assertTrue(offer(receiver.code).second is SetupTransferReceiver.Offer.Accepted)
    }

    @Test fun closedReceiversRefuseEverything() {
        val hello = SetupTransferWire.hello(receiver.hello(), "TV")
        receiver.close()
        val made = SetupTransferWire.offer(receiver.code, hello)
        val input = requireNotNull(SetupTransferWire.readOffer(made.body))
        assertEquals(SetupTransferReceiver.Offer.Renewed, receiver.offer(receiver.hello().revision, input.key, input.nonce, input.proof))
    }
}
