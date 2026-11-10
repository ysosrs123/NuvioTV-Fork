package com.nuvio.tv.core.scout

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.scout.ask.AskCandidate
import com.nuvio.tv.core.scout.ask.AskService
import com.nuvio.tv.core.scout.ask.buildMoodPrompt
import com.nuvio.tv.core.scout.ask.parseMoodKeep
import com.nuvio.tv.domain.model.ScoutPick
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutMoodAiTest {
    private val week = 7L * 24 * 60 * 60 * 1000
    private val now = week * 100

    private fun pick(id: Int) = ScoutPick(tmdbId = id, tv = false, name = "Title $id")

    @Test
    fun `the prompt carries only the mood and numbered titles`() {
        val prompt = buildMoodPrompt("Scary: actually frightening horror", listOf(AskCandidate("movie:1", "Alien", 1979, false), AskCandidate("tv:2", "FROM", 2022, true)))
        assertTrue(prompt.user.contains("Scary"))
        assertTrue(prompt.user.contains("1. Alien (1979) - movie"))
        assertTrue(prompt.user.contains("2. FROM (2022) - series"))
        assertFalse(prompt.user.contains("liked"))
    }

    @Test
    fun `an answer keeps only numbers on the list`() {
        assertEquals(setOf(1, 3), parseMoodKeep("""{"rowName":"1, 3, 9, x","includeSeen":false,"titles":[]}""", 4))
        assertNull(parseMoodKeep("""{"rowName":"none","includeSeen":false,"titles":[]}""", 4))
        assertNull(parseMoodKeep("not json", 4))
    }

    @Test
    fun `an answer that keeps almost nothing changes nothing`() {
        val listed = (1..20).map { "movie:$it" }
        assertNull(scoutMoodRejected(listed, null))
        assertNull(scoutMoodRejected(listed, emptySet()))
        assertNull(scoutMoodRejected(listed, setOf("movie:1", "movie:2")))
        assertEquals(15, scoutMoodRejected(listed, listed.take(5).toSet())?.size)
    }

    @Test
    fun `moods are due weekly and a clock moved back counts as due`() {
        assertTrue(scoutMoodDue(null, now))
        assertFalse(scoutMoodDue(ScoutMoodAiEntry(now - 1000), now))
        assertTrue(scoutMoodDue(ScoutMoodAiEntry(now - week), now))
        assertTrue(scoutMoodDue(ScoutMoodAiEntry(now + 1000), now))
    }

    @Test
    fun `only general moods and plain documentary have a verdict`() {
        assertEquals("sc", ScoutAnswers(mood = ScoutMood.SCARY).scoutMoodAiKey())
        assertEquals("doc", ScoutAnswers(watch = ScoutWatch.DOCUMENTARY).scoutMoodAiKey())
        assertNull(ScoutAnswers(watch = ScoutWatch.DOCUMENTARY, mood = ScoutMood.NATURE).scoutMoodAiKey())
        assertNull(ScoutAnswers(watch = ScoutWatch.ANIME, mood = ScoutMood.ISEKAI).scoutMoodAiKey())
        assertNull(ScoutAnswers().scoutMoodAiKey())
    }

    @Test
    fun `rejected titles leave and unjudged titles stay`() {
        val list = listOf(pick(1), pick(2), pick(3))
        assertEquals(list, list.scoutWithoutRejected(emptySet()))
        assertEquals(listOf(pick(1), pick(3)), list.scoutWithoutRejected(setOf(pick(2).key, "movie:99")))
    }

    private class Parts(val store: ScoutMoodAiStore, val finder: ScoutPicksFinder, val service: AskService, val check: ScoutMoodCheck)

    private fun parts(on: Boolean = true, moods: Map<String, ScoutMoodAiEntry> = emptyMap()): Parts {
        val store = mockk<ScoutMoodAiStore>(relaxed = true)
        every { store.isOn() } returns on
        every { store.read(any()) } returns ScoutMoodAiState(moods)
        val finder = mockk<ScoutPicksFinder>()
        coEvery { finder.fetchUnchecked(any(), any()) } returns (1..8).map { pick(it) }
        val service = mockk<AskService>()
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(2) }
        return Parts(store, finder, service, ScoutMoodCheck(store, dagger.Lazy { finder }, dagger.Lazy { service }, profiles))
    }

    private val everyMood = (ScoutMood.entries.filter { it.sliceId != null }.map { it.code } + "doc")

    @Test
    fun `switch off or no key means no call`() = runBlocking {
        val p = parts(on = false)
        p.check.runIfDue(now)
        coVerify(exactly = 0) { p.service.checkMood(any(), any()) }
    }

    @Test
    fun `nothing is asked again inside the week`() = runBlocking {
        val p = parts(moods = everyMood.associateWith { ScoutMoodAiEntry(now - 1000) })
        p.check.runIfDue(now)
        coVerify(exactly = 0) { p.service.checkMood(any(), any()) }
    }

    @Test
    fun `a verdict is saved for that profile and mood`() = runBlocking {
        val p = parts(moods = (everyMood - "sc").associateWith { ScoutMoodAiEntry(now - 1000) })
        coEvery { p.service.checkMood(any(), any()) } returns (1..6).map { pick(it).key }.toSet()
        val saved = slot<ScoutMoodAiEntry>()
        every { p.store.save(2, "sc", capture(saved)) } returns Unit
        p.check.runIfDue(now)
        assertEquals(now, saved.captured.checkedAt)
        assertEquals(listOf(pick(7).key, pick(8).key), saved.captured.rejected)
    }

    @Test
    fun `verdicts older than two weeks stop counting and a failed retry does not renew them`() {
        val fortnight = week * 2
        assertTrue(scoutMoodVerdictFresh(ScoutMoodAiEntry(now - fortnight + 1000), now))
        assertFalse(scoutMoodVerdictFresh(ScoutMoodAiEntry(now - fortnight), now))
        assertFalse(scoutMoodVerdictFresh(ScoutMoodAiEntry(now - fortnight, triedAt = now), now))
        assertFalse(scoutMoodVerdictFresh(ScoutMoodAiEntry(now + 1000), now))
    }

    private fun assertRetryNoted(p: Parts) {
        val saved = slot<ScoutMoodAiEntry>()
        every { p.store.save(2, "fg", capture(saved)) } returns Unit
        runBlocking { p.check.runIfDue(now) }
        assertEquals(now, saved.captured.triedAt)
        assertEquals(now - week, saved.captured.checkedAt)
        assertFalse(scoutMoodDue(saved.captured, now + 1000))
        verify(exactly = 3) { p.store.save(any(), any(), any()) }
    }

    @Test
    fun `laugh out loud leaves out animation unless family, anime or the taste says otherwise`() {
        fun pool(animated: Int) = com.nuvio.tv.domain.model.ScoutPool(
            picked = (1..10).map { ScoutPick(tmdbId = it, tv = false, name = "T", genres = if (it <= animated) listOf(16) else listOf(35)) }
        )
        val laugh = ScoutAnswers(mood = ScoutMood.LAUGH)
        assertTrue(laugh.scoutLaughLeavesOutAnimation(null))
        assertTrue(laugh.scoutLaughLeavesOutAnimation(pool(1)))
        assertFalse(laugh.scoutLaughLeavesOutAnimation(pool(2)))
        assertFalse(laugh.copy(family = true).scoutLaughLeavesOutAnimation(null))
        assertFalse(laugh.copy(watch = ScoutWatch.ANIME).scoutLaughLeavesOutAnimation(null))
        assertFalse(ScoutAnswers(mood = ScoutMood.ACTION).scoutLaughLeavesOutAnimation(null))
    }

    @Test
    fun `a failed mood does not stop the next one`() = runBlocking {
        val p = parts(moods = (everyMood - "fg" - "la").associateWith { ScoutMoodAiEntry(now - 1000) })
        coEvery { p.service.checkMood(any(), any()) } returnsMany listOf(null, (1..6).map { pick(it).key }.toSet())
        val saved = slot<ScoutMoodAiEntry>()
        every { p.store.save(2, "la", capture(saved)) } returns Unit
        p.check.runIfDue(now)
        assertEquals(now, saved.captured.checkedAt)
        assertEquals(listOf(pick(7).key, pick(8).key), saved.captured.rejected)
    }

    @Test
    fun `an empty list notes the try and asks no AI`() {
        val p = parts(moods = mapOf("fg" to ScoutMoodAiEntry(now - week)))
        coEvery { p.finder.fetchUnchecked(any(), any()) } returns emptyList()
        assertRetryNoted(p)
        coVerify(exactly = 0) { p.service.checkMood(any(), any()) }
    }

    @Test
    fun `an exception notes the try`() {
        val p = parts(moods = mapOf("fg" to ScoutMoodAiEntry(now - week)))
        coEvery { p.service.checkMood(any(), any()) } throws IllegalStateException("bad")
        assertRetryNoted(p)
    }

    @Test
    fun `a failed call keeps the last verdict, retries in a day and three failures end the round`() = runBlocking {
        val old = listOf(pick(9).key)
        val p = parts(moods = mapOf("fg" to ScoutMoodAiEntry(now - week, old)))
        coEvery { p.service.checkMood(any(), any()) } returns null
        val saved = slot<ScoutMoodAiEntry>()
        every { p.store.save(2, "fg", capture(saved)) } returns Unit
        p.check.runIfDue(now)
        assertEquals(old, saved.captured.rejected)
        assertTrue(scoutMoodDue(saved.captured, now + 24L * 60 * 60 * 1000))
        assertFalse(scoutMoodDue(saved.captured, now + 1000))
        coVerify(exactly = 3) { p.service.checkMood(any(), any()) }
        verify(exactly = 3) { p.store.save(any(), any(), any()) }
    }
}
