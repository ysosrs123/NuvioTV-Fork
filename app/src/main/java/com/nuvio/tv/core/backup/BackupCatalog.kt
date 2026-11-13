package com.nuvio.tv.core.backup

import com.nuvio.tv.core.sync.localOnlyPlayerProfileSettingsKeys

/**
 * A store a backup reads. [part] is where its keys go unless a key is a secret (always kept sealed),
 * a box-specific setting, or bookkeeping that is never copied.
 */
data class BackupStoreSpec(
    val kind: BackupStoreKind,
    val name: String,
    val part: BackupPart,
    val deviceKeys: Set<String> = emptySet(),
    val partByKey: Map<String, BackupPart> = emptyMap(),
    val skipKeys: Set<String> = emptySet(),
    /** Sync positions: never copied, and removed when the part is restored so the next sign-in pulls everything. */
    val resetKeys: Set<String> = emptySet(),
    /** PROTECTED only: the key the owner encrypts with. */
    val keyAlias: String? = null,
    /** FILE only: the file name in front of `_<profile>.json`. */
    val filePrefix: String? = null
)

object BackupCatalog {
    private val secretKeyPattern = Regex(
        "(api_?key|token|secret|password|passwd|cookie|session|client_?id|credential|addon_urls?|poster_url_pattern|^custom_url$|_auth$|^auth_)"
    )

    private val neverCopiedPattern = Regex("^(migration_|last_playback_diagnostics|enable_buffer_logs|assessment_revert|audio_rejection)")

    private val playerDeviceKeys: Set<String> by lazy { localOnlyPlayerProfileSettingsKeys }

    val stores: List<BackupStoreSpec> = listOf(
        profile("theme_settings"),
        profile("layout_settings"),
        profile("experience_settings", skipKeys = setOf("essential_chosen")),
        profile("player_settings"),
        profile("stream_badge_settings"),
        profile("plugin_settings"),
        profile("trailer_settings", deviceKeys = setOf("trailer_allow_4k")),
        profile("tmdb_settings"),
        profile("mdblist_settings"),
        profile("trakt_settings"),
        profile("debrid_settings"),
        profile("track_preference"),
        profile("calendar_settings"),
        profile("ambient_mode_settings"),
        profile("discover_selection"),
        profile("collections"),
        profile("scout"),
        profile("scout_mixes"),
        profile("scout_ask"),
        profile("animeskip_settings"),
        profile("wetrakr_settings"),
        BackupStoreSpec(BackupStoreKind.PROFILE_STORE, "addon_preferences", BackupPart.SECRETS),
        BackupStoreSpec(BackupStoreKind.PROFILE_STORE, "trakt_auth_store", BackupPart.SECRETS),
        BackupStoreSpec(BackupStoreKind.PROFILE_STORE, "audio_delay_route_preference", BackupPart.DEVICE),

        history(BackupHistory.LIBRARY, "library_delta_cursor", "library_delta_initialized"),
        history(
            BackupHistory.PROGRESS_STATE,
            "last_successful_push_ms", "watch_progress_delta_cursor", "watch_progress_delta_initialized"
        ),
        history(BackupHistory.PROGRESS_RECENT),
        history(BackupHistory.PROGRESS_ARCHIVE),
        history(BackupHistory.PENDING),
        history(
            BackupHistory.WATCHED,
            "last_successful_watched_push_ms", "watched_items_delta_cursor", "watched_items_delta_initialized"
        ),

        BackupStoreSpec(
            BackupStoreKind.BOX_STORE, "profile_settings", BackupPart.SETTINGS,
            partByKey = mapOf("profiles_json" to BackupPart.PROFILES),
            skipKeys = setOf("active_profile_id", "profile_has_ever_selected")
        ),
        BackupStoreSpec(BackupStoreKind.BOX_STORE, "app_onboarding", BackupPart.SETTINGS),
        BackupStoreSpec(BackupStoreKind.BOX_STORE, "appearance_v2", BackupPart.SETTINGS),
        BackupStoreSpec(BackupStoreKind.BOX_STORE, "device_ui_preferences_v2", BackupPart.SETTINGS),
        BackupStoreSpec(BackupStoreKind.BOX_STORE, "seek_thumbnails", BackupPart.SETTINGS),
        BackupStoreSpec(BackupStoreKind.BOX_STORE, "torrent_settings", BackupPart.SETTINGS),
        BackupStoreSpec(BackupStoreKind.BOX_STORE, "ui_scale_prefs", BackupPart.DEVICE),
        BackupStoreSpec(BackupStoreKind.BOX_STORE, "device_local_player_prefs", BackupPart.DEVICE),

        BackupStoreSpec(BackupStoreKind.PREFS, "profile_local_avatars", BackupPart.PROFILES),
        BackupStoreSpec(BackupStoreKind.PREFS, "network_dns", BackupPart.SETTINGS),
        BackupStoreSpec(BackupStoreKind.PREFS, "nuvio_tv_autosync", BackupPart.SETTINGS),
        BackupStoreSpec(BackupStoreKind.PREFS, "app_icon", BackupPart.SETTINGS),
        BackupStoreSpec(BackupStoreKind.PREFS, "app_locale", BackupPart.SETTINGS),
        BackupStoreSpec(BackupStoreKind.PREFS, "server_configuration", BackupPart.SECRETS),
        BackupStoreSpec(BackupStoreKind.PREFS, "image_performance", BackupPart.DEVICE),
        BackupStoreSpec(BackupStoreKind.PREFS, "watch_party_friends", BackupPart.SECRETS),
        BackupStoreSpec(BackupStoreKind.PREFS, "real_debrid_tokens", BackupPart.SECRETS),
        BackupStoreSpec(BackupStoreKind.PREFS, "nuvio_simkl_auth", BackupPart.SECRETS),
        BackupStoreSpec(BackupStoreKind.PREFS, BackupSupporter.STORE, BackupPart.SECRETS),

        BackupStoreSpec(BackupStoreKind.PROTECTED, "media_servers", BackupPart.SECRETS, keyAlias = "com.nuvio.tv.mediaservers.v1"),
        BackupStoreSpec(BackupStoreKind.PROTECTED, "floppy_auth", BackupPart.SECRETS, keyAlias = "com.nuvio.tv.floppy.credentials.v1"),
        BackupStoreSpec(BackupStoreKind.PROTECTED, "mdblist_auth", BackupPart.SECRETS, keyAlias = "com.nuvio.tv.mdblist.credentials.v1"),
        BackupStoreSpec(BackupStoreKind.PROTECTED, "wetrakr_auth", BackupPart.SECRETS, keyAlias = "com.nuvio.tv.wetrakr.credentials.v1"),
        BackupStoreSpec(BackupStoreKind.PROTECTED, "scout_ask_keys", BackupPart.SECRETS, keyAlias = "scout_ask_keys_v1"),

        BackupStoreSpec(BackupStoreKind.FILE, "scout_taste", BackupPart.SETTINGS, filePrefix = "scout/taste")
    )

    fun spec(kind: BackupStoreKind, name: String): BackupStoreSpec? =
        stores.firstOrNull { it.kind == kind && it.name == name }

    /** Where one key of [spec] belongs, or null when it is never copied. */
    fun partFor(spec: BackupStoreSpec, key: String): BackupPart? {
        if (key in spec.skipKeys || key in spec.resetKeys || neverCopiedPattern.containsMatchIn(key)) return null
        spec.partByKey[key]?.let { return it }
        if (spec.part == BackupPart.SECRETS || secretKeyPattern.containsMatchIn(key.lowercase())) return BackupPart.SECRETS
        if (key in spec.deviceKeys) return BackupPart.DEVICE
        if (spec.name == "player_settings" && key in playerDeviceKeys) return BackupPart.DEVICE
        return spec.part
    }

    /** `layout_settings` is profile 1, `layout_settings_p3` is profile 3. */
    fun profileStoreFileName(name: String, profileId: Int): String =
        if (profileId == 1) name else "${name}_p$profileId"

    fun profileIdOf(name: String, fileName: String): Int? = when {
        fileName == name -> 1
        fileName.startsWith("${name}_p") -> fileName.removePrefix("${name}_p").toIntOrNull()?.takeIf { it > 1 }
        else -> null
    }

    /** Splits the values of one store into the parts they belong to. */
    fun split(spec: BackupStoreSpec, values: Map<String, BackupValue>): Map<BackupPart, Map<String, BackupValue>> {
        val result = linkedMapOf<BackupPart, MutableMap<String, BackupValue>>()
        values.forEach { (key, value) ->
            val part = partFor(spec, key) ?: return@forEach
            result.getOrPut(part) { linkedMapOf() }[key] = value
        }
        return result
    }

    private fun history(name: String, vararg resetKeys: String) =
        BackupStoreSpec(BackupStoreKind.PROFILE_STORE, name, BackupPart.HISTORY, resetKeys = resetKeys.toSet())

    private fun profile(name: String, deviceKeys: Set<String> = emptySet(), skipKeys: Set<String> = emptySet()) =
        BackupStoreSpec(BackupStoreKind.PROFILE_STORE, name, BackupPart.SETTINGS, deviceKeys = deviceKeys, skipKeys = skipKeys)
}
