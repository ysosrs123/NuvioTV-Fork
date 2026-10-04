package com.nuvio.tv.ui.v2.navigation

import androidx.activity.compose.BackHandler
import com.nuvio.tv.ui.v2.components.nuvioV2Focus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.ui.components.BrandWordmark
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.DrawerItem
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.LocalSidebarExpanded
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.components.AvatarModel
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.NuvioActionPill
import com.nuvio.tv.ui.v2.components.NuvioAvatar
import com.nuvio.tv.ui.v2.components.NuvioGlassSurface

val LocalV2TopChrome = staticCompositionLocalOf { false }

/** Alternative chrome around the same navigation controller and destination graph. */
@Composable
@OptIn(ExperimentalTvMaterial3Api::class)
fun V2TopNavigation(
    navigationContent: @Composable (Boolean) -> Unit,
    currentRoute: String?,
    rootRoutes: Set<String>,
    items: List<DrawerItem>,
    selectedRoute: String?,
    profile: AvatarModel?,
    onNavigate: (String) -> Unit,
    onSwitchProfile: () -> Unit,
    onExit: () -> Unit
) {
    val visible = currentRoute in rootRoutes
    val contentFocus = remember { FocusRequester() }
    val routes = items.map { it.route }
    val routeFocus = remember(routes) { routes.associateWith { FocusRequester() } }
    var lastRoute by remember { mutableStateOf(selectedRoute) }
    var navFocused by remember { mutableStateOf(false) }
    var returnToContent by remember { mutableStateOf(false) }
    val entryFocus = routeFocus[lastRoute] ?: routeFocus[selectedRoute] ?: routeFocus.values.first()

    // Destination BackHandlers are composed later and retain priority for sheets/settings.
    BackHandler(enabled = visible) {
        if (navFocused) onExit() else entryFocus.requestFocus()
    }
    LaunchedEffect(returnToContent, currentRoute) {
        if (returnToContent) {
            repeat(2) { withFrameNanos { } }
            contentFocus.requestFocus()
            returnToContent = false
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(top = if (visible) 54.dp else 0.dp)
            .focusRequester(contentFocus).focusRestorer()
            .focusProperties {
                this.onExit = {
                    if (visible && requestedFocusDirection == FocusDirection.Up) entryFocus.requestFocus()
                }
            }.focusGroup()) {
            CompositionLocalProvider(LocalContentFocusRequester provides contentFocus,
                LocalSidebarExpanded provides navFocused,
                LocalV2TopChrome provides visible) {
                navigationContent(true)
            }
        }
        if (visible) {
            NuvioGlassSurface(GlassRole.NAVIGATION,
                Modifier.align(Alignment.TopCenter).padding(horizontal = 36.dp, vertical = 10.dp).fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)
                    .onFocusChanged { navFocused = it.hasFocus }.focusGroup(),
                    verticalAlignment = Alignment.CenterVertically) {
                    com.nuvio.tv.ui.v2.components.V2Wordmark(modifier = Modifier.width(96.dp).height(30.dp))
                    LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        items(items, key = { it.route }) { item ->
                            val isSelected = item.route == selectedRoute
                            var itemFocused by remember { mutableStateOf(false) }
                            Button(onClick = {
                                onNavigate(item.route)
                                returnToContent = true
                            }, modifier = Modifier.focusRequester(routeFocus.getValue(item.route))
                                .focusProperties { down = contentFocus }
                                .onFocusChanged { itemFocused = it.isFocused; if (it.isFocused) lastRoute = item.route }
                                .nuvioV2Focus(itemFocused, RoundedCornerShape(7.dp))
                                .semantics { selected = isSelected },
                                shape = ButtonDefaults.shape(RoundedCornerShape(7.dp)),
                                scale = ButtonDefaults.scale(focusedScale = 1f),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                colors = ButtonDefaults.colors(
                                    containerColor = if (isSelected) NuvioTheme.colors.Secondary.copy(alpha = .20f) else Color.Transparent,
                                    focusedContainerColor = NuvioTheme.colors.Secondary.copy(alpha = .38f),
                                    contentColor = if (isSelected) Color.White else NuvioTheme.colors.TextSecondary,
                                    focusedContentColor = Color.White)) {
                                when {
                                    item.icon != null -> androidx.tv.material3.Icon(item.icon, null, Modifier.size(14.dp))
                                    item.iconRes != null -> androidx.tv.material3.Icon(com.nuvio.tv.ui.screens.settings.rememberRawSvgPainter(item.iconRes, 14.dp), null, Modifier.size(14.dp))
                                }
                                Spacer(Modifier.width(7.dp))
                                Text(item.label, style = androidx.tv.material3.MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    if (profile != null) {
                        Spacer(Modifier.width(12.dp))
                        var profileFocused by remember { mutableStateOf(false) }
                        Button(onSwitchProfile, Modifier.focusProperties { down = contentFocus }
                            .onFocusChanged { profileFocused = it.isFocused }
                            .nuvioV2Focus(profileFocused, RoundedCornerShape(7.dp)),
                            shape = ButtonDefaults.shape(RoundedCornerShape(7.dp)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            scale = ButtonDefaults.scale(focusedScale = 1f),
                            colors = ButtonDefaults.colors(containerColor = Color.Transparent,
                                focusedContainerColor = NuvioTheme.colors.Secondary.copy(alpha = .3f),
                                contentColor = Color.White, focusedContentColor = Color.White)) {
                            NuvioAvatar(profile, 26.dp, description = null)
                            Spacer(Modifier.width(8.dp))
                            Text(profile.name, style = androidx.tv.material3.MaterialTheme.typography.labelLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(76.dp))
                        }
                    }
                }
            }
        }
    }
}
