package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.nuvio.tv.ui.util.StableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedHeroSceneTest {
    private fun scene(owner: String?, play: Boolean = false, url: String? = null, rendered: Boolean = false) =
        ModernHeroSceneState(
            artwork = heroArtworkSelection(owner, "https://img/$owner.jpg"),
            heroBackdrop = "https://img/$owner.jpg",
            preview = null,
            enrichmentActive = false,
            shouldPlayTrailer = play,
            trailerFirstFrameRendered = rendered,
            trailerUrl = url,
            trailerAudioUrl = null,
            trailerPlaybackKey = url,
            trailerMuted = true,
            fullScreenBackdrop = false
        )

    @Test fun `a request rebuilt after start-up still starts the trailer`() {
        val firstRequest = mutableStateOf(scene("spider", play = false))
        val request = mutableStateOf<State<ModernHeroSceneState>>(firstRequest)
        val displayed = mutableStateOf<ModernHeroSceneState?>(scene("spider"))
        val prepared = preparedHeroSceneState({ displayed.value }, { request.value })
        assertFalse(prepared.value.shouldPlayTrailer)

        // Rows and settings arrive: Home builds a new requested state instead of updating the first one.
        request.value = mutableStateOf(scene("spider", play = true, url = "https://trailer/spider.mp4"))
        assertTrue(prepared.value.shouldPlayTrailer)
        assertEquals("https://trailer/spider.mp4", prepared.value.trailerUrl)

        request.value = mutableStateOf(scene("spider", play = true, url = "https://trailer/spider.mp4", rendered = true))
        assertTrue(prepared.value.trailerFirstFrameRendered)
    }

    @Test fun `the first request object is no longer read once it is replaced`() {
        val firstRequest = mutableStateOf(scene("spider", play = false))
        val request = mutableStateOf<State<ModernHeroSceneState>>(firstRequest)
        val prepared = preparedHeroSceneState({ scene("spider") }, { request.value })
        request.value = mutableStateOf(scene("spider", play = false))
        firstRequest.value = scene("spider", play = true, url = "https://trailer/old.mp4")
        assertFalse(prepared.value.shouldPlayTrailer)
        assertNull(prepared.value.trailerUrl)
    }

    @Test fun `no trailer while the hero still shows the previous title`() {
        val request = mutableStateOf<State<ModernHeroSceneState>>(
            mutableStateOf(scene("resident", play = true, url = "https://trailer/resident.mp4"))
        )
        val displayed = mutableStateOf<ModernHeroSceneState?>(scene("spider"))
        val prepared = preparedHeroSceneState({ displayed.value }, { request.value })
        assertFalse(prepared.value.shouldPlayTrailer)
        assertEquals("spider", prepared.value.artwork.ownerKey)

        displayed.value = scene("resident")
        assertTrue(prepared.value.shouldPlayTrailer)
    }

    @Test fun `trailers switched off stay off`() {
        val request = mutableStateOf<State<ModernHeroSceneState>>(mutableStateOf(scene("spider")))
        val prepared = preparedHeroSceneState({ scene("spider") }, { request.value })
        request.value = mutableStateOf(scene("spider", play = false, url = null))
        assertFalse(prepared.value.shouldPlayTrailer)
        assertNull(prepared.value.trailerUrl)
    }

    private fun timeLeft(owner: String, text: String) = scene(owner).copy(
        preview = HeroPreview(
            title = "Film", logo = null, description = null, contentTypeText = null, yearText = null,
            secondaryHighlightText = text, imdbText = null, genres = StableList(emptyList()),
            poster = null, backdrop = null, imageUrl = null
        )
    )

    @Test fun `time left follows the row while the same title stays in the hero`() {
        val shown = timeLeft("cw", "2H 39M LEFT")
        assertEquals("2H 35M LEFT", presentedHeroScene(shown, timeLeft("cw", "2H 35M LEFT")).preview?.secondaryHighlightText)
        assertEquals("2H 39M LEFT", presentedHeroScene(shown, timeLeft("other", "1H LEFT")).preview?.secondaryHighlightText)
    }

    @Test fun `nothing prepared yet shows the neutral hero of the requested title`() {
        val request = mutableStateOf<State<ModernHeroSceneState>>(
            mutableStateOf(scene("spider", play = true, url = "https://trailer/spider.mp4"))
        )
        val prepared = preparedHeroSceneState({ null }, { request.value })
        assertEquals("spider", prepared.value.artwork.ownerKey)
        assertNull(prepared.value.heroBackdrop)
        assertTrue(prepared.value.shouldPlayTrailer)
    }
}
