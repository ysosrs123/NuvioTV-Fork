@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.scout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.core.image.sizedHomeArtworkUrl
import com.nuvio.tv.core.scout.SCOUT_TASTE_MIN_TITLES
import com.nuvio.tv.core.scout.SCOUT_TASTE_TITLES
import com.nuvio.tv.core.scout.ScoutSlices
import com.nuvio.tv.core.scout.ScoutTasteBar
import com.nuvio.tv.core.scout.ScoutTastePerson
import com.nuvio.tv.domain.model.ScoutPick
import com.nuvio.tv.domain.model.ScoutRating
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import com.nuvio.tv.ui.scout.ScoutRateChip
import com.nuvio.tv.ui.scout.ScoutRatingIcon
import com.nuvio.tv.ui.screens.home.MODERN_LANDSCAPE_LOGO_GRADIENT
import com.nuvio.tv.ui.screens.settings.ScoutThumbsPage
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberRawSvgPainter
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.AvatarModel
import com.nuvio.tv.ui.v2.components.NuvioAvatar
import com.nuvio.tv.ui.v2.components.nuvioV2Focus
import java.util.Locale

private val TASTE_CHART_WIDTH = 290.dp
private val TASTE_DECADES_WIDTH = 270.dp
private val CHART_GAP = 40.dp
private val CHARTS_DROP = 28.dp
private val CHART_BODY = 112.dp
private val CHART_LINE = 26.dp
private val LEARN_BUTTON = 150.dp
private const val SOFTER_BAR = .55f
private const val DECADE_SLOTS = 6
private val FACE_NAME_ROOM = 52.dp
private val FACE_SMALLEST = 72.dp
private const val BACKDROP_WIDTH = 500
private const val POSTER_IMAGE_WIDTH = 342

private fun genreRes(key: String): Int? = ScoutSlices.byId("genre_$key")?.titleRes?.takeIf { it != 0 }

@Composable
private fun genreName(key: String): String? = genreRes(key)?.let { stringResource(it) }

@Composable
private fun countryName(code: String): String = remember(code) { Locale("", code).displayCountry }

@Composable
private fun decadeName(key: String): String = stringResource(R.string.scout_slice_decade, key)

private const val TASTE_LINES = 3

internal const val SCOUT_ROW_TITLES = "titles"
internal const val SCOUT_ROW_DIRECTORS = "directors"
internal const val SCOUT_ROW_ACTORS = "actors"
private const val SCOUT_ROW_LEARN = "learn"

/** The upper part of the Your taste tab: the headline and three charts, straight on the picture. */
@Composable
internal fun ScoutTasteHero(state: ScoutTasteState, focus: (String) -> Modifier, onGenre: (String) -> Unit) {
    val summary = state.summary
    Column(Modifier.offset(y = NuvioTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
        when {
            state.working -> Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                Text(stringResource(R.string.scout_taste_working, state.done, state.total), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary)
                Text(stringResource(R.string.scout_taste_working_sub), style = scoutReadingStyle(), color = NuvioTheme.colors.TextSecondary)
                ScoutTrack(state.done.toFloat() / state.total.coerceAtLeast(1), Modifier.fillMaxWidth(.4f))
            }
            state.titlesUsed < SCOUT_TASTE_MIN_TITLES -> Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                Text(stringResource(R.string.scout_taste_new), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary)
                Text(stringResource(R.string.scout_taste_new_sub), style = scoutReadingStyle(), color = NuvioTheme.colors.TextSecondary)
            }
        }
        if (summary.isEmpty) return@Column
        ScoutTasteHead(state)
        Row(
            modifier = Modifier.offset(x = -NuvioTheme.spacing.xs).padding(top = CHARTS_DROP),
            horizontalArrangement = Arrangement.spacedBy(CHART_GAP)
        ) {
            val genres = summary.genres.mapNotNull { bar -> genreName(bar.key)?.let { bar to it } }.take(TASTE_LINES)
            ScoutChart(stringResource(R.string.scout_taste_genres), TASTE_CHART_WIDTH) {
                ScoutChartLines(genres.size) {
                    genres.forEachIndexed { index, (bar, name) ->
                        ScoutGenreRow(name, bar.share, index == 0, onClick = { onGenre(bar.key) }, modifier = focus("taste_genre_${bar.key}"))
                    }
                }
            }
            ScoutChart(stringResource(R.string.scout_taste_decades), TASTE_DECADES_WIDTH) {
                ScoutDecades(summary.decades)
            }
            val countries = summary.countries.take(TASTE_LINES)
            ScoutChart(stringResource(R.string.scout_taste_from), TASTE_CHART_WIDTH) {
                ScoutChartLines(countries.size) {
                    countries.forEachIndexed { index, bar ->
                        ScoutBarLine(countryName(bar.key), bar.share, index == 0, Modifier.padding(horizontal = NuvioTheme.spacing.xs, vertical = NuvioTheme.spacing.xxs))
                    }
                }
            }
        }
    }
}

/** Same title line and same body height for every chart, however much each one has to show. */
@Composable
private fun ScoutChart(title: String, width: Dp, body: @Composable () -> Unit) {
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xs))
        Box(Modifier.fillMaxWidth().height(CHART_BODY)) { body() }
    }
}

/** First line at the top of the chart, last line at the bottom, with empty slots kept when there are fewer. */
@Composable
private fun ScoutChartLines(filled: Int, lines: @Composable () -> Unit) {
    val towardsEnd = if (LocalLayoutDirection.current == LayoutDirection.Rtl) FocusDirection.Left else FocusDirection.Right
    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusProperties { onExit = { if (requestedFocusDirection == towardsEnd) cancelFocusChange() } }
            .focusGroup(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        lines()
        repeat((TASTE_LINES - filled).coerceAtLeast(0)) { Spacer(Modifier.height(CHART_LINE)) }
    }
}

/** The lower part of the Your taste tab: what shapes the picks, as titles, directors or actors. */
@Composable
internal fun ScoutTasteBottom(
    state: ScoutTasteState,
    viewModel: ScoutTasteViewModel,
    height: Dp,
    slot: Dp,
    focus: (String) -> Modifier,
    onOpenTitle: (String, String) -> Unit,
    onOpenPerson: (Int, String, Boolean) -> Unit,
    onThumbs: () -> Unit,
    onStartFresh: () -> Unit
) {
    val settings = state.profile.settings
    val showNudge = settings.ratingsEnabled && state.nudge != null
    val row = state.profile.settings.tasteRow.let { chosen ->
        when {
            chosen == SCOUT_ROW_LEARN && !showNudge -> SCOUT_ROW_TITLES
            chosen == SCOUT_ROW_TITLES && state.shaping.isEmpty() -> SCOUT_ROW_DIRECTORS
            else -> chosen
        }
    }
    val showOnRest: (String) -> Unit = { wanted -> if (row != wanted) viewModel.showRow(wanted) }
    Column(Modifier.padding(top = NuvioTheme.spacing.xs), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = ScoutFocusRoom), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.padding(vertical = NuvioTheme.spacing.xs)) {
                Text(stringResource(R.string.scout_taste_shaping_short), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.Secondary, maxLines = 1)
                Spacer(Modifier.padding(top = NuvioTheme.spacing.xxs).height(NuvioTheme.spacing.xxs))
            }
            Spacer(Modifier.width(NuvioTheme.spacing.sm))
            ScoutWord(stringResource(R.string.scout_taste_titles), { showOnRest(SCOUT_ROW_TITLES) }, focus("taste_row_titles"), on = row == SCOUT_ROW_TITLES, onFocused = { showOnRest(SCOUT_ROW_TITLES) })
            ScoutWord(stringResource(R.string.scout_taste_directors), { showOnRest(SCOUT_ROW_DIRECTORS) }, focus("taste_row_directors"), on = row == SCOUT_ROW_DIRECTORS, onFocused = { showOnRest(SCOUT_ROW_DIRECTORS) })
            ScoutWord(stringResource(R.string.scout_taste_actors), { showOnRest(SCOUT_ROW_ACTORS) }, focus("taste_row_actors"), on = row == SCOUT_ROW_ACTORS, onFocused = { showOnRest(SCOUT_ROW_ACTORS) })
            if (showNudge) {
                ScoutWord(stringResource(R.string.scout_taste_learn), { showOnRest(SCOUT_ROW_LEARN) }, focus("taste_row_learn"), on = row == SCOUT_ROW_LEARN, onFocused = { showOnRest(SCOUT_ROW_LEARN) })
            }
            Spacer(Modifier.weight(1f))
            if (settings.ratingsEnabled && state.thumbs > 0) {
                ScoutWord(stringResource(R.string.scout_taste_thumbs, state.thumbs), onThumbs, focus("taste_thumbs"))
            }
            if (state.thumbs > 0) {
                ScoutWord(stringResource(R.string.scout_taste_start_fresh), onStartFresh, focus("taste_fresh"))
            }
        }
        val faces = (height - FACE_NAME_ROOM).coerceAtLeast(FACE_SMALLEST)
        when (row) {
            SCOUT_ROW_DIRECTORS -> ScoutFaceRow(state.summary.directors, faces, slot, focus, onOpenPerson)
            SCOUT_ROW_ACTORS -> ScoutFaceRow(state.summary.actors, faces, slot, focus, onOpenPerson)
            SCOUT_ROW_LEARN -> state.nudge?.let { pick ->
                Box(Modifier.padding(horizontal = ScoutFocusRoom).padding(top = NuvioTheme.spacing.xs)) {
                    ScoutLearn(pick, height, onRate = { viewModel.rate(pick, it) }, onSkip = { viewModel.skip(pick) }, focus = null)
                }
            }
            else -> LazyRow(
                modifier = Modifier.scoutRowBounds(),
                contentPadding = PaddingValues(horizontal = ScoutFocusRoom, vertical = NuvioTheme.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
            ) {
                items(state.shaping, key = { it.id }) { title ->
                    ScoutPosterTile(title, slot, onClick = { onOpenTitle(title.id, title.type) }, modifier = focus("taste_title_${title.id}"))
                }
            }
        }
    }
}

@Composable
private fun ScoutPosterTile(title: ScoutShapingTitle, width: Dp, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val v2 = LocalV2Appearance.current != null
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Card(
        onClick = onClick,
        modifier = modifier
            .size(width = width, height = width * 1.5f)
            .onFocusChanged { focused = it.isFocused }
            .nuvioV2Focus(focused, shape, stationary = true),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f),
        colors = CardDefaults.colors(containerColor = NuvioTheme.colors.BackgroundCard, focusedContainerColor = NuvioTheme.colors.BackgroundCard),
        border = CardDefaults.border(
            focusedBorder = if (v2) Border.None else Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        )
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = title.poster?.let { sizedHomeArtworkUrl(it, POSTER_IMAGE_WIDTH) },
                contentDescription = title.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            title.rating?.let { thumb ->
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(NuvioTheme.spacing.xs)
                        .background(NuvioTheme.colors.Background.copy(alpha = .72f), RoundedCornerShape(NuvioTheme.radii.full))
                        .padding(horizontal = NuvioTheme.spacing.xs, vertical = NuvioTheme.spacing.xxs)
                ) { ScoutRatingIcon(thumb, 14.dp) }
            }
        }
    }
}

@Composable
private fun ScoutFaceRow(people: List<ScoutTastePerson>, size: Dp, slot: Dp, focus: (String) -> Modifier, onOpen: (Int, String, Boolean) -> Unit) {
    LazyRow(
        modifier = Modifier.scoutRowBounds(),
        contentPadding = PaddingValues(horizontal = ScoutFocusRoom, vertical = NuvioTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        items(people, key = { it.id }) { person ->
            ScoutFace(person, size, slot, onClick = { onOpen(person.id, person.name, person.crew) }, modifier = focus("taste_person_${person.id}"))
        }
    }
}

/** What pressing a genre offers, and the question before starting over. */
@Composable
internal fun ScoutTasteDialogs(
    viewModel: ScoutTasteViewModel,
    thumbs: Int,
    chosenGenre: String?,
    onCloseGenre: () -> Unit,
    onOpenSlice: (String) -> Unit,
    confirmFresh: Boolean,
    onCloseFresh: () -> Unit,
    onFresh: () -> Unit
) {
    chosenGenre?.let { key ->
        val name = genreName(key).orEmpty()
        val show = remember { FocusRequester() }
        NuvioDialog(onDismiss = onCloseGenre, title = name) {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                ScoutChip(
                    stringResource(R.string.scout_genre_show, name),
                    onClick = {
                        onCloseGenre()
                        onOpenSlice("genre_$key")
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(show)
                )
                ScoutChip(
                    stringResource(R.string.scout_genre_leave_out, name),
                    onClick = {
                        onCloseGenre()
                        key.toIntOrNull()?.let(viewModel::leaveOut)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        LaunchedEffect(Unit) { show.requestFocusAfterFrames() }
    }
    if (confirmFresh) {
        val cancel = remember { FocusRequester() }
        NuvioDialog(
            onDismiss = onCloseFresh,
            title = stringResource(R.string.scout_fresh_title),
            subtitle = pluralStringResource(R.plurals.scout_fresh_body, thumbs, thumbs)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                ScoutChip(stringResource(R.string.scout_cancel), onClick = onCloseFresh, modifier = Modifier.weight(1f).focusRequester(cancel))
                ScoutChip(
                    stringResource(R.string.scout_taste_start_fresh),
                    onClick = {
                        onCloseFresh()
                        viewModel.startFresh()
                        onFresh()
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        LaunchedEffect(Unit) { cancel.requestFocusAfterFrames() }
    }
}

@Composable
private fun ScoutTasteHead(state: ScoutTasteState) {
    val summary = state.summary
    val genres = summary.genres.mapNotNull { genreName(it.key) }.take(2)
    val decades = summary.topDecades.map { decadeName(it) }
    val headline = buildString {
        if (genres.size >= 2) append(stringResource(R.string.scout_taste_headline_two, genres[0], genres[1]))
        else if (genres.size == 1) append(stringResource(R.string.scout_taste_headline_one, genres[0]))
        if (genres.isNotEmpty()) {
            if (decades.size >= 2) append(stringResource(R.string.scout_taste_headline_decades, decades[0], decades[1]))
            else if (decades.size == 1) append(stringResource(R.string.scout_taste_headline_decade, decades[0]))
        }
    }
    val latelyGenre = summary.latelyGenre?.let { genreName(it.toString()) }
    val latelyCountry = summary.latelyCountry?.let { countryName(it) }
    val lately = when {
        latelyGenre != null && latelyCountry != null -> stringResource(R.string.scout_taste_lately_both, latelyGenre, latelyCountry)
        latelyGenre != null -> stringResource(R.string.scout_taste_lately_genre, latelyGenre)
        latelyCountry != null -> stringResource(R.string.scout_taste_lately_country, latelyCountry)
        else -> null
    }
    Column(
        modifier = Modifier.fillMaxWidth(.8f),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        if (headline.isNotBlank()) {
            Text(headline, style = MaterialTheme.typography.headlineLarge, color = NuvioTheme.colors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (lately != null) {
            Text(lately, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)) {
            ScoutStat(state.watchedCount, stringResource(R.string.scout_taste_stat_watched))
            ScoutStat(state.loved, stringResource(R.string.scout_taste_stat_loved))
            ScoutStat(state.liked, stringResource(R.string.scout_taste_stat_liked))
            ScoutStat(state.notForMe, stringResource(R.string.scout_taste_stat_no))
        }
    }
}

@Composable
private fun ScoutStat(value: Int, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, modifier = Modifier.alignByBaseline())
        Text(label, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.alignByBaseline())
    }
}

@Composable
private fun ScoutGenreRow(name: String, share: Float, strongest: Boolean, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val v2 = LocalV2Appearance.current != null
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(if (v2) Modifier.nuvioV2Focus(focused, shape) else Modifier),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f),
        colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = NuvioTheme.colors.Secondary.copy(alpha = .2f)),
        border = CardDefaults.border(
            focusedBorder = if (v2) Border.None else Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        )
    ) {
        ScoutBarLine(name, share, strongest, Modifier.padding(horizontal = NuvioTheme.spacing.xs, vertical = NuvioTheme.spacing.xxs))
    }
}

@Composable
private fun ScoutBarLine(name: String, share: Float, strongest: Boolean, modifier: Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        Text(name, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(.42f))
        ScoutTrack(share, Modifier.weight(.58f), strongest)
    }
}

/** Six places, newest on the right, so the bars keep their width however many decades there are. */
@Composable
private fun ScoutDecades(decades: List<ScoutTasteBar>) {
    val shown = decades.takeLast(DECADE_SLOTS)
    val peak = shown.maxOfOrNull { it.share } ?: 1f
    val gap = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    Column(Modifier.fillMaxSize().padding(horizontal = NuvioTheme.spacing.xs)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = gap, verticalAlignment = Alignment.Bottom) {
            repeat(DECADE_SLOTS - shown.size) { Spacer(Modifier.weight(1f)) }
            shown.forEach { bar ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight((bar.share / peak.coerceAtLeast(.01f)).coerceIn(.04f, 1f))
                        .alpha(if (bar.share == peak) 1f else SOFTER_BAR)
                        .background(NuvioTheme.colors.Secondary, RoundedCornerShape(topStart = NuvioTheme.radii.xs, topEnd = NuvioTheme.radii.xs))
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(NuvioTheme.spacing.hairline).background(NuvioTheme.colors.TextPrimary.copy(alpha = .22f)))
        Row(Modifier.fillMaxWidth().padding(top = NuvioTheme.spacing.xxs), horizontalArrangement = gap) {
            repeat(DECADE_SLOTS - shown.size) { Spacer(Modifier.weight(1f)) }
            shown.forEach { bar ->
                Text(decadeName(bar.key), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Clip, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ScoutFace(person: ScoutTastePerson, size: Dp, slot: Dp, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val v2 = LocalV2Appearance.current != null
    val facePlaceholder = "#%06X".format(NuvioTheme.colors.BackgroundElevated.toArgb() and 0xFFFFFF)
    Column(Modifier.width(slot), horizontalAlignment = Alignment.CenterHorizontally) {
        Card(
            onClick = onClick,
            modifier = modifier.size(size).onFocusChanged { focused = it.isFocused },
            shape = CardDefaults.shape(CircleShape),
            scale = CardDefaults.scale(focusedScale = 1f),
            colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
            border = CardDefaults.border(focusedBorder = if (v2) Border.None else Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = CircleShape))
        ) {
            NuvioAvatar(AvatarModel(person.id.toString(), person.name, facePlaceholder, person.photo), size, focused = focused, description = null)
        }
        Spacer(Modifier.height(NuvioTheme.spacing.xxs))
        Text(person.name, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ScoutLearn(
    pick: ScoutPick,
    height: Dp,
    onRate: (ScoutRating) -> Unit,
    onSkip: () -> Unit,
    focus: FocusRequester?
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    val gap = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    Row(
        modifier = Modifier.fillMaxWidth().height(height).clip(shape).scoutPanel(shape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = (pick.backdrop ?: pick.poster)?.let { sizedHomeArtworkUrl(it, BACKDROP_WIDTH) },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(height * 16f / 9f).fillMaxHeight()
        )
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = NuvioTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            Text(stringResource(R.string.scout_taste_seen, pick.name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.scout_taste_learn_sub), style = scoutReadingStyle(), color = NuvioTheme.colors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val button = Modifier.width(LEARN_BUTTON)
        val rate: @Composable (ScoutRating) -> Unit = { rating ->
            ScoutRateChip(
                rating = rating,
                selected = false,
                onClick = { onRate(rating) },
                modifier = button,
                focusRequester = focus.takeIf { rating == ScoutRating.LIKE }
            )
        }
        Column(Modifier.padding(end = NuvioTheme.spacing.lg), verticalArrangement = gap) {
            Row(horizontalArrangement = gap) {
                rate(ScoutRating.NOT_FOR_ME)
                rate(ScoutRating.LIKE)
            }
            Row(horizontalArrangement = gap) {
                rate(ScoutRating.LOVE)
                ScoutChip(stringResource(R.string.scout_taste_not_seen), onClick = onSkip, modifier = button)
            }
        }
    }
}

@Composable
private fun ScoutTrack(share: Float, modifier: Modifier, strongest: Boolean = true) {
    val shape = RoundedCornerShape(NuvioTheme.radii.full)
    Box(modifier.height(NuvioTheme.spacing.sm).background(NuvioTheme.colors.TextPrimary.copy(alpha = .12f), shape)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(share.coerceIn(0f, 1f)).alpha(if (strongest) 1f else SOFTER_BAR).background(NuvioTheme.colors.Secondary, shape))
    }
}
