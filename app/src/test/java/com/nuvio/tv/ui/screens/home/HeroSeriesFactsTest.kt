package com.nuvio.tv.ui.screens.home

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeroSeriesFactsTest {
    private val context = mockk<Context>(relaxed = true).also { context ->
        every { context.getString(R.string.season_episode_format, *anyVararg()) } answers {
            val args = it.invocation.args[1] as Array<*>
            "S${args[0]} E${args[1]}"
        }
        every { context.getString(R.string.cw_new_season) } returns "New season"
        every { context.getString(R.string.cw_next_up) } returns "Next up"
    }

    private val showFacts = HeroSeriesFacts(
        description = "A Navy SEAL sets out for revenge.",
        genres = listOf("Action", "Drama"),
        releaseInfo = "2022-",
        runtime = "50 min",
        ageRating = "MA15+"
    )

    private fun nextUp(
        episodeTitle: String? = "Romulus S.V.",
        episodeDescription: String? = null,
        badgeLabel: String? = null,
        newSeason: Boolean = true
    ) = ContinueWatchingItem.NextUp(
        NextUpInfo(
            contentId = "tt11061714", contentType = "series", name = "The Terminal List",
            poster = null, backdrop = null, logo = null, videoId = "tt11061714:2:1",
            season = 2, episode = 1, episodeTitle = episodeTitle, episodeDescription = episodeDescription,
            thumbnail = null, lastWatched = 0L, sortTimestamp = 0L,
            isReleaseAlert = newSeason, isNewSeasonRelease = newSeason, badgeLabel = badgeLabel
        )
    )

    private fun build(item: ContinueWatchingItem) = buildContinueWatchingItem(
        item = item, useLandscapePosters = true, showImdbRatings = true,
        airsDateTemplate = "Airs %s", upcomingLabel = "Upcoming", context = context
    )

    @Test fun continueWatchingHeroShowsTheEpisodeTitleOnlyOnce() {
        val preview = build(nextUp()).heroPreview
        assertEquals("S2 E1 · Romulus S.V.", preview.contentTypeText)
        assertEquals("NEW SEASON", preview.secondaryHighlightText)
        assertNull(preview.description)
    }

    @Test fun continueWatchingHeroGetsTheShowLinesAndSynopsis() {
        val preview = build(nextUp()).heroPreview.withSeriesFacts(showFacts)
        assertEquals(listOf("Action", "Drama"), preview.genres.list)
        assertEquals("50m", preview.runtimeText)
        assertEquals("2022-", preview.yearText)
        assertEquals("MA15+", preview.ageRatingText)
        assertEquals("A Navy SEAL sets out for revenge.", preview.description)
        assertEquals("NEW SEASON", preview.secondaryHighlightText)
    }

    @Test fun episodeSynopsisWinsOverTheShowSynopsis() {
        val own = build(nextUp(episodeDescription = "Reece heads to Venezuela.")).heroPreview.withSeriesFacts(showFacts)
        assertEquals("Reece heads to Venezuela.", own.description)
        val fromMeta = build(nextUp()).heroPreview.withSeriesFacts(showFacts.copy(episodeDescription = "From the meta."))
        assertEquals("From the meta.", fromMeta.description)
    }

    @Test fun comingUpHeroKeepsItsDateAndGetsTheShowLines() {
        val card = nextUp(episodeTitle = "Risky Business", badgeLabel = "Fri, 30 Oct", newSeason = false)
        val preview = build(card).heroPreview
        assertEquals("Fri, 30 Oct", preview.secondaryHighlightText)
        assertEquals("S2 E1 · Risky Business", preview.contentTypeText)
        assertNull(preview.description)
        val filled = preview.withSeriesFacts(showFacts)
        assertEquals("Action", filled.genres.first())
        assertEquals("A Navy SEAL sets out for revenge.", filled.description)
        assertEquals("Fri, 30 Oct", filled.secondaryHighlightText)
    }

    @Test fun inProgressHeroNoLongerRepeatsTheEpisodeTitle() {
        val item = ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = "tt1", contentType = "series", name = "Show", poster = null, backdrop = null,
                logo = null, videoId = "tt1:1:2", season = 1, episode = 2, episodeTitle = "The Pilot",
                position = 10L, duration = 100L, lastWatched = 1L
            )
        )
        val preview = build(item).heroPreview
        assertNull(preview.description)
        assertEquals("Show synopsis", preview.withSeriesFacts(HeroSeriesFacts(description = "Show synopsis")).description)
    }

    @Test fun theCardsOwnValuesWin() {
        val card = build(nextUp()).heroPreview.copy(yearText = "2025", runtimeText = "1h")
        val filled = card.withSeriesFacts(showFacts)
        assertEquals("2025", filled.yearText)
        assertEquals("1h", filled.runtimeText)
        assertEquals(card, card.withSeriesFacts(null))
    }

    @Test fun factsComeFromTheShowAndTheMatchingEpisode() {
        val meta = Meta(
            id = "tt1", type = ContentType.SERIES, name = "Show", poster = null, posterShape = PosterShape.POSTER,
            background = null, logo = null, description = " Show synopsis ", releaseInfo = "2020-2024",
            imdbRating = null, genres = listOf("Crime", "", "Drama", "Mystery", "Thriller"), runtime = "45 min",
            director = emptyList(), cast = emptyList(),
            videos = listOf(
                Video("tt1:2:1", "One", null, null, season = 2, episode = 1, overview = "Episode one.", runtime = 58),
                Video("tt1:2:2", "Two", null, null, season = 2, episode = 2, overview = "Episode two.")
            ),
            ageRating = "M", country = null, awards = null, language = null, links = emptyList()
        )
        val first = meta.toHeroSeriesFacts(2, 1)
        assertEquals("Show synopsis", first.description)
        assertEquals("Episode one.", first.episodeDescription)
        assertEquals(listOf("Crime", "Drama", "Mystery"), first.genres)
        assertEquals("58 min", first.runtime)
        assertEquals("M", first.ageRating)
        assertEquals("45 min", meta.toHeroSeriesFacts(2, 2).runtime)
        assertNull(meta.toHeroSeriesFacts(null, null).episodeDescription)
    }

    @Test fun comingUpCardWithoutArtGetsTheShowBackdropAndLogoBehindIt() {
        val card = nextUp(episodeTitle = "Risky Business", badgeLabel = "New", newSeason = false).let {
            it.copy(info = it.info.copy(poster = "https://img/poster.jpg"))
        }
        val item = build(card)
        fun artwork(preview: HeroPreview) = landscapeHeroArtwork(
            owner = item.key, poster = preview.poster, background = preview.backdrop, landscape = null,
            fallbackBackground = item.heroPreview.backdrop, fallbackPoster = item.heroPreview.poster
        ).urls
        assertEquals(emptyList<String>(), artwork(item.heroPreview))

        val meta = Meta(
            id = "tt11061714", type = ContentType.SERIES, name = "Show", poster = "https://img/poster.jpg",
            posterShape = PosterShape.POSTER, background = "https://img/backdrop.jpg", logo = "https://img/logo.png",
            description = null, releaseInfo = null, imdbRating = null, genres = emptyList(), runtime = null,
            director = emptyList(), cast = emptyList(), videos = emptyList(), ageRating = null, country = null,
            awards = null, language = null, links = emptyList()
        )
        val filled = item.heroPreview.withSeriesFacts(meta.toHeroSeriesFacts(2, 1))
        assertEquals(listOf("https://img/backdrop.jpg"), artwork(filled))
        assertEquals("https://img/logo.png", filled.logo)

        val posterOnly = meta.copy(background = "https://img/poster.jpg").toHeroSeriesFacts(2, 1)
        assertNull(posterOnly.backdrop)
        assertEquals(emptyList<String>(), artwork(item.heroPreview.withSeriesFacts(posterOnly)))
    }

    @Test fun theCardsOwnBackdropAndLogoWinOverTheShows() {
        val card = build(nextUp()).heroPreview.copy(backdrop = "own-backdrop", logo = "own-logo")
        val filled = card.withSeriesFacts(HeroSeriesFacts(backdrop = "show-backdrop", logo = "show-logo"))
        assertEquals("own-backdrop", filled.backdrop)
        assertEquals("own-logo", filled.logo)
    }

    @Test fun comingUpFilmsAreLookedUpWithoutAnEpisode() {
        val film = ContinueWatchingItem.NextUp(
            NextUpInfo(
                contentId = "tt9", contentType = "movie", name = "Tenzing", poster = null, backdrop = null,
                logo = null, videoId = "", season = 0, episode = 0, episodeTitle = "Streaming and digital release",
                thumbnail = null, lastWatched = 0L, sortTimestamp = 0L, badgeLabel = "New"
            )
        )
        assertEquals(HeroSeriesRef("movie", "tt9", null, null), film.heroSeriesRef())
        assertEquals("series:tt11061714:2:1", nextUp().heroSeriesRef()?.key)
    }
}
