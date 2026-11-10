@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.support

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.data.local.SupportNuvioDataStore
import com.nuvio.tv.data.repository.MemberAccessRepository
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.MemberTier
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val ThanksNoteDelayMs = 1_500L
private const val ThanksNoteVisibleMs = 7_000L

@HiltViewModel
class SupporterThanksViewModel @Inject constructor(
    authManager: AuthManager,
    memberAccessRepository: MemberAccessRepository,
    private val supportNuvioDataStore: SupportNuvioDataStore
) : ViewModel() {
    val pending: StateFlow<Pair<String, MemberTier>?> = combine(
        authManager.authState,
        memberAccessRepository.access,
        supportNuvioDataStore.thankedUsers
    ) { authState, access, thanked ->
        val account = authState as? AuthState.FullAccount
        val tier = access.tier
        if (!AppFeaturePolicy.supportNuvioEnabled || account == null || tier == null || account.userId in thanked) null
        else account.userId to tier
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _note = MutableStateFlow<MemberTier?>(null)
    val note: StateFlow<MemberTier?> = _note.asStateFlow()
    private var showJob: Job? = null

    fun show(userId: String, tier: MemberTier) {
        if (showJob?.isActive == true) return
        showJob = viewModelScope.launch {
            supportNuvioDataStore.markThanked(userId)
            _note.value = tier
            delay(ThanksNoteVisibleMs)
            _note.value = null
        }
    }
}

@Composable
fun SupporterThanksNote(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    viewModel: SupporterThanksViewModel = hiltViewModel()
) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(pending, enabled, resumed) {
        val (userId, pendingTier) = pending ?: return@LaunchedEffect
        if (!enabled || !resumed) return@LaunchedEffect
        delay(ThanksNoteDelayMs)
        viewModel.show(userId, pendingTier)
    }
    val tier by viewModel.note.collectAsStateWithLifecycle()
    var lastTier by remember { mutableStateOf<MemberTier?>(null) }
    if (tier != null) lastTier = tier
    val shownTier = lastTier ?: return
    val v2 = LocalV2Appearance.current != null
    val shape = RoundedCornerShape(if (v2) 20.dp else 10.dp)

    AnimatedVisibility(
        visible = tier != null,
        modifier = modifier.padding(top = NuvioTheme.spacing.xl, end = NuvioTheme.spacing.xl),
        enter = fadeIn() + slideInVertically { -it / 3 },
        exit = fadeOut()
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 380.dp)
                .clip(shape)
                .then(
                    if (v2) Modifier.nuvioGlass(GlassRole.MODAL, shape = shape)
                    else Modifier.background(NuvioTheme.colors.BackgroundElevated, shape)
                )
                .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(
                        R.string.support_nuvio_thanks_note_title,
                        stringResource(
                            when (shownTier) {
                                MemberTier.SUPPORTER -> R.string.supporter_membership_tier_supporter
                                MemberTier.SUPPORTER_PLUS -> R.string.supporter_membership_tier_supporter_plus
                            }
                        )
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioTheme.colors.TextPrimary
                )
                Text(
                    text = stringResource(R.string.support_nuvio_thanks_note_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }
    }
}
