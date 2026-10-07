package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.RecordingUpload
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FakeShare : IptvShareConnector {
    val files = LinkedHashMap<String, ByteArray>()
    val folders = HashSet<String>()
    @Volatile var down = false
    @Volatile var writesBeforeDrop = -1
    @Volatile var free = Long.MAX_VALUE
    @Volatile var readOnly = false
    @Volatile var refusal: IptvShareError? = null
    var connects = 0
    var flushes = 0

    @Synchronized override fun connect(): IptvShareSession {
        connects += 1
        refusal?.let { throw IptvShareException(it) }
        if (down) throw IptvShareException(IptvShareError.UNREACHABLE)
        return Session()
    }

    @Synchronized fun text(path: String): ByteArray? = files[path]?.copyOf()

    private fun check() { if (down) throw IptvShareException(IptvShareError.DISCONNECTED) }

    private inner class Session : IptvShareSession {
        override fun length(path: String): Long? = synchronized(this@FakeShare) { check(); files[path]?.size?.toLong() }
        override fun openWrite(path: String): IptvShareFile = synchronized(this@FakeShare) {
            check()
            if (readOnly) throw IptvShareException(IptvShareError.ACCESS_DENIED)
            files.getOrPut(path) { ByteArray(0) }
            Handle(path)
        }
        override fun openRead(path: String): IptvShareFile = synchronized(this@FakeShare) {
            check()
            if (path !in files) throw IptvShareException(IptvShareError.FOLDER_NOT_FOUND)
            Handle(path)
        }
        override fun rename(from: String, to: String, replace: Boolean) = synchronized(this@FakeShare) {
            check()
            val data = files.remove(from) ?: throw IptvShareException(IptvShareError.FOLDER_NOT_FOUND)
            if (!replace && to in files) throw IptvShareException(IptvShareError.OTHER)
            files[to] = data
        }
        override fun delete(path: String): Boolean = synchronized(this@FakeShare) { check(); files.remove(path) != null }
        override fun list(folder: String): List<String> = synchronized(this@FakeShare) {
            check()
            if (folder.isNotEmpty() && folder !in folders) throw IptvShareException(IptvShareError.FOLDER_NOT_FOUND)
            files.keys.filter { it.substringBeforeLast('/', "") == folder }.map { it.substringAfterLast('/') }
        }
        override fun ensureFolder(folder: String) = synchronized(this@FakeShare) {
            check()
            if (readOnly && folder !in folders) throw IptvShareException(IptvShareError.ACCESS_DENIED)
            folders += folder
            Unit
        }
        override fun freeBytes(): Long = synchronized(this@FakeShare) { check(); free }
        override fun close() { }
    }

    private inner class Handle(private val path: String) : IptvShareFile {
        override val length: Long get() = synchronized(this@FakeShare) { check(); files[path]?.size?.toLong() ?: 0 }
        override fun write(offset: Long, buffer: ByteArray, start: Int, count: Int) = synchronized(this@FakeShare) {
            check()
            if (writesBeforeDrop == 0) { down = true; throw IptvShareException(IptvShareError.DISCONNECTED) }
            if (writesBeforeDrop > 0) writesBeforeDrop -= 1
            val current = files[path] ?: ByteArray(0)
            val next = current.copyOf(maxOf(current.size, (offset + count).toInt()))
            System.arraycopy(buffer, start, next, offset.toInt(), count)
            files[path] = next
        }
        override fun read(offset: Long, buffer: ByteArray, start: Int, count: Int): Int = synchronized(this@FakeShare) {
            check()
            val data = files[path] ?: return -1
            if (offset >= data.size) return -1
            val n = minOf(count.toLong(), data.size - offset).toInt()
            System.arraycopy(data, offset.toInt(), buffer, start, n)
            n
        }
        override fun flush() { synchronized(this@FakeShare) { check(); flushes += 1 } }
        override fun close() { }
    }
}

class IptvRecordingUploaderTest {
    @get:Rule val temp = TemporaryFolder()

    private fun bytes(size: Int, seed: Int) = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }
    private fun spool(vararg pieces: ByteArray): File {
        val dir = temp.newFolder()
        var start = 0L
        pieces.forEach { File(dir, RecordingUpload.spoolName(start)).writeBytes(it); start += it.size }
        return dir
    }
    private fun uploader(share: FakeShare, clock: AtomicLong = AtomicLong(), chunk: Int = 64, free: (Long) -> Unit = { }) =
        IptvRecordingUploader(share, pause = { clock.addAndGet(it) }, now = { clock.get() }, chunkBytes = chunk, minimumFreeBytes = 0, onFree = free)

    @Test fun finishedSpoolIsUploadedVerifiedRenamedAndRemoved() = runBlocking {
        val share = FakeShare()
        val a = bytes(300, 1)
        val b = bytes(120, 2)
        val dir = spool(a, b)
        var free = -1L
        val result = uploader(share, free = { free = it }).upload(dir, "TV/show.ts", { Long.MAX_VALUE }, { true }, 60_000)
        assertEquals(IptvUploadOutcome(IptvUploadResult.DONE, 420), result)
        assertArrayEquals(a + b, share.text("TV/show.ts"))
        assertNull(share.text("TV/show.ts.part"))
        assertTrue("TV" in share.folders)
        assertFalse(dir.exists())
        assertEquals(Long.MAX_VALUE, free)
        assertTrue(share.flushes >= 2)
    }

    @Test fun dropMidPieceResumesFromRemoteSizeAndKeepsUnconfirmedPieces() = runBlocking {
        val share = FakeShare()
        val a = bytes(256, 3)
        val b = bytes(200, 4)
        val dir = spool(a, b)
        share.writesBeforeDrop = 5
        val clock = AtomicLong()
        val first = uploader(share, clock).upload(dir, "show.ts", { Long.MAX_VALUE }, { true }, 0)
        assertEquals(IptvUploadResult.PENDING, first.result)
        assertEquals(IptvShareError.DISCONNECTED, first.error)
        assertEquals(320L, share.text("show.ts.part")!!.size.toLong())
        assertFalse(File(dir, RecordingUpload.spoolName(0)).exists())
        assertTrue(File(dir, RecordingUpload.spoolName(256)).exists())
        assertNull(share.text("show.ts"))
        share.down = false
        share.writesBeforeDrop = -1
        val second = uploader(share, clock).upload(dir, "show.ts", { Long.MAX_VALUE }, { true }, 0)
        assertEquals(IptvUploadOutcome(IptvUploadResult.DONE, 456), second)
        assertArrayEquals(a + b, share.text("show.ts"))
        assertFalse(dir.exists())
    }

    @Test fun reconnectsWithBackoffWhileRecordingAndGivesUpOnlyAfterPatience() = runBlocking {
        val share = FakeShare().apply { down = true }
        val dir = spool(bytes(100, 5))
        val clock = AtomicLong()
        val result = uploader(share, clock).upload(dir, "a.ts", { Long.MAX_VALUE }, { true }, 30_000)
        assertEquals(IptvUploadResult.PENDING, result.result)
        assertEquals(IptvShareError.UNREACHABLE, result.error)
        assertTrue(clock.get() >= 30_000)
        assertTrue(share.connects in 3..10)
        assertTrue(File(dir, RecordingUpload.spoolName(0)).exists())
    }

    @Test fun growingSpoolIsUploadedOnlyUpToCommittedBytes() = runBlocking {
        val share = FakeShare()
        val dir = temp.newFolder()
        val output = IptvRecordingOutput(dir, { _, start -> File(dir, RecordingUpload.spoolName(start)) }, 1_000)
        output.open()
        val committed = AtomicLong()
        val finished = AtomicBoolean(false)
        val expected = java.io.ByteArrayOutputStream()
        val uploader = IptvRecordingUploader(share, pause = { delay(1) }, chunkBytes = 128, pollMillis = 1, minimumFreeBytes = 0)
        withTimeout(20_000) {
            val job = async(Dispatchers.IO) { uploader.upload(dir, "live.ts", { committed.get() }, { finished.get() }, 60_000) }
            repeat(40) { round ->
                val chunk = bytes(188, round)
                output.write(chunk, 0, chunk.size, true)
                expected.write(chunk)
                val junk = bytes(50, 99)
                output.write(junk, 0, junk.size, false)
                assertTrue(output.rollback(output.bytes - junk.size))
                committed.set(output.bytes)
                delay(2)
            }
            output.close()
            finished.set(true)
            val result = job.await()
            assertEquals(IptvUploadOutcome(IptvUploadResult.DONE, 188L * 40), result)
        }
        assertArrayEquals(expected.toByteArray(), share.text("live.ts"))
        assertFalse(dir.exists())
    }

    @Test fun missingRemoteDataIsReportedLost() = runBlocking {
        val share = FakeShare()
        val dir = temp.newFolder()
        File(dir, RecordingUpload.spoolName(500)).writeBytes(bytes(100, 6))
        val result = uploader(share).upload(dir, "x.ts", { Long.MAX_VALUE }, { true }, 60_000)
        assertEquals(IptvUploadResult.LOST, result.result)
    }

    @Test fun completedUploadFromAnEarlierRunIsRecognised() = runBlocking {
        val share = FakeShare()
        val data = bytes(64, 7)
        share.files["x.ts"] = data
        val dir = spool(data)
        assertEquals(IptvUploadOutcome(IptvUploadResult.DONE, 64), uploader(share).upload(dir, "x.ts", { Long.MAX_VALUE }, { true }, 60_000))
        assertFalse(dir.exists())
        val empty = temp.newFolder()
        share.files["y.ts.part"] = data
        assertEquals(IptvUploadOutcome(IptvUploadResult.DONE, 64), uploader(share).upload(empty, "y.ts", { Long.MAX_VALUE }, { true }, 60_000))
        assertArrayEquals(data, share.text("y.ts"))
    }

    @Test fun nothingRecordedUploadsNothing() = runBlocking {
        val share = FakeShare()
        val result = uploader(share).upload(temp.newFolder(), "z.ts", { 0L }, { true }, 60_000)
        assertEquals(IptvUploadOutcome(IptvUploadResult.DONE, 0), result)
        assertEquals(0, share.connects)
        assertTrue(share.files.isEmpty())
    }

    @Test fun fullShareWaitsWithoutWriting() = runBlocking {
        val share = FakeShare().apply { free = 10 }
        val dir = spool(bytes(100, 8))
        val result = IptvRecordingUploader(share, pause = { }, now = { 0L }, minimumFreeBytes = 50).upload(dir, "f.ts", { Long.MAX_VALUE }, { true }, 0)
        assertEquals(IptvUploadResult.PENDING, result.result)
        assertEquals(IptvShareError.FULL, result.error)
        assertEquals(0, share.text("f.ts.part")!!.size)
    }
}
