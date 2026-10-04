package com.nuvio.tv.ui.components

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.util.contentLayoutDirection
import com.nuvio.tv.ui.util.contentTextDirection
import kotlinx.coroutines.launch

@Composable
fun P2pConsentDialog(
    onEnableP2p: () -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val bodyText = stringResource(R.string.p2p_consent_body)
    val layoutDirection = remember(bodyText) { bodyText.contentLayoutDirection() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(NuvioTheme.radii.xxl))
                    .background(Color.Black.copy(alpha = 0.85f))
            ) {
                Column(
                    modifier = Modifier
                        .width(520.dp) // Increased width from 460.dp to 520.dp for better text distribution
                        .padding(NuvioTheme.spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.p2p_consent_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = NuvioTheme.colors.TextPrimary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

                    val scrollState = rememberScrollState()
                    val coroutineScope = rememberCoroutineScope()

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(scrollState)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.DirectionDown -> {
                                            if (scrollState.value < scrollState.maxValue) {
                                                coroutineScope.launch { scrollState.animateScrollBy(100f) }
                                                true
                                            } else false
                                        }
                                        Key.DirectionUp -> {
                                            if (scrollState.value > 0) {
                                                coroutineScope.launch { scrollState.animateScrollBy(-100f) }
                                                true
                                            } else false
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                            .focusable()
                    ) {
                        Text(
                            text = bodyText,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                textDirection = bodyText.contentTextDirection()
                            ),
                            color = NuvioTheme.colors.TextSecondary,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.padding(end = NuvioTheme.spacing.xs)
                        )
                    }

                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        PanelActionRow(
                            label = stringResource(R.string.p2p_consent_cancel),
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            focusRequester = focusRequester
                        )
                        PanelActionRow(
                            label = stringResource(R.string.p2p_consent_enable),
                            onClick = onEnableP2p,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
