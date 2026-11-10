@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.scout.ask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.scout.ask.AskKeyVault
import com.nuvio.tv.core.scout.ask.ScoutAskFeature
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.nuvioV2Focus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

private const val MIN_WORDS = 3

/** A sentence, or a search with nothing to show. Short searches look like titles. */
internal fun askLineWanted(query: String, noResults: Boolean): Boolean {
    val trimmed = query.trim()
    return trimmed.isNotEmpty() && (noResults || askLineSentence(trimmed))
}

internal fun askLineSentence(query: String): Boolean =
    query.trim().split(Regex("\\s+")).count { it.isNotEmpty() } >= MIN_WORDS

/** True once this profile has an AI key. Stage 2's Ask button uses the same answer. */
@HiltViewModel
class AskAccessViewModel @Inject constructor(vault: dagger.Lazy<AskKeyVault>) : ViewModel() {
    val hasKey: StateFlow<Boolean> =
        (if (ScoutAskFeature.available) vault.get().keys.map { it.activeConfig != null }.flowOn(Dispatchers.IO) else flowOf(false))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}

@Composable
fun AskSearchLine(query: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val v2 = LocalV2Appearance.current != null
    val shape = RoundedCornerShape(if (v2) NuvioTheme.radii.md else 999.dp)
    val secondary = NuvioTheme.colors.Secondary
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.xxxl)
            .onFocusChanged { focused = it.isFocused }
            .then(if (v2) Modifier.nuvioV2Focus(focused, shape) else Modifier),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = if (v2) secondary.copy(alpha = .24f) else secondary,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContentColor = if (v2) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.OnSecondary
        ),
        border = CardDefaults.border(
            focusedBorder = if (v2) Border.None else Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.scout_question_search_line), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(query, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
