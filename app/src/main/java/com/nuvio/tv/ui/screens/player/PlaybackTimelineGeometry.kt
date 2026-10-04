package com.nuvio.tv.ui.screens.player

/** Fractions for a media timeline; a preview never manufactures buffered media. */
internal data class PlaybackTimelineGeometry(
    val played: Float,
    val preview: Float,
    val buffered: Float,
    val hasDuration: Boolean,
    val isPreviewing: Boolean
) {
    companion object {
        fun from(playbackPosition: Long, previewPosition: Long, bufferedPosition: Long, duration: Long): PlaybackTimelineGeometry {
            if (duration <= 0) return PlaybackTimelineGeometry(0f, 0f, 0f, false, false)
            fun fraction(position: Long) = (position.coerceIn(0L, duration).toDouble() / duration).toFloat()
            val played = fraction(playbackPosition)
            return PlaybackTimelineGeometry(played, fraction(previewPosition),
                fraction(bufferedPosition).coerceAtLeast(played), true,
                previewPosition.coerceIn(0L, duration) != playbackPosition.coerceIn(0L, duration))
        }
    }
}
