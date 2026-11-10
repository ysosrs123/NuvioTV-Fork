package com.nuvio.tv.ui.screens.scout.ask

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

private val ASK_CHOICES_HEIGHT = 48.dp

@Stable
internal class AskInputSlots {
    var heard by mutableStateOf<String?>(null)
    var typed by mutableStateOf("")
    var typing by mutableStateOf(false)
    val mic = FocusRequester()
    val field = FocusRequester()
    val typingFocus = FocusRequester()
    val firstChoice = FocusRequester()
}

internal class AskInput(val voice: AskVoice, val slots: AskInputSlots) {
    val hasMic: Boolean get() = voice.available
    val home: FocusRequester get() = if (hasMic) slots.mic else slots.field
}

@Composable
internal fun rememberAskInput(): AskInput {
    val slots = remember { AskInputSlots() }
    val voice = rememberAskVoice(keepGoing = true, onHeard = { slots.heard = it })
    return AskInput(voice, slots)
}

/** The mic, question box and what to do with the words. For a page that only needs somewhere to ask. */
@Composable
internal fun AskInputBar(
    onAsk: (String) -> Unit,
    focusIn: FocusRequester,
    down: FocusRequester,
    modifier: Modifier = Modifier
) {
    val input = rememberAskInput()
    AskInputBar(
        input = input,
        onAsk = onAsk,
        next = { spot, move ->
            when {
                move == AskMove.DOWN -> down
                spot == AskSpot.Mic && move == AskMove.END -> input.slots.field
                spot == AskSpot.Field && move == AskMove.START && input.hasMic -> input.slots.mic
                else -> null
            }
        },
        choicesDown = down,
        modifier = modifier,
        focusIn = focusIn
    )
}

@Composable
internal fun AskInputBar(
    input: AskInput,
    onAsk: (String) -> Unit,
    next: (AskSpot, AskMove) -> FocusRequester?,
    choicesDown: FocusRequester,
    modifier: Modifier = Modifier,
    focusIn: FocusRequester? = null,
    header: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
    idleLine: @Composable (String?) -> Unit = { problem ->
        problem?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.Error) }
    }
) {
    val voice = input.voice
    val slots = input.slots
    val hasMic = input.hasMic
    val keyboard = LocalSoftwareKeyboardController.current

    fun Modifier.at(spot: AskSpot): Modifier = focusRequester(if (spot == AskSpot.Mic) slots.mic else slots.field).focusProperties {
        fun target(move: AskMove): FocusRequester = when {
            voice.listening -> FocusRequester.Cancel
            slots.heard != null && move == AskMove.DOWN -> slots.firstChoice
            else -> next(spot, move) ?: FocusRequester.Cancel
        }
        up = target(AskMove.UP)
        down = target(AskMove.DOWN)
        start = target(AskMove.START)
        end = target(AskMove.END)
    }

    val changeWords = {
        slots.typed = slots.heard.orEmpty()
        slots.heard = null
        slots.typing = true
    }

    BackHandler(enabled = slots.typing) {
        slots.typing = false
        keyboard?.hide()
    }
    BackHandler(enabled = voice.listening) { voice.cancel() }
    BackHandler(enabled = slots.heard != null) { slots.heard = null }

    val choosing = slots.heard != null
    LaunchedEffect(voice.listening, choosing, slots.typing) {
        when {
            slots.typing -> runCatching { slots.typingFocus.requestFocus() }
            voice.listening -> slots.mic.requestFocusAfterFrames()
            choosing -> slots.firstChoice.requestFocusAfterFrames()
            else -> runCatching { input.home.requestFocus() }
        }
    }

    val entry = focusIn?.let { Modifier.focusRequester(it) } ?: Modifier
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            Column(Modifier.weight(1f)) {
                header()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                ) {
                    if (hasMic) {
                        MicButton(
                            listening = voice.listening,
                            onClick = if (voice.listening) voice.stop else voice.start,
                            modifier = Modifier.size(56.dp).then(entry).at(AskSpot.Mic),
                            onFocused = voice.warm
                        )
                    }
                    if (slots.typing) {
                        QuestionField(
                            value = slots.typed,
                            onValueChange = { slots.typed = it },
                            onSubmit = {
                                slots.typing = false
                                onAsk(slots.typed)
                                slots.typed = ""
                            },
                            modifier = Modifier.weight(1f).focusRequester(slots.typingFocus),
                            placeholder = stringResource(R.string.scout_question_say_or_type)
                        )
                    } else {
                        val shape = RoundedCornerShape(NuvioTheme.radii.lg)
                        AskCard(
                            onClick = { if (slots.heard != null) { changeWords() } else { slots.typing = true } },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .askGlass(shape)
                                .then(if (hasMic) Modifier else entry)
                                .at(AskSpot.Field),
                            shape = shape
                        ) {
                            Box(Modifier.fillMaxSize().padding(horizontal = NuvioTheme.spacing.lg), contentAlignment = Alignment.CenterStart) {
                                val spoken = slots.heard ?: voice.words.takeIf { voice.listening && it.isNotBlank() }?.let(::askWordsTail)
                                Text(
                                    spoken ?: slots.typed.ifBlank { stringResource(R.string.scout_question_say_or_type) },
                                    style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content),
                                    color = if (spoken == null && slots.typed.isBlank()) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            trailing()
        }
        Spacer(Modifier.height(NuvioTheme.spacing.xs))
        Box(Modifier.fillMaxWidth().heightIn(min = ASK_CHOICES_HEIGHT), contentAlignment = Alignment.CenterStart) {
            val words = slots.heard
            when {
                words != null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                ) {
                    fun Modifier.choice(first: Boolean = false, last: Boolean = false) = focusProperties {
                        up = input.home
                        down = choicesDown
                        if (first) start = FocusRequester.Cancel
                        if (last) end = FocusRequester.Cancel
                    }
                    AskButton(
                        stringResource(R.string.scout_question_search_line),
                        {
                            slots.heard = null
                            onAsk(words)
                        },
                        Modifier.focusRequester(slots.firstChoice).choice(first = true),
                        primary = true
                    )
                    AskButton(
                        stringResource(R.string.scout_question_add_more),
                        {
                            slots.heard = null
                            voice.more(words)
                        },
                        Modifier.choice()
                    )
                    AskButton(
                        stringResource(R.string.scout_question_say_again),
                        {
                            slots.heard = null
                            voice.start()
                        },
                        Modifier.choice()
                    )
                    AskButton(stringResource(R.string.scout_question_change_words), changeWords, Modifier.choice(last = true))
                    Text(
                        stringResource(R.string.scout_question_stopped_pause),
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = NuvioTheme.spacing.xs)
                    )
                }
                voice.listening -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                ) {
                    Text(
                        stringResource(if (voice.ready) R.string.scout_question_speak_now else R.string.scout_question_getting_ready),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (voice.ready) NuvioTheme.colors.FocusRing else NuvioTheme.colors.TextSecondary
                    )
                    Text(
                        stringResource(R.string.scout_question_voice_hint),
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                else -> idleLine(voice.problem)
            }
        }
    }
}
