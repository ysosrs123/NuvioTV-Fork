package com.nuvio.tv.ui.screens.stream

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun RatingLimitPartyDialog(
    request: StreamScreenViewModel.RatingPinRequest,
    title: String,
    onDismiss: () -> Unit
) {
    RatingLimitNotice(
        heading = stringResource(R.string.rating_limit_party_heading, request.profileName),
        text = if (request.certification != null) {
            stringResource(R.string.rating_limit_party_rated, title, request.certification, request.profileName, request.limit.label)
        } else {
            stringResource(R.string.rating_limit_party_unrated, title, request.profileName, request.limit.label)
        },
        onDismiss = onDismiss
    )
}

@Composable
internal fun RatingLimitNotice(heading: String, text: String, onDismiss: () -> Unit) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }
    NuvioDialog(
        onDismiss = onDismiss,
        title = heading,
        subtitle = text,
        suppressFirstKeyUp = false
    ) {
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().focusRequester(closeFocus),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.action_close))
        }
    }
}
