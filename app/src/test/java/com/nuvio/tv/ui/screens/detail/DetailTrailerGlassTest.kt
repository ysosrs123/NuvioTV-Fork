package com.nuvio.tv.ui.screens.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailTrailerGlassTest {
    private fun smoked(
        foreground: Boolean = false,
        background: Boolean = false,
        rendered: Boolean = false,
        pauseOnScroll: Boolean = true,
        scrolled: Boolean = false
    ) = heroGlassOverTrailer(foreground, background, rendered, pauseOnScroll, scrolled)

    @Test fun noTrailerKeepsFrostedGlass() {
        listOf(false, true).forEach { pause ->
            listOf(false, true).forEach { scrolled ->
                assertFalse(smoked(pauseOnScroll = pause, scrolled = scrolled))
                assertFalse(smoked(rendered = true, pauseOnScroll = pause, scrolled = scrolled))
            }
        }
    }

    @Test fun backgroundTrailerSmokesTheGlassOnlyOnceItsPictureIsUp() {
        assertFalse(smoked(background = true))
        assertTrue(smoked(background = true, rendered = true))
        assertTrue(smoked(background = true, rendered = true, pauseOnScroll = false))
    }

    @Test fun pauseOnScrollBringsTheStillBackdropAndFrostedGlassBack() {
        assertFalse(smoked(background = true, rendered = true, pauseOnScroll = true, scrolled = true))
        assertTrue(smoked(background = true, rendered = true, pauseOnScroll = true, scrolled = false))
    }

    @Test fun trailerThatKeepsPlayingWhileScrolledStaysSmoked() {
        assertTrue(smoked(background = true, rendered = true, pauseOnScroll = false, scrolled = true))
    }

    @Test fun foregroundTrailerNeverSmokesBecauseTheButtonsAreHidden() {
        listOf(false, true).forEach { rendered ->
            listOf(false, true).forEach { pause ->
                listOf(false, true).forEach { scrolled ->
                    assertFalse(smoked(foreground = true, rendered = rendered, pauseOnScroll = pause, scrolled = scrolled))
                    assertFalse(smoked(foreground = true, background = true, rendered = rendered,
                        pauseOnScroll = pause, scrolled = scrolled))
                }
            }
        }
    }

    @Test fun stillBackdropHidesExactlyWhileTheBackgroundTrailerShows() {
        listOf(false, true).forEach { playing ->
            listOf(false, true).forEach { rendered ->
                listOf(false, true).forEach { pause ->
                    listOf(false, true).forEach { scrolled ->
                        assertEquals(
                            playing && rendered && !(pause && scrolled),
                            isBackgroundTrailerShowing(playing, rendered, pause, scrolled)
                        )
                    }
                }
            }
        }
    }
}
