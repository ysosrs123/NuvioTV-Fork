package com.nuvio.tv.core.scout.ask

import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

private const val MAX_BODY_BYTES = 16 * 1024
private const val TOKEN_HEADER = "x-ask-token"

data class AskPhoneKeyStatus(val provider: AskProvider, val hasKey: Boolean, val model: String)

data class AskPhoneState(
    val selected: AskProvider,
    val active: AskProvider?,
    val providers: List<AskPhoneKeyStatus>,
    val testMessage: String?,
    val testGood: Boolean
)

internal fun newAskPhoneToken(): String {
    val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Runs only while the Ask screen is open. The code in the QR is needed for every request, so another
 * device on the network cannot add a key or a question; a stored key is never sent back out.
 */
class AskPhoneServer(
    private val token: String,
    private val page: () -> String,
    private val state: () -> AskPhoneState,
    private val onKey: (AskConfig) -> Boolean,
    private val onQuestion: (String) -> Unit,
    private val logo: () -> ByteArray?,
    port: Int
) : NanoHTTPD(port) {
    private val json = Json { ignoreUnknownKeys = true }

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.GET && session.uri == "/") {
            return if (session.parameters["t"]?.firstOrNull() == token) html(page()) else forbidden()
        }
        if (session.method == Method.GET && session.uri == "/logo.png") {
            if (session.parameters["t"]?.firstOrNull() != token) return forbidden()
            val bytes = logo() ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found").closing()
            return newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong())
        }
        if (session.headers[TOKEN_HEADER] != token) return forbidden()
        return when {
            session.method == Method.GET && session.uri == "/api/state" -> ok(stateJson(state()).toString())
            session.method == Method.POST && session.uri == "/api/key" -> handleKey(session)
            session.method == Method.POST && session.uri == "/api/question" -> handleQuestion(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found").closing()
        }
    }

    private fun handleKey(session: IHTTPSession): Response {
        val body = readBody(session) ?: return badRequest()
        val config = parseAskPhoneKey(body) ?: return badRequest()
        return if (onKey(config)) ok("""{"status":"saved"}""") else badRequest()
    }

    private fun handleQuestion(session: IHTTPSession): Response {
        val body = readBody(session) ?: return badRequest()
        val question = body.text("question")?.trim()?.take(ASK_MAX_QUESTION).orEmpty()
        if (question.isEmpty()) return badRequest()
        onQuestion(question)
        return ok("""{"status":"asked"}""")
    }

    private fun readBody(session: IHTTPSession): JsonObject? {
        val length = session.headers["content-length"]?.toIntOrNull() ?: return null
        if (length !in 1..MAX_BODY_BYTES) return null
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = session.inputStream.read(buffer, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return runCatching {
            json.parseToJsonElement(String(buffer, 0, offset, StandardCharsets.UTF_8)).jsonObject
        }.getOrNull()
    }

    private fun html(body: String) = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
    private fun ok(body: String) = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)
    private fun forbidden() = newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Scan the code on the TV again").closing()
    private fun badRequest() = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", """{"error":"bad request"}""").closing()

    /** A refused request's body may be unread, and would be taken for the next request on the same connection. */
    private fun Response.closing(): Response = apply { addHeader("connection", "close") }

    companion object {
        fun startOnAvailablePort(
            token: String,
            page: () -> String,
            state: () -> AskPhoneState,
            onKey: (AskConfig) -> Boolean,
            onQuestion: (String) -> Unit,
            logo: () -> ByteArray? = { null },
            startPort: Int = 8103,
            maxAttempts: Int = 10
        ): AskPhoneServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    return AskPhoneServer(token, page, state, onKey, onQuestion, logo, port).also { it.start(SOCKET_READ_TIMEOUT, false) }
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}

private fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull

internal fun parseAskPhoneKey(body: JsonObject): AskConfig? {
    val provider = AskProvider.fromId(body.text("provider")) ?: return null
    val key = body.text("key")?.trim().orEmpty()
    if (key.isEmpty() || key.length > 1024 || key.any { it.code !in 33..126 }) return null
    if (provider != AskProvider.OTHER) return AskConfig(provider, key)
    val model = body.text("model")?.trim().orEmpty().take(200)
    val baseUrl = body.text("baseUrl")?.trim().orEmpty().take(500)
    if (model.isEmpty() || !isAllowedAskBase(baseUrl)) return null
    return AskConfig(provider, key, model, baseUrl)
}

internal fun stateJson(state: AskPhoneState): JsonObject = buildJsonObject {
    put("selected", state.selected.id)
    state.active?.let { put("active", it.id) }
    state.testMessage?.let { put("test", it) }
    put("testGood", state.testGood)
    putJsonArray("providers") {
        state.providers.forEach {
            addJsonObject {
                put("id", it.provider.id)
                put("hasKey", it.hasKey)
                put("model", it.model)
            }
        }
    }
}
