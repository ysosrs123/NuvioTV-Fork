package com.nuvio.tv.core.scout.ask

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.core.scout.ScoutArtwork
import com.nuvio.tv.core.tmdb.TmdbImageSizes
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private const val LOOKUPS_AT_ONCE = 6
private const val MAX_BUSY_TRIES = 4
private const val BACKOFF_MS = 1_000L
private const val JITTER_MS = 500L
private const val RETRY_BUDGET_MS = 40_000L
private const val MODEL_RECHECK_MS = 7L * 24 * 60 * 60 * 1000
private const val MAX_RESPONSE_BYTES = 512L * 1024

interface AskTransport {
    /** Throws IOException when the service cannot be reached. */
    suspend fun send(request: AskHttpRequest): AskHttpResponse
}

/** Its own client: no logging interceptor, and no redirects, so a key header never follows one. */
@Singleton
class OkHttpAskTransport @Inject constructor() : AskTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun send(request: AskHttpRequest): AskHttpResponse = runInterruptible(Dispatchers.IO) {
        val builder = Request.Builder()
        try {
            builder.url(request.url)
            request.headers.forEach { (name, value) -> builder.header(name, value) }
        } catch (_: IllegalArgumentException) {
            throw IOException("Bad request settings")
        }
        request.body?.let { builder.post(it.toRequestBody("application/json".toMediaType())) }
        client.newCall(builder.build()).execute().use { response ->
            val source = response.body.source()
            source.request(MAX_RESPONSE_BYTES)
            AskHttpResponse(response.code, source.buffer.clone().readUtf8())
        }
    }
}

data class AskItem(val meta: MetaPreview, val reason: String, val seen: Boolean = false, val watched: Boolean = false)

sealed interface AskSummaryOutcome {
    data class Written(val text: String) : AskSummaryOutcome
    data class Failed(val failure: AskFailure) : AskSummaryOutcome
}

sealed interface AskOutcome {
    data class Answer(val rowName: String, val items: List<AskItem>, val held: List<AskItem> = emptyList()) : AskOutcome
    data class Failed(val failure: AskFailure) : AskOutcome
}

interface AskTitleLookup {
    suspend fun lookup(pick: AskPick): MetaPreview?
}

@Singleton
class TmdbAskTitleLookup @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val settings: TmdbSettingsDataStore
) : AskTitleLookup {
    override suspend fun lookup(pick: AskPick): MetaPreview? {
        val language = settings.settings.first().language
        val order = if (pick.isSeries == true) listOf(true, false) else listOf(false, true)
        for (series in order) {
            val results = try {
                val response = if (series) tmdbApi.searchTv(BuildConfig.TMDB_API_KEY, pick.title, language)
                else tmdbApi.searchMovies(BuildConfig.TMDB_API_KEY, pick.title, language)
                response.body()?.results.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            bestTitleMatch(results, pick)?.toMeta(series)?.let { return it }
        }
        return null
    }

    private fun TmdbDiscoverResult.toMeta(series: Boolean): MetaPreview? {
        val shown = (title ?: name ?: originalTitle ?: originalName)?.takeIf { it.isNotBlank() } ?: return null
        val date = (if (series) firstAirDate else releaseDate)?.takeIf { it.isNotBlank() }
        return MetaPreview(
            id = "tmdb:$id",
            type = if (series) ContentType.SERIES else ContentType.MOVIE,
            rawType = if (series) "series" else "movie",
            name = shown,
            poster = image(posterPath, "w500") ?: image(backdropPath, "w780"),
            posterShape = PosterShape.POSTER,
            background = image(backdropPath, TmdbImageSizes.backdrop),
            logo = null,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = date?.take(4),
            released = date,
            imdbRating = voteAverage?.toFloat(),
            voteCount = voteCount,
            genres = emptyList()
        )
    }

    private fun image(path: String?, size: String): String? =
        path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }
}

internal class AskEngine(
    private val transport: AskTransport,
    private val lookup: AskTitleLookup,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val newest = ConcurrentHashMap<AskProvider, Pair<String, Long>>()

    private suspend fun modelFor(config: AskConfig): String {
        val family = config.provider.family ?: return config.baseModel
        newest[config.provider]?.takeIf { now() - it.second < MODEL_RECHECK_MS }?.let { return it.first }
        val found = try {
            val response = transport.send(AskConnectors.modelsRequest(config))
            if (response.code in 200..299) AskConnectors.newestOfFamily(family, response.body) else null
        } catch (_: IOException) {
            null
        } ?: return config.baseModel
        newest[config.provider] = found to now()
        return found
    }

    private suspend fun attempt(config: AskConfig, prompt: AskPrompt, model: String): AskReply = try {
        AskConnectors.parseReply(config, transport.send(AskConnectors.answerRequest(config, prompt, model)))
    } catch (_: IOException) {
        AskReply.Failed(AskFailure.NETWORK)
    }

    /**
     * A busy service gets a few tries with growing waits, moving down its models because demand and
     * free limits are per model. No answer at all gets one more try: each can take the full time limit.
     */
    private suspend fun reply(config: AskConfig, prompt: AskPrompt): AskReply {
        val model = modelFor(config)
        val chain = listOf(model) + config.provider.fallbacks
        val started = now()
        var result = attempt(config, prompt, model)
        var tries = 1
        while (true) {
            val failure = (result as? AskReply.Failed)?.failure ?: return result
            if (failure == AskFailure.MODEL_GONE && model != config.baseModel && tries == 1) {
                newest.remove(config.provider)
                result = attempt(config, prompt, config.baseModel)
                tries++
                continue
            }
            val retry = when (failure) {
                AskFailure.BUSY -> tries < MAX_BUSY_TRIES
                AskFailure.NETWORK -> tries < 2
                AskFailure.RATE_LIMITED -> tries < chain.size
                else -> false
            }
            if (!retry || now() - started > RETRY_BUDGET_MS) return result
            if (failure != AskFailure.RATE_LIMITED) delay((BACKOFF_MS shl (tries - 1)) + Random.nextLong(JITTER_MS))
            result = attempt(config, prompt, chain[minOf(tries, chain.lastIndex)])
            tries++
        }
    }

    suspend fun ask(config: AskConfig, question: String, taste: AskTaste, answerLanguage: String, seen: AskSeen = AskSeen()): AskOutcome {
        val text = when (val reply = reply(config, buildAskPrompt(question, taste, answerLanguage))) {
            is AskReply.Failed -> return AskOutcome.Failed(reply.failure)
            is AskReply.Text -> reply
        }
        val parsed = AskConnectors.parseAnswer(text.text, text.cutOff)
            ?: return AskOutcome.Failed(if (text.cutOff) AskFailure.CUT_OFF else AskFailure.BAD_ANSWER)
        val found = lookUpAll(parsed.picks).map { it.copy(seen = seen.blocks(it.meta), watched = seen.finished(it.meta)) }
        if (found.isEmpty()) return AskOutcome.Failed(AskFailure.NOTHING_FOUND)
        if (parsed.includeSeen) return AskOutcome.Answer(parsed.rowName, found)
        val (held, items) = found.partition(AskItem::seen)
        return AskOutcome.Answer(parsed.rowName, items, held)
    }

    suspend fun sortPicks(config: AskConfig, candidates: List<AskCandidate>, taste: AskTaste, answerLanguage: String): List<AskRanked>? {
        val text = reply(config, buildSortPrompt(candidates, taste, answerLanguage)) as? AskReply.Text ?: return null
        val parsed = AskConnectors.parseAnswer(text.text, text.cutOff) ?: return null
        return matchSorted(parsed, candidates)
    }

    suspend fun checkMood(config: AskConfig, mood: String, candidates: List<AskCandidate>): Set<String>? {
        val text = reply(config, buildMoodPrompt(mood, candidates)) as? AskReply.Text ?: return null
        if (text.cutOff) return null
        return parseMoodKeep(text.text, candidates.size)?.map { candidates[it - 1].key }?.toSet()
    }

    suspend fun writeSummary(config: AskConfig, taste: AskTaste, answerLanguage: String): AskSummaryOutcome =
        when (val reply = reply(config, buildSummaryPrompt(taste, answerLanguage))) {
            is AskReply.Failed -> AskSummaryOutcome.Failed(reply.failure)
            is AskReply.Text -> parseSummary(reply.text)?.let { AskSummaryOutcome.Written(it) }
                ?: AskSummaryOutcome.Failed(AskFailure.BAD_ANSWER)
        }

    suspend fun testKey(config: AskConfig): AskKeyTest = try {
        AskConnectors.parseKeyTest(config, transport.send(AskConnectors.modelsRequest(config)))
    } catch (_: IOException) {
        AskKeyTest.Failed(AskFailure.NETWORK)
    }

    private suspend fun lookUpAll(picks: List<AskPick>): List<AskItem> = coroutineScope {
        val gate = Semaphore(LOOKUPS_AT_ONCE)
        picks.map { pick ->
            async { gate.withPermit { lookup.lookup(pick) }?.let { AskItem(it, pick.reason) } }
        }.awaitAll().filterNotNull().distinctBy { it.meta.id }
    }
}

/** The only way a question leaves the box: this is everything the AI is told. */
private const val ART_RETRY_MS = 2_000L

@Singleton
class AskService @Inject constructor(
    @ApplicationContext private val context: Context,
    transport: OkHttpAskTransport,
    private val lookup: TmdbAskTitleLookup,
    private val artwork: ScoutArtwork,
    private val vault: AskKeyVault,
    private val watchProgress: dagger.Lazy<WatchProgressRepository>,
    private val tasteSources: Set<@JvmSuppressWildcards AskTasteSource>
) {
    private val engine = AskEngine(transport, lookup)

    suspend fun ask(question: String): AskOutcome {
        val config = vault.current().activeConfig ?: return AskOutcome.Failed(AskFailure.NO_KEY)
        val taste = tasteSources.firstOrNull()?.taste() ?: AskTaste()
        return when (val outcome = engine.ask(config, question, taste, answerLanguage(), seen())) {
            is AskOutcome.Failed -> outcome
            is AskOutcome.Answer -> {
                val all = outcome.items + outcome.held
                val shown = artwork.follow(all.map(AskItem::meta), CustomPosterScreen.SEARCH)
                val merged = all.zip(shown) { item, meta -> item.copy(meta = meta) }
                outcome.copy(items = merged.take(outcome.items.size), held = merged.drop(outcome.items.size))
            }
        }
    }

    /** Artwork for a title named in the app, not by the AI. No key is used. */
    suspend fun titleArt(titles: List<AskPick>, onFound: (List<MetaPreview?>) -> Unit = {}): List<MetaPreview?> {
        var found = lookUpEach(titles)
        if (found.all { it == null }) {
            delay(ART_RETRY_MS)
            found = lookUpEach(titles)
        }
        onFound(found)
        val shown = artwork.follow(found.filterNotNull(), CustomPosterScreen.SEARCH).associateBy(MetaPreview::id)
        return found.map { meta -> meta?.let { shown[it.id] ?: it } }
    }

    private suspend fun lookUpEach(titles: List<AskPick>): List<MetaPreview?> = coroutineScope {
        titles.map { pick -> async { lookup.lookup(pick) } }.awaitAll()
    }

    private suspend fun seen(): AskSeen {
        val watched = watchProgress.get().tasteHistory.first()
        val started = watchProgress.get().allProgress.first()
        return AskSeen(
            ids = (watched.map { it.contentId } + started.map { it.contentId }).toSet(),
            names = (watched.map { askSeenName(it.title, it.releaseInfo) } + started.map { normaliseTitle(it.name) }).toSet(),
            watchedIds = watched.map { it.contentId }.toSet(),
            watchedNames = watched.map { askSeenName(it.title, it.releaseInfo) }.toSet()
        )
    }

    suspend fun testKey(config: AskConfig): AskKeyTest = engine.testKey(config)

    suspend fun sortPicks(candidates: List<AskCandidate>): List<AskRanked>? {
        val config = vault.current().activeConfig ?: return null
        return engine.sortPicks(config, candidates, tasteSources.firstOrNull()?.taste() ?: AskTaste(), answerLanguage())
    }

    /** Only the mood and the title names leave the box. */
    suspend fun checkMood(mood: String, candidates: List<AskCandidate>): Set<String>? {
        val config = vault.current().activeConfig ?: return null
        return engine.checkMood(config, mood, candidates)
    }

    suspend fun writeSummary(): AskSummaryOutcome {
        val config = vault.current().activeConfig ?: return AskSummaryOutcome.Failed(AskFailure.NO_KEY)
        val taste = (tasteSources.firstOrNull()?.taste() ?: AskTaste()).copy(summary = null)
        if (taste.likedTitles.isEmpty() && taste.dislikedTitles.isEmpty()) return AskSummaryOutcome.Failed(AskFailure.NOTHING_FOUND)
        return engine.writeSummary(config, taste, answerLanguage())
    }

    private fun answerLanguage(): String {
        val tag = context.getSharedPreferences("app_locale", Context.MODE_PRIVATE).getString("locale_tag", null)
        val locale = if (tag.isNullOrEmpty()) Locale.getDefault() else Locale.forLanguageTag(tag)
        return locale.getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" }
    }
}
