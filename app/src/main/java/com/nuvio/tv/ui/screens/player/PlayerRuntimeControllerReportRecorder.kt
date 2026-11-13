package com.nuvio.tv.ui.screens.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.nuvio.tv.core.player.BufferBudgetControl
import com.nuvio.tv.core.report.PlaybackRecorder
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails
import com.nuvio.tv.core.report.RECORDER_UNKNOWN
import com.nuvio.tv.core.report.ReportBusy
import com.nuvio.tv.core.report.SampleExtras
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
private val recordedFrameListener =VideoFrameMetadataListener { _, _, _, _ -> PlaybackRecorder.onFrame() }

@OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.recordExoSample(player: ExoPlayer, positionMs: Long) {
    PlaybackRecorder.notePosition(positionMs)
    if (!PlaybackRecorder.isSampleDue(currentPlayerSettingsForReport.reportRecorderEnabled)) return
    PlaybackRecorder.checkRecordingLimits()
    if (PlaybackRecorder.isRecording) attachFrameListener(player)
    if (!player.playWhenReady) {
        reportRecordingEnded(PlaybackRecorder.takeAutoStop())
        return
    }
    val tunnelled = player.isTunnelingEnabled
    val counters = if (tunnelled) null else player.videoDecoderCounters?.also { it.ensureUpdated() }
    val dropped = counters?.droppedBufferCount ?: RECORDER_UNKNOWN
    val sink = playbackSpeedAwareAudioSink
    val jitter = sink?.audioClockJitter()
    val clock = sink?.iecClockReading()
    PlaybackRecorder.sample(
        engine = PlaybackRecorder.ENGINE_EXO,
        readyAheadMs = player.bufferedPosition - positionMs,
        loaderActive = if (player.isLoading) 1 else 0,
        droppedFrames = dropped,
        audioUnderruns = sink?.iecUnderruns() ?: playbackAnalyticsDiagnostics.audioUnderrunTotal(),
        bufferCapBytes = (_loadControl as? BufferBudgetControl)?.allocatorTargetBytes()?.toLong() ?: -1L,
        tunnelled = tunnelled,
        playing = player.isPlaying,
        extras = SampleExtras(
            skippedFrames = counters?.skippedOutputBufferCount ?: RECORDER_UNKNOWN,
            frameLeadUs = counters?.let {
                PlaybackRecorder.frameLeadUs(it.totalVideoFrameProcessingOffsetUs, it.videoFrameProcessingOffsetCount)
            } ?: RECORDER_UNKNOWN,
            audioJitterAvgMs = jitter?.meanAbsMs?.toInt() ?: RECORDER_UNKNOWN,
            audioJitterMaxMs = jitter?.maxAbsMs?.toInt() ?: RECORDER_UNKNOWN,
            fillerCount = sink?.iecFillerWhilePlaying()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: RECORDER_UNKNOWN,
            audioPositionMs = sink?.lastAudioPositionMs()?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: RECORDER_UNKNOWN,
            speedPercent = (player.playbackParameters.speed * 100f).roundToInt(),
            connections = PlaybackLanes.current()?.connections ?: RECORDER_UNKNOWN,
            audioQueueMs = sink?.takeIecQueueLowMs() ?: RECORDER_UNKNOWN,
            busyFlags = ReportBusy.flags(SeekThumbnails.memoryFloorBytes() > 0L),
            audioHeadMs = clock?.headMs ?: RECORDER_UNKNOWN,
            audioLatencyMs = clock?.latencyMs ?: RECORDER_UNKNOWN,
            audioClockSource = clock?.source ?: RECORDER_UNKNOWN,
            audioWriteStalls = clock?.writeStalls ?: RECORDER_UNKNOWN
        )
    )
    reportRecordingEnded(PlaybackRecorder.takeAutoStop())
}

@OptIn(UnstableApi::class)
private fun PlayerRuntimeController.attachFrameListener(player: ExoPlayer) {
    if (frameListenerPlayer === player) return
    runCatching { player.setVideoFrameMetadataListener(recordedFrameListener) }
    frameListenerPlayer = player
}

@OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.detachFrameListener() {
    val player = frameListenerPlayer as? ExoPlayer ?: return
    frameListenerPlayer = null
    runCatching { player.clearVideoFrameMetadataListener(recordedFrameListener) }
}

internal fun PlayerRuntimeController.recordMpvSample(positionMs: Long, cacheAheadMs: Long, playing: Boolean, stalled: Boolean) {
    PlaybackRecorder.notePosition(positionMs)
    PlaybackRecorder.noteMpvState(playing, stalled)
    if (!PlaybackRecorder.isSampleDue(currentPlayerSettingsForReport.reportRecorderEnabled)) return
    PlaybackRecorder.checkRecordingLimits()
    if (!playing && !stalled) {
        reportRecordingEnded(PlaybackRecorder.takeAutoStop())
        return
    }
    PlaybackRecorder.sample(
        engine = PlaybackRecorder.ENGINE_MPV,
        readyAheadMs = cacheAheadMs,
        loaderActive = RECORDER_UNKNOWN,
        droppedFrames = RECORDER_UNKNOWN,
        audioUnderruns = RECORDER_UNKNOWN,
        bufferCapBytes = -1L,
        tunnelled = false,
        playing = playing && !stalled
    )
    reportRecordingEnded(PlaybackRecorder.takeAutoStop())
}
