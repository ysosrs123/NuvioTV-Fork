package com.nuvio.tv.core.iptv

import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import org.json.JSONObject

class SetupTransferReceiver(
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Long = System::currentTimeMillis,
    val maxAttempts: Int = 5,
    private val ticketMillis: Long = 120_000,
) {
    class Hello(val key: ByteArray, val nonce: ByteArray, val revision: Int)
    sealed interface Offer {
        class Accepted(val ticket: String, val proof: ByteArray) : Offer { override fun toString() = "Accepted" }
        data class Refused(val attemptsLeft: Int) : Offer
        data object Renewed : Offer
        data object Busy : Offer
    }
    sealed interface Delivery {
        class Opened(val payload: ByteArray) : Delivery { override fun toString() = "Opened" }
        data object Unknown : Delivery
        data object Damaged : Delivery
    }

    private class Pending(val ticket: String, val session: SetupTransferSession, val until: Long)

    var code: String = newCode(null)
        private set
    var revision: Int = 0
        private set
    private var keys = SetupTransferKeys(random)
    private var nonce = ByteArray(SetupTransferCrypto.NONCE_BYTES).also(random::nextBytes)
    private var attempts = 0
    private var pending: Pending? = null
    private var closed = false

    @Synchronized fun hello(): Hello = Hello(keys.publicKey, nonce.copyOf(), revision)

    @Synchronized fun offer(revision: Int, senderKey: ByteArray, senderNonce: ByteArray, proof: ByteArray): Offer {
        if (closed || revision != this.revision) return Offer.Renewed
        pending?.let { if (now() < it.until) return Offer.Busy else { it.session.close(); pending = null } }
        if (senderNonce.size != SetupTransferCrypto.NONCE_BYTES || proof.size != 32) return refuse()
        val session = try {
            SetupTransferCrypto.session(keys, senderKey, code, keys.publicKey, senderKey, nonce, senderNonce)
        } catch (_: Exception) { return refuse() }
        if (!session.accepts(SetupTransferCrypto.SENDER, proof)) { session.close(); return refuse() }
        val ticket = SetupPairing.hex(ByteArray(16).also(random::nextBytes))
        pending = Pending(ticket, session, now() + ticketMillis)
        attempts = 0
        return Offer.Accepted(ticket, session.proof(SetupTransferCrypto.RECEIVER))
    }

    @Synchronized fun deliver(ticket: String, box: ByteArray, limit: Int = SetupVault.MAX_PLAIN_BYTES): Delivery {
        val current = pending ?: return Delivery.Unknown
        if (closed || !SetupPairing.same(current.ticket, ticket) || now() >= current.until) return Delivery.Unknown
        pending = null
        return try { Delivery.Opened(current.session.open(box, limit)) }
        catch (_: SetupCryptoException) { Delivery.Damaged }
        finally { current.session.close(); renew() }
    }

    @Synchronized fun renew() {
        pending?.session?.close()
        pending = null
        attempts = 0
        keys = SetupTransferKeys(random)
        nonce = ByteArray(SetupTransferCrypto.NONCE_BYTES).also(random::nextBytes)
        code = newCode(code)
        revision++
    }

    @Synchronized fun close() { closed = true; renew() }

    private fun refuse(): Offer {
        attempts++
        if (attempts >= maxAttempts) { renew(); return Offer.Renewed }
        return Offer.Refused(maxAttempts - attempts)
    }

    private fun newCode(previous: String?): String {
        while (true) String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000)).let { if (it != previous) return it }
    }
}

class SetupTransferOffer(val session: SetupTransferSession, val body: String)

object SetupTransferWire {
    const val SERVICE_TYPE = "_nuviolivetv._tcp."
    const val TICKET_HEADER = "x-nuvio-ticket"
    const val MAX_BOX_BYTES = 16 * 1024 * 1024
    const val MAX_JSON_BYTES = 8 * 1024
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    fun hello(value: SetupTransferReceiver.Hello, name: String): String =
        JSONObject().put("key", encoder.encodeToString(value.key)).put("nonce", encoder.encodeToString(value.nonce)).put("revision", value.revision).put("name", name).toString()

    fun offer(code: String, hello: String, random: SecureRandom = SecureRandom()): SetupTransferOffer {
        val json = JSONObject(hello)
        val receiverKey = decoder.decode(json.getString("key"))
        val receiverNonce = decoder.decode(json.getString("nonce"))
        require(receiverNonce.size == SetupTransferCrypto.NONCE_BYTES)
        val keys = SetupTransferKeys(random)
        val nonce = ByteArray(SetupTransferCrypto.NONCE_BYTES).also(random::nextBytes)
        val session = SetupTransferCrypto.session(keys, receiverKey, code, receiverKey, keys.publicKey, receiverNonce, nonce)
        val body = JSONObject().put("revision", json.getInt("revision")).put("key", encoder.encodeToString(keys.publicKey))
            .put("nonce", encoder.encodeToString(nonce)).put("proof", encoder.encodeToString(session.proof(SetupTransferCrypto.SENDER))).toString()
        return SetupTransferOffer(session, body)
    }

    class OfferInput(val revision: Int, val key: ByteArray, val nonce: ByteArray, val proof: ByteArray)

    fun readOffer(body: String): OfferInput? = try {
        val json = JSONObject(body)
        OfferInput(json.getInt("revision"), decoder.decode(json.getString("key")), decoder.decode(json.getString("nonce")), decoder.decode(json.getString("proof")))
    } catch (_: Exception) { null }

    fun accepted(value: SetupTransferReceiver.Offer.Accepted): String =
        JSONObject().put("ticket", value.ticket).put("proof", encoder.encodeToString(value.proof)).toString()

    fun confirm(offer: SetupTransferOffer, answer: String): String? = try {
        val json = JSONObject(answer)
        val ticket = json.getString("ticket").takeIf { it.matches(Regex("[0-9a-f]{32}")) }
        ticket?.takeIf { offer.session.accepts(SetupTransferCrypto.RECEIVER, decoder.decode(json.getString("proof"))) }
    } catch (_: Exception) { null }

    fun name(hello: String): String = runCatching { JSONObject(hello).optString("name").take(80).filterNot(Char::isISOControl) }.getOrDefault("")
}
