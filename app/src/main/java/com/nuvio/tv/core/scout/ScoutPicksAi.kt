package com.nuvio.tv.core.scout

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.scout.ask.AskCandidate
import com.nuvio.tv.core.scout.ask.AskFailure
import com.nuvio.tv.core.scout.ask.AskKeyVault
import com.nuvio.tv.core.scout.ask.AskService
import com.nuvio.tv.core.scout.ask.AskTaste
import com.nuvio.tv.core.scout.ask.AskTasteSource
import com.nuvio.tv.data.local.ScoutStore
import com.nuvio.tv.domain.model.ScoutPick
import com.nuvio.tv.domain.model.ScoutRating
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val SORT_EVERY_MS = 7L * 24 * 60 * 60 * 1000
private const val LIKED_MAX = 30
private const val DISLIKED_MAX = 10

@JsonClass(generateAdapter = true)
data class ScoutAiSorted(val key: String, val reason: String)

@JsonClass(generateAdapter = true)
data class ScoutAiState(
    val summary: String? = null,
    val sortOn: Boolean = false,
    val sortedAt: Long = 0L,
    val sorted: List<ScoutAiSorted> = emptyList(),
    val quickStartOffered: Boolean = false
)

@Singleton
class ScoutAiStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager
) {
    private val adapter = Moshi.Builder().build().adapter(ScoutAiState::class.java)

    private fun file() = File(context.filesDir, "scout/ai_${profileManager.activeProfileId.value}.json")

    @Synchronized
    fun read(): ScoutAiState = runCatching { adapter.fromJson(file().readText()) }.getOrNull() ?: ScoutAiState()

    @Synchronized
    fun update(transform: (ScoutAiState) -> ScoutAiState): ScoutAiState {
        val next = transform(read())
        runCatching { file().apply { parentFile?.mkdirs() }.writeText(adapter.toJson(next)) }
        return next
    }
}

/** The AI's chosen titles first, each with its reason; everything else stays in Scout's own order. */
fun scoutApplySorted(picks: List<ScoutPick>, sorted: List<ScoutAiSorted>): List<ScoutPick> {
    if (sorted.isEmpty()) return picks
    val byKey = picks.associateBy { it.key }
    val first = sorted.mapNotNull { entry -> byKey[entry.key]?.copy(aiReason = entry.reason, stretch = false) }
    val used = first.map { it.key }.toSet()
    return first + picks.filter { it.key !in used }
}

fun scoutSortDue(state: ScoutAiState, now: Long): Boolean =
    state.sortOn && (now - state.sortedAt >= SORT_EVERY_MS || now < state.sortedAt)

/** Only runs when the profile has a key and has switched AI sorting on. A failure leaves Scout's own order. */
@Singleton
class ScoutPicksAi @Inject constructor(
    private val store: ScoutAiStore,
    private val vault: AskKeyVault,
    private val service: dagger.Lazy<AskService>
) {
    suspend fun sort(picks: List<ScoutPick>, now: Long): List<ScoutPick> {
        val hasKey = vault.current().activeConfig != null
        var state = store.read()
        if (!state.sortOn || !hasKey) return picks
        if (scoutSortDue(state, now)) {
            val candidates = picks.take(com.nuvio.tv.core.scout.ask.ASK_SORT_CANDIDATES).map {
                AskCandidate(it.key, it.name, it.released?.take(4)?.toIntOrNull(), it.tv)
            }
            val ranked = service.get().sortPicks(candidates)
            if (!ranked.isNullOrEmpty()) {
                state = store.update { old ->
                    old.copy(sortedAt = now, sorted = ranked.map { ScoutAiSorted(it.key, it.reason) })
                }
            }
        }
        return scoutApplySorted(picks, state.sorted)
    }

    suspend fun writeSummary(): AskFailure? = when (val outcome = service.get().writeSummary()) {
        is com.nuvio.tv.core.scout.ask.AskSummaryOutcome.Written -> {
            store.update { it.copy(summary = outcome.text) }
            null
        }
        is com.nuvio.tv.core.scout.ask.AskSummaryOutcome.Failed -> outcome.failure
    }
}

/** What Ask may tell the AI: rated titles only, never what was merely watched. */
@Singleton
class ScoutAskTasteSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scoutStore: ScoutStore,
    private val aiStore: ScoutAiStore
) : AskTasteSource {
    override suspend fun taste(): AskTaste {
        val profile = scoutStore.current()
        val rated = profile.ratings.values.sortedByDescending { it.ratedAt }
        val liked = rated.filter { it.rating == ScoutRating.LOVE } + rated.filter { it.rating == ScoutRating.LIKE }
        return AskTaste(
            likedTitles = liked.take(LIKED_MAX).map { it.title.name },
            dislikedTitles = rated.filter { it.rating == ScoutRating.NOT_FOR_ME }.take(DISLIKED_MAX).map { it.title.name },
            leaveOutGenres = profile.settings.leaveOutGenres.mapNotNull { id ->
                ScoutSlices.byId("genre_$id")?.titleRes?.takeIf { it != 0 }?.let(context::getString)
            },
            summary = aiStore.read().summary
        )
    }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class ScoutPicksModule {
    @dagger.Binds
    @dagger.multibindings.IntoSet
    abstract fun bindTaste(source: ScoutAskTasteSource): AskTasteSource
}
