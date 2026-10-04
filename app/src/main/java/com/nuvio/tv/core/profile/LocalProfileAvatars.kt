package com.nuvio.tv.core.profile

import android.content.Context
import com.nuvio.tv.core.party.PartyAvatars
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import com.nuvio.tv.domain.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Avatars from the set built into the app, picked for a Nuvio profile in the profile editor. They stay on this box:
 * the profile itself keeps its Nuvio avatar, so the account, other devices and nuvio.tv never see them.
 */
@Singleton
class LocalProfileAvatars @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ProfileScopedCredentialStore {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _avatars = MutableStateFlow(read())
    val avatars: StateFlow<Map<Int, String>> = _avatars.asStateFlow()

    /** The built-in set as catalogue entries for the editor's picker, under their own category. */
    val catalog: List<AvatarCatalogItem> by lazy {
        PartyAvatars.SETS.flatMap { set ->
            val names = runCatching { context.assets.list("${PartyAvatars.ASSET_DIR}/$set") }.getOrNull()
                ?.filter { it.endsWith(".svg") }?.map { it.removeSuffix(".svg") }?.sorted().orEmpty()
            names.map { name -> PartyAvatars.ref(set, name) }
        }.mapIndexedNotNull { index, ref ->
            val image = PartyAvatars.imageUri(ref) ?: return@mapIndexedNotNull null
            AvatarCatalogItem(
                id = ref,
                displayName = ref.substringAfterLast('/').replace('-', ' ').replaceFirstChar { it.uppercase() },
                imageUrl = image,
                category = CATEGORY,
                sortOrder = index,
            )
        }
    }

    fun get(profileId: Int): String? = _avatars.value[profileId]

    fun set(profileId: Int, ref: String?) {
        val clean = ref?.takeIf(PartyAvatars::isBundled)
        if (_avatars.value[profileId] == clean) return
        preferences.edit().apply { if (clean == null) remove(key(profileId)) else putString(key(profileId), clean) }.apply()
        _avatars.value = _avatars.value.toMutableMap().apply { if (clean == null) remove(profileId) else put(profileId, clean) }
    }

    override fun removeProfile(profileId: Int) = set(profileId, null)

    override fun clearAllProfiles() {
        preferences.edit().clear().apply()
        _avatars.value = emptyMap()
    }

    /** The profile as this box draws it: with its local avatar as the picture when it has one. */
    fun shown(profile: UserProfile): UserProfile =
        _avatars.value[profile.id]?.let { ref -> profile.copy(avatarUrl = PartyAvatars.imageUri(ref)) } ?: profile

    private fun read(): Map<Int, String> = preferences.all.mapNotNull { (name, value) ->
        val id = name.removePrefix(KEY_PREFIX).toIntOrNull() ?: return@mapNotNull null
        (value as? String)?.takeIf(PartyAvatars::isBundled)?.let { id to it }
    }.toMap()

    private fun key(profileId: Int) = "$KEY_PREFIX$profileId"

    companion object {
        const val CATEGORY = "fun-set"
        private const val PREFS = "profile_local_avatars"
        private const val KEY_PREFIX = "p"

        fun isLocal(avatarId: String?): Boolean = PartyAvatars.isBundled(avatarId)

        fun isLocalImage(url: String?): Boolean = url?.startsWith("file:///android_asset/${PartyAvatars.ASSET_DIR}/") == true
    }
}
