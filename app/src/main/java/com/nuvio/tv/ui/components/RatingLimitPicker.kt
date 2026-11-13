package com.nuvio.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.ratinglimit.RatingLimit
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun ratingLimitDescription(limit: RatingLimit?): String = stringResource(
    when (limit) {
        null -> R.string.rating_limit_none
        RatingLimit.PRESCHOOL -> R.string.rating_limit_preschool
        RatingLimit.YOUNG -> R.string.rating_limit_young
        RatingLimit.PG -> R.string.rating_limit_pg
        RatingLimit.TEEN -> R.string.rating_limit_teen
    }
)

@Composable
fun RatingLimitPicker(
    selected: RatingLimit?,
    ownerName: String,
    onSelected: (RatingLimit?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.rating_limit_hint, ownerName),
            modifier = Modifier.fillMaxWidth(),
            color = NuvioTheme.colors.TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
        Column(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
        ) {
            (listOf<RatingLimit?>(null) + RatingLimit.entries).forEach { limit ->
                RatingLimitOption(
                    title = limit?.label ?: ratingLimitDescription(null),
                    subtitle = limit?.let { ratingLimitDescription(it) },
                    isSelected = selected == limit,
                    onClick = { onSelected(limit) }
                )
            }
        }
        Text(
            text = stringResource(R.string.rating_limit_note, ownerName),
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            color = NuvioTheme.colors.TextTertiary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RatingLimitOption(
    title: String,
    subtitle: String?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val background by animateColorAsState(
        targetValue = if (isFocused) NuvioTheme.colors.FocusBackground else Color.White.copy(alpha = 0.06f),
        animationSpec = tween(120),
        label = "ratingLimitOptionBackground"
    )
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(
                border = if (isFocused) NuvioTheme.focusRing.border(2.dp) else BorderStroke(1.dp, NuvioTheme.colors.Border),
                shape = shape
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .semantics {
                role = Role.RadioButton
                selected = isSelected
            }
            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (isFocused || isSelected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                fontSize = 15.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
            )
            if (subtitle != null) {
                Text(text = subtitle, color = NuvioTheme.colors.TextTertiary, fontSize = 12.sp)
            }
        }
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(2.dp, if (isSelected) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextTertiary, CircleShape)
                .padding(4.dp)
        ) {
            if (isSelected) Box(Modifier.size(10.dp).clip(CircleShape).background(NuvioTheme.colors.Secondary))
        }
    }
}
