package com.nuvio.tv.core.scout.ask

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class AskPrompt(val system: String, val user: String)

internal const val ASK_MAX_QUESTION = 500
internal const val ASK_MAX_LIKED = 30
internal const val ASK_MAX_DISLIKED = 10
internal const val ASK_MAX_TITLES = 20

internal fun buildAskPrompt(question: String, taste: AskTaste, answerLanguage: String): AskPrompt {
    val system = """
        You suggest films and series for a TV app. Answer with JSON only, in this shape:
        {"rowName":"...","includeSeen":false,"titles":[{"title":"...","year":1999,"type":"movie","reason":"..."}]}
        - Up to $ASK_MAX_TITLES real titles that best fit the viewer's request, best first. Never invent a title.
        - title: the name the title is best known by, so a film database can find it. year: year of first release, or 0 if unsure. type: movie or series.
        - reason: one short sentence, at most 15 words, written in $answerLanguage.
        - rowName: at most 5 words naming the request, written in $answerLanguage.
        - The request can be in any language. If it is not about films or series, return an empty titles list.
        - Titles the viewer liked show their taste. Do not suggest them unless the request asks for them.
        - Titles the viewer did not like show what to avoid. Do not suggest them or anything much like them.
        - includeSeen: true only if the request asks for something the viewer has already seen, such as a rewatch. Otherwise false.
        - Do not suggest anything in a genre the viewer leaves out, unless the request asks for it.
    """.trimIndent()
    val user = buildString {
        append("Request: ").append(question.trim().take(ASK_MAX_QUESTION))
        taste.summary?.takeIf { it.isNotBlank() }?.let { append("\nThe viewer's taste in their own words: ").append(it.take(ASK_MAX_SUMMARY)) }
        taste.likedTitles.take(ASK_MAX_LIKED).takeIf { it.isNotEmpty() }?.let {
            append("\nTitles the viewer liked: ").append(it.joinToString("; "))
        }
        taste.dislikedTitles.take(ASK_MAX_DISLIKED).takeIf { it.isNotEmpty() }?.let {
            append("\nTitles the viewer did not like: ").append(it.joinToString("; "))
        }
        taste.leaveOutGenres.takeIf { it.isNotEmpty() }?.let {
            append("\nGenres the viewer leaves out: ").append(it.joinToString(", "))
        }
    }
    return AskPrompt(system, user)
}

private fun strings(vararg values: String) = JsonArray(values.map(::JsonPrimitive))

internal val askAnswerSchema: JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("required", strings("rowName", "includeSeen", "titles"))
    putJsonObject("properties") {
        putJsonObject("rowName") { put("type", "string") }
        putJsonObject("includeSeen") { put("type", "boolean") }
        putJsonObject("titles") {
            put("type", "array")
            putJsonObject("items") {
                put("type", "object")
                put("additionalProperties", false)
                put("required", strings("title", "year", "type", "reason"))
                putJsonObject("properties") {
                    putJsonObject("title") { put("type", "string") }
                    putJsonObject("year") { put("type", "integer") }
                    putJsonObject("type") {
                        put("type", "string")
                        put("enum", strings("movie", "series"))
                    }
                    putJsonObject("reason") { put("type", "string") }
                }
            }
        }
    }
}
