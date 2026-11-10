package com.nuvio.tv.ui.screens.scout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.domain.model.V2AppearancePreferences
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalLayoutApi::class, ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class ScoutChipFocusPathTest {
    @get:Rule val rule = createComposeRule()

    private fun rows() {
        rule.setContent {
            NuvioTheme {
                CompositionLocalProvider(LocalV2Appearance provides V2AppearancePreferences()) {
                    Column(Modifier.padding(ScoutFocusRoom), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ScoutChip("a1", {}, Modifier.testTag("a1"))
                            ScoutChip("a2", {}, Modifier.testTag("a2"))
                        }
                        FlowRow(Modifier.width(200.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ScoutChip("b1 long enough", {}, Modifier.testTag("b1"))
                            ScoutChip("b2 long enough", {}, Modifier.testTag("b2"))
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ScoutChip("c1", {}, Modifier.testTag("c1"), primary = true)
                            ScoutChip("c2", {}, Modifier.testTag("c2"))
                        }
                    }
                }
            }
        }
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
    }

    @Test
    fun rightAtTheEndOfARowStaysThere() {
        rule.setContent {
            NuvioTheme {
                CompositionLocalProvider(LocalV2Appearance provides V2AppearancePreferences()) {
                    Column(Modifier.padding(ScoutFocusRoom), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row {
                            Spacer(Modifier.width(500.dp))
                            ScoutChip("taste", {}, Modifier.testTag("taste"))
                        }
                        ScoutQuestion("question") {
                            ScoutChip("e1", {}, Modifier.testTag("e1"))
                            ScoutChip("e2", {}, Modifier.testTag("e2"))
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("e1").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        press(Key.DirectionRight)
        rule.onNodeWithTag("e2").assertIsFocused()
        press(Key.DirectionRight)
        rule.onNodeWithTag("e2").assertIsFocused()
    }

    @Test
    fun downWalksEveryRowAndStopsAtTheBottom() {
        rows()
        rule.onNodeWithTag("a1").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        press(Key.DirectionDown)
        rule.onNodeWithTag("b1").assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag("b2").assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag("c1").assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag("c1").assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag("c1").assertIsFocused()
    }

    @Test
    fun upFromTheBottomClimbsBack() {
        rows()
        rule.onNodeWithTag("c1").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        press(Key.DirectionUp)
        rule.onNodeWithTag("b2").assertIsFocused()
        press(Key.DirectionUp)
        rule.onNodeWithTag("b1").assertIsFocused()
        press(Key.DirectionUp)
        rule.onNodeWithTag("a1").assertIsFocused()
    }
}
