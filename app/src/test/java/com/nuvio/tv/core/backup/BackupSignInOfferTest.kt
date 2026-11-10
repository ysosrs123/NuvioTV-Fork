package com.nuvio.tv.core.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSignInOfferTest {
    @Test
    fun `shows after a signed out restore`() {
        assertTrue(BackupSignInOffer.shouldShow(pending = true, hasSeenSignInPage = true, signedOut = true))
    }

    @Test
    fun `stays away when nothing is pending or the account is signed in`() {
        assertFalse(BackupSignInOffer.shouldShow(pending = false, hasSeenSignInPage = true, signedOut = true))
        assertFalse(BackupSignInOffer.shouldShow(pending = true, hasSeenSignInPage = true, signedOut = false))
    }

    @Test
    fun `waits while the sign-in page is up or not known yet`() {
        assertFalse(BackupSignInOffer.shouldShow(pending = true, hasSeenSignInPage = false, signedOut = true))
        assertFalse(BackupSignInOffer.shouldShow(pending = true, hasSeenSignInPage = null, signedOut = true))
    }
}
