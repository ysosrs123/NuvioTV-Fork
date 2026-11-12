package com.nuvio.tv.ui.screens.scout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.ScoutMix
import com.nuvio.tv.domain.model.ScoutMixPick
import com.nuvio.tv.domain.model.ScoutMixShow
import com.nuvio.tv.ui.components.NuvioCard
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import com.nuvio.tv.ui.components.PanelActionRow
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.home.MODERN_LANDSCAPE_LOGO_GRADIENT
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.formatHeroRuntime
import com.nuvio.tv.ui.util.localizedGenreLabel
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.nuvioV2Focus

private const val FIND = "find"
private const val TAB_COMING = "tab_coming"
private const val TAB_SHOW = "tab_show_"
private val COMPACT_PAGE = 680.dp
private val TILE_LOWEST = 150.dp
private val TILE_TALLEST = 216.dp
private val LOGO_TALL = 80.dp
private val LOGO_SMALL = 56.dp
private const val ADD_TILE_KEY = "add"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScoutMixScreen(
    onBack: () -> Unit,
    onPlay: (ScoutMixPick.Episode, Boolean) -> Unit,
    onOpenShow: (ScoutMixShow) -> Unit,
    onFindShow: () -> Unit,
    viewModel: ScoutMixViewModel = hiltViewModel()
) {
    val mix by viewModel.mix.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gone by viewModel.gone.collectAsStateWithLifecycle()
    val pickSources by viewModel.pickSources.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val primaryFocus = remember { FocusRequester() }
    val openers = remember { mutableMapOf<String, FocusRequester>() }
    var returnTo by rememberSaveable { mutableStateOf<String?>(null) }

    LifecycleResumeEffect(Unit) {
        viewModel.onShown()
        onPauseOrDispose { viewModel.onHidden() }
    }
    LaunchedEffect(Unit) { viewModel.start.collect { onPlay(it, viewModel.pickSources.value) } }
    LaunchedEffect(gone) { if (gone) onBack() }
    BackHandler { onBack() }

    val pick = state.pick
    val episode = pick as? ScoutMixPick.Episode
    val ranOut = pick is ScoutMixPick.AllSeen
    val counting = state.countdown != null
    var remixPressed by remember { mutableStateOf(false) }
    LaunchedEffect(pick?.let { it::class }, counting) {
        if (remixPressed) {
            remixPressed = false
            return@LaunchedEffect
        }
        withFrameNanos { }
        val opener = returnTo?.let(openers::get)
        returnTo = null
        if (opener == null || runCatching { opener.requestFocus() }.isFailure) runCatching { primaryFocus.requestFocus() }
    }

    val current = mix
    val offers = if (ranOut) state.offers else emptyList()
    val art = episode?.let { it.meta.backdropUrl ?: it.show.backdrop }
        ?: current?.shows?.firstNotNullOfOrNull { it.backdrop ?: it.poster }

    fun open(key: String, go: () -> Unit) {
        viewModel.stop()
        returnTo = key
        go()
    }
    fun opener(key: String) = openers.getOrPut(key) { FocusRequester() }
    fun focus(key: String): Modifier = Modifier.focusRequester(opener(key))

    BoxWithConstraints(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        val compact = maxHeight < COMPACT_PAGE
        val tileHeight = (maxHeight * .3f).coerceIn(TILE_LOWEST, TILE_TALLEST)
        ScoutBackdrop(art)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = NuvioTheme.spacing.screen.overscanHorizontal - ScoutFocusRoom,
                    end = NuvioTheme.spacing.screen.overscanHorizontal - ScoutFocusRoom,
                    top = NuvioTheme.spacing.md
                )
        ) {
            Row(Modifier.fillMaxWidth().padding(start = ScoutFocusRoom), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shuffle, null, tint = NuvioTheme.colors.Secondary, modifier = Modifier.size(NuvioTheme.spacing.lg))
                Spacer(Modifier.width(NuvioTheme.spacing.xs))
                Text(current?.name.orEmpty(), style = MaterialTheme.typography.titleSmall, color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (current != null && !ranOut) {
                    ScoutWord(stringResource(R.string.scout_mixes_unwatched_only), { viewModel.setUnwatchedOnly(!current.unwatchedOnly) }, focus("unwatched"), on = current.unwatchedOnly)
                    ScoutWord(stringResource(R.string.scout_mixes_keep_playing), { viewModel.setKeepPlaying(!current.keepPlaying) }, focus("keep"), on = current.keepPlaying)
                    ScoutWord(stringResource(R.string.scout_mixes_pick_sources), { viewModel.setPickSources(!pickSources) }, focus("pick"), on = pickSources)
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = ScoutFocusRoom),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                if (episode != null) ScoutHeroLogo(current?.shows?.firstOrNull { it.sameShow(episode.show) }?.logo ?: episode.meta.logo, episode.meta.name, if (compact) LOGO_SMALL else LOGO_TALL)
                else MixMessageFor(state.loading, pick)
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm), modifier = Modifier.focusGroup()) {
                    if (episode != null || (state.loading && !ranOut)) {
                        MixButton(
                            label = stringResource(if (counting) R.string.scout_mixes_play_now else R.string.scout_mixes_play),
                            icon = Icons.Default.PlayArrow,
                            enabled = episode != null,
                            focusRequester = primaryFocus,
                            onClick = { viewModel.play()?.let { onPlay(it, pickSources) } }
                        )
                    }
                    if (ranOut) {
                        MixButton(
                            label = stringResource(R.string.scout_mixes_watch_again),
                            icon = Icons.Default.Replay,
                            focusRequester = if (offers.isEmpty()) primaryFocus else null,
                            onClick = { viewModel.setUnwatchedOnly(false) }
                        )
                        MixButton(
                            label = stringResource(R.string.scout_mixes_find_show),
                            icon = Icons.Default.Search,
                            focusRequester = opener(FIND),
                            onClick = { open(FIND, onFindShow) }
                        )
                    } else if (pick !is ScoutMixPick.NoShows) {
                        MixButton(
                            label = stringResource(R.string.scout_mixes_remix),
                            icon = Icons.Default.Shuffle,
                            enabled = !state.loading,
                            focusRequester = if (pick is ScoutMixPick.Unavailable) primaryFocus else null,
                            onClick = {
                                remixPressed = counting
                                viewModel.remix()
                            }
                        )
                    }
                    if (counting) {
                        MixButton(label = stringResource(R.string.scout_mixes_stop), icon = Icons.Default.Stop, onClick = viewModel::stop)
                    } else if (current != null) {
                        MixButton(
                            label = stringResource(R.string.scout_mixes_edit),
                            icon = Icons.Default.Edit,
                            focusRequester = if (pick is ScoutMixPick.NoShows) primaryFocus else null,
                            onClick = {
                                viewModel.stop()
                                editing = true
                            }
                        )
                    }
                }
                if (episode != null) MixEpisodeText(episode, state.loading, state.countdown, compact)
            }
            if (ranOut) {
                if (offers.isNotEmpty()) {
                    Text(stringResource(R.string.scout_mixes_fits), style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.TextPrimary, maxLines = 1, modifier = Modifier.padding(horizontal = ScoutFocusRoom))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = ScoutFocusRoom, vertical = NuvioTheme.spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                    ) {
                        items(offers, key = { it.id }) { offer ->
                            OfferTile(
                                offer = offer,
                                width = tileHeight * 16f / 9f,
                                corner = NuvioTheme.radii.md,
                                focusRequester = if (offer.id == offers.first().id) primaryFocus else null,
                                onClick = { viewModel.add(offer) }
                            )
                        }
                    }
                }
            } else if (current != null && current.shows.isNotEmpty()) {
                val comingWord = stringResource(R.string.scout_mixes_coming_up)
                val addWord = stringResource(R.string.scout_mixes_find_show)
                ScoutTabs(
                    selected = opener(state.tabShow?.let { TAB_SHOW + it } ?: TAB_COMING),
                    items = buildList {
                        add(ScoutTabItem(TAB_COMING, comingWord, state.tabShow == null))
                        current.shows.forEach { add(ScoutTabItem(TAB_SHOW + it.id, it.name, state.tabShow == it.id)) }
                        add(ScoutTabItem(ADD_TILE_KEY, addWord, false))
                    },
                    focus = ::focus,
                    onFocused = { item ->
                        when {
                            item.key == TAB_COMING -> viewModel.focusTab(null)
                            item.key.startsWith(TAB_SHOW) -> viewModel.focusTab(item.key.removePrefix(TAB_SHOW))
                        }
                    },
                    onClick = { item ->
                        when {
                            item.key == ADD_TILE_KEY -> open(ADD_TILE_KEY, onFindShow)
                            item.key.startsWith(TAB_SHOW) -> current.shows.firstOrNull { it.id == item.key.removePrefix(TAB_SHOW) }?.let { show -> open(item.key) { onOpenShow(show) } }
                        }
                    }
                )
                val tiles = remember(state.tabShow, episode, state.coming, state.showEpisodes) {
                    if (state.tabShow == null) mixQueueTiles(episode, state.coming) else state.showEpisodes.distinctBy { it.key }
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth().height(tileHeight + NuvioTheme.spacing.md),
                    contentPadding = PaddingValues(horizontal = ScoutFocusRoom, vertical = NuvioTheme.spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                ) {
                    itemsIndexed(tiles, key = { _, it -> it.meta.id + it.video.id }) { index, item ->
                        ScoutWideTile(
                            picture = item.video.thumbnail ?: item.meta.backdropUrl ?: item.show.backdrop,
                            title = item.video.title.ifBlank { item.meta.name },
                            line = item.meta.name.ifBlank { item.show.name } + " • " + stringResource(R.string.scout_mixes_episode_short, item.video.season ?: 0, item.video.episode ?: 0),
                            height = tileHeight,
                            onClick = { viewModel.playFrom(item)?.let { onPlay(it, pickSources) } },
                            badge = if (index == 0 && state.tabShow == null) stringResource(R.string.scout_mixes_up_next) else null,
                            modifier = focus("tile_" + item.meta.id + item.video.id)
                        )
                    }
                }
                Spacer(Modifier.height(NuvioTheme.spacing.sm))
            }
        }
    }

    if (editing && current != null) {
        MixEditDialog(
            mix = current,
            coveredByAnother = renaming || deleting,
            onDismiss = { editing = false },
            onRename = { renaming = true },
            onRemove = viewModel::remove,
            onDelete = { deleting = true }
        )
    }
    if (renaming && current != null) {
        ScoutMixNameDialog(
            initial = current.name,
            onSave = {
                viewModel.rename(it)
                renaming = false
            },
            onDismiss = { renaming = false }
        )
    }
    if (deleting && current != null) {
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { cancelFocus.requestFocusAfterFrames() }
        NuvioDialog(
            onDismiss = { deleting = false },
            title = stringResource(R.string.scout_mixes_delete_title, current.name),
            subtitle = stringResource(R.string.scout_mixes_delete_detail)
        ) {
            PanelActionRow(
                label = stringResource(R.string.scout_mixes_cancel),
                onClick = { deleting = false },
                focusRequester = cancelFocus
            )
            PanelActionRow(label = stringResource(R.string.scout_mixes_delete_confirm), onClick = viewModel::delete)
        }
    }
}

@Composable
private fun MixMessageFor(loading: Boolean, pick: ScoutMixPick?) {
    when {
        loading -> MixMessage(stringResource(R.string.scout_mixes_choosing), null)
        pick is ScoutMixPick.AllSeen -> MixMessage(stringResource(R.string.scout_mixes_all_seen), stringResource(R.string.scout_mixes_all_seen_hint))
        pick is ScoutMixPick.NoShows -> MixMessage(stringResource(R.string.scout_mixes_no_shows), stringResource(R.string.scout_mixes_add_hint))
        else -> MixMessage(stringResource(R.string.scout_mixes_unavailable), stringResource(R.string.scout_mixes_unavailable_hint))
    }
}

@Composable
private fun MixEpisodeText(episode: ScoutMixPick.Episode, loading: Boolean, countdown: Int?, compact: Boolean) {
    val context = LocalContext.current
    val status = when {
        loading -> stringResource(R.string.scout_mixes_choosing)
        countdown != null -> pluralStringResource(R.plurals.scout_mixes_starts_in, countdown, countdown)
        else -> stringResource(R.string.scout_mixes_up_next)
    }
    Text(
        text = status + " • " + episodeLine(episode),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = NuvioTheme.colors.TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(.62f)
    )
    Text(
        text = episode.video.overview?.takeIf(String::isNotBlank) ?: episode.meta.description.orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextPrimary,
        maxLines = if (compact) 1 else 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(.5f)
    )
    val runtime = formatHeroRuntime(episode.video.runtime?.let { "$it min" } ?: episode.meta.runtime)
    val year = episode.video.released?.take(4)?.takeIf(String::isNotBlank) ?: episode.meta.releaseInfo
    val details = listOfNotNull(episode.meta.genres.firstOrNull()?.let { localizedGenreLabel(context, it) }, runtime, year).joinToString(" • ")
    if (details.isNotBlank()) {
        Text(details, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MixMessage(title: String, detail: String?) {
    Column(
        modifier = Modifier.fillMaxWidth(.6f),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun OfferTile(offer: MetaPreview, width: Dp, corner: Dp, focusRequester: FocusRequester?, onClick: () -> Unit) {
    val context = LocalContext.current
    val caption = listOfNotNull(
        offer.genres.firstOrNull()?.let { localizedGenreLabel(context, it) },
        offer.releaseInfo?.takeIf(String::isNotBlank)
    ).joinToString(" • ")
    MixTile(
        picture = offer.background ?: offer.poster,
        logo = offer.logo,
        name = offer.name,
        width = width,
        corner = corner,
        focusRequester = focusRequester,
        onClick = onClick,
        logoAlignment = Alignment.Center,
        badge = { focused ->
            MixBadge(
                text = stringResource(R.string.scout_mixes_add_show),
                icon = Icons.Default.Add,
                active = focused,
                modifier = Modifier.align(Alignment.TopEnd)
            )
            if (caption.isNotBlank()) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextPrimary.copy(alpha = .9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(NuvioTheme.spacing.sm)
                )
            }
        }
    )
}

@Composable
private fun MixTile(
    picture: String?,
    logo: String?,
    name: String,
    width: Dp,
    corner: Dp,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    logoAlignment: Alignment = Alignment.BottomCenter,
    badge: @Composable BoxScope.(focused: Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    var logoFailed by remember(logo) { mutableStateOf(false) }
    MixCard(width = width, corner = corner, focusRequester = focusRequester, description = name, onClick = onClick, onFocus = { focused = it }) {
        AsyncImage(
            model = picture,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(Modifier.fillMaxSize().background(MODERN_LANDSCAPE_LOGO_GRADIENT))
        if (!logo.isNullOrBlank() && !logoFailed) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { logoFailed = true },
                modifier = Modifier
                    .align(logoAlignment)
                    .fillMaxWidth(.68f)
                    .fillMaxHeight(.38f)
                    .padding(bottom = NuvioTheme.spacing.xs)
            )
        } else {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = NuvioTheme.colors.TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(logoAlignment).padding(NuvioTheme.spacing.md)
            )
        }
        badge(focused)
    }
}

/** A wide card with Arc's own focus: the ring in Original, the V2 edge in Nuvio V2. */
@Composable
private fun MixCard(
    width: Dp,
    corner: Dp,
    focusRequester: FocusRequester?,
    description: String,
    onClick: () -> Unit,
    onFocus: (Boolean) -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    val v2 = LocalV2Appearance.current != null
    val shape = remember(corner) { RoundedCornerShape(corner) }
    var focused by remember { mutableStateOf(false) }
    val ring = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs)
    NuvioCard(
        onClick = onClick,
        shape = shape,
        light = v2,
        containerColor = NuvioTheme.colors.BackgroundCard,
        focusedBorder = if (v2) Border.None else Border(border = ring, shape = shape),
        modifier = Modifier
            .width(width)
            .aspectRatio(16f / 9f)
            .semantics { contentDescription = description }
            .nuvioV2Focus(focused, shape, stationary = true)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                onFocus(it.isFocused)
            }
    ) {
        Box(modifier = Modifier.fillMaxSize().clip(shape), content = content)
    }
}

@Composable
private fun MixBadge(text: String, active: Boolean, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val v2 = LocalV2Appearance.current != null
    val shape = RoundedCornerShape(if (v2) NuvioTheme.radii.sm else NuvioTheme.radii.full)
    val tint = if (active) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary
    Row(
        modifier = modifier
            .padding(NuvioTheme.spacing.sm)
            .clip(shape)
            .background(if (active) NuvioTheme.colors.Secondary else NuvioTheme.colors.Background.copy(alpha = .7f))
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
    ) {
        icon?.let { Icon(imageVector = it, contentDescription = null, tint = tint, modifier = Modifier.size(NuvioTheme.spacing.md)) }
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun episodeLine(episode: ScoutMixPick.Episode): String {
    val season = episode.video.season ?: 0
    val number = episode.video.episode ?: 0
    val title = episode.video.title
    return if (title.isBlank()) stringResource(R.string.scout_mixes_episode_short, season, number)
    else stringResource(R.string.scout_mixes_episode, season, number, title)
}

@Composable
private fun MixButton(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null
) {
    ScoutChip(
        label = label,
        onClick = onClick,
        modifier = Modifier.heightIn(min = NuvioTheme.spacing.xxxl).then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        enabled = enabled,
        icon = if (icon != null) ({ Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null
    )
}

@Composable
private fun MixEditDialog(
    mix: ScoutMix,
    coveredByAnother: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onRemove: (ScoutMixShow) -> Unit,
    onDelete: () -> Unit
) {
    val focus = remember { FocusRequester() }
    val rows = remember { HashMap<String, FocusRequester>() }
    var focusAfterRemoval by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { focus.requestFocusAfterFrames() }
    LaunchedEffect(mix.shows.size) {
        val target = focusAfterRemoval ?: return@LaunchedEffect
        focusAfterRemoval = null
        (rows[target] ?: focus).requestFocusAfterFrames()
    }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.scout_mixes_edit_title, mix.name)) {
        HideDialogWindowWhile(coveredByAnother)
        LazyColumn(modifier = Modifier.heightIn(max = 300.dp), contentPadding = PaddingValues(vertical = NuvioTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            item {
                PanelActionRow(label = stringResource(R.string.scout_mixes_rename), onClick = onRename, focusRequester = focus)
            }
            items(mix.shows, key = { it.id }) { show ->
                PanelActionRow(
                    label = stringResource(R.string.scout_mixes_remove, show.name),
                    focusRequester = rows.getOrPut(show.id) { FocusRequester() },
                    onClick = {
                        val at = mix.shows.indexOf(show)
                        focusAfterRemoval = (mix.shows.getOrNull(at + 1) ?: mix.shows.getOrNull(at - 1))?.id ?: ""
                        onRemove(show)
                    }
                )
            }
            item {
                Text(
                    text = stringResource(R.string.scout_mixes_add_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xs)
                )
            }
            item {
                PanelActionRow(label = stringResource(R.string.scout_mixes_delete), onClick = onDelete)
            }
        }
    }
}
