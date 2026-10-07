package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.LocalTimeshiftFailure
import com.nuvio.tv.core.iptv.LocalTimeshiftLength
import com.nuvio.tv.core.iptv.LocalTimeshiftPolicy
import com.nuvio.tv.core.iptv.LocalTimeshiftRing
import com.nuvio.tv.core.iptv.LocalTimeshiftSizing
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

data class IptvLocalTimeshiftConfig(val directory: File, val length: LocalTimeshiftLength)

interface IptvLocalTimeshiftInput {
    fun open(): InputStream
    fun cancel()
    fun release()
}

class IptvLocalTimeshiftSession private constructor(val id: String, private val ring: IptvLocalTimeshiftRing,
    private val length: LocalTimeshiftLength, private val usable: () -> Long, private val clock: () -> Long) {
    @Volatile var failure: LocalTimeshiftFailure? = null
        private set
    @Volatile var running = false
        private set
    @Volatile private var stopped = false
    @Volatile private var input: IptvLocalTimeshiftInput? = null
    private var writer: Thread? = null

    val head: Long get() = ring.head

    fun start(source: IptvLocalTimeshiftInput, onEnd: (LocalTimeshiftFailure?) -> Unit) = synchronized(this) {
        check(writer == null && !stopped)
        input = source
        running = true
        writer = thread(name = "IptvTimeshift", isDaemon = true) {
            var reason: LocalTimeshiftFailure? = null
            try {
                val stream = try { source.open() } catch (_: Exception) { null }
                if (stream == null) reason = if (stopped) null else LocalTimeshiftFailure.START
                else stream.use {
                    reason = try { IptvLocalTimeshiftWriter(ring, length, usable).run(it) { stopped } }
                        catch (_: Exception) { if (stopped) null else LocalTimeshiftFailure.STORAGE }
                }
            } catch (_: Exception) {
                if (!stopped && reason == null) reason = LocalTimeshiftFailure.NETWORK
            } finally {
                running = false
                try { source.release() } catch (_: Exception) { }
                reason?.let { failure = it; ring.fail(it) }
                if (!stopped) onEnd(reason)
            }
        }
    }

    fun stop() {
        stopped = true
        try { input?.cancel() } catch (_: Exception) { }
    }

    fun awaitStopped(timeoutMillis: Long): Boolean {
        val worker = synchronized(this) { writer } ?: return true
        try { worker.join(timeoutMillis) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        return !worker.isAlive
    }

    fun close() {
        stop()
        ring.close()
        active.remove(id)
    }

    fun oldest(): Long = ring.oldest()

    fun liveAnchor(): Long {
        val oldest = ring.oldest()
        val target = ring.index.offsetAt(clock() - LocalTimeshiftPolicy.LIVE_PREROLL_MILLIS, oldest) ?: oldest
        return LocalTimeshiftRing.clamp(target, ring.head, ring.capacity)
    }

    fun anchorAt(timeMillis: Long): Long {
        val oldest = ring.oldest()
        return LocalTimeshiftRing.clamp(ring.index.offsetAt(timeMillis, oldest) ?: oldest, ring.head, ring.capacity)
    }

    fun timeAt(offset: Long): Long? = ring.index.timeAt(offset)

    fun oldestTime(): Long? = ring.index.oldestTime(ring.oldest())

    fun newestTime(): Long? = ring.index.newestTime()

    fun read(at: Long, target: ByteArray, offset: Int, length: Int, stallMillis: Long = STALL_MILLIS): Int {
        val deadline = System.nanoTime() / 1_000_000 + stallMillis
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            val count = ring.read(at, target, offset, length, WAIT_MILLIS)
            if (count > 0) return count
            if (System.nanoTime() / 1_000_000 >= deadline) throw IptvLocalTimeshiftStalledException()
        }
    }

    fun uri(anchor: Long): String = "$SCHEME://$id/$anchor"

    companion object {
        const val SCHEME = "nuvio-timeshift"
        const val STALL_MILLIS = 15_000L
        private const val WAIT_MILLIS = 250L
        private const val PREFIX = "ring-"
        private const val SUFFIX = ".ts"
        private val active: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun create(config: IptvLocalTimeshiftConfig, usable: (File) -> Long = { it.usableSpace },
            clock: () -> Long = System::currentTimeMillis): IptvLocalTimeshiftSession {
            val directory = config.directory
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Local timeshift folder unavailable")
            val provisional = LocalTimeshiftSizing.provisional(usable(directory)) ?: throw IOException("Local timeshift has no room")
            val id = UUID.randomUUID().toString().replace("-", "")
            active += id
            return try {
                IptvLocalTimeshiftSession(id, IptvLocalTimeshiftRing(File(directory, PREFIX + id + SUFFIX), provisional, clock), config.length,
                    { usable(directory) }, clock)
            } catch (error: Exception) { active.remove(id); throw error }
        }

        fun parse(uri: String): Pair<String, Long>? {
            val rest = uri.removePrefix("$SCHEME://").takeIf { it != uri } ?: return null
            val id = rest.substringBefore('/')
            val anchor = rest.substringAfter('/', "").toLongOrNull()?.takeIf { it >= 0 } ?: return null
            return id.takeIf { it.isNotEmpty() }?.let { it to anchor }
        }

        fun sweep(directories: List<File>): Int = directories.sumOf { directory ->
            (directory.listFiles() ?: emptyArray()).count { file ->
                val name = file.name
                file.isFile && name.startsWith(PREFIX) && name.endsWith(SUFFIX) &&
                    name.removePrefix(PREFIX).removeSuffix(SUFFIX) !in active && file.delete()
            }
        }
    }
}
