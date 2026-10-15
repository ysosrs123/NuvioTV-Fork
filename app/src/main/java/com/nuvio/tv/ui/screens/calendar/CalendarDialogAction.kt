package com.nuvio.tv.ui.screens.calendar

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme

/** A full width action in a Calendar dialog, styled like the Continue Watching options. */
@Composable
internal fun CalendarDialogAction(
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary
        )
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
