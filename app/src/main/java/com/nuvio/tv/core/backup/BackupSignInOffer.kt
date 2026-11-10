package com.nuvio.tv.core.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** After a restore done while signed out, Arc offers the Nuvio sign-in once on the next open. */
@Singleton
class BackupSignInOffer @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs by lazy { context.getSharedPreferences("backup_restore", Context.MODE_PRIVATE) }

    val pending: Boolean get() = prefs.getBoolean(KEY, false)

    fun arm() {
        prefs.edit().putBoolean(KEY, true).commit()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val KEY = "offer_sign_in"

        /** Not while the first sign-in page is up or the account state is unknown. */
        fun shouldShow(pending: Boolean, hasSeenSignInPage: Boolean?, signedOut: Boolean): Boolean =
            pending && hasSeenSignInPage == true && signedOut
    }
}
