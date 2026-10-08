@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.LocalGuideFile
import com.nuvio.tv.core.iptv.AccountGroups
import com.nuvio.tv.core.iptv.DEFAULT_ACCOUNT_ID
import com.nuvio.tv.core.iptv.HeldCatalogue
import com.nuvio.tv.core.iptv.StalkerPortal
import com.nuvio.tv.data.iptv.IptvGuideFeed
import com.nuvio.tv.data.iptv.IptvSource
import com.nuvio.tv.data.iptv.IptvSourceKind
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.appearance.V2Atmosphere
import com.nuvio.tv.ui.v2.components.NuvioActionPill

@Composable
fun IptvSourcesScreen(onBack: () -> Unit, onLive: () -> Unit = {}, onSetup: () -> Unit = {}, viewModel: IptvSourcesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    var initiallyFocused by remember(state.profileId, state.revision) { mutableStateOf(false) }
    var choosingKind by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf<IptvSource?>(null) }
    var feedMenu by remember { mutableStateOf<IptvGuideFeed?>(null) }
    var confirmSource by remember { mutableStateOf<IptvSource?>(null) }
    var confirmFeed by remember { mutableStateOf<IptvGuideFeed?>(null) }
    var connectionsFor by remember { mutableStateOf<IptvSource?>(null) }
    var userAgentFor by remember { mutableStateOf<IptvSource?>(null) }
    var reviewFor by remember { mutableStateOf<IptvSource?>(null) }
    var groupMenu by remember { mutableStateOf<IptvGroupView?>(null) }
    var naming by remember { mutableStateOf<IptvGroupView?>(null) }
    var creating by remember { mutableStateOf(false) }
    var limitFor by remember { mutableStateOf<IptvGroupView?>(null) }
    var membersFor by remember { mutableStateOf<IptvGroupView?>(null) }
    var transfer by remember { mutableStateOf<IptvTransferMode?>(null) }
    LaunchedEffect(state.ready, state.busy, state.revision) {
        if (state.ready && !state.busy && !initiallyFocused) { withFrameNanos { }; runCatching { first.requestFocus() }; initiallyFocused = true }
    }
    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        if (!LocalIptvAppearance.current.plainBackground) LocalV2Appearance.current?.let { V2Atmosphere(rich = false, background = it.settingsBackground) }
        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.width(340.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.iptv_sources_title), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary)
                Text(stringResource(R.string.iptv_sources_description), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
                Spacer(Modifier.height(18.dp))
                SettingsActionRow(title = stringResource(R.string.iptv_live_title), subtitle = stringResource(R.string.iptv_sources_watch_subtitle),
                    onClick = onLive, enabled = state.sources.isNotEmpty(), leadingIcon = Icons.Filled.LiveTv,
                    modifier = if (state.sources.isNotEmpty()) Modifier.focusRequester(first) else Modifier)
                SettingsActionRow(title = stringResource(R.string.iptv_live_add_source), subtitle = stringResource(R.string.iptv_sources_add_subtitle),
                    onClick = { choosingKind = true }, enabled = state.ready && !state.busy, leadingIcon = Icons.Filled.Add,
                    modifier = if (state.sources.isEmpty()) Modifier.focusRequester(first) else Modifier)
                SettingsActionRow(title = stringResource(R.string.iptv_add_guide), subtitle = stringResource(R.string.iptv_sources_guide_subtitle),
                    onClick = { viewModel.add(true) }, enabled = state.ready && !state.busy, leadingIcon = Icons.Filled.Schedule)
                SettingsActionRow(title = stringResource(R.string.iptv_remote_entry_title), subtitle = stringResource(R.string.iptv_remote_entry_subtitle),
                    onClick = onSetup, enabled = state.ready, leadingIcon = Icons.Filled.PhoneAndroid)
                Spacer(Modifier.weight(1f))
                if (state.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LoadingIndicator(Modifier.size(20.dp))
                    Text(stringResource(R.string.iptv_setup_working), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                if (state.form == null) state.message?.let {
                    Text(stringResource(it), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                if (state.reviews.isNotEmpty()) item(key = "reviews") {
                    SettingsGroupCard(title = stringResource(R.string.iptv_review_title), subtitle = stringResource(R.string.iptv_review_description)) {
                        state.sources.filter { it.ref.sourceId in state.reviews }.forEach { source ->
                            SettingsActionRow(title = source.label, subtitle = reviewText(state.reviews.getValue(source.ref.sourceId)),
                                onClick = { reviewFor = source }, leadingIcon = Icons.Filled.Warning)
                        }
                    }
                }
                item(key = "sources") {
                    SettingsGroupCard(title = stringResource(R.string.iptv_live_sources)) {
                        if (state.ready && state.sources.isEmpty()) Text(stringResource(R.string.iptv_sources_empty), color = NuvioTheme.colors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
                        if (!state.ready) Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(32.dp)) }
                        state.sources.forEach { source ->
                            val status = state.refresh[IptvRefreshCoordinator.key(source.ref)]
                            SettingsActionRow(title = source.label,
                                subtitle = null,
                                subtitleContent = { _, _ -> SourceLine(kindLabel(source.kind), state.counts[source.ref.sourceId], status,
                                    source.refreshedAtMillis, source.playbackEligible) },
                                value = if (state.selected == source.ref && state.sources.size > 1) stringResource(R.string.iptv_sources_guides_shown) else null,
                                onClick = { sourceMenu = source }, leadingIcon = kindIcon(source.kind))
                        }
                    }
                }
                item(key = "guides") {
                    val name = state.sources.firstOrNull { it.ref == state.selected }?.label
                    SettingsGroupCard(title = if (name == null) stringResource(R.string.iptv_guides_title) else stringResource(R.string.iptv_guides_for, name),
                        subtitle = stringResource(R.string.iptv_guides_description)) {
                        if (state.ready && state.feeds.isEmpty()) Text(stringResource(R.string.iptv_guides_empty), color = NuvioTheme.colors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
                        orderedFeeds(state).forEach { feed ->
                            val linked = feed.ref.feedId in state.linked
                            val priority = state.linkedOrder.indexOf(feed.ref.feedId)
                            SettingsActionRow(title = feed.label, subtitle = null,
                                subtitleContent = { _, _ -> SourceLine(stringResource(if (feed.ref.feedId in state.automatic) R.string.iptv_guide_provider else R.string.iptv_guide_xmltv),
                                    null, state.refresh[IptvRefreshCoordinator.key(feed.ref)], feed.refreshedAtMillis, feed.activeGeneration != null) },
                                value = if (linked && priority >= 0 && state.linked.size > 1) stringResource(R.string.iptv_guide_priority, priority + 1) else null,
                                onClick = { feedMenu = feed },
                                leadingIcon = if (linked) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked)
                        }
                        if (state.feeds.isNotEmpty()) Text(stringResource(R.string.iptv_guides_choice_help), color = NuvioTheme.colors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
                    }
                }
                if (state.ready && state.sources.isNotEmpty()) item(key = "groups") {
                    SettingsGroupCard(title = stringResource(R.string.iptv_groups_title), subtitle = stringResource(R.string.iptv_groups_description)) {
                        state.groups.forEach { group ->
                            SettingsActionRow(title = groupLabel(group), subtitle = pluralStringResource(R.plurals.iptv_group_sources, group.sources.size, group.sources.size),
                                value = pluralStringResource(R.plurals.iptv_connections, group.limit, group.limit), onClick = { groupMenu = group },
                                leadingIcon = Icons.Filled.Groups)
                        }
                        state.suggestions.forEach { suggestion ->
                            SettingsActionRow(title = stringResource(R.string.iptv_group_suggested, suggestion.label),
                                subtitle = suggestion.sources.mapNotNull { ref -> state.sources.firstOrNull { it.ref == ref }?.label }.joinToString(", "),
                                onClick = { viewModel.applySuggestion(suggestion) }, leadingIcon = Icons.Filled.Lightbulb)
                        }
                        SettingsActionRow(title = stringResource(R.string.iptv_group_create), subtitle = stringResource(R.string.iptv_group_create_subtitle),
                            onClick = { creating = true }, leadingIcon = Icons.Filled.Add)
                    }
                }
                if (state.ready) item(key = "transfer") {
                    SettingsGroupCard(title = stringResource(R.string.iptv_transfer_title), subtitle = stringResource(R.string.iptv_transfer_description)) {
                        SettingsActionRow(title = stringResource(R.string.iptv_copy_send_title), subtitle = stringResource(R.string.iptv_copy_send_row),
                            onClick = { transfer = IptvTransferMode.SEND }, enabled = state.sources.isNotEmpty(), leadingIcon = Icons.Filled.Tv)
                        SettingsActionRow(title = stringResource(R.string.iptv_copy_receive_title), subtitle = stringResource(R.string.iptv_copy_receive_row),
                            onClick = { transfer = IptvTransferMode.RECEIVE }, leadingIcon = Icons.Filled.Download)
                        SettingsActionRow(title = stringResource(R.string.iptv_backup_title), subtitle = stringResource(R.string.iptv_backup_row),
                            onClick = { transfer = IptvTransferMode.BACKUP }, enabled = state.sources.isNotEmpty(), leadingIcon = Icons.Filled.Backup)
                        SettingsActionRow(title = stringResource(R.string.iptv_restore_title), subtitle = stringResource(R.string.iptv_restore_row),
                            onClick = { transfer = IptvTransferMode.RESTORE }, leadingIcon = Icons.Filled.Restore)
                    }
                }
            }
        }
    }
    if (choosingKind) KindDialog(onDismiss = { choosingKind = false }, onKind = { choosingKind = false; viewModel.add(false, it) })
    sourceMenu?.let { source ->
        val index = state.sources.indexOf(source)
        val running = state.refresh[IptvRefreshCoordinator.key(source.ref)]?.running == true
        OptionsDialog(source.label, kindLabel(source.kind), onDismiss = { sourceMenu = null }) {
            Option(stringResource(R.string.iptv_live_watch), Icons.Filled.LiveTv, enabled = source.playbackEligible) { sourceMenu = null; viewModel.watch(source); onLive() }
            Option(stringResource(R.string.iptv_setup_refresh), Icons.Filled.Refresh, enabled = !running) { sourceMenu = null; viewModel.refresh(source) }
            Option(stringResource(R.string.iptv_choose_guides), Icons.Filled.Schedule, enabled = state.selected != source.ref) { sourceMenu = null; viewModel.select(source.ref) }
            Option(stringResource(R.string.iptv_setup_edit), Icons.Filled.Edit) { sourceMenu = null; viewModel.edit(source) }
            SettingsActionRow(title = stringResource(R.string.iptv_source_connections), subtitle = null,
                value = (state.connections[source.ref.sourceId] ?: 1).toString(), onClick = { sourceMenu = null; connectionsFor = source },
                leadingIcon = Icons.Filled.Dns)
            IptvUserAgentRow(source.ref) { sourceMenu = null; userAgentFor = source }
            if (index > 0) Option(stringResource(R.string.iptv_guide_move_up), Icons.Filled.KeyboardArrowUp) { sourceMenu = null; viewModel.moveUp(source) }
            Option(stringResource(R.string.iptv_source_remove), Icons.Filled.Delete) { sourceMenu = null; confirmSource = source }
        }
    }
    feedMenu?.let { feed ->
        val linked = feed.ref.feedId in state.linked
        val automatic = feed.ref.feedId in state.automatic
        val running = state.refresh[IptvRefreshCoordinator.key(feed.ref)]?.running == true
        OptionsDialog(feed.label, stringResource(if (automatic) R.string.iptv_guide_provider else R.string.iptv_guide_xmltv), onDismiss = { feedMenu = null }) {
            if (state.selected != null) Option(stringResource(if (linked) R.string.iptv_guide_unlink else R.string.iptv_guide_link),
                if (linked) Icons.Filled.RadioButtonUnchecked else Icons.Filled.CheckCircle) { feedMenu = null; viewModel.link(feed) }
            if (linked && state.linkedOrder.indexOf(feed.ref.feedId) > 0) Option(stringResource(R.string.iptv_guide_move_up), Icons.Filled.KeyboardArrowUp) {
                feedMenu = null; viewModel.moveGuideUp(feed)
            }
            Option(stringResource(R.string.iptv_setup_refresh), Icons.Filled.Refresh, enabled = !running) { feedMenu = null; viewModel.refresh(feed) }
            if (!automatic) Option(stringResource(R.string.iptv_setup_edit), Icons.Filled.Edit) { feedMenu = null; viewModel.edit(feed) }
            Option(stringResource(R.string.iptv_guide_remove), Icons.Filled.Delete) { feedMenu = null; confirmFeed = feed }
        }
    }
    userAgentFor?.let { source -> IptvUserAgentDialog(source.ref, onDismiss = { userAgentFor = null }) }
    connectionsFor?.let { source ->
        val id = source.ref.sourceId
        val provider = state.providerConnections[id]
        val automatic = if (source.kind == IptvSourceKind.XTREAM && provider != null && provider > 0)
            com.nuvio.tv.ui.screens.settings.SettingsPickerOption(0, pluralStringResource(R.plurals.iptv_connections_automatic_provider, provider, provider))
        else com.nuvio.tv.ui.screens.settings.SettingsPickerOption(0, stringResource(R.string.iptv_connections_automatic), stringResource(when {
            source.kind != IptvSourceKind.XTREAM -> R.string.iptv_connections_automatic_unreported
            provider == null -> R.string.iptv_connections_automatic_pending
            else -> R.string.iptv_connections_automatic_kept
        }))
        com.nuvio.tv.ui.screens.settings.SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_source_connections),
            subtitle = stringResource(R.string.iptv_source_connections_description),
            options = listOf(automatic) + (1..4).map { com.nuvio.tv.ui.screens.settings.SettingsPickerOption(it, pluralStringResource(R.plurals.iptv_connections, it, it)) },
            selectedValue = if (id in state.manualConnections) state.connections[id] ?: 1 else 0,
            onOptionSelected = { viewModel.setConnections(source, it.takeIf { count -> count > 0 }); connectionsFor = null },
            onDismiss = { connectionsFor = null })
    }
    confirmSource?.let { source ->
        ConfirmDialog(stringResource(R.string.iptv_source_remove_title, source.label), stringResource(R.string.iptv_source_remove_description),
            onConfirm = { confirmSource = null; viewModel.remove(source) }, onDismiss = { confirmSource = null })
    }
    confirmFeed?.let { feed ->
        ConfirmDialog(stringResource(R.string.iptv_guide_remove_title, feed.label), stringResource(R.string.iptv_guide_remove_description),
            onConfirm = { confirmFeed = null; viewModel.remove(feed) }, onDismiss = { confirmFeed = null })
    }
    state.form?.let { form -> key(state.profileId, state.revision, form) { SourceForm(form, state, viewModel) } }
    reviewFor?.let { source ->
        val held = state.reviews[source.ref.sourceId]
        if (held != null) ReviewDialog(source.label, reviewText(held), onAccept = { reviewFor = null; viewModel.acceptReview(source) },
            onKeep = { reviewFor = null; viewModel.keepReview(source) }, onDismiss = { reviewFor = null })
    }
    groupMenu?.let { group ->
        OptionsDialog(groupLabel(group), pluralStringResource(R.plurals.iptv_group_sources, group.sources.size, group.sources.size), onDismiss = { groupMenu = null }) {
            Option(stringResource(R.string.iptv_group_sources_choose), Icons.Filled.CheckCircle) { groupMenu = null; membersFor = group }
            SettingsActionRow(title = stringResource(R.string.iptv_source_connections), subtitle = null,
                value = pluralStringResource(R.plurals.iptv_connections, group.limit, group.limit), onClick = { groupMenu = null; limitFor = group },
                leadingIcon = Icons.Filled.Dns)
            Option(stringResource(R.string.iptv_group_rename), Icons.Filled.Edit) { groupMenu = null; naming = group }
            Option(stringResource(R.string.iptv_group_remove), Icons.Filled.Delete) { groupMenu = null; viewModel.removeGroup(group) }
        }
    }
    limitFor?.let { group ->
        com.nuvio.tv.ui.screens.settings.SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_source_connections),
            subtitle = stringResource(R.string.iptv_group_limit_description),
            options = (1..AccountGroups.MAX_LIMIT).map { com.nuvio.tv.ui.screens.settings.SettingsPickerOption(it, pluralStringResource(R.plurals.iptv_connections, it, it)) },
            selectedValue = group.limit, onOptionSelected = { viewModel.setGroupLimit(group, it); limitFor = null }, onDismiss = { limitFor = null })
    }
    membersFor?.let { group ->
        val current = state.groups.firstOrNull { it.id == group.id } ?: group
        OptionsDialog(groupLabel(current), stringResource(R.string.iptv_group_sources_description), onDismiss = { membersFor = null }) {
            state.sources.forEach { source ->
                val member = source.accountId == current.id
                SettingsActionRow(title = source.label, subtitle = kindLabel(source.kind), onClick = { viewModel.toggleInGroup(current, source) },
                    trailingIcon = null, leadingIcon = if (member) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked)
            }
        }
    }
    if (creating || naming != null) GroupNameDialog(naming?.let { groupLabel(it) } ?: "", onSave = { label ->
        naming?.let { viewModel.renameGroup(it, label) } ?: viewModel.createGroup(label)
        creating = false; naming = null
    }, onDismiss = { creating = false; naming = null })
    transfer?.let { mode -> IptvTransferDialog(mode, onClose = { transfer = null; viewModel.reloadNow() }) }
}

@Composable
private fun groupLabel(group: IptvGroupView): String =
    if (group.id == DEFAULT_ACCOUNT_ID && group.label == group.id) stringResource(R.string.iptv_group_default) else group.label

@Composable
private fun reviewText(held: HeldCatalogue): String =
    if (held.candidate == 0) stringResource(R.string.iptv_review_empty, held.previous) else stringResource(R.string.iptv_review_counts, held.previous, held.candidate)

@Composable
private fun ReviewDialog(label: String, description: String, onAccept: () -> Unit, onKeep: () -> Unit, onDismiss: () -> Unit) {
    val keep = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { keep.requestFocus() } }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.iptv_review_dialog_title, label), subtitle = description, width = 560.dp) {
        Text(stringResource(R.string.iptv_review_dialog_note), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NuvioActionPill(onKeep, Modifier.focusRequester(keep)) { Text(stringResource(R.string.iptv_review_keep)) }
            NuvioActionPill(onAccept) { Text(stringResource(R.string.iptv_review_accept)) }
        }
    }
}

@Composable
private fun GroupNameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf(initial) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(if (initial.isEmpty()) R.string.iptv_group_create else R.string.iptv_group_rename),
        subtitle = stringResource(R.string.iptv_group_name_hint), width = 560.dp) {
        SourceField(stringResource(R.string.iptv_setup_name), label, { label = it.take(240) }, last = true, modifier = Modifier.focusRequester(first))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NuvioActionPill({ onSave(label) }, enabled = label.isNotBlank()) { Text(stringResource(R.string.iptv_setup_save)) }
            NuvioActionPill(onDismiss) { Text(stringResource(R.string.iptv_setup_cancel)) }
        }
    }
}

private fun orderedFeeds(state: IptvSourcesState): List<IptvGuideFeed> {
    val order = state.linkedOrder.withIndex().associate { it.value to it.index }
    return state.feeds.sortedWith(compareBy<IptvGuideFeed>({ order[it.ref.feedId] ?: Int.MAX_VALUE }, { it.label.lowercase() }))
}

@Composable
private fun kindLabel(kind: IptvSourceKind): String = stringResource(when (kind) {
    IptvSourceKind.M3U -> R.string.iptv_kind_m3u
    IptvSourceKind.XTREAM -> R.string.iptv_kind_xtream
    IptvSourceKind.STALKER -> R.string.iptv_kind_stalker
})

private fun kindIcon(kind: IptvSourceKind): ImageVector = when (kind) {
    IptvSourceKind.M3U -> Icons.AutoMirrored.Filled.PlaylistPlay
    IptvSourceKind.XTREAM -> Icons.Filled.Dns
    IptvSourceKind.STALKER -> Icons.Filled.Router
}

@Composable
private fun SourceLine(kind: String, channels: Int?, status: IptvRefreshStatus?, refreshedAt: Long?, loaded: Boolean) {
    val idle = when {
        refreshedAt != null -> stringResource(R.string.iptv_refresh_updated, android.text.format.DateUtils.getRelativeTimeSpanString(
            refreshedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString())
        loaded -> stringResource(R.string.iptv_setup_imported)
        else -> stringResource(R.string.iptv_setup_refresh_needed)
    }
    val outcome = status?.message?.takeIf { it != R.string.iptv_setup_refreshed }?.let { stringResource(it) }
    val text = when (status?.phase) {
        IptvRefreshPhase.QUEUED -> stringResource(R.string.iptv_refresh_queued)
        IptvRefreshPhase.DOWNLOADING -> stringResource(R.string.iptv_refresh_downloading)
        IptvRefreshPhase.SAVING -> stringResource(R.string.iptv_refresh_saving)
        IptvRefreshPhase.GUIDE -> stringResource(R.string.iptv_refresh_guide)
        IptvRefreshPhase.FAILED -> outcome ?: stringResource(R.string.iptv_setup_failed)
        IptvRefreshPhase.DONE -> outcome ?: idle
        null -> idle
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status?.running == true) LoadingIndicator(Modifier.size(14.dp))
        Text(listOfNotNull(kind, channels?.let { pluralStringResource(R.plurals.iptv_source_channels, it, it) }, text).joinToString(" · "),
            color = if (status?.phase == IptvRefreshPhase.FAILED) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun OptionsDialog(title: String, subtitle: String?, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    NuvioDialog(onDismiss = onDismiss, title = title, subtitle = subtitle, width = 520.dp) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).focusRequester(first), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
private fun Option(title: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    SettingsActionRow(title = title, subtitle = null, onClick = onClick, enabled = enabled, leadingIcon = icon, trailingIcon = null)
}

@Composable
private fun ConfirmDialog(title: String, description: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { cancel.requestFocus() } }
    NuvioDialog(onDismiss = onDismiss, title = title, subtitle = description, width = 520.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NuvioActionPill(onDismiss, Modifier.focusRequester(cancel)) { Text(stringResource(R.string.iptv_setup_cancel)) }
            NuvioActionPill(onConfirm) { Text(stringResource(R.string.iptv_remove), color = NuvioTheme.colors.Error) }
        }
    }
}

@Composable
private fun KindDialog(onDismiss: () -> Unit, onKind: (IptvSourceKind) -> Unit) {
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.iptv_live_add_source), subtitle = stringResource(R.string.iptv_kind_question), width = 600.dp) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SettingsActionRow(title = stringResource(R.string.iptv_kind_m3u), subtitle = stringResource(R.string.iptv_kind_m3u_description),
                onClick = { onKind(IptvSourceKind.M3U) }, leadingIcon = kindIcon(IptvSourceKind.M3U), modifier = Modifier.focusRequester(first))
            SettingsActionRow(title = stringResource(R.string.iptv_kind_xtream), subtitle = stringResource(R.string.iptv_kind_xtream_description),
                onClick = { onKind(IptvSourceKind.XTREAM) }, leadingIcon = kindIcon(IptvSourceKind.XTREAM))
            SettingsActionRow(title = stringResource(R.string.iptv_kind_stalker), subtitle = stringResource(R.string.iptv_kind_stalker_description),
                onClick = { onKind(IptvSourceKind.STALKER) }, leadingIcon = kindIcon(IptvSourceKind.STALKER))
        }
    }
}

@Composable
private fun SourceForm(form: IptvSourceForm, state: IptvSourcesState, viewModel: IptvSourcesViewModel) {
    var label by remember { mutableStateOf(form.label) }
    var endpoint by remember { mutableStateOf(form.endpoint) }
    var username by remember { mutableStateOf(form.username) }
    var password by remember { mutableStateOf(form.password) }
    var showPassword by remember { mutableStateOf(false) }
    var choosingFolder by remember { mutableStateOf(false) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && uri.scheme == "content") endpoint = uri.toString()
    }
    val kind = if (form.guide) null else form.kind
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val macValid = kind != IptvSourceKind.STALKER || StalkerPortal.normalizeMac(username) != null
    val complete = label.isNotBlank() && endpoint.isNotBlank() && when (kind) {
        IptvSourceKind.XTREAM -> username.isNotEmpty() && password.isNotEmpty()
        IptvSourceKind.STALKER -> macValid
        else -> true
    }
    val title = when {
        form.guide -> R.string.iptv_guide_form
        kind == IptvSourceKind.XTREAM -> R.string.iptv_xtream_form
        kind == IptvSourceKind.STALKER -> R.string.iptv_kind_stalker
        else -> R.string.iptv_playlist_form
    }
    NuvioDialog(onDismiss = viewModel::dismiss, title = stringResource(title),
        subtitle = stringResource(if (form.source != null || form.feed != null) R.string.iptv_form_edit_subtitle else R.string.iptv_form_add_subtitle), width = 640.dp) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SourceField(stringResource(R.string.iptv_setup_name), label, { label = it.take(240) }, hint = stringResource(R.string.iptv_form_name_hint),
                modifier = Modifier.focusRequester(first))
            SourceField(stringResource(when (kind) {
                IptvSourceKind.XTREAM -> R.string.iptv_form_server
                IptvSourceKind.STALKER -> R.string.iptv_form_portal
                else -> R.string.iptv_setup_url
            }), endpoint, { endpoint = it.take(16384) }, hint = stringResource(when (kind) {
                IptvSourceKind.XTREAM -> R.string.iptv_form_server_hint
                IptvSourceKind.STALKER -> R.string.iptv_form_portal_hint
                null -> R.string.iptv_guide_file_help
                else -> R.string.iptv_form_url_hint
            }), keyboardType = KeyboardType.Uri, last = kind == IptvSourceKind.M3U || kind == null)
            if (form.guide) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NuvioActionPill({
                        try { picker.launch(arrayOf("*/*")) }
                        catch (_: android.content.ActivityNotFoundException) { viewModel.documentUnavailable() }
                    }, enabled = !state.busy) { Text(stringResource(R.string.iptv_guide_choose_file)) }
                    NuvioActionPill({ choosingFolder = true }, enabled = !state.busy) {
                        Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.iptv_guide_choose_folder))
                    }
                    if (endpoint.startsWith("content:") || endpoint.startsWith("file:")) Text(stringResource(R.string.iptv_guide_file_selected), color = NuvioTheme.colors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (kind == IptvSourceKind.XTREAM) {
                SourceField(stringResource(R.string.iptv_xtream_username), username, { username = it.take(4096) }, keyboardType = KeyboardType.Ascii)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SourceField(stringResource(R.string.iptv_xtream_password), password, { password = it.take(4096) },
                        keyboardType = KeyboardType.Password, masked = !showPassword, last = true, modifier = Modifier.weight(1f))
                    NuvioActionPill({ showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (showPassword) R.string.iptv_password_hide else R.string.iptv_password_show))
                    }
                }
            }
            if (kind == IptvSourceKind.STALKER) {
                SourceField(stringResource(R.string.iptv_form_mac), username, { username = it.take(17) }, keyboardType = KeyboardType.Ascii, last = true,
                    hint = stringResource(R.string.iptv_form_mac_hint), error = username.isNotEmpty() && !macValid)
            }
            state.message?.let { Text(stringResource(it), color = NuvioTheme.colors.Error, style = MaterialTheme.typography.bodyMedium) }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NuvioActionPill({ viewModel.save(form, label, endpoint, username, password) }, enabled = !state.busy && complete) {
                    Text(stringResource(if (form.guide) R.string.iptv_setup_save else R.string.iptv_form_save_load))
                }
                NuvioActionPill(viewModel::dismiss, enabled = !state.busy) { Text(stringResource(R.string.iptv_setup_cancel)) }
                if (state.busy) LoadingIndicator(Modifier.size(24.dp))
            }
        }
    }
    if (choosingFolder) GuideFolderDialog(viewModel, onDismiss = { choosingFolder = false }) { file ->
        choosingFolder = false
        endpoint = file.uri
        if (label.isBlank()) label = file.name.substringBefore('.').ifBlank { file.name }.take(240)
    }
}

@Composable
private fun GuideFolderDialog(viewModel: IptvSourcesViewModel, onDismiss: () -> Unit, onChoose: (LocalGuideFile) -> Unit) {
    var listing by remember { mutableStateOf<Pair<List<LocalGuideFile>, List<String>>?>(null) }
    LaunchedEffect(Unit) { listing = viewModel.guideFiles() }
    val context = androidx.compose.ui.platform.LocalContext.current
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.iptv_guide_folder_title),
        subtitle = stringResource(R.string.iptv_guide_folder_description), width = 640.dp) {
        val first = remember { FocusRequester() }
        val current = listing
        when {
            current == null -> Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(32.dp)) }
            current.first.isEmpty() -> {
                LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
                Text(stringResource(R.string.iptv_guide_folder_empty, current.second.joinToString("\n")),
                    color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                NuvioActionPill(onDismiss, Modifier.focusRequester(first)) { Text(stringResource(R.string.iptv_setup_cancel)) }
            }
            else -> {
                LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    current.first.forEachIndexed { index, file ->
                        val modified = android.text.format.DateUtils.getRelativeTimeSpanString(file.modifiedMillis, System.currentTimeMillis(),
                            android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
                        SettingsActionRow(title = file.name,
                            subtitle = listOf(android.text.format.Formatter.formatShortFileSize(context, file.bytes), modified).joinToString(" · "),
                            value = stringResource(if (file.rootIndex == 0) R.string.iptv_guide_folder_device else R.string.iptv_guide_folder_usb),
                            onClick = { onChoose(file) }, leadingIcon = Icons.Filled.Description, trailingIcon = null,
                            modifier = if (index == 0) Modifier.focusRequester(first) else Modifier)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SourceField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text, masked: Boolean = false, last: Boolean = false, error: Boolean = false) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.labelLarge)
        BasicTextField(value, onChange, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = NuvioTheme.colors.TextPrimary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType,
                autoCorrectEnabled = keyboardType == KeyboardType.Text, imeAction = if (last) ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) },
                onDone = { keyboard?.hide(); focus.moveFocus(FocusDirection.Next) }),
            visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = SolidColor(NuvioTheme.colors.TextPrimary),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionDown -> focus.moveFocus(FocusDirection.Next)
                    Key.DirectionUp -> focus.moveFocus(FocusDirection.Previous)
                    else -> false
                }
            }.clip(shape).background(NuvioTheme.colors.TextPrimary.copy(alpha = if (focused) .10f else .06f), shape)
                .border(if (focused || error) 2.dp else 1.dp, when {
                    error -> NuvioTheme.colors.Error
                    focused -> NuvioTheme.colors.FocusRing
                    else -> NuvioTheme.colors.TextPrimary.copy(alpha = .12f)
                }, shape).padding(horizontal = 16.dp, vertical = 12.dp))
        if (hint != null) Text(hint, color = if (error) NuvioTheme.colors.Error else NuvioTheme.colors.TextTertiary, style = MaterialTheme.typography.bodySmall)
    }
}
