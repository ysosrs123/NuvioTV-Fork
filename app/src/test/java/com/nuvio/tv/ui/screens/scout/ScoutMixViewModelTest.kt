package com.nuvio.tv.ui.screens.scout

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.player.ScoutMixReturn
import com.nuvio.tv.core.player.ScoutMixSession
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.scout.ScoutMixLibrary
import com.nuvio.tv.core.scout.ScoutMixSuggestions
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.ScoutMixStore
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.ScoutMix
import com.nuvio.tv.domain.model.ScoutMixEpisodes
import com.nuvio.tv.domain.model.ScoutMixPick
import com.nuvio.tv.domain.model.ScoutMixShow
import com.nuvio.tv.domain.model.Video
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScoutMixViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val profile = 1
    private val show = ScoutMixShow("tt1", "One")
    private val session = ScoutMixSession()

    private fun meta() = Meta(
        id = "tt1",
        type = ContentType.SERIES,
        name = "One",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        videos = (1..6).map {
            Video("tt1:1:$it", "E$it", "2020-01-01", null, season = 1, episode = it, overview = null)
        },
        country = null,
        awards = null,
        language = null,
        links = emptyList()
    )

    private val added = ScoutMixShow("tt2", "Two")
    private val offer = MetaPreview(
        id = "tmdb:2", type = ContentType.SERIES, name = "Two", poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
    )
    private val allWatched = (1..6).map { 1 to it }.toSet()

    private fun viewModel(keepPlaying: Boolean = true, seenEverything: Boolean = false): ScoutMixViewModel {
        val store = mockk<ScoutMixStore>(relaxed = true)
        every { store.observe(profile) } returns flowOf(listOf(ScoutMix("m", "Mix", listOf(show), keepPlaying = keepPlaying)))
        val library = mockk<ScoutMixLibrary>(relaxed = true)
        coEvery { library.episodes(profile, any()) } returns ScoutMixEpisodes(meta(), if (seenEverything) allWatched else emptySet())
        coEvery { library.episodes(profile, match { it.id == "tt2" }) } returns ScoutMixEpisodes(meta())
        coEvery { library.showFrom(offer) } returns added
        coEvery { library.startedEpisode(any()) } returns null
        every { library.withSuggestedName(any(), any()) } answers { firstArg() }
        val suggestions = mockk<ScoutMixSuggestions>()
        coEvery { suggestions.forMix(any()) } returns listOf(offer)
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.activeProfileId } returns MutableStateFlow(profile)
        val settings = mockk<PlayerSettingsDataStore>(relaxed = true)
        every { settings.playerSettings } returns flowOf(PlayerSettings(stillWatchingEnabled = false))
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        every { layout.posterCardWidthDp } returns flowOf(126)
        every { layout.posterCardCornerRadiusDp } returns flowOf(12)
        return ScoutMixViewModel(SavedStateHandle(mapOf("mixId" to "m")), store, library, suggestions, session, profiles, settings, layout)
    }

    @Test
    fun `a mix with nothing left offers shows, and only then`() = runTest(mainDispatcherRule.dispatcher) {
        val out = viewModel(seenEverything = true)
        runCurrent()
        assertEquals(ScoutMixPick.AllSeen, out.state.value.pick)
        assertEquals(listOf(offer), out.state.value.offers)

        val going = viewModel()
        runCurrent()
        assertTrue(going.state.value.pick is ScoutMixPick.Episode)
        assertTrue(going.state.value.offers.isEmpty())
    }

    @Test
    fun `adding an offered show picks an episode from it`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(seenEverything = true)
        runCurrent()
        vm.add(offer)
        runCurrent()
        assertTrue(vm.state.value.pick is ScoutMixPick.Episode)
        assertTrue(vm.state.value.offers.isEmpty())
    }

    @Test
    fun `an episode the countdown starts still belongs to the mix`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        runCurrent()
        val first = vm.play()!!
        assertTrue(session.returnFromPlayer("tt1", ScoutMixReturn.ENDED))
        vm.onShown()
        runCurrent()
        assertEquals(8, vm.state.value.countdown)
        assertFalse(session.active)

        advanceTimeBy(8_001)
        runCurrent()

        val started = vm.start.first()
        assertTrue(started.video.id != first.video.id)
        assertTrue(session.matches(profile, "tt1", started.video.id))
        assertEquals(null, vm.state.value.countdown)
        assertTrue(vm.state.value.pick is ScoutMixPick.Episode)
    }

    @Test
    fun `stopping the countdown starts nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.play()
        session.returnFromPlayer("tt1", ScoutMixReturn.ENDED)
        vm.onShown()
        runCurrent()
        vm.stop()
        advanceTimeBy(8_001)
        runCurrent()
        assertFalse(session.active)
        assertEquals(null, vm.state.value.countdown)
    }

    @Test
    fun `leaving the page during the countdown starts nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.play()
        session.returnFromPlayer("tt1", ScoutMixReturn.ENDED)
        vm.onShown()
        runCurrent()
        assertEquals(8, vm.state.value.countdown)

        vm.onHidden()
        advanceTimeBy(8_001)
        runCurrent()

        assertFalse(session.active)
        assertEquals(null, vm.state.value.countdown)
        assertTrue(vm.state.value.pick is ScoutMixPick.Episode)
    }

    @Test
    fun `a pick that lands after the page is left does not count down`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.play()
        session.returnFromPlayer("tt1", ScoutMixReturn.ENDED)
        vm.onShown()
        vm.onHidden()
        advanceTimeBy(8_001)
        runCurrent()

        assertFalse(session.active)
        assertEquals(null, vm.state.value.countdown)
    }

    @Test
    fun `while an episode plays the player is offered the mix's next pick`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        runCurrent()
        val first = vm.play()!!
        runCurrent()
        val next = session.upNextFor(profile, "tt1", first.video.id)!!
        assertEquals("Mix", next.mixName)
        assertEquals("One", next.showName)
        assertTrue(next.videoId != first.video.id)
        assertTrue(next.startsByItself)
    }

    @Test
    fun `taking the player's card plays that pick at once`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        runCurrent()
        val first = vm.play()!!
        runCurrent()
        val offered = session.upNextFor(profile, "tt1", first.video.id)!!
        session.pressedNext(profile, "tt1", first.video.id)
        assertTrue(session.returnFromPlayer("tt1", ScoutMixReturn.NEXT))
        vm.onShown()
        runCurrent()

        val started = vm.start.first()
        assertEquals(offered.videoId, started.video.id)
        assertTrue(session.matches(profile, "tt1", offered.videoId))
        assertEquals(null, vm.state.value.countdown)
    }

    @Test
    fun `the episode that is up next is never also in the queue`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        runCurrent()
        repeat(3) {
            vm.play()
            session.returnFromPlayer("tt1", ScoutMixReturn.ENDED)
            vm.onShown()
            runCurrent()
            val pick = vm.state.value.pick as ScoutMixPick.Episode
            assertFalse(vm.state.value.coming.any { it.key == pick.key })
            vm.stop()
        }
    }

    @Test
    fun `the row lists each episode once even when the queue repeats the pick`() {
        val episodes = meta().videos.map { ScoutMixPick.Episode(show, meta(), it) }
        val tiles = mixQueueTiles(episodes[1], listOf(episodes[1], episodes[2], episodes[2], episodes[3]))
        assertEquals(listOf(episodes[1].key, episodes[2].key, episodes[3].key), tiles.map { it.key })
        assertEquals(listOf(episodes[2].key), comingWithout(listOf(episodes[1], episodes[2]), episodes[1]).map { it.key })
        assertEquals(2, comingWithout(listOf(episodes[1], episodes[2]), ScoutMixPick.AllSeen).size)
    }

    @Test
    fun `with Keep playing off the player's card waits to be pressed`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(keepPlaying = false)
        runCurrent()
        val first = vm.play()!!
        runCurrent()
        assertFalse(session.upNextFor(profile, "tt1", first.video.id)!!.startsByItself)
    }
}
