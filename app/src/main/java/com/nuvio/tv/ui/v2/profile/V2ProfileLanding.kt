package com.nuvio.tv.ui.v2.profile

import android.view.KeyEvent
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.components.MemberBrandWordmark
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import com.nuvio.tv.ui.v2.components.AvatarModel
import com.nuvio.tv.ui.v2.components.NuvioActionPill
import com.nuvio.tv.ui.v2.components.NuvioAvatar

@Composable
fun V2ProfileLanding(
    title: String,
    hint: String,
    profiles: List<UserProfile>,
    activeProfileId: Int,
    initialFocusId: Int = activeProfileId,
    avatarUrls: Map<String, String>,
    canAdd: Boolean,
    management: Boolean,
    onFocused: (UserProfile?) -> Unit,
    onSelect: (UserProfile) -> Unit,
    onLongPress: (UserProfile) -> Unit,
    onAdd: () -> Unit,
    onManage: () -> Unit,
    interactive: Boolean
) {
    val ids = profiles.map { it.id }
    val requesters = remember(ids) { ids.associateWith { FocusRequester() } }
    val addFocus = remember { FocusRequester() }
    val manageFocus = remember { FocusRequester() }
    var lastId by remember { mutableIntStateOf(initialFocusId) }
    var restoringFocus by remember { mutableStateOf(true) }
    fun freezeReturnTarget(profile: UserProfile? = null) {
        if (profile != null) lastId = profile.id
        // Disabling focus nodes can synchronously visit their still-enabled siblings,
        // before the effect for interactive=false runs. Freeze before opening overlays.
        restoringFocus = true
    }
    val listState = rememberLazyListState()
    val returnFocus = requesters[lastId] ?: requesters[activeProfileId] ?: requesters.values.firstOrNull()
    LaunchedEffect(ids, management, interactive) {
        restoringFocus = true
        if (!interactive) return@LaunchedEffect
        val targetIndex = ids.indexOf(lastId).takeIf { it >= 0 }
            ?: ids.indexOf(activeProfileId).takeIf { it >= 0 } ?: 0
        if (ids.isNotEmpty()) listState.scrollToItem(targetIndex)
        repeat(2) { withFrameNanos { } }
        val target = ids.getOrNull(targetIndex)?.let(requesters::get)
            ?: if (canAdd) addFocus else manageFocus
        target.requestFocus()
        profiles.getOrNull(targetIndex)?.let(onFocused)
        restoringFocus = false
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    val compactHeight = maxHeight < 600.dp
    val gap = if (profiles.size >= 5) 20.dp else 32.dp
    // Include label gutters, focus overscan and row padding in the six-profile fit.
    val count = profiles.size.coerceIn(1, 6)
    val available = (maxWidth - 96.dp - 32.dp - gap * (count - 1)).coerceAtLeast(0.dp)
    val groupTop = minOf(maxHeight * .26f, maxHeight * .52f - 160.dp).coerceAtLeast(80.dp)
    val portraitSize = minOf(144.dp, maxHeight * .205f, available / count - 22.dp).coerceAtLeast(56.dp)
    androidx.compose.foundation.layout.Box(Modifier.align(Alignment.TopCenter).padding(top = 24.dp)) {
        MemberBrandWordmark(height = 40.dp, contentDescription = stringResource(R.string.cd_nuvio_logo))
    }
    Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 48.dp).padding(top = groupTop),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = NuvioTheme.colors.TextPrimary, fontSize = if (compactHeight) 30.sp else 34.sp, fontWeight = FontWeight.Normal)
        Spacer(Modifier.height(if (compactHeight) 18.dp else 36.dp))
        LazyRow(Modifier.fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) {
            items(profiles, key = { it.id }) { profile ->
                var focused by remember { mutableStateOf(false) }
                val tracker = rememberLongPressKeyTracker()
                var consumedLongPress by remember { mutableStateOf(false) }
                Column(Modifier.width(portraitSize + 22.dp).focusRequester(requesters.getValue(profile.id))
                    .focusProperties { canFocus = interactive; down = if (canAdd) addFocus else manageFocus }
                    .onFocusChanged {
                        focused = it.isFocused && interactive
                        // Re-enabling the row can briefly move focus through other avatars.
                        // Keep the user's return target until the explicit restoration finishes.
                        if (focused && !restoringFocus) { lastId = profile.id; onFocused(profile) }
                    }
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.keyCode == KeyEvent.KEYCODE_MENU) {
                            if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                                freezeReturnTarget(profile)
                                onLongPress(profile)
                            }
                            true
                        } else if (tracker.handle(native, { it == KeyEvent.KEYCODE_DPAD_CENTER || it == KeyEvent.KEYCODE_ENTER || it == KeyEvent.KEYCODE_NUMPAD_ENTER }) {
                            consumedLongPress = true
                            freezeReturnTarget(profile)
                            onLongPress(profile)
                        }) {
                            if (native.action == KeyEvent.ACTION_UP) consumedLongPress = false
                            true
                        }
                        else if (consumedLongPress && native.action == KeyEvent.ACTION_UP) {
                            consumedLongPress = false; true
                        } else false
                    }
                    .clickable(remember { MutableInteractionSource() }, indication = null) {
                        freezeReturnTarget(profile)
                        onSelect(profile)
                    }
                    .semantics(mergeDescendants = true) { }, horizontalAlignment = Alignment.CenterHorizontally) {
                    NuvioAvatar(AvatarModel(profile.id.toString(), profile.name, profile.avatarColorHex,
                        profile.avatarUrl?.takeIf(String::isNotBlank) ?: profile.avatarId?.let(avatarUrls::get)),
                        size = portraitSize, portraitPlaceholder = true, focused = focused, selected = profile.id == activeProfileId, description = null)
                    Spacer(Modifier.height(if (compactHeight) 12.dp else 18.dp))
                    Text(profile.name, color = if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                        fontSize = 17.sp, fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(if (profile.isPrimary) stringResource(R.string.profile_selection_primary_badge) else "",
                        color = NuvioTheme.colors.TextTertiary, fontSize = 11.sp, modifier = Modifier.height(18.dp))
                }
            }
        }
    }
    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (canAdd) NuvioActionPill({ freezeReturnTarget(); onAdd() }, Modifier.focusRequester(addFocus)
                .focusProperties { returnFocus?.let { up = it } }, enabled = interactive, neutralBackdrop = true) {
                androidx.tv.material3.Icon(androidx.compose.material.icons.Icons.Default.Add, null, Modifier.width(20.dp))
                Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.profile_add))
            }
            NuvioActionPill({ freezeReturnTarget(); onManage() }, Modifier.focusRequester(manageFocus)
                .focusProperties { returnFocus?.let { up = it } }, enabled = interactive, neutralBackdrop = true) {
                androidx.tv.material3.Icon(androidx.compose.material.icons.Icons.Default.Settings, null, Modifier.width(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(if (management) R.string.sync_generate_done else R.string.profile_manage_button))
            }
        }
    Text(hint, color = NuvioTheme.colors.TextTertiary, fontSize = 14.sp,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
}

}
