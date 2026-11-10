package com.nuvio.tv.core.scout.ask

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal const val ASK_MAX_OUTPUT_TOKENS = 4096
private const val CLAUDE_API_VERSION = "2026-10-21"

data class AskHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String?
) {
    override fun toString(): String = "AskHttpRequest($url)"
}

data class AskHttpResponse(val code: Int, val body: String)

sealed interface AskReply {
    data class Text(val text: String, val cutOff: Boolean) : AskReply
    data class Failed(val failure: AskFailure) : AskReply
}

internal object AskConnectors {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun answerRequest(config: AskConfig, prompt: AskPrompt, model: String = config.baseModel): AskHttpRequest =
        when (config.provider.format) {
            AskFormat.OPENAI -> openAiRequest(config, prompt, model)
            AskFormat.CLAUDE -> claudeRequest(config, prompt, model)
            AskFormat.GEMINI -> geminiRequest(config, prompt, model)
        }

    /** Listing models costs nothing on any service and shows both that the key works and that the model exists. */
    fun modelsRequest(config: AskConfig): AskHttpRequest =
        AskHttpRequest("${config.effectiveBase}/models", authHeaders(config), null)

    private fun authHeaders(config: AskConfig): Map<String, String> = when (config.provider.format) {
        AskFormat.OPENAI -> mapOf("Authorization" to "Bearer ${config.key}")
        AskFormat.CLAUDE -> mapOf("x-api-key" to config.key, "anthropic-version" to CLAUDE_API_VERSION)
        AskFormat.GEMINI -> mapOf("x-goog-api-key" to config.key)
    }

    private fun openAiRequest(config: AskConfig, prompt: AskPrompt, model: String): AskHttpRequest {
        val provider = config.provider
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", prompt.system) }
                addJsonObject { put("role", "user"); put("content", prompt.user) }
            }
            if (provider == AskProvider.OTHER) put("max_tokens", ASK_MAX_OUTPUT_TOKENS)
            else put("max_completion_tokens", ASK_MAX_OUTPUT_TOKENS)
            provider.effort?.let { put("reasoning_effort", it) }
            when (provider) {
                AskProvider.CHATGPT -> putJsonObject("response_format") {
                    put("type", "json_schema")
                    putJsonObject("json_schema") {
                        put("name", "titles")
                        put("strict", true)
                        put("schema", askAnswerSchema)
                    }
                }
                AskProvider.GROQ -> putJsonObject("response_format") { put("type", "json_object") }
                else -> Unit
            }
        }
        return AskHttpRequest(
            "${config.effectiveBase}/chat/completions",
            authHeaders(config) + ("Content-Type" to "application/json"),
            body.toString()
        )
    }

    private fun claudeRequest(config: AskConfig, prompt: AskPrompt, model: String): AskHttpRequest {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", ASK_MAX_OUTPUT_TOKENS)
            put("system", prompt.system)
            putJsonArray("messages") {
                addJsonObject { put("role", "user"); put("content", prompt.user) }
            }
            putJsonObject("output_config") {
                config.provider.effort?.let { put("effort", it) }
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("schema", askAnswerSchema)
                }
            }
        }
        return AskHttpRequest(
            "${config.effectiveBase}/messages",
            authHeaders(config) + ("Content-Type" to "application/json"),
            body.toString()
        )
    }

    private fun geminiRequest(config: AskConfig, prompt: AskPrompt, model: String): AskHttpRequest {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", prompt.system) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", prompt.user) } }
                }
            }
            putJsonObject("generationConfig") {
                put("maxOutputTokens", ASK_MAX_OUTPUT_TOKENS)
                put("responseMimeType", "application/json")
                put("responseJsonSchema", askAnswerSchema)
            }
        }
        return AskHttpRequest(
            "${config.effectiveBase}/models/$model:generateContent",
            authHeaders(config) + ("Content-Type" to "application/json"),
            body.toString()
        )
    }

    fun parseReply(config: AskConfig, response: AskHttpResponse): AskReply {
        if (response.code !in 200..299) return AskReply.Failed(failureFor(response))
        val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: return AskReply.Failed(AskFailure.BAD_ANSWER)
        return when (config.provider.format) {
            AskFormat.OPENAI -> parseOpenAi(root)
            AskFormat.CLAUDE -> parseClaude(root)
            AskFormat.GEMINI -> parseGemini(root)
        }
    }

    fun parseKeyTest(config: AskConfig, response: AskHttpResponse): AskKeyTest {
        if (response.code !in 200..299) return AskKeyTest.Failed(failureFor(response))
        val wanted = config.baseModel
        if (wanted.isEmpty()) return AskKeyTest.Failed(AskFailure.MODEL_GONE)
        val listed = modelIds(response.body)
        val family = config.provider.family
        val present = wanted in listed || (family != null && listed.any(family::matches))
        return if (listed.isNotEmpty() && !present) AskKeyTest.Failed(AskFailure.MODEL_GONE) else AskKeyTest.Good
    }

    /** The newest model of a family, so a new version is used once the service offers it. */
    fun newestOfFamily(family: Regex, listBody: String): String? {
        val models = modelEntries(listBody).filter { family.matches(it.first) }
        if (models.isEmpty()) return null
        return if (models.all { it.second != null }) models.maxByOrNull { it.second ?: 0L }?.first else models.first().first
    }

    private fun modelIds(body: String): List<String> = modelEntries(body).map { it.first }

    private fun modelEntries(body: String): List<Pair<String, Long?>> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val list = (root["data"] ?: root["models"]) as? JsonArray ?: return emptyList()
        return list.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = (obj.string("id") ?: obj.string("name"))?.removePrefix("models/") ?: return@mapNotNull null
            id to (obj["created"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        }
    }

    private fun failureFor(response: AskHttpResponse): AskFailure {
        val body = response.body.lowercase()
        return when {
            response.code == 402 || "insufficient_quota" in body || "credit balance" in body -> AskFailure.NO_CREDIT
            "user location is not supported" in body -> AskFailure.REGION
            response.code == 401 || response.code == 403 -> AskFailure.BAD_KEY
            response.code == 400 && ("api key not valid" in body || "api_key_invalid" in body) -> AskFailure.BAD_KEY
            response.code == 429 -> AskFailure.RATE_LIMITED
            response.code == 404 -> AskFailure.MODEL_GONE
            response.code == 400 && "model" in body &&
                ("not found" in body || "does not exist" in body || "decommissioned" in body) -> AskFailure.MODEL_GONE
            response.code == 408 || response.code >= 500 -> AskFailure.BUSY
            else -> AskFailure.REJECTED
        }
    }

    private fun parseOpenAi(root: JsonObject): AskReply {
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return AskReply.Failed(AskFailure.BAD_ANSWER)
        val message = choice["message"] as? JsonObject
        val finish = choice.string("finish_reason")
        if (finish == "content_filter" || message?.string("refusal") != null) return AskReply.Failed(AskFailure.DECLINED)
        val text = message?.string("content")
        if (text.isNullOrBlank()) return AskReply.Failed(if (finish == "length") AskFailure.CUT_OFF else AskFailure.BAD_ANSWER)
        return AskReply.Text(text, finish == "length")
    }

    private fun parseClaude(root: JsonObject): AskReply {
        val stop = root.string("stop_reason")
        if (stop == "refusal") return AskReply.Failed(AskFailure.DECLINED)
        val text = (root["content"] as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .filter { it.string("type") == "text" }
            .joinToString("") { it.string("text").orEmpty() }
        if (text.isBlank()) return AskReply.Failed(if (stop == "max_tokens") AskFailure.CUT_OFF else AskFailure.BAD_ANSWER)
        return AskReply.Text(text, stop == "max_tokens")
    }

    private fun parseGemini(root: JsonObject): AskReply {
        if ((root["promptFeedback"] as? JsonObject)?.string("blockReason") != null) return AskReply.Failed(AskFailure.DECLINED)
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return AskReply.Failed(AskFailure.BAD_ANSWER)
        val finish = candidate.string("finishReason")
        if (finish != null && finish != "STOP" && finish != "MAX_TOKENS") return AskReply.Failed(AskFailure.DECLINED)
        val parts = ((candidate["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .filterNot { it.string("thought") == "true" }
        val text = parts.joinToString("") { it.string("text").orEmpty() }
        if (text.isBlank()) return AskReply.Failed(if (finish == "MAX_TOKENS") AskFailure.CUT_OFF else AskFailure.BAD_ANSWER)
        return AskReply.Text(text, finish == "MAX_TOKENS")
    }

    /** Models wrap the JSON in fences or stray thinking text, and can stop part way through it. */
    fun parseAnswer(text: String, cutOff: Boolean = false): AskParsed? {
        val cleaned = text.replace(Regex("(?s)<think>.*?</think>"), "")
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val whole = cleaned.substring(start, end + 1)
        val root = listOfNotNull(whole, (whole + "]}").takeIf { cutOff }).firstNotNullOfOrNull { candidate ->
            runCatching { json.parseToJsonElement(candidate).jsonObject }.getOrNull()
        } ?: return null
        val titles = root["titles"] as? JsonArray ?: return null
        val picks = titles.filterIsInstance<JsonObject>().mapNotNull(::pickFrom)
            .distinctBy { it.title.lowercase() to it.year }
            .take(ASK_MAX_TITLES)
        return AskParsed(clean(root.string("rowName").orEmpty(), 60), picks, root.string("includeSeen") == "true")
    }

    private fun pickFrom(item: JsonObject): AskPick? {
        val title = clean(item.string("title").orEmpty(), 200).ifEmpty { return null }
        val year = (item["year"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1880..2100 }
        val series = when (item.string("type")?.trim()?.lowercase()) {
            "movie", "film" -> false
            "series", "tv", "show", "tvshow", "tv series" -> true
            else -> null
        }
        return AskPick(title, year, series, clean(item.string("reason").orEmpty(), 200))
    }

    private fun clean(value: String, max: Int): String =
        value.filterNot { it.isISOControl() }.trim().take(max)

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
}
