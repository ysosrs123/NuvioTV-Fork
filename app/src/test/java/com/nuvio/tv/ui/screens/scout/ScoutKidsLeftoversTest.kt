package com.nuvio.tv.ui.screens.scout

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.scout.ScoutMixPreview
import com.nuvio.tv.core.scout.ScoutMixRecipe
import com.nuvio.tv.core.scout.ScoutReadyMixBuilder
import com.nuvio.tv.core.scout.ScoutWidened
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScoutKidsLeftoversTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val builder = mockk<ScoutReadyMixBuilder>()
    private val nothing = ScoutMixPreview(emptyList(), ScoutWidened.NONE, true)

    @Test
    fun `Mix your own on a limited profile waits for the limit before its first preview`() = runTest(mainDispatcherRule.dispatcher) {
        val answer = CompletableDeferred<Boolean>()
        coEvery { builder.limited() } coAnswers { answer.await() }
        coEvery { builder.preview(any(), any(), any()) } returns nothing

        val model = ScoutMixOwnViewModel(SavedStateHandle(), builder)
        advanceUntilIdle()
        coVerify(exactly = 0) { builder.preview(any(), any(), any()) }

        answer.complete(true)
        advanceUntilIdle()
        coVerify(exactly = 1) { builder.preview(match { it.family }, any(), any()) }
        coVerify(exactly = 0) { builder.preview(match { !it.family }, any(), any()) }
        assertTrue(model.state.value.recipe.family)
        assertEquals(false, model.state.value.finding)
    }

    @Test
    fun `Mix your own without a limit previews the recipe as it came`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { builder.limited() } returns false
        coEvery { builder.preview(any(), any(), any()) } returns nothing

        ScoutMixOwnViewModel(SavedStateHandle(), builder)
        advanceUntilIdle()
        coVerify(exactly = 1) { builder.preview(ScoutMixRecipe(), any(), any()) }
    }

    @Test
    fun `a choice made before the limit answers is previewed again as a family recipe`() = runTest(mainDispatcherRule.dispatcher) {
        val answer = CompletableDeferred<Boolean>()
        coEvery { builder.limited() } coAnswers { answer.await() }
        coEvery { builder.preview(any(), any(), any()) } returns nothing

        val model = ScoutMixOwnViewModel(SavedStateHandle(), builder)
        val blend = com.nuvio.tv.core.scout.ScoutMixBlend.entries.last()
        model.change { it.copy(blend = blend) }
        answer.complete(true)
        advanceUntilIdle()
        assertEquals(ScoutMixRecipe(blend = blend, family = true), model.state.value.recipe)
        assertEquals(false, model.state.value.finding)
        coVerify(exactly = 0) { builder.preview(match { !it.family }, any(), any()) }
    }

    @Test
    fun `the who chip names Family night when nobody else is ticked`() {
        assertEquals(ScoutWhoLabel.JUST_ME, scoutWhoLabel(0, family = false))
        assertEquals(ScoutWhoLabel.FAMILY, scoutWhoLabel(0, family = true))
        assertEquals(ScoutWhoLabel.WITH_ONE, scoutWhoLabel(1, family = true))
        assertEquals(ScoutWhoLabel.COUNT, scoutWhoLabel(2, family = false))
    }

    @Test
    fun `Mix it pressed before the limit answers waits and builds a family mix`() = runTest(mainDispatcherRule.dispatcher) {
        val answer = CompletableDeferred<Boolean>()
        coEvery { builder.limited() } coAnswers { answer.await() }
        coEvery { builder.preview(any(), any(), any()) } returns nothing
        coEvery { builder.own(any(), any()) } returns null

        val model = ScoutMixOwnViewModel(SavedStateHandle(), builder)
        model.mixIt("Mine")
        advanceUntilIdle()
        coVerify(exactly = 0) { builder.own(any(), any()) }

        answer.complete(true)
        advanceUntilIdle()
        coVerify(exactly = 1) { builder.own(match { it.family }, "Mine") }
    }

    @Test
    fun `a forced family recipe drops a mood Family night does not offer`() {
        val dark = com.nuvio.tv.core.scout.SCOUT_NOT_FAMILY_FEELS.first()
        assertEquals(com.nuvio.tv.core.scout.ScoutMixFeel.ANY, scoutFamilyRecipe(ScoutMixRecipe(feel = dark)).feel)
        val kept = com.nuvio.tv.core.scout.ScoutMixFeel.entries.first {
            it != com.nuvio.tv.core.scout.ScoutMixFeel.ANY && it !in com.nuvio.tv.core.scout.SCOUT_NOT_FAMILY_FEELS
        }
        assertEquals(ScoutMixRecipe(feel = kept, family = true), scoutFamilyRecipe(ScoutMixRecipe(feel = kept)))
    }

    @Test
    fun `an empty build is not tried again for half an hour, then it is`() {
        var clock = 1_000L
        val empty = com.nuvio.tv.core.scout.ScoutEmptyBuilds { clock }
        assertEquals(false, empty.recent("1|ready_x|true|PG"))
        empty.remember("1|ready_x|true|PG")
        clock += com.nuvio.tv.core.scout.ScoutEmptyBuilds.WAIT_MS - 1
        assertTrue(empty.recent("1|ready_x|true|PG"))
        assertEquals(false, empty.recent("1|ready_x|false|PG"))
        clock += 1
        assertEquals(false, empty.recent("1|ready_x|true|PG"))
    }
}
