@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.scout

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ScoutRating
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.ui.v2.components.NuvioActionPill

@StringRes
fun ScoutRating.labelRes(): Int = when (this) {
    ScoutRating.NOT_FOR_ME -> R.string.scout_rate_not_for_me
    ScoutRating.LIKE -> R.string.scout_rate_like
    ScoutRating.LOVE -> R.string.scout_rate_love
}

@Composable
fun ScoutRatingIcon(rating: ScoutRating?, size: Dp, modifier: Modifier = Modifier) {
    when (rating) {
        ScoutRating.NOT_FOR_ME -> Icon(Icons.Default.ThumbDown, null, modifier.size(size))
        ScoutRating.LOVE -> Box(modifier.width(size * 1.32f)) {
            Icon(
                Icons.Default.ThumbUp,
                null,
                Modifier.size(size).offset(x = size * 0.32f).alpha(0.55f),
                tint = LocalContentColor.current
            )
            Icon(Icons.Default.ThumbUp, null, Modifier.size(size))
        }
        else -> Icon(Icons.Default.ThumbUp, null, modifier.size(size))
    }
}

@Composable
fun ScoutRateChip(
    rating: ScoutRating,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    val requester = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    val secondary = NuvioTheme.colors.Secondary
    if (LocalV2Appearance.current != null) {
        val shape = RoundedCornerShape(NuvioTheme.radii.md)
        NuvioActionPill(
            onClick = onClick,
            stationary = true,
            modifier = modifier.then(requester)
                .then(if (selected) Modifier.background(secondary.copy(alpha = .34f), shape).border(1.dp, secondary, shape) else Modifier)
        ) { ChipContent(rating, 17.dp, MaterialTheme.typography.labelMedium) }
        return
    }
    val shape = RoundedCornerShape(NuvioTheme.radii.full)
    Button(
        onClick = onClick,
        modifier = modifier.then(requester),
        shape = ButtonDefaults.shape(shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) secondary.copy(alpha = .34f) else NuvioTheme.colors.TextPrimary.copy(alpha = .08f),
            focusedContainerColor = secondary,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContentColor = NuvioTheme.colors.OnSecondary
        ),
        border = ButtonDefaults.border(
            border = if (selected) Border(androidx.compose.foundation.BorderStroke(1.dp, secondary), shape = shape) else Border.None,
            focusedBorder = Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    ) { ChipContent(rating, 18.dp, MaterialTheme.typography.labelLarge) }
}

@Composable
private fun ChipContent(rating: ScoutRating, icon: Dp, style: androidx.compose.ui.text.TextStyle) {
    ScoutRatingIcon(rating, icon)
    Spacer(Modifier.width(8.dp))
    Text(stringResource(rating.labelRes()), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** The line of three in a dialog: long-press menu, the question after a film, Your thumbs. */
@Composable
fun ScoutRateRow(
    current: ScoutRating?,
    onRate: (ScoutRating?) -> Unit,
    modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ScoutRating.entries.forEach { rating ->
            ScoutRateChip(
                rating = rating,
                selected = current == rating,
                onClick = { onRate(if (current == rating) null else rating) },
                modifier = Modifier.weight(1f),
                focusRequester = firstFocus.takeIf { rating == (current ?: ScoutRating.LIKE) }
            )
        }
    }
}

@StringRes
fun scoutRatingLabelRes(rating: ScoutRating): Int = rating.labelRes()

/** Long-press menu line; the rating feature's switch hides it with the rest. */
@Composable
fun ScoutPosterRateLine(
    title: com.nuvio.tv.domain.model.ScoutTitle,
    onDone: () -> Unit = {},
    viewModel: ScoutRatingViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    if (!com.nuvio.tv.domain.model.ScoutFeature.available) return
    val profile = viewModel.profile.collectAsStateWithLifecycle().value
    if (!profile.settings.ratingsEnabled) return
    ScoutRateRow(
        current = profile.rating(title.id),
        onRate = {
            viewModel.setRating(title, it)
            onDone()
        }
    )
}

/** A labelled button that follows both looks. */
@Composable
fun ScoutPageButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    com.nuvio.tv.ui.screens.scout.ScoutChip(label, onClick, modifier)
}
