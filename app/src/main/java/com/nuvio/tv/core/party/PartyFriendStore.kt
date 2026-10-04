package com.nuvio.tv.core.party

import javax.crypto.AEADBadTagException
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.UnrecoverableKeyException
import android.content.Context
import android.util.Log
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Each Nuvio profile's friends, friend key, party name and party avatar, kept on the device only and cleared with the
 * profile or on sign-out. Called on the main thread, except [friendsOf], which only reads.
 */
@Singleton
class PartyFriendStore @Inject constructor(
    @param:ApplicationContext context: Context,
) : ProfileScopedCredentialStore {
    private val log: (String) -> Unit = { Log.i(TAG, it) }
    private val preferences = context.getSharedPreferences("watch_party_friends", Context.MODE_PRIVATE)
    private val identities = HashMap<Int, PartyIdentity?>()

    private val _friends = MutableStateFlow<Map<Int, List<PartyFriend>>>(emptyMap())

    /** Friends of every profile that has any, most recent first. */
    val friends: StateFlow<Map<Int, List<PartyFriend>>> = _friends.asStateFlow()

    init {
        reload()
    }

    /** Moves what was kept for the whole device to [profileId], the profile that was in use; runs once. */
    fun adoptDeviceData(profileId: Int, deviceName: String?) {
        val oldFriends = preferences.getString(KEY_FRIENDS, null)
        val oldSecret = preferences.getString(KEY_SECRET, null)
        if (oldFriends == null && oldSecret == null && deviceName == null) return
        preferences.edit().apply {
            if (oldFriends != null && !preferences.contains(key(KEY_FRIENDS, profileId))) putString(key(KEY_FRIENDS, profileId), oldFriends)
            if (oldSecret != null && !preferences.contains(key(KEY_SECRET, profileId))) putString(key(KEY_SECRET, profileId), oldSecret)
            if (deviceName != null && !preferences.contains(key(KEY_NAME, profileId))) putString(key(KEY_NAME, profileId), deviceName)
            remove(KEY_FRIENDS)
            remove(KEY_SECRET)
        }.commit()
        identities.remove(profileId)
        log("friends moved to profile $profileId")
        reload()
    }

    /** Forgets everything of a profile that was deleted, so a new profile in its place starts empty. */
    override fun removeProfile(profileId: Int) = clearProfile(profileId)

    override fun clearAllProfiles() {
        preferences.edit().clear().apply()
        identities.clear()
        _friends.value = emptyMap()
        log("party data cleared on sign-out")
    }

    fun clearProfile(profileId: Int) {
        if (listOf(KEY_FRIENDS, KEY_SECRET, KEY_NAME, KEY_AVATAR).none { preferences.contains(key(it, profileId)) }) return
        preferences.edit().apply {
            listOf(KEY_FRIENDS, KEY_SECRET, KEY_NAME, KEY_AVATAR).forEach { remove(key(it, profileId)) }
        }.apply()
        identities.remove(profileId)
        _friends.value = _friends.value - profileId
        log("party data of deleted profile $profileId cleared")
    }

    private fun reload() {
        _friends.value = preferences.all.keys
            .mapNotNull { name -> name.removePrefix("$KEY_FRIENDS.p").takeIf { name.startsWith("$KEY_FRIENDS.p") }?.toIntOrNull() }
            .associateWith { id -> PartyFriendCodec.sorted(PartyFriendCodec.decode(preferences.getString(key(KEY_FRIENDS, id), null))) }
            .filterValues { it.isNotEmpty() }
    }

    fun friendsOf(profileId: Int): List<PartyFriend> = _friends.value[profileId].orEmpty()

    fun isFriend(profileId: Int, key: String): Boolean = friendsOf(profileId).any { it.key == key }

    fun friend(profileId: Int, key: String): PartyFriend? = friendsOf(profileId).firstOrNull { it.key == key }

    /** The profile's key pair if one was made before; [create] makes and keeps one the first time. */
    fun identity(profileId: Int, create: Boolean): PartyIdentity? {
        if (!identities.containsKey(profileId)) {
            val stored = preferences.getString(key(KEY_SECRET, profileId), null)
            val loaded = when (val read = stored?.let(::readSecret)) {
                null -> null
                // The keystore did not answer: keep the stored key and the friends, and try again next time.
                SecretRead.Unavailable -> return null
                SecretRead.Broken -> null
                is SecretRead.Ok -> runCatching { PartyIdentity(read.secret, PartyCrypto.hex(Bip340.publicKey(read.secret))) }.getOrNull()
            }
            if (stored != null && loaded == null) {
                log("friend key of profile $profileId lost, its friends cleared")
                preferences.edit().remove(key(KEY_SECRET, profileId)).apply()
                save(profileId, emptyList())
            }
            identities[profileId] = loaded
        }
        if (identities[profileId] == null && create) {
            val created = PartyIdentity.random()
            preferences.edit().putString(key(KEY_SECRET, profileId), writeSecret(created.secretKey)).apply()
            identities[profileId] = created
            log("friend key created for profile $profileId")
        }
        return identities[profileId]
    }

    fun put(profileId: Int, friend: PartyFriend) {
        save(profileId, friendsOf(profileId).filterNot { it.key == friend.key } + friend)
    }

    fun update(profileId: Int, key: String, change: (PartyFriend) -> PartyFriend) {
        val current = friend(profileId, key) ?: return
        val changed = change(current)
        if (changed != current) put(profileId, changed)
    }

    fun remove(profileId: Int, key: String) {
        save(profileId, friendsOf(profileId).filterNot { it.key == key })
    }

    /** The name others see in a party; empty means the profile name. */
    fun partyName(profileId: Int): String = preferences.getString(key(KEY_NAME, profileId), null).orEmpty()

    fun setPartyName(profileId: Int, name: String) {
        preferences.edit().apply {
            if (name.isBlank()) remove(key(KEY_NAME, profileId)) else putString(key(KEY_NAME, profileId), name)
        }.apply()
    }

    /** The avatar chosen for parties ([PartyAvatars] reference or picture link); null means the profile's own. */
    fun partyAvatar(profileId: Int): String? = preferences.getString(key(KEY_AVATAR, profileId), null)

    fun setPartyAvatar(profileId: Int, avatar: String?) {
        preferences.edit().apply {
            if (avatar.isNullOrBlank()) remove(key(KEY_AVATAR, profileId)) else putString(key(KEY_AVATAR, profileId), avatar)
        }.apply()
    }

    /** Invites already shown, so a restart does not show them again. */
    fun seenInvites(): MutableMap<String, Long> =
        preferences.getString(KEY_SEEN, null).orEmpty().split('\n').mapNotNull { line ->
            val (id, at) = line.split(' ').takeIf { it.size == 2 } ?: return@mapNotNull null
            at.toLongOrNull()?.let { id to it }
        }.toMap(LinkedHashMap())

    fun saveSeenInvites(seen: Map<String, Long>) {
        preferences.edit().putString(KEY_SEEN, seen.entries.joinToString("\n") { "${it.key} ${it.value}" }).apply()
    }

    private fun save(profileId: Int, list: List<PartyFriend>) {
        val sorted = PartyFriendCodec.sorted(list)
        _friends.value = (_friends.value + (profileId to sorted)).filterValues { it.isNotEmpty() }
        preferences.edit().apply {
            if (sorted.isEmpty()) remove(key(KEY_FRIENDS, profileId)) else putString(key(KEY_FRIENDS, profileId), PartyFriendCodec.encode(sorted))
        }.apply()
    }

    private fun key(base: String, profileId: Int) = "$base.p$profileId"

    private fun writeSecret(secret: ByteArray): String = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        "k:" + cipher.iv.base64() + "." + cipher.doFinal(secret).base64()
    }.getOrElse {
        log("keystore unavailable, friend key kept in app storage: ${it.javaClass.simpleName}")
        "p:" + secret.base64()
    }

    private sealed interface SecretRead {
        class Ok(val secret: ByteArray) : SecretRead
        /** The stored key can never be read again (wrong format, or the keystore key that sealed it is gone). */
        data object Broken : SecretRead
        /** The keystore did not answer this time. */
        data object Unavailable : SecretRead
    }

    private fun readSecret(stored: String): SecretRead = try {
        val secret = when {
            stored.startsWith("k:") -> {
                val (iv, data) = stored.removePrefix("k:").split('.').also { require(it.size == 2) }
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(GCM_TAG_BITS, iv.unbase64()))
                cipher.doFinal(data.unbase64())
            }
            stored.startsWith("p:") -> stored.removePrefix("p:").unbase64()
            else -> null
        }
        if (secret?.size == 32) SecretRead.Ok(secret) else SecretRead.Broken
    } catch (e: Exception) {
        log("friend key unreadable: ${e.javaClass.simpleName}")
        when (e) {
            is AEADBadTagException, is KeyPermanentlyInvalidatedException, is UnrecoverableKeyException,
            is IllegalArgumentException -> SecretRead.Broken
            else -> SecretRead.Unavailable
        }
    }

    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.unbase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val KEY_FRIENDS = "friends"
        const val KEY_SECRET = "identity"
        const val KEY_NAME = "name"
        const val KEY_AVATAR = "avatar"
        const val KEY_SEEN = "seen_invites"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.nuvio.tv.party.friends.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

private const val TAG = "WatchParty"
