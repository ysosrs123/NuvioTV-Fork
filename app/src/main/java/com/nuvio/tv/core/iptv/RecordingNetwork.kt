package com.nuvio.tv.core.iptv

import java.io.StringReader
import java.security.MessageDigest
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class FtpReply(val code: Int, val text: String) {
    val preliminary: Boolean get() = code in 100..199
    val ok: Boolean get() = code in 200..399
}

object FtpProtocol {
    fun extendedPort(text: String): Int? {
        val inner = text.substringAfter('(', "").substringBefore(')', "")
        if (inner.length < 5) return null
        val delimiter = inner[0]
        val fields = inner.split(delimiter)
        if (fields.size != 5) return null
        return fields[3].toIntOrNull()?.takeIf { it in 1..65535 }
    }

    fun passive(text: String): Pair<String, Int>? {
        val numbers = Regex("(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3})").find(text)?.groupValues?.drop(1)?.map { it.toInt() } ?: return null
        if (numbers.any { it > 255 }) return null
        val port = numbers[4] * 256 + numbers[5]
        if (port == 0) return null
        return numbers.take(4).joinToString(".") to port
    }

    fun dataHost(passive: String, control: String, controlAddress: ByteArray?): String {
        val offered = ipv4(passive) ?: return control
        val peer = controlAddress?.takeIf { it.size == 4 }
        if (peer != null && peer.contentEquals(offered)) return control
        if (!public(offered)) return control
        if (peer == null || !public(peer)) return control
        return passive
    }

    fun names(lines: List<String>, machine: Boolean): List<String> = lines.mapNotNull { line ->
        val clean = line.trimEnd('\r')
        if (clean.isBlank()) return@mapNotNull null
        if (!machine) return@mapNotNull clean.substringAfterLast('/').takeIf { it.isNotEmpty() && it != "." && it != ".." }
        val space = clean.indexOf(' ')
        if (space < 0) return@mapNotNull null
        val facts = clean.substring(0, space).lowercase().split(';')
        val type = facts.firstOrNull { it.startsWith("type=") }?.substringAfter('=')
        val name = clean.substring(space + 1)
        if (type == "cdir" || type == "pdir" || name == "." || name == ".." || name.isEmpty()) null else name
    }

    fun reuseRequired(reply: FtpReply): Boolean = reply.code == 522 || reply.text.contains("reuse", true) || reply.text.contains("resum", true)

    fun directory(text: String): String? {
        val start = text.indexOf('"')
        if (start < 0) return null
        val result = StringBuilder()
        var index = start + 1
        while (index < text.length) {
            val c = text[index]
            if (c == '"') {
                if (index + 1 < text.length && text[index + 1] == '"') { result.append('"'); index += 2; continue }
                return result.toString().takeIf { it.isNotEmpty() }
            }
            result.append(c)
            index++
        }
        return null
    }

    fun facts(line: String): Map<String, String> = line.trim().substringBefore(' ').split(';').filter { '=' in it }
        .associate { it.substringBefore('=').lowercase() to it.substringAfter('=') }

    private fun ipv4(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val values = parts.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: return null }
        return ByteArray(4) { values[it].toByte() }
    }

    private fun public(address: ByteArray): Boolean {
        val a = address[0].toInt() and 0xff
        val b = address[1].toInt() and 0xff
        return when {
            a == 0 || a == 10 || a == 127 || a >= 224 -> false
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 168 -> false
            a == 100 && b in 64..127 -> false
            else -> true
        }
    }
}

data class WebDavChallenge(val scheme: String, val params: Map<String, String>)

object WebDavAuth {
    fun challenges(headers: List<String>): List<WebDavChallenge> = headers.flatMap(::parse)

    fun basic(username: String, password: String): String =
        "Basic " + java.util.Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))

    fun digest(challenge: WebDavChallenge, username: String, password: String, method: String, uri: String, count: Int, cnonce: String): String? {
        val realm = challenge.params["realm"] ?: return null
        val nonce = challenge.params["nonce"] ?: return null
        val algorithm = challenge.params["algorithm"] ?: "MD5"
        val hash = when (algorithm.uppercase().removeSuffix("-SESS")) {
            "MD5" -> "MD5"
            "SHA-256" -> "SHA-256"
            else -> return null
        }
        val qops = challenge.params["qop"]?.split(',')?.map { it.trim().lowercase() }
        if (qops != null && "auth" !in qops) return null
        fun h(text: String) = MessageDigest.getInstance(hash).digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        var ha1 = h("$username:$realm:$password")
        if (algorithm.endsWith("-sess", true)) ha1 = h("$ha1:$nonce:$cnonce")
        val ha2 = h("$method:$uri")
        val nc = "%08x".format(count)
        val response = if (qops == null) h("$ha1:$nonce:$ha2") else h("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
        val parts = mutableListOf("username=\"${quote(username)}\"", "realm=\"${quote(realm)}\"", "nonce=\"${quote(nonce)}\"", "uri=\"${quote(uri)}\"",
            "response=\"$response\"", "algorithm=$algorithm")
        challenge.params["opaque"]?.let { parts += "opaque=\"${quote(it)}\"" }
        if (qops != null) { parts += "qop=auth"; parts += "nc=$nc"; parts += "cnonce=\"$cnonce\"" }
        return "Digest " + parts.joinToString(", ")
    }

    private fun quote(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun parse(header: String): List<WebDavChallenge> {
        val result = ArrayList<WebDavChallenge>()
        var index = 0
        var scheme: String? = null
        var params = LinkedHashMap<String, String>()
        fun finish() { scheme?.let { result += WebDavChallenge(it.lowercase(), params) }; scheme = null; params = LinkedHashMap() }
        while (index < header.length) {
            while (index < header.length && (header[index] == ' ' || header[index] == ',')) index++
            if (index >= header.length) break
            val start = index
            while (index < header.length && header[index] != ' ' && header[index] != '=' && header[index] != ',') index++
            val token = header.substring(start, index)
            var look = index
            while (look < header.length && header[look] == ' ') look++
            if (look < header.length && header[look] == '=' && scheme != null) {
                index = look + 1
                if (index < header.length && header[index] == '=') {
                    while (index < header.length && header[index] != ',') index++
                    continue
                }
                while (index < header.length && header[index] == ' ') index++
                val value = StringBuilder()
                if (index < header.length && header[index] == '"') {
                    index++
                    while (index < header.length && header[index] != '"') {
                        if (header[index] == '\\' && index + 1 < header.length) index++
                        value.append(header[index])
                        index++
                    }
                    index++
                } else {
                    while (index < header.length && header[index] != ',') { value.append(header[index]); index++ }
                }
                params[token.lowercase()] = value.toString().trim()
            } else {
                finish()
                scheme = token
            }
        }
        finish()
        return result
    }
}

data class WebDavEntry(val path: String, val collection: Boolean, val length: Long?, val available: Long?)

object WebDavXml {
    fun propfind(vararg names: String): String = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop>" +
        names.joinToString("") { "<d:$it/>" } + "</d:prop></d:propfind>"

    fun parse(text: String): List<WebDavEntry> {
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "Document type not allowed" }
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        try { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) } catch (_: Exception) { }
        parser.setInput(StringReader(text))
        val result = ArrayList<WebDavEntry>()
        var href: String? = null
        var collection = false
        var length: Long? = null
        var available: Long? = null
        val stack = ArrayList<String>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> {
                    val name = if (parser.namespace == "DAV:") parser.name else "?"
                    stack += name
                    when (name) {
                        "response" -> { href = null; collection = false; length = null; available = null }
                        "collection" -> if ("resourcetype" in stack) collection = true
                    }
                }
                XmlPullParser.TEXT -> {
                    val value = parser.text.trim()
                    when (stack.lastOrNull()) {
                        "href" -> if (stack.getOrNull(stack.size - 2) == "response") href = value
                        "getcontentlength" -> length = value.toLongOrNull()?.takeIf { it >= 0 }
                        "quota-available-bytes" -> available = value.toLongOrNull()
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = stack.removeAt(stack.size - 1)
                    if (name == "response") href?.let { result += WebDavEntry(hrefPath(it) ?: return@let, collection, length, available) }
                }
            }
        }
        return result
    }

    fun hrefPath(href: String): String? {
        val path = if ("://" in href) href.substringAfter("://").let { rest -> rest.indexOf('/').let { if (it < 0) "" else rest.substring(it) } } else href
        return path.substringBefore('?').split('/').filter { it.isNotEmpty() }.map { RecordingShareAddress.decode(it) ?: return null }.joinToString("/")
    }
}
