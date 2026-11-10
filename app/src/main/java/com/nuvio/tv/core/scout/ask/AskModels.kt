package com.nuvio.tv.core.scout.ask

import com.nuvio.tv.BuildConfig

object ScoutAskFeature {
    val available: Boolean get() = BuildConfig.SCOUT_ENABLED
}

enum class AskFormat { OPENAI, CLAUDE, GEMINI }

enum class AskProvider(
    val id: String,
    val format: AskFormat,
    val baseUrl: String,
    val baseModel: String,
    val fallbacks: List<String>,
    val family: Regex?,
    val effort: String?,
    val freeTier: Boolean
) {
    GEMINI("gemini", AskFormat.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "gemini-flash-lite-latest", listOf("gemini-3.1-flash-lite", "gemini-3.5-flash-lite"), null, null, true),
    GROQ("groq", AskFormat.OPENAI, "https://api.groq.com/openai/v1", "openai/gpt-oss-120b", emptyList(), null, "low", true),
    CHATGPT("chatgpt", AskFormat.OPENAI, "https://api.openai.com/v1", "gpt-6-luna", emptyList(), Regex("gpt-[\\d.]+-luna"), "low", false),
    CLAUDE("claude", AskFormat.CLAUDE, "https://api.anthropic.com/v1", "claude-sonnet-5-5", listOf("claude-haiku-5-5"), Regex("claude-sonnet-\\d[\\d-]*"), "low", false),
    OTHER("other", AskFormat.OPENAI, "", "", emptyList(), null, null, false);

    companion object {
        fun fromId(id: String?): AskProvider? = entries.firstOrNull { it.id == id }
    }
}

data class AskConfig(
    val provider: AskProvider,
    val key: String,
    val model: String = "",
    val baseUrl: String = ""
) {
    val baseModel: String get() = if (provider == AskProvider.OTHER) model.trim() else provider.baseModel
    val effectiveBase: String get() = (if (provider == AskProvider.OTHER) baseUrl else provider.baseUrl).trim().trimEnd('/')

    override fun toString(): String = "AskConfig(${provider.id}, model=$baseModel)"
}

enum class AskFailure {
    NO_KEY, BAD_KEY, NO_CREDIT, RATE_LIMITED, MODEL_GONE, REGION, DECLINED, CUT_OFF, BUSY, NETWORK, BAD_ANSWER, NOTHING_FOUND, REJECTED
}

data class AskTaste(
    val likedTitles: List<String> = emptyList(),
    val dislikedTitles: List<String> = emptyList(),
    val leaveOutGenres: List<String> = emptyList(),
    val summary: String? = null
)

/** What a user has rated. With no source bound, only the question is sent. */
interface AskTasteSource {
    suspend fun taste(): AskTaste
}

data class AskPick(
    val title: String,
    val year: Int?,
    val isSeries: Boolean?,
    val reason: String
)

data class AskParsed(val rowName: String, val picks: List<AskPick>, val includeSeen: Boolean = false)

sealed interface AskKeyTest {
    data object Good : AskKeyTest
    data class Failed(val failure: AskFailure) : AskKeyTest
}

/** A key may only go to a secure address, or in the clear to a machine on the user's own network. */
internal fun isAllowedAskBase(url: String): Boolean {
    val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return when (uri.scheme?.lowercase()) {
        "https" -> true
        "http" -> host == "localhost" || host.endsWith(".local") || isPrivateIpv4(host)
        else -> false
    }
}

private fun isPrivateIpv4(host: String): Boolean {
    val parts = host.split('.').map { it.toIntOrNull() ?: return false }
    if (parts.size != 4 || parts.any { it !in 0..255 }) return false
    return parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31)
}
