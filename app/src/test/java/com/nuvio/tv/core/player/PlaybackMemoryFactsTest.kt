package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultAllocator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackMemoryFactsTest {
    private val mb = 1024L * 1024L

    @After fun tearDown() = PlaybackMemoryFacts.clear()

    @Test fun nothingIsWrittenWithoutAPlayer() {
        PlaybackMemoryFacts.clear()
        PlaybackMemoryFacts.onVideoFormat("video/hevc", 3840, 2160)
        assertTrue(PlaybackMemoryFacts.lines().isEmpty())
    }

    @Test fun exoLinesNameTheFormatsAndTheBuffer() {
        val player = PlaybackMemoryFacts.Player(
            engine = "ExoPlayer", nativeMemory = true, buffer = "automatic",
            video = "hevc 3840x2160", videoDecoder = "hw", audio = "eac3", audioOut = "passthrough",
        )
        val buffer = PlaybackMemoryFacts.Buffer(
            budgetMb = 380, targetMb = 228, heldMb = 210, footprintMb = 240,
            pressure = BufferMemoryKeeper.LEVEL_LOW, pressureForS = 45, grownMb = 0, lentMb = 0,
        )
        assertEquals(
            listOf(
                "Player: ExoPlayer, native memory on, video hevc 3840x2160 hw, audio eac3 passthrough",
                "Buffer: automatic, budget 380 MB, target 228 MB, held 210 of 240 MB, pressure LOW for 45 s",
            ),
            PlaybackMemoryFacts.format(player, buffer)
        )
    }

    @Test fun mpvHasNoBufferLine() {
        assertEquals(
            listOf("Player: MPV (hwdec auto_safe)"),
            PlaybackMemoryFacts.format(PlaybackMemoryFacts.Player("MPV", mpvDecode = "auto_safe"), null)
        )
    }

    @Test fun codecsAndDecodersAreShort() {
        assertEquals("hevc", PlaybackMemoryFacts.codec("video/hevc"))
        assertEquals("matroska", PlaybackMemoryFacts.codec("video/x-matroska"))
        assertEquals("true-hd", PlaybackMemoryFacts.codec("audio/true-hd"))
        assertEquals("unknown", PlaybackMemoryFacts.codec(null))
        assertEquals("sw dav1d", PlaybackMemoryFacts.videoDecoderLabel("libdav1d", true))
        assertEquals("sw ffmpeg", PlaybackMemoryFacts.videoDecoderLabel("ffmpegVideo", true))
        assertEquals("sw", PlaybackMemoryFacts.videoDecoderLabel("c2.android.hevc.decoder", true))
        assertEquals("hw", PlaybackMemoryFacts.videoDecoderLabel("OMX.amlogic.hevc.decoder.awesome2", false))
    }

    @Test fun audioDecodedInFfmpegStaysNamedWhenTheOutputIsPcm() {
        PlaybackMemoryFacts.onExoBuilt(nativeMemory = false, buffer = "stock", allocator = null)
        PlaybackMemoryFacts.onAudioFormat("audio/vnd.dts.hd")
        PlaybackMemoryFacts.onAudioDecoder("ffmpegLib-dca")
        PlaybackMemoryFacts.onAudioOutput(null)
        assertEquals(listOf("Player: ExoPlayer, native memory off, audio vnd.dts.hd ffmpeg"), PlaybackMemoryFacts.lines())
        PlaybackMemoryFacts.onAudioOutput("ac3 transcode")
        assertEquals(listOf("Player: ExoPlayer, native memory off, audio vnd.dts.hd ac3 transcode"), PlaybackMemoryFacts.lines())
    }

    @Test fun theBufferIsReadFromTheKeeperAndTheAllocator() {
        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE, 0, false)
        val control = BitrateAwareLoadControl(
            minBufferMs = 15_000,
            maxBufferMs = 45_000,
            bufferForPlaybackMs = 5_000,
            bufferForPlaybackAfterRebufferMs = 3_000,
            prioritizeTimeOverSizeThresholds = true,
            backBufferDurationMs = 0,
            retainBackBufferFromKeyframe = true,
            budgetBytes = 300 * mb,
            allocator = allocator,
        )
        val keeper = BufferMemoryKeeper(control, { null })
        PlaybackMemoryFacts.onExoBuilt(nativeMemory = false, buffer = "custom", allocator = allocator)
        PlaybackMemoryFacts.onKeeper(keeper)
        PlaybackMemoryFacts.onVideoFormat("video/avc", 1920, 1080)
        PlaybackMemoryFacts.onVideoDecoder("OMX.amlogic.avc.decoder.awesome2", software = false)
        val held = List(32) { allocator.allocate() }
        val lines = PlaybackMemoryFacts.lines()
        assertEquals("Player: ExoPlayer, native memory off, video avc 1920x1080 hw", lines[0])
        assertTrue(lines[1], lines[1].startsWith("Buffer: custom, budget 300 MB, held 2 of "))
        assertTrue(lines[1], lines[1].endsWith(" MB, pressure NONE"))
        held.forEach(allocator::release)
    }

    @Test fun aRebuiltPlayerDropsTheOldFormats() {
        PlaybackMemoryFacts.onExoBuilt(nativeMemory = true, buffer = "automatic", allocator = null)
        PlaybackMemoryFacts.onVideoFormat("video/hevc", 3840, 2160)
        PlaybackMemoryFacts.onExoBuilt(nativeMemory = true, buffer = "automatic", allocator = null)
        assertEquals(listOf("Player: ExoPlayer, native memory on"), PlaybackMemoryFacts.lines())
    }
}
