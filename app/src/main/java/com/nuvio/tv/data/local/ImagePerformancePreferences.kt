package com.nuvio.tv.data.local

import android.content.Context
import com.nuvio.tv.core.image.PosterUpdateInterval
import com.nuvio.tv.core.image.PosterUpdates
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImagePerformancePreferences @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val rgb565Enabled: Boolean
        get() = preferences.getBoolean(KEY_RGB565_ENABLED, true)

    fun setRgb565Enabled(enabled: Boolean): Boolean {
        return preferences.edit()
            .putBoolean(KEY_RGB565_ENABLED, enabled)
            .commit()
    }

    val posterUpdateInterval: PosterUpdateInterval
        get() = PosterUpdateInterval.fromStorage(preferences.getString(KEY_POSTER_UPDATES, null))

    fun setPosterUpdateInterval(interval: PosterUpdateInterval) {
        preferences.edit().putString(KEY_POSTER_UPDATES, interval.storageValue).apply()
        PosterUpdates.interval = interval
    }

    private companion object {
        const val PREFERENCES_NAME = "image_performance"
        const val KEY_RGB565_ENABLED = "rgb565_enabled"
        const val KEY_POSTER_UPDATES = "poster_updates"
    }
}
