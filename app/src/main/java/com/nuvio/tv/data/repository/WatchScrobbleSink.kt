package com.nuvio.tv.data.repository

/** A destination for playback scrobbles; implementations gate themselves on their own sign-in state. */
interface WatchScrobbleSink {

    /** Human-readable sink name, used in fan-out log messages. */
    val sinkName: String

    /** Reports that playback has begun, or resumed after a seek. */
    suspend fun scrobbleStart(item: TraktScrobbleItem, progressPercent: Float)

    /**
     * Reports that playback stopped, paused, finished or was exited. Both
     * backends treat a stop at or above their watched threshold as a completed
     * view, and anything below it as a resumable position.
     */
    suspend fun scrobbleStop(item: TraktScrobbleItem, progressPercent: Float)
}
