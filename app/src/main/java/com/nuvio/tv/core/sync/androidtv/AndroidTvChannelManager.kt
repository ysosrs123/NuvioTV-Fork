package com.nuvio.tv.core.sync.androidtv

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.TvContractCompat.PreviewPrograms as P
import com.nuvio.tv.MainActivity
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.launcher.AppIconManager
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private const val TAG = "TvChannelSync"
private const val CHANNEL_LOGO_PX = 256

@Singleton
class AndroidTvChannelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: TvChannelPreferences,
    private val appIconManager: Lazy<AppIconManager>,
) {
    private val syncedProgramFingerprints = ConcurrentHashMap<String, ChannelProgramFingerprint>()

    private data class ChannelProgramFingerprint(
        val title: String?,
        val position: Int?,
        val duration: Int?,
        val imageUri: String?,
        val logoUri: String?,
        val description: String?,
        val sortOrder: Int,
        val season: Int?,
        val episode: Int?,
        val lastEngagementTime: Long
    )

    fun isSupported(): Boolean = isSupported(context)

    /**
     * Returns the channel id, creating or reusing the channel as needed.
     * Returns null on non-leanback devices or on failure.
     */
    suspend fun ensureChannel(): Long? = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext null
        runCatching {
            val stored = prefs.getChannelId()
            if (stored != null) {
                val cursor = context.contentResolver.query(
                    TvContractCompat.buildChannelUri(stored),
                    arrayOf(TvContractCompat.Channels._ID),
                    null, null, null
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        refreshLogoIfIconChanged(stored)
                        return@runCatching stored
                    }
                }
                Log.d(TAG, "Stored channel $stored gone; recreating")
                prefs.clearChannelId()
            }

            val orphan = context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf(
                    TvContractCompat.Channels._ID,
                    TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID
                ),
                null, null, null
            )?.use { c ->
                val idIdx = c.getColumnIndex(TvContractCompat.Channels._ID)
                val providerIdx = c.getColumnIndex(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID)
                if (idIdx < 0 || providerIdx < 0) return@use null
                while (c.moveToNext()) {
                    val providerId = c.getString(providerIdx)
                    if (providerId != null && providerId.startsWith(context.packageName)) {
                        return@use c.getLong(idIdx)
                    }
                }
                null
            }
            if (orphan != null) {
                Log.d(TAG, "Reusing orphaned channel $orphan")
                prefs.setChannelId(orphan)
                writeChannelLogo(orphan)
                return@runCatching orphan
            }

            val appLinkUri = Uri.parse(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }.toUri(Intent.URI_INTENT_SCHEME)
            )
            val channel = Channel.Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName(
                    launcherChannelName(
                        context.getString(R.string.app_name),
                        context.getString(R.string.tv_channel_continue_watching)
                    )
                )
                .setAppLinkIntentUri(appLinkUri)
                .setInternalProviderId(context.packageName)
                .build()

            val inserted = context.contentResolver.insert(
                TvContractCompat.Channels.CONTENT_URI,
                channel.toContentValues()
            ) ?: return@runCatching null

            val id = ContentUris.parseId(inserted)
            prefs.setChannelId(id)
            writeChannelLogo(id)
            runCatching { TvContractCompat.requestChannelBrowsable(context, id) }
                .onFailure { Log.w(TAG, "requestChannelBrowsable failed", it) }
            Log.d(TAG, "Created channel id=$id")
            id
        }.onFailure { Log.w(TAG, "ensureChannel failed", it) }.getOrNull()
    }

    /**
     * Syncs [items] to the Continue Watching channel: upserts present items and
     * removes rows that are no longer in the list (completed or dismissed).
     */
    suspend fun reconcile(
        items: List<WatchProgress>,
        descriptions: Map<String, String> = emptyMap()
    ) = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext
        runCatching {
            // Nothing to show yet: no channel is created and no browsable prompt raised.
            if (items.isEmpty() && prefs.getChannelId() == null) return@runCatching
            val channelId = ensureChannel() ?: return@runCatching
            val existing = queryExistingPrograms(channelId)
            val desiredKeys = items.map { progressKey(it) }.toSet()

            for ((key, rows) in existing) {
                if (key !in desiredKeys) {
                    rows.rowIds.forEach { rowId ->
                        context.contentResolver.delete(
                            TvContractCompat.buildPreviewProgramUri(rowId), null, null
                        )
                        Log.d(TAG, "Removed program key=$key rowId=$rowId")
                    }
                    syncedProgramFingerprints.remove(key)
                }
            }

            items.forEachIndexed { index, progress ->
                val key = progressKey(progress)
                val existingRows = existing[key]
                val rowIds = existingRows?.rowIds

                val (imageUri, _) = when {
                    !progress.backdrop.isNullOrBlank() -> progress.backdrop to TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
                    !progress.poster.isNullOrBlank() -> progress.poster to TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
                    else -> null to null
                }
                val positionMs = if (progress.position > 0) {
                    progress.position.toInt()
                } else {
                    (progress.progressPercent?.let { it / 100f * progress.duration }?.toLong() ?: 0L).toInt()
                }

                val newFingerprint = ChannelProgramFingerprint(
                    title = progress.name,
                    position = positionMs,
                    duration = progress.duration.toInt(),
                    imageUri = imageUri,
                    logoUri = progress.logo,
                    description = descriptions[progress.videoId],
                    sortOrder = index,
                    season = progress.season,
                    episode = progress.episode,
                    lastEngagementTime = progress.lastWatched
                )
                val oldFingerprint = syncedProgramFingerprints[key]

                if (!rowIds.isNullOrEmpty() && oldFingerprint != null &&
                    oldFingerprint.title == newFingerprint.title &&
                    oldFingerprint.imageUri == newFingerprint.imageUri &&
                    oldFingerprint.logoUri == newFingerprint.logoUri &&
                    oldFingerprint.description == newFingerprint.description &&
                    oldFingerprint.sortOrder == newFingerprint.sortOrder &&
                    oldFingerprint.season == newFingerprint.season &&
                    oldFingerprint.episode == newFingerprint.episode &&
                    oldFingerprint.duration == newFingerprint.duration &&
                    abs((oldFingerprint.position ?: 0) - (newFingerprint.position ?: 0)) < 2_000
                ) {
                    // Item already in sync, skip database update to prevent waking up launcher
                    return@forEachIndexed
                }

                val values = buildProgramValues(progress, channelId, index, key, newFingerprint.description)
                if (existingRows != null && existingRows.rowIds.size == 1 &&
                    storedProgramMatches(values.toMap(), existingRows.stored)
                ) {
                    // A fresh process has no fingerprints yet; the row the launcher holds already matches.
                    syncedProgramFingerprints[key] = newFingerprint
                    return@forEachIndexed
                }
                if (!rowIds.isNullOrEmpty()) {
                    val primaryRowId = rowIds.first()
                    // UPDATE the existing program row in place, keeping its row ID stable.
                    context.contentResolver.update(
                        TvContractCompat.buildPreviewProgramUri(primaryRowId), values, null, null
                    )
                    // If there are duplicate rows for the same key, delete them to clean up the database.
                    if (rowIds.size > 1) {
                        rowIds.drop(1).forEach { extraRowId ->
                            context.contentResolver.delete(
                                TvContractCompat.buildPreviewProgramUri(extraRowId), null, null
                            )
                            Log.d(TAG, "Removed duplicate program key=$key rowId=$extraRowId")
                        }
                    }
                } else {
                    context.contentResolver.insert(
                        TvContractCompat.PreviewPrograms.CONTENT_URI, values
                    )
                }
                syncedProgramFingerprints[key] = newFingerprint
                Log.d(TAG, "${if (!rowIds.isNullOrEmpty()) "Updated" else "Inserted"} program key=$key pos=${values.getAsInteger("last_playback_position_millis")} dur=${values.getAsInteger("duration_millis")} pct=${progress.progressPercent}")
            }
        }.onFailure { Log.w(TAG, "reconcile failed", it) }
    }

    /** Removes all preview programs from our channel (used on sign-out / history clear). */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext
        runCatching {
            val channelId = prefs.getChannelId() ?: return@runCatching
            val rows = queryExistingPrograms(channelId)
            var deletedCount = 0
            rows.values.flatMap { it.rowIds }.forEach { rowId ->
                context.contentResolver.delete(
                    TvContractCompat.buildPreviewProgramUri(rowId), null, null
                )
                deletedCount++
            }
            syncedProgramFingerprints.clear()
            Log.d(TAG, "Cleared $deletedCount programs for channel $channelId")
        }.onFailure { Log.w(TAG, "clearAll failed", it) }
    }

    // ---

    private class ExistingPrograms(val rowIds: MutableList<Long>, val stored: Map<String, String?>)

    private fun queryExistingPrograms(channelId: Long): Map<String, ExistingPrograms> {
        val result = mutableMapOf<String, ExistingPrograms>()
        val projection = arrayOf(
            TvContractCompat.PreviewPrograms._ID,
            TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID,
            TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID
        ) + COMPARED_COLUMNS
        // Fire OS rejects any selection clause on preview_program URIs with SecurityException;
        // the provider auto-scopes to the calling package, so we filter channelId in memory.
        context.contentResolver.query(
            TvContractCompat.PreviewPrograms.CONTENT_URI,
            projection,
            null, null, null
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms._ID)
            val channelIdx = c.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID)
            val keyIdx = c.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID)
            while (c.moveToNext()) {
                if (c.getLong(channelIdx) != channelId) continue
                val key = c.getString(keyIdx) ?: continue
                val rowId = c.getLong(idIdx)
                val known = result[key]
                if (known != null) {
                    known.rowIds.add(rowId)
                } else {
                    val stored = COMPARED_COLUMNS.associateWith { column ->
                        val index = c.getColumnIndex(column)
                        if (index < 0 || c.isNull(index)) null else runCatching { c.getString(index) }.getOrNull()
                    }
                    result[key] = ExistingPrograms(mutableListOf(rowId), stored)
                }
            }
        }
        return result
    }

    private fun ContentValues.toMap(): Map<String, Any?> =
        (valueSet() ?: emptySet()).filter { it.key in COMPARED_COLUMNS }.associate { it.key to it.value } +
            COMPARED_COLUMNS.filterNot { containsKey(it) }.associateWith { null }

    private fun buildProgramValues(
        progress: WatchProgress,
        channelId: Long,
        sortOrder: Int,
        key: String,
        description: String?
    ): ContentValues {
        val intentUri = Uri.parse(
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("contentId", progress.contentId)
                putExtra("contentType", progress.contentType)
                putExtra("videoId", progress.videoId)
                putExtra("name", progress.name)
                putExtra("poster", progress.poster)
                putExtra("backdrop", progress.backdrop)
                putExtra("logo", progress.logo)
                progress.season?.let { putExtra("season", it) }
                progress.episode?.let { putExtra("episode", it) }
                progress.episodeTitle?.let { putExtra("episodeTitle", it) }
                putExtra("launchMode", "stream")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }.toUri(Intent.URI_INTENT_SCHEME)
        )

        val type = if (progress.contentType.equals("movie", ignoreCase = true))
            TvContractCompat.PreviewPrograms.TYPE_MOVIE
        else
            TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE

        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(type)
            .setTitle(progress.name)
            .setIntentUri(intentUri)
            .setInternalProviderId(key)
            .setWeight(Int.MAX_VALUE - sortOrder)
            .setDescription(description)

        // Backdrop/poster fills the tile via posterArt; logo goes to the dedicated logo column
        // so the launcher renders it as a small badge overlay on focus.
        val (imageUri, aspectRatio) = when {
            !progress.backdrop.isNullOrBlank() ->
                progress.backdrop to TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
            !progress.poster.isNullOrBlank() ->
                progress.poster to TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
            else -> null to null
        }
        imageUri?.let { builder.setPosterArtUri(Uri.parse(it)).setPosterArtAspectRatio(aspectRatio!!) }
        progress.logo?.let { builder.setLogoUri(Uri.parse(it)) }

        if (progress.duration > 0) {
            builder.setDurationMillis(progress.duration.toInt())
            val positionMs = if (progress.position > 0) {
                progress.position.toInt()
            } else {
                (progress.progressPercent?.let { it / 100f * progress.duration }?.toLong() ?: 0L).toInt()
            }
            builder.setLastPlaybackPositionMillis(positionMs)
        } else if (progress.progressPercent != null && progress.progressPercent > 0f) {
            // No real duration known (e.g. Simkl/Trakt sync), but we have a percent.
            // Use synthetic values so the launcher can render a progress bar.
            val syntheticDuration = 100_000
            val syntheticPosition = (progress.progressPercent / 100f * syntheticDuration).toInt()
            builder.setDurationMillis(syntheticDuration)
            builder.setLastPlaybackPositionMillis(syntheticPosition)
        }

        if (type == TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE) {
            progress.season?.let { builder.setSeasonNumber(it) }
            progress.episode?.let { builder.setEpisodeNumber(it) }
            progress.episodeTitle?.let { builder.setEpisodeTitle(it) }
        }

        return builder.build().toContentValues().also {
            // COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS drives launcher ordering;
            // the Builder method was added after tvprovider 1.0.0, so set directly.
            it.put("last_engagement_time_utc_millis", progress.lastWatched)
            // Explicitly clear poster art when no image is available, so UPDATE operations
            // don't leave stale artwork from previous reconcile cycles.
            if (imageUri == null) {
                it.putNull(TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_URI)
            }
            if (progress.logo.isNullOrBlank()) {
                it.putNull(TvContractCompat.PreviewPrograms.COLUMN_LOGO_URI)
            }
            // Clear duration/position when unknown so stale values (e.g. previous 1hr fallback)
            // don't persist across UPDATE cycles.
            if (progress.duration <= 0 && (progress.progressPercent == null || progress.progressPercent <= 0f)) {
                it.putNull("duration_millis")
                it.putNull("last_playback_position_millis")
            }
        }
    }

    /** The channel logo is the launcher icon the user picked; adaptive icons are drawn, not decoded. */
    private suspend fun writeChannelLogo(channelId: Long) {
        runCatching {
            val icons = appIconManager.get()
            val icon = icons.selectedIcon()
                ?: ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                ?: return
            val bitmap = icon.toBitmap(CHANNEL_LOGO_PX, CHANNEL_LOGO_PX)
            context.contentResolver.openOutputStream(
                TvContractCompat.buildChannelLogoUri(channelId)
            )?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            prefs.setLogoKey(icons.selectedEntry())
        }.onFailure { Log.w(TAG, "writeChannelLogo failed", it) }
    }

    private suspend fun refreshLogoIfIconChanged(channelId: Long) {
        val current = runCatching { appIconManager.get().selectedEntry() }.getOrNull() ?: return
        if (prefs.getLogoKey() != current) writeChannelLogo(channelId)
    }

    /**
     * Mirrors WatchProgressPreferences.createKey() so channel and storage agree on identity.
     */
    private fun progressKey(progress: WatchProgress): String =
        if (progress.season != null && progress.episode != null)
            "${progress.contentId}_s${progress.season}e${progress.episode}"
        else
            progress.contentId

    companion object {
        private const val POSITION_COLUMN = TvContractCompat.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS

        /** Everything the channel writes that the launcher shows; the engagement time alone never forces a write. */
        internal val COMPARED_COLUMNS = arrayOf(
            P.COLUMN_TYPE,
            P.COLUMN_TITLE,
            P.COLUMN_INTENT_URI,
            P.COLUMN_WEIGHT,
            P.COLUMN_SHORT_DESCRIPTION,
            P.COLUMN_POSTER_ART_URI,
            P.COLUMN_POSTER_ART_ASPECT_RATIO,
            P.COLUMN_LOGO_URI,
            P.COLUMN_DURATION_MILLIS,
            POSITION_COLUMN,
            P.COLUMN_SEASON_DISPLAY_NUMBER,
            P.COLUMN_EPISODE_DISPLAY_NUMBER,
            P.COLUMN_EPISODE_TITLE
        )

        /**
         * True when the row the launcher already holds carries every value about to be written, the position
         * within 2 s, so a sync after a restart leaves it alone instead of rewriting it and waking the launcher.
         */
        internal fun storedProgramMatches(values: Map<String, Any?>, stored: Map<String, String?>): Boolean {
            if (stored.isEmpty()) return false
            return values.all { (column, value) ->
                if (!stored.containsKey(column)) return@all false
                val old = stored[column]
                when {
                    value == null -> old == null
                    column == POSITION_COLUMN -> {
                        val newPosition = value.toString().toLongOrNull()
                        val oldPosition = old?.toLongOrNull()
                        newPosition != null && oldPosition != null && abs(newPosition - oldPosition) < 2_000
                    }
                    value is ByteArray -> false
                    value is Boolean -> old == (if (value) "1" else "0") || old.equals(value.toString(), ignoreCase = true)
                    else -> old == value.toString()
                }
            }
        }

        fun isSupported(context: Context): Boolean = try {
            val packageManager = context.packageManager
            launcherChannelsSupported(
                hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
                isFireTv = packageManager.hasSystemFeature("amazon.hardware.fire_tv")
            )
        } catch (_: Exception) {
            false
        }
    }
}
