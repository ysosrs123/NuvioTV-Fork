package com.nuvio.tv.core.iptv

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

data class SetupRecordingEntry(val id: String, val title: String, val channel: String, val startMillis: Long, val durationMillis: Long,
    val bytes: Long?, val partial: Boolean, val available: Boolean) {
    override fun toString() = "SetupRecordingEntry(partial=$partial, available=$available)"
}

sealed interface SetupByteRange {
    data object Whole : SetupByteRange
    data object Unsatisfiable : SetupByteRange
    data class Part(val first: Long, val last: Long) : SetupByteRange { val length: Long get() = last - first + 1 }
}

object SetupRecordingDownloads {
    const val LINK_MILLIS = 6 * 60 * 60_000L
    const val MAX_LISTED = 500
    const val MAX_NAME = 80
    val ID = Regex("[A-Za-z0-9-]{8,64}")
    private val NUMBER = Regex("[0-9]{1,18}")
    private val STAMP = DateTimeFormatter.ofPattern("dd-MMM-yy HHmm", Locale.ENGLISH)
    private const val UNSAFE = "\\/:*?\"<>|"
    private const val ATTR = "!#$&+-.^_`|~"

    fun range(header: String?, length: Long, ifRange: String? = null): SetupByteRange {
        if (header == null || ifRange != null) return SetupByteRange.Whole
        val text = header.trim()
        if (!text.startsWith("bytes=", ignoreCase = true)) return SetupByteRange.Whole
        val spec = text.substring(6).trim()
        val dash = spec.indexOf('-')
        if (dash < 0 || spec.contains(',')) return SetupByteRange.Whole
        val from = spec.substring(0, dash).trim()
        val to = spec.substring(dash + 1).trim()
        if (from.isEmpty()) {
            if (!NUMBER.matches(to)) return SetupByteRange.Whole
            val suffix = to.toLong()
            if (suffix == 0L || length <= 0) return SetupByteRange.Unsatisfiable
            return SetupByteRange.Part(maxOf(0L, length - suffix), length - 1)
        }
        if (!NUMBER.matches(from) || (to.isNotEmpty() && !NUMBER.matches(to))) return SetupByteRange.Whole
        val first = from.toLong()
        val last = if (to.isEmpty()) Long.MAX_VALUE else to.toLong()
        if (last < first) return SetupByteRange.Whole
        if (first >= length) return SetupByteRange.Unsatisfiable
        return SetupByteRange.Part(first, minOf(last, length - 1))
    }

    fun contentRange(part: SetupByteRange.Part, length: Long): String = "bytes ${part.first}-${part.last}/$length"

    fun unsatisfiedRange(length: Long): String = "bytes */$length"

    fun fileName(title: String, startMillis: Long, zone: ZoneId): String {
        val cleaned = buildString {
            title.codePoints().forEach { point ->
                append(if (Character.isISOControl(point) || (point < 128 && point.toChar() in UNSAFE) || Character.getType(point) == Character.FORMAT.toInt()) " "
                    else String(Character.toChars(point)))
            }
        }.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").trim(' ', '.', '-')
        val name = cleaned.ifEmpty { "Recording" }.let { text ->
            if (text.codePointCount(0, text.length) <= MAX_NAME) text else text.substring(0, text.offsetByCodePoints(0, MAX_NAME)).trimEnd(' ', '.', '-')
        }
        return name + " " + STAMP.withZone(zone).format(Instant.ofEpochMilli(startMillis))
    }

    fun asciiName(name: String): String {
        val stripped = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val ascii = buildString { stripped.forEach { append(if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in " ._()-") it else '_') } }
            .replace(Regex("_+"), "_").replace(Regex(" +"), " ").trim(' ', '.', '-', '_')
        return if (ascii.any { it.isLetterOrDigit() }) ascii else "Recording"
    }

    fun disposition(name: String, attachment: Boolean): String {
        val full = "$name.ts"
        return (if (attachment) "attachment" else "inline") + "; filename=\"${asciiName(name)}.ts\"; filename*=UTF-8''" + encode(full)
    }

    private fun encode(text: String): String = buildString {
        text.toByteArray(Charsets.UTF_8).forEach { byte ->
            val value = byte.toInt() and 255
            val char = value.toChar()
            if (value < 128 && (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in ATTR)) append(char)
            else append('%').append(HEX[value ushr 4]).append(HEX[value and 15])
        }
    }

    fun json(entries: List<SetupRecordingEntry>, link: (SetupRecordingEntry) -> String?): String = JSONObject().put("recordings", JSONArray().apply {
        entries.forEach { entry ->
            put(JSONObject().put("id", entry.id).put("title", entry.title).put("channel", entry.channel).put("start", entry.startMillis)
                .put("duration", entry.durationMillis).put("status", if (entry.partial) "partial" else "done").put("available", entry.available)
                .apply { entry.bytes?.takeIf { it > 0 }?.let { put("size", it) }; if (entry.available) link(entry)?.let { put("file", it) } })
        }
    }).toString()

    internal fun number(text: String?): Long? = text?.takeIf(NUMBER::matches)?.toLong()

    private const val HEX = "0123456789ABCDEF"
}

class SetupLinkSigner(key: ByteArray, private val validMillis: Long = SetupRecordingDownloads.LINK_MILLIS) {
    private val key = key.copyOf()
    init { require(key.size >= 32 && validMillis > 0) }

    constructor(random: SecureRandom) : this(ByteArray(32).also(random::nextBytes))

    fun expiry(nowMillis: Long): Long = (nowMillis + validMillis) / 1000

    fun sign(profile: Int, id: String, expirySeconds: Long): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        return SetupPairing.hex(mac.doFinal("$profile:$id:$expirySeconds".toByteArray(Charsets.UTF_8)))
    }

    fun query(profile: Int, id: String, nowMillis: Long): String = expiry(nowMillis).let { "e=$it&t=${sign(profile, id, it)}" }

    fun verify(profile: Int, id: String, expiry: String?, signature: String?, nowMillis: Long): Boolean {
        val seconds = SetupRecordingDownloads.number(expiry) ?: return false
        if (signature == null || !SIGNATURE.matches(signature) || !SetupRecordingDownloads.ID.matches(id)) return false
        val now = nowMillis / 1000
        if (seconds <= now || seconds > now + validMillis / 1000 + 1) return false
        return MessageDigest.isEqual(sign(profile, id, seconds).toByteArray(Charsets.US_ASCII), signature.toByteArray(Charsets.US_ASCII))
    }

    override fun toString() = "SetupLinkSigner(withheld)"

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        val SIGNATURE = Regex("[0-9a-f]{64}")
    }
}

class SetupRangeStream(
    private val source: (Long, ByteArray, Int, Int) -> Int,
    start: Long,
    length: Long,
    private val onClose: () -> Unit,
    private val onRead: () -> Unit = {},
) : InputStream() {
    init { require(start >= 0 && length >= 0) }
    private var position = start
    private var remaining = length
    private val closed = AtomicBoolean(false)

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed.get()) throw IOException("Stream closed")
        if (length == 0) return 0
        if (remaining <= 0) return -1
        val count = try { source(position, buffer, offset, minOf(length.toLong(), remaining).toInt()) } catch (_: Exception) {
            throw IOException("Recording read failed")
        }
        if (count <= 0) { remaining = 0; return -1 }
        position += count
        remaining -= count
        onRead()
        return count
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }
}
