package com.nuvio.tv.core.player.thumbnail

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Backs the "Clear saved thumbnails" settings row. Storage itself is [ThumbStore]. */
class ThumbnailCache private constructor() {
    companion object {
        suspend fun clearAll(context: Context): Boolean = withContext(Dispatchers.IO) {
            // Background passes must have ended before the files go.
            SeekThumbnails.stopForClear()
            NextEpisodeThumbs.stopAndJoinAll()
            val ok = runCatching { ThumbStore.clearAll(context) }.getOrDefault(false)
            NextEpisodeThumbs.forgetAttempts()
            Log.i("ThumbCache", "clearAll: deleted=$ok")
            ok
        }
    }
}
