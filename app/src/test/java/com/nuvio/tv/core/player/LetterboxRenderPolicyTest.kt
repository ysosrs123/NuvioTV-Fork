package com.nuvio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterboxRenderPolicyTest {
    @Test fun `Amazon retains opaque bars while other manufacturers use transparent bars`() {
        assertFalse(LetterboxRenderPolicy.defaultTransparentLetterbox(" Amazon "))
        assertFalse(LetterboxRenderPolicy.defaultTransparentLetterbox("amazon"))
        assertTrue(LetterboxRenderPolicy.defaultTransparentLetterbox("Ugoos"))
    }

    @Test fun `overlapping player destinations keep transparency until both leave`() {
        assertFalse(PlayerWindowBackdrop.isTransparentRequested)
        PlayerWindowBackdrop.acquireTransparent()
        try {
            PlayerWindowBackdrop.acquireTransparent()
            PlayerWindowBackdrop.releaseTransparent()
            assertTrue(PlayerWindowBackdrop.isTransparentRequested)
        } finally {
            PlayerWindowBackdrop.releaseTransparent()
        }
        assertFalse(PlayerWindowBackdrop.isTransparentRequested)
        PlayerWindowBackdrop.releaseTransparent()
        PlayerWindowBackdrop.acquireTransparent()
        try {
            assertTrue(PlayerWindowBackdrop.isTransparentRequested)
        } finally {
            PlayerWindowBackdrop.releaseTransparent()
        }
    }
}
