package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.util.localizeEpisodeTitle

internal fun playerMovieYear(value: String?): String? = value?.trim()
    ?.takeIf { it.matches(Regex("[12][0-9]{3}(-[0-9]{2}-[0-9]{2})?")) }
    ?.take(4)

internal fun playerSecondaryMetadata(state: PlayerUiState, designation: String, episodeTitle: String?): String? =
    when (state.contentType?.lowercase()) {
        "movie" -> playerMovieYear(state.releaseYear)
        "cloud" -> null // Cloud file names are not episode metadata.
        else -> if (state.currentSeason != null && state.currentEpisode != null) {
            listOfNotNull(designation, episodeTitle?.trim()?.takeIf(String::isNotEmpty)).joinToString(" • ")
        } else null
    }

/** Uses the timer's theme typography, including the user's UI/font scale. */
@Composable
internal fun PlayerSecondaryMetadata(state: PlayerUiState, modifier: Modifier = Modifier) {
    val designation = if (state.currentSeason != null && state.currentEpisode != null)
        stringResource(R.string.season_episode_format, state.currentSeason, state.currentEpisode) else ""
    val title = state.currentEpisodeTitle?.localizeEpisodeTitle(LocalContext.current)
    val text = playerSecondaryMetadata(state, designation, title) ?: return
    Text(text, color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.bodyMedium,
        maxLines = 1, modifier = modifier.fillMaxWidth()
            .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1200, velocity = 45.dp))
}
