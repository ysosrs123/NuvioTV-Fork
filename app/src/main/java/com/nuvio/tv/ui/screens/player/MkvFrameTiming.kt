@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsExtractor
import com.nuvio.tv.core.player.FrameRateUtils
import com.nuvio.tv.core.player.HdrColorSignalingExtractor
import com.nuvio.tv.core.player.dvmkv.MatroskaExtractor as DvMatroskaExtractor
import java.io.IOException
import kotlin.math.abs

/**
 * Video samples timed for an MKV that states no frame rate. With millisecond times about 100 gaps over 4.5 s or more
 * tell 23.976 from 24 and 59.94 from 60.
 */
internal const val MKV_FRAME_TIMING_SAMPLES = 120
internal const val MKV_FRAME_TIMING_MIN_SPAN_US = 4_500_000L
private const val MKV_FRAME_TIMING_MAX_SAMPLES = 480
private const val SKIP_LOWEST = 3
private const val SKIP_HIGHEST = 16

/** Matroska stores times in whole milliseconds, so single gaps of 41 and 42 ms both belong to 23.976. */
private const val GAP_TOLERANCE_US = 1_500f
private const val MIN_STEADY_SHARE = 0.9f

/**
 * The frame rate from the times of the first video samples, or null when they do not show one steady rate
 * (variable frame rate, gaps, or too few samples). Samples come in decode order.
 *
 * Interlaced TS often carries the two fields of a frame as two samples with the same time. Those count once, and the
 * result is the field rate (50 for 1080i50), which is the rate the picture moves at.
 */
internal fun frameRateFromSampleTimes(timestampsUs: List<Long>): Float? {
    val distinct = timestampsUs.distinct()
    val fieldPairs = timestampsUs.size - distinct.size >= timestampsUs.size * 2 / 5
    val fps = steadyRateFromSampleTimes(distinct) ?: return null
    return (if (fieldPairs) fps * 2 else fps).takeIf { it in 10f..121f }
}

private fun steadyRateFromSampleTimes(timestampsUs: List<Long>): Float? {
    val averageUs = FrameRateUtils.averageSampleDurationUs(timestampsUs, SKIP_LOWEST, SKIP_HIGHEST) ?: return null
    val sorted = timestampsUs.sorted()
    val last = sorted.size - 1 - SKIP_HIGHEST
    var steady = 0
    var gaps = 0
    for (i in SKIP_LOWEST + 1..last) {
        gaps++
        if (abs((sorted[i] - sorted[i - 1]) - averageUs) <= GAP_TOLERANCE_US) steady++
    }
    if (gaps == 0 || steady < gaps * MIN_STEADY_SHARE) return null
    return 1_000_000f / averageUs
}

internal fun timedFrameRateDetection(fps: Float, format: Format) = FrameRateUtils.FrameRateDetection(
    raw = fps,
    snapped = FrameRateUtils.snapToStandardRate(fps),
    videoWidth = format.width.takeIf { it > 0 },
    videoHeight = format.height.takeIf { it > 0 }
)

/** A fresh timing for the player being built; a rate from an older player is dropped. */
internal fun PlayerRuntimeController.startMkvFrameTiming(): (Float) -> Unit {
    val token = ++mkvFrameTimingToken
    mkvTimedFrameRate.value = null
    return { fps -> if (token == mkvFrameTimingToken) mkvTimedFrameRate.value = fps }
}

/**
 * Times the first video samples of MKV and TS files that state no frame rate, on the playback stream itself, and hands
 * the rate to [onMeasured] once. Only the Matroska and TS extractors are wrapped; everything is forwarded unchanged.
 */
internal class MkvFrameTimingExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val onMeasured: (Float) -> Unit
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    private fun wrap(extractor: Extractor): Extractor =
        if (extractor is DvMatroskaExtractor || extractor is HdrColorSignalingExtractor ||
            extractor.underlyingImplementation is TsExtractor
        ) {
            MkvFrameTimingExtractor(extractor, onMeasured)
        } else {
            extractor
        }
}

private class MkvFrameTimingExtractor(
    private val delegate: Extractor,
    private val onMeasured: (Float) -> Unit
) : RewrappableExtractor {
    private var timing: MkvFrameTimingTrackOutput? = null

    override val wrappedExtractor: Extractor get() = delegate
    override fun rewrap(inner: Extractor): Extractor = MkvFrameTimingExtractor(inner, onMeasured)

    override fun init(output: ExtractorOutput) = delegate.init(object : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput {
            val track = output.track(id, type)
            if (type != C.TRACK_TYPE_VIDEO || timing != null) return track
            return MkvFrameTimingTrackOutput(track, onMeasured).also { timing = it }
        }

        override fun endTracks() = output.endTracks()
        override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
    })

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        timing?.restart()
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()
    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
    override fun getSniffFailureDetails() = delegate.sniffFailureDetails
}

internal class MkvFrameTimingTrackOutput(
    private val delegate: TrackOutput,
    private val onMeasured: (Float) -> Unit
) : TrackOutput by delegate {
    private var timing = false
    private var reported = false
    private val times = ArrayList<Long>(MKV_FRAME_TIMING_SAMPLES)
    private var minUs = Long.MAX_VALUE
    private var maxUs = Long.MIN_VALUE

    override fun format(format: Format) {
        timing = !reported && needsProbeForUndeclaredRate(format.frameRate, format.containerMimeType)
        if (!timing) restart()
        delegate.format(format)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        if (!timing) return
        times.add(timeUs)
        if (timeUs < minUs) minUs = timeUs
        if (timeUs > maxUs) maxUs = timeUs
        val enough = times.size >= MKV_FRAME_TIMING_SAMPLES && maxUs - minUs >= MKV_FRAME_TIMING_MIN_SPAN_US
        if (!enough && times.size < MKV_FRAME_TIMING_MAX_SAMPLES) return
        timing = false
        reported = true
        val fps = frameRateFromSampleTimes(times)
        restart()
        if (fps != null) onMeasured(fps)
    }

    /** A seek breaks the run of samples; timing starts over from the new position. */
    fun restart() {
        times.clear()
        minUs = Long.MAX_VALUE
        maxUs = Long.MIN_VALUE
    }
}
