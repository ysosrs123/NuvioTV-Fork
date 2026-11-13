@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.DisplayRefreshMeter
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionButton
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionRow
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.keepLtr

/** Shown once per TV, at a pause, when the screen was timed at another rate than the one Arc asked for. */
@Composable
internal fun QmsHintDialog(reading: DisplayRefreshMeter.Reading, onTurnOff: () -> Unit, onKeep: () -> Unit) {
    val keep = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { keep.requestFocus() }
    }
    val asked = DisplayRefreshMeter.format(reading.androidHz).keepLtr()
    val measured = DisplayRefreshMeter.format(reading.measuredHz).keepLtr()
    val qms = reading.seamless == true
    val effect = DisplayRefreshMeter.repeatEverySeconds(reading.measuredHz, reading.androidHz)
        ?.let { stringResource(R.string.qms_hint_effect_repeat, it) }
        ?: stringResource(R.string.qms_hint_effect_judder)
    NuvioDialog(
        onDismiss = onKeep,
        title = stringResource(R.string.qms_hint_title),
        subtitle = stringResource(if (qms) R.string.qms_hint_body_qms_v2 else R.string.qms_hint_body_v2, asked, measured, effect),
        width = 560.dp,
        solidWithoutBlur = true
    ) {
        if (qms) {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
                Text(
                    text = stringResource(R.string.qms_hint_fix),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextPrimary
                )
                Text(
                    text = stringResource(R.string.qms_hint_fix_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.extendedColors.textSecondary
                )
            }
        }
        if (!qms) {
            // After a real mode change turning matching off only trades this for 60 Hz judder: no off button.
            Text(
                text = stringResource(R.string.qms_hint_mode_tip),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.extendedColors.textSecondary
            )
            SettingsDialogActionRow {
                SettingsDialogActionButton(
                    text = stringResource(R.string.qms_hint_ok),
                    onClick = onKeep,
                    primary = true,
                    modifier = Modifier.focusRequester(keep)
                )
            }
        } else {
            Text(
                text = stringResource(R.string.qms_hint_off_hint_qms),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.extendedColors.textSecondary
            )
            SettingsDialogActionRow {
                SettingsDialogActionButton(text = stringResource(R.string.qms_hint_off), onClick = onTurnOff)
                SettingsDialogActionButton(
                    text = stringResource(R.string.qms_hint_keep),
                    onClick = onKeep,
                    primary = true,
                    modifier = Modifier.focusRequester(keep)
                )
            }
        }
    }
}
