package com.nuvio.tv.core.iptv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CapturePlaybackConsumer(private val source: OwnedCaptureConsumer,
    private val player: OwnedCaptureConsumer) : OwnedCaptureConsumer {
    override val minimumMemoryReservationBytes = Math.addExact(source.minimumMemoryReservationBytes, player.minimumMemoryReservationBytes)
    override val minimumDecoderReservationCount = Math.addExact(source.minimumDecoderReservationCount, player.minimumDecoderReservationCount)
    init {
        require(source !== player)
        require(source.minimumMemoryReservationBytes >= 0 && player.minimumMemoryReservationBytes >= 0)
        require(source.minimumDecoderReservationCount >= 0 && player.minimumDecoderReservationCount > 0)
    }
    private val closeMutex = Mutex()
    private var started = false
    private var stopping = false
    private var playerClosed = false
    private var sourceClosed = false
    @Synchronized override fun start() {
        check(!started && !stopping); started = true
        source.start()
        player.start()
    }
    override suspend fun close(): Boolean = closeMutex.withLock {
        synchronized(this) { stopping = true }
        if (!playerClosed) playerClosed = confirmed(player)
        if (!playerClosed) return@withLock false
        if (!sourceClosed) sourceClosed = confirmed(source)
        sourceClosed
    }
    private suspend fun confirmed(owner: OwnedCaptureConsumer): Boolean = try { owner.close() }
    catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { false }
}
