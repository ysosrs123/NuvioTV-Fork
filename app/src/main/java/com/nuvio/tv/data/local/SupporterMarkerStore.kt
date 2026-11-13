package com.nuvio.tv.data.local

import android.content.Context
import com.nuvio.tv.domain.model.CosmeticEntitlement
import com.nuvio.tv.domain.model.CosmeticEntitlements
import com.nuvio.tv.domain.model.MemberAccess
import com.nuvio.tv.domain.model.MemberTier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The supporter status the account service last confirmed. It only counts while the box is signed out,
 * so supporter looks stay when the service cannot be reached. A backup carries it.
 */
@Singleton
class SupporterMarkerStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs by lazy { context.getSharedPreferences(STORE, Context.MODE_PRIVATE) }

    fun get(): MemberAccess? =
        supporterMarkerAccess(prefs.getString(KEY_TIER, null), prefs.getStringSet(KEY_ENTITLEMENTS, emptySet()).orEmpty())

    fun save(access: MemberAccess, nowMs: Long = System.currentTimeMillis()) {
        val tier = access.tier
        val editor = prefs.edit().putLong(KEY_CONFIRMED, nowMs)
        if (tier == null) {
            editor.remove(KEY_TIER).remove(KEY_ENTITLEMENTS)
        } else {
            editor.putString(KEY_TIER, tier.name)
                .putStringSet(KEY_ENTITLEMENTS, access.entitlements.unlocked.map { it.name }.toSet())
        }
        editor.apply()
    }

    companion object {
        const val STORE = "supporter_marker"
        const val KEY_TIER = "tier"
        const val KEY_ENTITLEMENTS = "entitlements"
        const val KEY_CONFIRMED = "confirmed_ms"

        private val clearCount = MutableStateFlow(0)

        /** Ticks whenever a sign-out clears the marker, so whoever shows supporter looks can drop them. */
        val cleared: StateFlow<Int> = clearCount.asStateFlow()

        fun clear(context: Context) {
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().clear().commit()
            clearCount.update { it + 1 }
        }
    }
}

internal fun supporterMarkerAccess(tierName: String?, entitlementNames: Set<String>): MemberAccess? {
    val tier = MemberTier.entries.firstOrNull { it.name == tierName } ?: return null
    val unlocked = entitlementNames.mapNotNull { name -> CosmeticEntitlement.entries.firstOrNull { it.name == name } }.toSet()
    return MemberAccess(tier = tier, entitlements = CosmeticEntitlements(unlocked))
}

/** What a signed-out box shows: nothing unless a marker says the account was a supporter. */
internal fun signedOutMemberAccess(marker: MemberAccess?): MemberAccess = marker ?: MemberAccess.None
