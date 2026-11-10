package com.nuvio.tv.core.scout.ask

import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val SECRET = "sk-test-SECRET-1234567890"
private const val ANSWER = """{"rowName":"Twisty","titles":[{"title":"Primer","year":2004,"type":"movie","reason":"Clever"},{"title":"Dark","year":2017,"type":"series","reason":"Knotty"},{"title":"Made Up","year":2020,"type":"movie","reason":"None"}]}"""

private fun meta(id: String, series: Boolean = false) = MetaPreview(
    id = id,
    type = if (series) ContentType.SERIES else ContentType.MOVIE,
    name = id,
    poster = null,
    posterShape = PosterShape.POSTER,
    background = null,
    logo = null,
    description = null,
    releaseInfo = null,
    imdbRating = null,
    genres = emptyList()
)

private class FakeTransport(val respond: (AskHttpRequest) -> AskHttpResponse) : AskTransport {
    val requests = mutableListOf<AskHttpRequest>()
    override suspend fun send(request: AskHttpRequest): AskHttpResponse {
        requests += request
        return respond(request)
    }
}

private object FakeLookup : AskTitleLookup {
    override suspend fun lookup(pick: AskPick): MetaPreview? =
        if (pick.title == "Made Up") null else meta("tmdb:${pick.title}", pick.isSeries == true)
}

private fun openAiBody(content: String, finish: String = "stop") =
    Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject {
        put("choices", kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonObject {
                put("finish_reason", kotlinx.serialization.json.JsonPrimitive(finish))
                put("message", kotlinx.serialization.json.buildJsonObject {
                    put("content", kotlinx.serialization.json.JsonPrimitive(content))
                })
            })
        })
    })

private fun claudeBody(content: String, stop: String = "end_turn") =
    Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject {
        put("stop_reason", kotlinx.serialization.json.JsonPrimitive(stop))
        put("content", kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("thinking"))
                put("thinking", kotlinx.serialization.json.JsonPrimitive(""))
            })
            add(kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("text"))
                put("text", kotlinx.serialization.json.JsonPrimitive(content))
            })
        })
    })

private fun geminiBody(content: String, finish: String = "STOP") =
    Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject {
        put("candidates", kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonObject {
                put("finishReason", kotlinx.serialization.json.JsonPrimitive(finish))
                put("content", kotlinx.serialization.json.buildJsonObject {
                    put("parts", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("text", kotlinx.serialization.json.JsonPrimitive(content))
                        })
                    })
                })
            })
        })
    })

class AskEngineTest {
    private val taste = AskTaste(List(40) { "Liked $it" }, listOf("Horror"))

    private suspend fun run(provider: AskProvider, code: Int, body: String, base: String = "", model: String = ""): Pair<AskOutcome, FakeTransport> {
        val transport = FakeTransport { AskHttpResponse(code, body) }
        val outcome = AskEngine(transport, FakeLookup).ask(AskConfig(provider, SECRET, model, base), "something twisty", taste, "German")
        return outcome to transport
    }

    @Test
    fun `every provider returns titles and drops ones that cannot be found`() = runTest {
        val cases = listOf(
            AskProvider.CHATGPT to openAiBody(ANSWER),
            AskProvider.GROQ to openAiBody("<think>hm</think>```json\n$ANSWER\n```"),
            AskProvider.CLAUDE to claudeBody(ANSWER),
            AskProvider.GEMINI to geminiBody(ANSWER)
        )
        cases.forEach { (provider, body) ->
            val (outcome, _) = run(provider, 200, body)
            val answer = outcome as AskOutcome.Answer
            assertEquals(provider.id, "Twisty", answer.rowName)
            assertEquals(provider.id, listOf("tmdb:Primer", "tmdb:Dark"), answer.items.map { it.meta.id })
            assertEquals(provider.id, "Clever", answer.items[0].reason)
            assertEquals(provider.id, ContentType.SERIES, answer.items[1].meta.type)
        }
    }

    @Test
    fun `each provider gets its own address and header, never the key in the address`() = runTest {
        val expected = mapOf(
            AskProvider.GEMINI to ("https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-lite-latest:generateContent" to "x-goog-api-key"),
            AskProvider.GROQ to ("https://api.groq.com/openai/v1/chat/completions" to "Authorization"),
            AskProvider.CHATGPT to ("https://api.openai.com/v1/chat/completions" to "Authorization"),
            AskProvider.CLAUDE to ("https://api.anthropic.com/v1/messages" to "x-api-key")
        )
        expected.forEach { (provider, target) ->
            val request = AskConnectors.answerRequest(AskConfig(provider, SECRET), buildAskPrompt("q", AskTaste(), "English"))
            assertEquals(target.first, request.url)
            assertTrue(request.headers.getValue(target.second).contains(SECRET))
            assertFalse(request.url.contains(SECRET))
            assertFalse(request.body.orEmpty().contains(SECRET))
            assertFalse(request.toString().contains(SECRET))
        }
    }

    @Test
    fun `other uses the address the user gave and asks nothing provider specific`() {
        val request = AskConnectors.answerRequest(
            AskConfig(AskProvider.OTHER, SECRET, "my-model", "https://openrouter.ai/api/v1/"),
            buildAskPrompt("q", AskTaste(), "English")
        )
        assertEquals("https://openrouter.ai/api/v1/chat/completions", request.url)
        val body = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals("my-model", body["model"]!!.jsonPrimitive.content)
        assertNull(body["response_format"])
        assertNull(body["reasoning_effort"])
        assertNotNull(body["max_tokens"])
    }

    @Test
    fun `each service gets the tested model and effort`() {
        fun body(provider: AskProvider) = Json.parseToJsonElement(
            AskConnectors.answerRequest(AskConfig(provider, SECRET), buildAskPrompt("q", AskTaste(), "English")).body!!
        ).jsonObject
        assertEquals("claude-sonnet-5-5", body(AskProvider.CLAUDE)["model"]!!.jsonPrimitive.content)
        assertEquals("low", body(AskProvider.CLAUDE)["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("gpt-6-luna", body(AskProvider.CHATGPT)["model"]!!.jsonPrimitive.content)
        assertEquals("low", body(AskProvider.CHATGPT)["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("openai/gpt-oss-120b", body(AskProvider.GROQ)["model"]!!.jsonPrimitive.content)
        assertEquals("low", body(AskProvider.GROQ)["reasoning_effort"]!!.jsonPrimitive.content)
        assertNull(body(AskProvider.GEMINI)["generationConfig"]!!.jsonObject["thinkingConfig"])
    }

    private class Routed(val listBody: String, val answers: MutableList<AskHttpResponse>) : AskTransport {
        val models = mutableListOf<String>()
        var lists = 0
        override suspend fun send(request: AskHttpRequest): AskHttpResponse {
            if (request.body == null) {
                lists++
                return AskHttpResponse(200, listBody)
            }
            models += Json.parseToJsonElement(request.body!!).jsonObject["model"]?.jsonPrimitive?.content
                ?: request.url.substringAfter("/models/").substringBefore(":")
            return answers.removeAt(0)
        }
    }

    @Test
    fun `a new version of the family is used once the service lists it, and the list is not asked twice`() = runTest {
        val list = """{"data":[{"id":"claude-haiku-6"},{"id":"claude-sonnet-6"},{"id":"claude-sonnet-5-5"},{"id":"claude-opus-6"}]}"""
        val good = AskHttpResponse(200, claudeBody(ANSWER))
        val routed = Routed(list, mutableListOf(good, good))
        val engine = AskEngine(routed, FakeLookup)
        val config = AskConfig(AskProvider.CLAUDE, SECRET)
        engine.ask(config, "q", AskTaste(), "English")
        engine.ask(config, "q", AskTaste(), "English")
        assertEquals(listOf("claude-sonnet-6", "claude-sonnet-6"), routed.models)
        assertEquals(1, routed.lists)
    }

    @Test
    fun `openai's newest is by release date, and a dated or other model is ignored`() {
        val list = """{"data":[{"id":"gpt-6-luna","created":100},{"id":"gpt-7-luna","created":200},{"id":"gpt-7-terra","created":300},{"id":"gpt-7-luna-2027","created":400}]}"""
        assertEquals("gpt-7-luna", AskConnectors.newestOfFamily(AskProvider.CHATGPT.family!!, list))
        assertNull(AskConnectors.newestOfFamily(AskProvider.CHATGPT.family!!, "not json"))
    }

    @Test
    fun `a busy service gets one more try, on its lighter model`() = runTest {
        val busy = AskHttpResponse(529, "{}")
        val routed = Routed("{}", mutableListOf(busy, AskHttpResponse(200, claudeBody(ANSWER))))
        val outcome = AskEngine(routed, FakeLookup).ask(AskConfig(AskProvider.CLAUDE, SECRET), "q", AskTaste(), "English")
        assertTrue(outcome is AskOutcome.Answer)
        assertEquals(listOf("claude-sonnet-5-5", "claude-haiku-5-5"), routed.models)
    }

    private fun gemini(vararg responses: AskHttpResponse) = Routed("{}", responses.toMutableList())

    @Test
    fun `a busy Gemini moves down its models and stops at four tries`() = runTest {
        val busy = AskHttpResponse(503, "{}")
        val routed = gemini(busy, busy, busy, busy)
        val outcome = AskEngine(routed, FakeLookup).ask(AskConfig(AskProvider.GEMINI, SECRET), "q", AskTaste(), "English")
        assertEquals(AskOutcome.Failed(AskFailure.BUSY), outcome)
        assertEquals(listOf("gemini-flash-lite-latest", "gemini-3.1-flash-lite", "gemini-3.5-flash-lite", "gemini-3.5-flash-lite"), routed.models)
    }

    @Test
    fun `a used up free limit tries the next model, with no wait, then says so`() = runTest {
        val limit = AskHttpResponse(429, "{}")
        val answered = gemini(limit, AskHttpResponse(200, geminiBody(ANSWER)))
        assertTrue(AskEngine(answered, FakeLookup).ask(AskConfig(AskProvider.GEMINI, SECRET), "q", AskTaste(), "English") is AskOutcome.Answer)
        assertEquals(listOf("gemini-flash-lite-latest", "gemini-3.1-flash-lite"), answered.models)
        val spent = gemini(limit, limit, limit)
        assertEquals(AskOutcome.Failed(AskFailure.RATE_LIMITED), AskEngine(spent, FakeLookup).ask(AskConfig(AskProvider.GEMINI, SECRET), "q", AskTaste(), "English"))
        assertEquals(3, spent.models.size)
    }

    @Test
    fun `a wrong key or a refused request is not retried`() = runTest {
        val wrong = gemini(AskHttpResponse(403, "{}"))
        assertEquals(AskOutcome.Failed(AskFailure.BAD_KEY), AskEngine(wrong, FakeLookup).ask(AskConfig(AskProvider.GEMINI, SECRET), "q", AskTaste(), "English"))
        assertEquals(1, wrong.models.size)
    }

    @Test
    fun `a retired family version falls back to the known model`() = runTest {
        val routed = Routed("""{"data":[{"id":"claude-sonnet-9"}]}""", mutableListOf(AskHttpResponse(404, "{}"), AskHttpResponse(200, claudeBody(ANSWER))))
        val outcome = AskEngine(routed, FakeLookup).ask(AskConfig(AskProvider.CLAUDE, SECRET), "q", AskTaste(), "English")
        assertTrue(outcome is AskOutcome.Answer)
        assertEquals(listOf("claude-sonnet-9", "claude-sonnet-5-5"), routed.models)
    }

    @Test
    fun `only the question, about thirty liked titles, leave-out genres and language are sent`() = runTest {
        val (_, transport) = run(AskProvider.CHATGPT, 200, openAiBody(ANSWER))
        val body = Json.parseToJsonElement(transport.requests.last().body!!).jsonObject
        val messages = body["messages"]!!.jsonArray
        val system = messages[0].jsonObject["content"]!!.jsonPrimitive.content
        val user = messages[1].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(system.contains("written in German"))
        assertTrue(user.contains("something twisty"))
        assertTrue(user.contains("Liked 29"))
        assertFalse(user.contains("Liked 30"))
        assertTrue(user.contains("Horror"))
    }

    @Test
    fun `titles already seen are removed, unless the question asks for a rewatch`() = runTest {
        val config = AskConfig(AskProvider.GROQ, SECRET)
        val seen = AskSeen(ids = setOf("tmdb:Primer"))
        fun engine(body: String) = AskEngine(FakeTransport { AskHttpResponse(200, body) }, FakeLookup)
        val normal = engine(openAiBody(ANSWER)).ask(config, "q", AskTaste(), "English", seen) as AskOutcome.Answer
        assertEquals(listOf("tmdb:Dark"), normal.items.map { it.meta.id })
        val rewatch = ANSWER.replace("\"rowName\":\"Twisty\"", "\"rowName\":\"Twisty\",\"includeSeen\":true")
        val again = engine(openAiBody(rewatch)).ask(config, "q", AskTaste(), "English", seen) as AskOutcome.Answer
        assertEquals(listOf("tmdb:Primer", "tmdb:Dark"), again.items.map { it.meta.id })
    }

    @Test
    fun `seen titles are held back for the button, and only finished ones are marked watched`() = runTest {
        val config = AskConfig(AskProvider.GROQ, SECRET)
        val started = AskSeen(ids = setOf("tmdb:Primer"))
        fun engine() = AskEngine(FakeTransport { AskHttpResponse(200, openAiBody(ANSWER)) }, FakeLookup)
        val held = (engine().ask(config, "q", AskTaste(), "English", started) as AskOutcome.Answer).held.single()
        assertEquals("tmdb:Primer", held.meta.id)
        assertTrue(held.seen)
        assertFalse(held.watched)
        val finished = AskSeen(ids = setOf("tmdb:Primer"), watchedIds = setOf("tmdb:Primer"))
        assertTrue((engine().ask(config, "q", AskTaste(), "English", finished) as AskOutcome.Answer).held.single().watched)
        val everything = AskSeen(ids = setOf("tmdb:Primer", "tmdb:Dark"))
        val all = engine().ask(config, "q", AskTaste(), "English", everything) as AskOutcome.Answer
        assertTrue(all.items.isEmpty())
        assertEquals(2, all.held.size)
    }

    @Test
    fun `a seen title matches by name and year, and a remake does not`() {
        val seen = AskSeen(names = setOf(askSeenName("Dune", "1984")))
        assertTrue(seen.blocks(meta("x").copy(name = "Dune", releaseInfo = "1984")))
        assertFalse(seen.blocks(meta("x").copy(name = "Dune", releaseInfo = "2021")))
    }

    @Test
    fun `titles the viewer disliked are sent as things to avoid`() {
        val prompt = buildAskPrompt("q", AskTaste(dislikedTitles = listOf("Cats")), "English")
        assertTrue(prompt.user.contains("did not like: Cats"))
    }

    @Test
    fun `http failures become plain reasons`() = runTest {
        val cases = listOf(
            Triple(AskProvider.CHATGPT, 401, "{}") to AskFailure.BAD_KEY,
            Triple(AskProvider.GEMINI, 400, """{"error":{"message":"API key not valid. Please pass a valid API key."}}""") to AskFailure.BAD_KEY,
            Triple(AskProvider.CHATGPT, 429, """{"error":{"code":"insufficient_quota"}}""") to AskFailure.NO_CREDIT,
            Triple(AskProvider.CLAUDE, 400, """{"error":{"message":"Your credit balance is too low"}}""") to AskFailure.NO_CREDIT,
            Triple(AskProvider.GROQ, 429, "{}") to AskFailure.RATE_LIMITED,
            Triple(AskProvider.CLAUDE, 404, "{}") to AskFailure.MODEL_GONE,
            Triple(AskProvider.GROQ, 400, """{"error":{"message":"The model x has been decommissioned"}}""") to AskFailure.MODEL_GONE,
            Triple(AskProvider.GEMINI, 400, """{"error":{"message":"User location is not supported for the API use."}}""") to AskFailure.REGION,
            Triple(AskProvider.CLAUDE, 529, "{}") to AskFailure.BUSY,
            Triple(AskProvider.CHATGPT, 400, """{"error":{"message":"unknown parameter"}}""") to AskFailure.REJECTED
        )
        cases.forEach { (input, expected) ->
            val (outcome, _) = run(input.first, input.second, input.third)
            assertEquals(input.toString(), AskOutcome.Failed(expected), outcome)
        }
    }

    @Test
    fun `a refusal in any format is declined`() = runTest {
        assertEquals(AskOutcome.Failed(AskFailure.DECLINED), run(AskProvider.CLAUDE, 200, """{"stop_reason":"refusal","content":[]}""").first)
        assertEquals(AskOutcome.Failed(AskFailure.DECLINED), run(AskProvider.GEMINI, 200, """{"promptFeedback":{"blockReason":"SAFETY"}}""").first)
        assertEquals(AskOutcome.Failed(AskFailure.DECLINED), run(AskProvider.GEMINI, 200, geminiBody(ANSWER, "SAFETY")).first)
        assertEquals(AskOutcome.Failed(AskFailure.DECLINED), run(AskProvider.CHATGPT, 200, openAiBody("x", "content_filter")).first)
    }

    @Test
    fun `an answer cut short keeps the complete titles`() = runTest {
        val cut = """{"rowName":"Twisty","titles":[{"title":"Primer","year":2004,"type":"movie","reason":"Clever"},{"title":"Dark","yea"""
        val answer = run(AskProvider.CLAUDE, 200, claudeBody(cut, "max_tokens")).first as AskOutcome.Answer
        assertEquals(listOf("tmdb:Primer"), answer.items.map { it.meta.id })
    }

    @Test
    fun `nonsense and empty answers are reported, not shown`() = runTest {
        assertEquals(AskOutcome.Failed(AskFailure.BAD_ANSWER), run(AskProvider.CHATGPT, 200, openAiBody("I like films")).first)
        assertEquals(AskOutcome.Failed(AskFailure.BAD_ANSWER), run(AskProvider.CHATGPT, 200, "<html>").first)
        assertEquals(AskOutcome.Failed(AskFailure.NOTHING_FOUND), run(AskProvider.CHATGPT, 200, openAiBody("""{"rowName":"x","titles":[]}""")).first)
    }

    @Test
    fun `no connection is a network failure and never shows the key`() = runTest {
        val transport = object : AskTransport {
            override suspend fun send(request: AskHttpRequest): AskHttpResponse = throw IOException("no route")
        }
        val outcome = AskEngine(transport, FakeLookup).ask(AskConfig(AskProvider.GROQ, SECRET), "q", AskTaste(), "English")
        assertEquals(AskOutcome.Failed(AskFailure.NETWORK), outcome)
        assertFalse(AskConfig(AskProvider.GROQ, SECRET).toString().contains(SECRET))
    }

    @Test
    fun `testing a key lists models and spots a missing model`() = runTest {
        val listed = """{"data":[{"id":"gpt-6-luna"}]}"""
        val config = AskConfig(AskProvider.CHATGPT, SECRET)
        val engine = { code: Int, body: String -> AskEngine(FakeTransport { AskHttpResponse(code, body) }, FakeLookup) }
        assertEquals(AskKeyTest.Good, engine(200, listed).testKey(config))
        assertEquals(AskKeyTest.Failed(AskFailure.BAD_KEY), engine(401, "{}").testKey(config))
        assertEquals(AskKeyTest.Failed(AskFailure.MODEL_GONE), engine(200, """{"data":[{"id":"other"}]}""").testKey(config))
        val gemini = """{"models":[{"name":"models/gemini-flash-lite-latest"}]}"""
        assertEquals(AskKeyTest.Good, engine(200, gemini).testKey(AskConfig(AskProvider.GEMINI, SECRET)))
        assertEquals(AskKeyTest.Good, engine(200, "not json").testKey(AskConfig(AskProvider.OTHER, SECRET, "m", "https://x.example/v1")))
        val request = AskConnectors.modelsRequest(config)
        assertEquals("https://api.openai.com/v1/models", request.url)
        assertNull(request.body)
    }
}

class AskTitleMatchTest {
    private fun result(id: Int, title: String, date: String?, votes: Int = 100, original: String? = null) =
        TmdbDiscoverResult(id = id, title = title, originalTitle = original, releaseDate = date, voteCount = votes)

    private fun pick(title: String, year: Int?) = AskPick(title, year, false, "")

    @Test
    fun `the remake with the right year wins`() {
        val results = listOf(result(1, "Dune", "1984-12-14"), result(2, "Dune", "2021-09-15"))
        assertEquals(2, bestTitleMatch(results, pick("Dune", 2021))!!.id)
        assertEquals(1, bestTitleMatch(results, pick("Dune", 1984))!!.id)
    }

    @Test
    fun `punctuation, case and accents do not matter`() {
        val results = listOf(result(1, "Amélie", "2001-04-25"))
        assertEquals(1, bestTitleMatch(results, pick("AMELIE!", 2001))!!.id)
    }

    @Test
    fun `a translated name with the right year still counts`() {
        val results = listOf(result(7, "Das Leben der Anderen", "2006-03-15"))
        assertEquals(7, bestTitleMatch(results, pick("The Lives of Others", 2006))!!.id)
    }

    @Test
    fun `a wrong film is left out`() {
        val results = listOf(result(1, "Something Else", "1980-01-01"), result(2, "Dune", "1984-12-14"))
        assertNull(bestTitleMatch(results, pick("Dune", 2021)))
        assertNull(bestTitleMatch(emptyList(), pick("Dune", 2021)))
    }

    @Test
    fun `a different name only counts as the top result`() {
        val results = listOf(result(1, "Other Film", "1990-01-01"), result(2, "Similar Words", "2021-03-03"))
        assertNull(bestTitleMatch(results, pick("Dune", 2021)))
    }

    @Test
    fun `without a year it takes the same name, or a known top result`() {
        val results = listOf(result(1, "Other", "2000-01-01", votes = 5), result(2, "Dune", "2021-09-15"))
        assertEquals(2, bestTitleMatch(results, pick("Dune", null))!!.id)
        assertNull(bestTitleMatch(listOf(result(1, "Other", "2000-01-01", votes = 5)), pick("Dune", null)))
        assertEquals(1, bestTitleMatch(listOf(result(1, "Other", "2000-01-01", votes = 500)), pick("Dune", null))!!.id)
    }
}

class AskStorageTest {
    @Test
    fun `the search line waits for a sentence or an empty result`() {
        assertFalse(com.nuvio.tv.ui.screens.scout.ask.askLineWanted("dune", noResults = false))
        assertFalse(com.nuvio.tv.ui.screens.scout.ask.askLineWanted("the bear", noResults = false))
        assertTrue(com.nuvio.tv.ui.screens.scout.ask.askLineWanted("films like inception", noResults = false))
        assertTrue(com.nuvio.tv.ui.screens.scout.ask.askLineWanted("xyzzy", noResults = true))
        assertFalse(com.nuvio.tv.ui.screens.scout.ask.askLineWanted("   ", noResults = true))
    }

    @Test
    fun `a main service keeps no model from the phone form`() {
        val config = parseAskPhoneKey(kotlinx.serialization.json.buildJsonObject {
            put("provider", kotlinx.serialization.json.JsonPrimitive("claude"))
            put("key", kotlinx.serialization.json.JsonPrimitive("abc"))
            put("model", kotlinx.serialization.json.JsonPrimitive("claude-fable-5-1"))
        })!!
        assertEquals("claude-sonnet-5-5", config.baseModel)
    }

    @Test
    fun `keys survive the round trip and unknown data is dropped`() {
        val keys = AskKeys(
            AskProvider.CLAUDE,
            mapOf(
                AskProvider.CLAUDE to AskConfig(AskProvider.CLAUDE, SECRET),
                AskProvider.OTHER to AskConfig(AskProvider.OTHER, "k", "m", "https://x.example/v1")
            )
        )
        assertEquals(keys, AskKeysCodec.decode(AskKeysCodec.encode(keys)))
        assertEquals(AskKeys(), AskKeysCodec.decode(null))
        assertEquals(AskKeys(), AskKeysCodec.decode("broken"))
        assertNull(AskKeysCodec.decode("""{"active":"nope","configs":{"nope":{"key":"x"}}}""").active)
    }

    @Test
    fun `the phone form rejects bad input and insecure addresses`() {
        fun body(provider: String, key: String, model: String = "", base: String = "") = kotlinx.serialization.json.buildJsonObject {
            put("provider", kotlinx.serialization.json.JsonPrimitive(provider))
            put("key", kotlinx.serialization.json.JsonPrimitive(key))
            put("model", kotlinx.serialization.json.JsonPrimitive(model))
            put("baseUrl", kotlinx.serialization.json.JsonPrimitive(base))
        }
        assertEquals(AskProvider.GROQ, parseAskPhoneKey(body("groq", " abc "))!!.provider)
        assertEquals("abc", parseAskPhoneKey(body("groq", " abc "))!!.key)
        assertNull(parseAskPhoneKey(body("nope", "abc")))
        assertNull(parseAskPhoneKey(body("groq", "")))
        assertNull(parseAskPhoneKey(body("groq", "two words")))
        assertNull(parseAskPhoneKey(body("other", "abc", "", "https://x.example/v1")))
        assertNull(parseAskPhoneKey(body("other", "abc", "m", "http://x.example/v1")))
        assertNull(parseAskPhoneKey(body("other", "abc", "m", "ftp://192.168.1.2/v1")))
        assertNotNull(parseAskPhoneKey(body("other", "abc", "m", "https://x.example/v1")))
        assertNotNull(parseAskPhoneKey(body("other", "abc", "m", "http://192.168.1.5:11434/v1")))
        assertNull(parseAskPhoneKey(body("other", "abc", "m", "http://192.168.evil.example/v1")))
    }

    @Test
    fun `the state sent to the phone never holds a key`() {
        val state = AskPhoneState(
            AskProvider.CLAUDE,
            AskProvider.CLAUDE,
            listOf(AskPhoneKeyStatus(AskProvider.CLAUDE, true, "m")),
            null,
            false
        )
        assertFalse(stateJson(state).toString().contains(SECRET))
        assertTrue(stateJson(state).toString().contains("\"hasKey\":true"))
    }

    @Test
    fun `saved rows refresh weekly and wait a day after a failure`() {
        val week = 7L * 24 * 60 * 60 * 1000
        val day = 24L * 60 * 60 * 1000
        val row = AskSavedRow("a", "q", "n", refreshedAt = 0, attemptedAt = 0, items = emptyList())
        assertFalse(row.dueForRefresh(week - 1))
        assertTrue(row.dueForRefresh(week))
        assertFalse(row.copy(attemptedAt = week).dueForRefresh(week + day - 1))
        assertTrue(row.copy(attemptedAt = week).dueForRefresh(week + day))
    }

    @Test
    fun `recent questions stay short and unique`() {
        val once = recentWith(listOf("a", "B"), "b")
        assertEquals(listOf("b", "a"), once)
        assertEquals(ASK_MAX_RECENT, (1..50).fold(emptyList<String>()) { list, n -> recentWith(list, "$n") }.size)
    }

    @Test
    fun `a saved title reads back as the same title`() {
        val saved = AskItem(meta("tmdb:9", series = true).copy(name = "Dark", releaseInfo = "2017"), "Knotty").toSaved()
        val back = saved.toMeta()
        assertEquals("tmdb:9", back.id)
        assertEquals(ContentType.SERIES, back.type)
        assertEquals("series", back.apiType)
        assertEquals("2017", back.releaseInfo)
    }
}
