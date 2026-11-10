package com.nuvio.tv.core.profile

import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.ProviderException
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedCredentialFailureTest {
    @Test
    fun aValueThatCanNeverBeReadIsRemoved() {
        assertTrue(isPermanentCredentialFailure(IllegalArgumentException()))
        assertTrue(isPermanentCredentialFailure(AEADBadTagException()))
        assertTrue(isPermanentCredentialFailure(KeyPermanentlyInvalidatedException()))
    }

    @Test
    fun aOneOffKeystoreErrorKeepsTheSignIn() {
        assertFalse(isPermanentCredentialFailure(KeyStoreException()))
        assertFalse(isPermanentCredentialFailure(ProviderException()))
        assertFalse(isPermanentCredentialFailure(IllegalStateException()))
    }
}
