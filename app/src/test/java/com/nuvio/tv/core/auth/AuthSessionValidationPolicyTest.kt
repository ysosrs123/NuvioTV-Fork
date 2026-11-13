package com.nuvio.tv.core.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSessionValidationPolicyTest {

    @Test
    fun `invalidates unauthorized auth responses`() {
        assertTrue(isInvalidRemoteSessionResponse(401, "Unauthorized"))
    }

    @Test
    fun `invalidates explicit revoked session responses`() {
        assertTrue(isInvalidRemoteSessionResponse(400, "session_not_found"))
        assertTrue(isInvalidRemoteSessionResponse(403, "Invalid session"))
        assertTrue(isInvalidRemoteSessionResponse(null, "Invalid JWT"))
        assertTrue(isInvalidRemoteSessionResponse(404, "User does not exist"))
    }

    @Test
    fun `keeps the session when the server refuses the app itself`() {
        assertFalse(isInvalidRemoteSessionResponse(401, "Invalid API key"))
        assertFalse(isInvalidRemoteSessionResponse(401, "No API key found in request"))
        assertFalse(isInvalidRemoteSessionResponse(null, "Unauthorized Invalid API key"))
    }

    @Test
    fun `clears the box only when the server still answers the app`() {
        assertTrue(shouldClearBoxAfterUnexpectedSignOut(200))
        assertTrue(shouldClearBoxAfterUnexpectedSignOut(204))
        assertFalse(shouldClearBoxAfterUnexpectedSignOut(null))
        assertFalse(shouldClearBoxAfterUnexpectedSignOut(401))
        assertFalse(shouldClearBoxAfterUnexpectedSignOut(403))
        assertFalse(shouldClearBoxAfterUnexpectedSignOut(404))
        assertFalse(shouldClearBoxAfterUnexpectedSignOut(503))
    }

    @Test
    fun `keeps cached session for transient failures`() {
        assertFalse(isInvalidRemoteSessionResponse(403, "Forbidden"))
        assertFalse(isInvalidRemoteSessionResponse(403, "Request blocked by proxy"))
        assertFalse(isInvalidRemoteSessionResponse(429, "Too many requests"))
        assertFalse(isInvalidRemoteSessionResponse(503, "Service unavailable"))
        assertFalse(isInvalidRemoteSessionResponse(null, "Unable to resolve host"))
    }
}
