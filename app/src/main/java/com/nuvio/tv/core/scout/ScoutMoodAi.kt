package com.nuvio.tv.core.scout

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.scout.ask.AskCandidate
import com.nuvio.tv.core.scout.ask.AskKeyVault
import com.nuvio.tv.core.scout.ask.AskService
import com.nuvio.tv.domain.model.ScoutPick
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ScoutMoodAi"
private const val CHECK_EVERY_MS = 7L * 24 * 60 * 60 * 1000
private const val RETRY_AFTER_MS = 24L * 60 * 60 * 1000
private const val VERDICT_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
private const val MOOD_PAGES = 2
private const val MOOD_CANDIDATES = 80
private const val PAUSE_MS = 3_000L
private const val MAX_FAILURES = 3
private const val KEEP_AT_LEAST_ONE_IN = 4
private const val DOCUMENTARY_KEY = "doc"

@JsonClass(generateAdapter = true)
data class ScoutMoodAiEntry(val checkedAt: Long = 0L, val rejected: List<String> = emptyList(), val triedAt: Long = 0L)

@JsonClass(generateAdapter = true)
data class ScoutMoodAiState(val moods: Map<String, ScoutMoodAiEntry> = emptyMap())

internal fun ScoutAnswers.scoutMoodAiKey(): String? = when {
    mood?.sliceId != null -> mood.code
    mood == null && watch == ScoutWatch.DOCUMENTARY -> DOCUMENTARY_KEY
    else -> null
}

internal fun scoutMoodDue(entry: ScoutMoodAiEntry?, now: Long): Boolean = when {
    entry == null || now < entry.checkedAt -> true
    now - entry.triedAt in 0 until RETRY_AFTER_MS -> false
    else -> now - entry.checkedAt >= CHECK_EVERY_MS
}

internal fun scoutMoodVerdictFresh(entry: ScoutMoodAiEntry, now: Long): Boolean = now - entry.checkedAt in 0 until VERDICT_MAX_AGE_MS

/** An answer that keeps almost nothing is more likely a bad reply than a verdict, so it changes nothing. */
internal fun scoutMoodRejected(listed: List<String>, kept: Set<String>?): List<String>? {
    if (kept == null || kept.isEmpty() || kept.size * KEEP_AT_LEAST_ONE_IN < listed.size) return null
    return listed.filterNot { it in kept }
}

internal fun List<ScoutPick>.scoutWithoutRejected(rejected: Set<String>): List<ScoutPick> =
    if (rejected.isEmpty()) this else filterNot { it.key in rejected }

@Singleton
class ScoutMoodAiStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager,
    private val aiStore: ScoutAiStore,
    private val vault: AskKeyVault
) {
    private val adapter = Moshi.Builder().build().adapter(ScoutMoodAiState::class.java)

    private fun file(profileId: Int) = File(context.filesDir, "scout/mood_ai_$profileId.json")

    fun isOn(): Boolean = aiStore.read().sortOn && vault.current().activeConfig != null

    @Synchronized
    fun read(profileId: Int = profileManager.activeProfileId.value): ScoutMoodAiState =
        runCatching { adapter.fromJson(file(profileId).readText()) }.getOrNull() ?: ScoutMoodAiState()

    @Synchronized
    fun save(profileId: Int, key: String, entry: ScoutMoodAiEntry) {
        val next = read(profileId).let { it.copy(moods = it.moods + (key to entry)) }
        runCatching { file(profileId).apply { parentFile?.mkdirs() }.writeText(adapter.toJson(next)) }
    }

    /** Titles the AI said do not fit these answers. Empty when the switch is off or no key is left. */
    suspend fun rejected(answers: ScoutAnswers, now: Long = System.currentTimeMillis()): Set<String> = withContext(Dispatchers.IO) {
        val key = answers.scoutMoodAiKey() ?: return@withContext emptySet()
        if (!isOn()) return@withContext emptySet()
        read().moods[key]?.takeIf { scoutMoodVerdictFresh(it, now) }?.rejected.orEmpty().toSet()
    }
}

/** About once a week, for a profile with a key and AI sorting on. Only title names and the mood are sent. */
@Singleton
class ScoutMoodCheck @Inject constructor(
    private val store: ScoutMoodAiStore,
    private val finder: dagger.Lazy<ScoutPicksFinder>,
    private val service: dagger.Lazy<AskService>,
    private val profileManager: ProfileManager
) {
    private fun meaning(mood: ScoutMood?): String? = when (mood) {
        ScoutMood.FEEL_GOOD -> "Feel-good: warm and uplifting"
        ScoutMood.LAUGH -> "Laugh out loud: comedy that is really funny"
        ScoutMood.ACTION -> "Action-packed: fights, chases, stunts"
        ScoutMood.EDGE -> "Edge of your seat: tense and suspenseful"
        ScoutMood.MIND -> "Mind-bending: plays with reality, time or identity"
        ScoutMood.DARK -> "Dark and gritty: bleak, violent, grim"
        ScoutMood.SCARY -> "Scary: actually frightening horror"
        ScoutMood.ROMANTIC -> "Romantic: the love story is the heart of it"
        ScoutMood.EPIC -> "Epic: grand scale, sweeping story"
        ScoutMood.TEARS -> "Tear-jerker: built to make you cry"
        ScoutMood.SLOW -> "Slow burn: patient pacing that builds gradually"
        ScoutMood.WEIRD -> "Weird and wonderful: strange, surreal, offbeat"
        else -> null
    }

    suspend fun runIfDue(now: Long = System.currentTimeMillis()) {
        try {
            withContext(Dispatchers.IO) { checkAll(now) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Moods not checked: ${error.javaClass.simpleName}")
        }
    }

    private suspend fun checkAll(now: Long) {
        if (!store.isOn()) return
        val profileId = profileManager.activeProfileId.value
        val asks = ScoutMood.entries.filter { it.sliceId != null }.map { ScoutAnswers(mood = it) to meaning(it) } +
            (ScoutAnswers(watch = ScoutWatch.DOCUMENTARY) to "Documentary: a genuine factual programme")
        val saved = store.read(profileId).moods
        var failures = 0
        for ((answers, words) in asks) {
            val key = answers.scoutMoodAiKey() ?: continue
            val entry = saved[key]
            if (words == null || !scoutMoodDue(entry, now)) continue
            val rejected = try {
                verdict(key, answers, words)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Mood $key failed: ${error.javaClass.simpleName}")
                null
            }
            if (rejected == null) {
                Log.i(TAG, "Mood $key not checked")
                store.save(profileId, key, ScoutMoodAiEntry(entry?.checkedAt ?: 0L, entry?.rejected.orEmpty(), now))
                if (++failures >= MAX_FAILURES) return
                delay(PAUSE_MS)
                continue
            }
            store.save(profileId, key, ScoutMoodAiEntry(now, rejected))
            delay(PAUSE_MS)
        }
    }

    private suspend fun verdict(key: String, answers: ScoutAnswers, words: String): List<String>? {
        val listed = (1..MOOD_PAGES).flatMap { finder.get().fetchUnchecked(answers, it) }.distinctBy { it.key }.take(MOOD_CANDIDATES)
        if (listed.isEmpty()) return null
        val kept = service.get().checkMood(words, listed.map { AskCandidate(it.key, it.name, it.released?.take(4)?.toIntOrNull(), it.tv) })
        return scoutMoodRejected(listed.map { it.key }, kept)?.also {
            Log.i(TAG, "Mood $key checked: ${it.size} of ${listed.size} left out")
        }
    }
}
