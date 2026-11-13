package com.nuvio.tv.core.auth

/** Saves what is on the box before an unexpected sign-out empties it. */
fun interface SignOutSafetyCopy {
    suspend fun save()
}
