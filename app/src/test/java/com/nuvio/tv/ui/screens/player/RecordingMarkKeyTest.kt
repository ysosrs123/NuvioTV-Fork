package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingMarkKeyTest {
    @Test fun `confirm going down marks once and its repeats and release do not`() {
        assertEquals(MarkKey.PRESS, markKey(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, 0))
        assertEquals(MarkKey.RELEASE, markKey(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, 3))
        assertEquals(MarkKey.RELEASE, markKey(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP, 0))
    }

    @Test fun `enter keys mark like the centre key`() {
        assertEquals(MarkKey.PRESS, markKey(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, 0))
        assertEquals(MarkKey.PRESS, markKey(KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.ACTION_DOWN, 0))
    }

    @Test fun `other keys are left to focus`() {
        assertEquals(MarkKey.OTHER, markKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, 0))
        assertEquals(MarkKey.OTHER, markKey(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP, 0))
    }
}
