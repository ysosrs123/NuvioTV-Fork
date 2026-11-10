package com.nuvio.tv.ui.screens.detail

import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.repository.ParentalGuideCategory
import com.nuvio.tv.data.repository.ParentalGuideEntry
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance

internal fun parentalSeverityColor(severity: String): Color = when (severity) {
    "severe" -> Color(0xFFEF5350)
    "moderate" -> Color(0xFFFF9F43)
    else -> Color(0xFFF5D34A)
}

private val slotEntries = ParentalGuideCategory.entries.map { ParentalGuideEntry(it, "moderate") }

/** Holds the row's height until the guide arrives, so the sections below stay put. */
@Composable
fun ParentalGuideSlot(entries: List<ParentalGuideEntry>) {
    androidx.compose.animation.Crossfade(entries.isNotEmpty(), label = "parentalGuide") { ready ->
        if (ready) {
            ParentalGuideSection(entries)
        } else {
            Box(Modifier.alpha(0f)) { ParentalGuideSection(slotEntries) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParentalGuideSection(entries: List<ParentalGuideEntry>) {
    if (entries.isEmpty()) return
    val v2 = LocalV2Appearance.current != null
    val chipShape = RoundedCornerShape(50)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = NuvioTheme.spacing.sm)
    ) {
        Text(
            text = stringResource(R.string.detail_section_parental_guide),
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(start = detailStartInset, end = NuvioTheme.spacing.xxxl)
        )
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = detailStartInset, end = NuvioTheme.spacing.xxxl, top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            entries.forEach { entry ->
                Row(
                    modifier = Modifier
                        .height(36.dp)
                        .then(
                            if (v2) {
                                Modifier
                                    .background(Color.White.copy(alpha = 0.09f), chipShape)
                                    .border(1.dp, Color.White.copy(alpha = 0.16f), chipShape)
                            } else {
                                Modifier.background(NuvioTheme.colors.BackgroundCard, chipShape)
                            }
                        )
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(parentalSeverityColor(entry.severity), CircleShape)
                    )
                    Text(
                        text = stringResource(parentalCategoryLabel(entry.category)),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                    Text(
                        text = stringResource(parentalSeverityLabel(entry.severity)),
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.colors.TextPrimary.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }
}

private fun parentalCategoryLabel(category: ParentalGuideCategory): Int = when (category) {
    ParentalGuideCategory.NUDITY -> R.string.parental_nudity
    ParentalGuideCategory.VIOLENCE -> R.string.parental_violence
    ParentalGuideCategory.PROFANITY -> R.string.parental_profanity
    ParentalGuideCategory.ALCOHOL -> R.string.parental_alcohol
    ParentalGuideCategory.FRIGHTENING -> R.string.parental_frightening
}

private fun parentalSeverityLabel(severity: String): Int = when (severity) {
    "severe" -> R.string.parental_severity_severe
    "moderate" -> R.string.parental_severity_moderate
    else -> R.string.parental_severity_mild
}
