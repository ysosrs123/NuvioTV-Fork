package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.theme.NuvioTheme

/** Cached artwork and code-drawn chrome illustrate the choices without network work. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun <T> V2VisualOptions(title: String, selectedValue: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    val accent = NuvioTheme.colors.Secondary
    val values = options.map { it.first }
    val requesters = remember(values) { values.associateWith { FocusRequester() } }
    val entry = requesters.getValue(selectedValue)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = NuvioTheme.colors.TextPrimary)
        Row(Modifier.fillMaxWidth().settingsOptionRow(entry), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { (value, label) ->
                var focused by remember { mutableStateOf(false) }
                val chosen = selectedValue == value
                val shape = RoundedCornerShape(12.dp)
                Card(onClick = { onSelect(value) },
                    modifier = Modifier.weight(1f).focusRequester(requesters.getValue(value)).onFocusChanged { focused = it.isFocused }
                        .settingsItemFocus(focused)
                        .semantics { selected = chosen },
                    shape = CardDefaults.shape(shape),
                    scale = CardDefaults.scale(focusedScale = 1f),
                    colors = CardDefaults.colors(containerColor = settingsItemColor(NuvioTheme.colors.BackgroundCard.copy(alpha = .7f)),
                        focusedContainerColor = settingsFocusFillColor()),
                    border = CardDefaults.border(
                        border = Border(BorderStroke(if (chosen) 1.5.dp else 1.dp,
                            if (chosen) accent.copy(alpha = .8f) else Color.White.copy(alpha = .14f)), shape = shape),
                        focusedBorder = Border.None)) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        V2OptionPreview(value, accent, Modifier.width(112.dp).height(63.dp))
                        Row(Modifier.weight(1f), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White,
                                modifier = Modifier.weight(1f))
                            Canvas(Modifier.padding(start = 10.dp).size(16.dp)) {
                                drawCircle(if (chosen) accent else Color.White.copy(alpha = .55f), style = Stroke(1.5.dp.toPx()))
                                if (chosen) drawCircle(Color.White, radius = size.minDimension * .2f)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> V2OptionPreview(value: T, accent: Color, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val backdrop = remember { com.nuvio.tv.ui.screens.home.HeroBackdropState.lastDisplayedUrl }
    val image = remember(context, backdrop) {
        backdrop?.let { coil3.request.ImageRequest.Builder(context).data(it).size(256, 144)
            .diskCachePolicy(coil3.request.CachePolicy.READ_ONLY)
            .networkCachePolicy(coil3.request.CachePolicy.DISABLED).build() }
    }
    Box(modifier.clip(RoundedCornerShape(8.dp))) {
    coil3.compose.AsyncImage(image, contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.matchParentSize())
    Canvas(Modifier.matchParentSize()) {
        val w=size.width; val h=size.height
        val dark=value == VisualStyle.PURE_LIQUID_DARK || value == SettingsPresentation.MINIMAL
        val sidebar=value == NavigationStyle.FLOATING_SIDEBAR
        val settings=value is SettingsPresentation
        val player=value is PlayerChromeStyle
        val focus=value is FocusStyle
        drawRoundRect(Brush.linearGradient(if (dark) listOf(Color(0xFF07111A),Color(0xFF203544),Color(0xFF03080D))
            else listOf(Color(0xDD08121D),Color(0x66594738),Color(0x44BA8652))), cornerRadius=CornerRadius(8.dp.toPx()))
        if (settings) {
            drawRoundRect(Color(0x9908121E), Offset(w*.03f,h*.06f), Size(w*.23f,h*.88f),CornerRadius(5.dp.toPx()))
            repeat(3) { i -> drawRoundRect(if(i==0) accent.copy(alpha=.65f) else Color.White.copy(alpha=.22f),
                Offset(w*.05f,h*(.15f+i*.26f)),Size(w*.17f,h*.12f),CornerRadius(3.dp.toPx())) }
            repeat(2) { i -> drawRoundRect(Color(0xAA152434),Offset(w*(.3f+i*.33f),h*.13f),Size(w*.3f,h*.68f),CornerRadius(4.dp.toPx())) }
        } else if (player) {
            if(value==PlayerChromeStyle.CONTROL_DECK) drawRoundRect(Color(0xAA1C354C),Offset(w*.03f,h*.55f),Size(w*.94f,h*.4f),CornerRadius(6.dp.toPx()))
            drawLine(Color.White.copy(alpha=.4f),Offset(w*.08f,h*.67f),Offset(w*.92f,h*.67f),2.dp.toPx())
            drawLine(accent,Offset(w*.08f,h*.67f),Offset(w*.39f,h*.67f),2.dp.toPx())
            repeat(5) { i -> drawCircle(if(i==0) accent else Color.White.copy(alpha=.7f),4.dp.toPx(),Offset(w*(.12f+i*.16f),h*.83f)) }
        } else {
            if(sidebar) drawRoundRect(Color(0xAA162B3B),Offset(w*.02f,h*.06f),Size(w*.14f,h*.88f),CornerRadius(5.dp.toPx()))
            else drawRoundRect(Color(0xBB08131D),Offset(w*.05f,h*.06f),Size(w*.9f,h*.14f),CornerRadius(3.dp.toPx()))
            drawLine(Color.White.copy(alpha=.8f),Offset(w*.21f,h*.31f),Offset(w*.48f,h*.31f),3.dp.toPx())
            drawLine(Color.White.copy(alpha=.4f),Offset(w*.21f,h*.43f),Offset(w*.56f,h*.43f),1.dp.toPx())
            repeat(6) { i ->
                val x=w*(.21f+i*.125f)
                drawRoundRect(if(i==0) Color(0xFFAA7748) else Color(0xFF26323C),Offset(x,h*.55f),Size(w*.1f,h*.39f),CornerRadius(3.dp.toPx()))
                if(i==0 && focus && value==FocusStyle.CINEMATIC_FOCUS) {
                    listOf(10f, 7f, 4f).forEach { width ->
                        drawRoundRect(accent.copy(alpha=.16f), Offset(x,h*.55f), Size(w*.1f,h*.39f),
                            CornerRadius(3.dp.toPx()), style=Stroke(width.dp.toPx()))
                    }
                }
                if(i==0) drawRoundRect(accent,
                    Offset(x,h*.55f),Size(w*.1f,h*.39f),CornerRadius(3.dp.toPx()),style=Stroke(1.5.dp.toPx()))
            }
        }
    }
    }
}
