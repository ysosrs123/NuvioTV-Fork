package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.RecordingUpload
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FakeMediaFiles(private val root: File) : IptvMediaFiles {
    class Row(val id: Long, val name: String, var pending: Boolean, var owned: Boolean = true)
    val rows = LinkedHashMap<Long, Row>()
    var next = 100L
    var free = Long.MAX_VALUE
    var full = false
    var mounted = true
    var failPublish = false

    fun bytes(id: Long): ByteArray? = File(root, "$id").takeIf { it.isFile }?.readBytes()
    fun fill(id: Long, data: ByteArray) = File(root, "$id").writeBytes(data)
    fun visible(): List<Row> = rows.values.filter { !it.pending }

    @Synchronized override fun entries(name: String?): List<IptvMediaEntry> =
        rows.values.filter { it.owned && (name == null || it.name == name) }.map { IptvMediaEntry(it.id, it.name, it.pending) }
    @Synchronized override fun present(ids: Collection<Long>): Set<Long> = ids.filter { id -> rows[id]?.let { it.owned && !it.pending } == true }.toSet()
    @Synchronized override fun size(id: Long): Long? = rows[id]?.takeIf { it.owned }?.let { File(root, "$id").length() }
    @Synchronized override fun create(name: String): Long {
        if (!mounted) throw IllegalStateException("Volume not mounted")
        val id = next++
        rows[id] = Row(id, name, true)
        File(root, "$id").writeBytes(ByteArray(0))
        return id
    }
    @Synchronized override fun publish(id: Long) {
        if (failPublish) throw IOException("publish failed")
        val row = rows[id] ?: throw IOException("missing")
        row.pending = false
    }
    override fun openWrite(id: Long): FileChannel {
        if (full) throw IOException("write failed: ENOSPC (No space left on device)")
        return RandomAccessFile(File(root, "$id"), "rw").channel
    }
    override fun openRead(id: Long): FileChannel {
        val row = synchronized(this) { rows[id] } ?: throw FileNotFoundException("missing")
        if (!row.owned) throw SecurityException("not owned")
        return RandomAccessFile(File(root, "$id"), "r").channel
    }
    @Synchronized override fun delete(id: Long): Boolean {
        val row = rows[id] ?: return false
        if (!row.owned) throw SecurityException("not owned")
        rows.remove(id)
        File(root, "$id").delete()
        return true
    }
    override fun freeBytes(): Long = free
    override fun mounted(): Boolean = mounted
}

class IptvMediaStoreTargetTest {
    @get:Rule val temp = TemporaryFolder()

    private fun bytes(size: Int, seed: Int) = ByteArray(size) { ((it * 17 + seed) % 251).toByte() }
    private fun spool(vararg pieces: ByteArray): File {
        val dir = temp.newFolder()
        var start = 0L
        pieces.forEach { File(dir, RecordingUpload.spoolName(start)).writeBytes(it); start += it.size }
        return dir
    }
    private fun uploader(connector: IptvShareConnector, clock: AtomicLong = AtomicLong()) =
        IptvRecordingUploader(connector, pause = { clock.addAndGet(it) }, now = { clock.get() }, chunkBytes = 64, minimumFreeBytes = 0)

    @Test fun finishedSpoolIsCopiedAsOnePendingEntryThenPublished() = runBlocking {
        val files = FakeMediaFiles(temp.newFolder())
        val target = IptvMediaStoreTarget(files)
        val a = bytes(300, 1)
        val b = bytes(77, 2)
        val dir = spool(a, b)
        val connector = target.connector()
        val result = uploader(connector).upload(dir, "News - 2026-10-07 1100 - 0f3c9a2e.ts", { Long.MAX_VALUE }, { true }, 60_000)
        assertEquals(IptvUploadOutcome(IptvUploadResult.DONE, 377), result)
        val id = requireNotNull(connector.published)
        assertEquals(listOf("News - 2026-10-07 1100 - 0f3c9a2e.ts"), files.visible().map { it.name })
        assertEquals(id, files.visible().single().id)
        assertArrayEquals(a + b, files.bytes(id))
        assertFalse(dir.exists())
        assertEquals(setOf(id), target.present(listOf(id, 999L)))
        target.reader(id)!!.use { reader ->
            assertFalse(reader.network)
            assertEquals(377L, reader.length())
            val buffer = ByteArray(10)
            assertEquals(10, reader.read(295, buffer, 0, 10))
            assertArrayEquals(a.copyOfRange(295, 300) + b.copyOfRange(0, 5), buffer)
            assertEquals(-1, reader.read(377, buffer, 0, 10))
        }
    }

    @Test fun waitsForTheRecordingToFinishBeforeCopying() = runBlocking {
        val files = FakeMediaFiles(temp.newFolder())
        val dir = spool(bytes(200, 3))
        var polls = 0
        val result = IptvRecordingUploader(IptvMediaStoreTarget(files).connector(), pause = { polls += 1 }, chunkBytes = 64, minimumFreeBytes = 0)
            .upload(dir, "a.ts", { 200 }, { polls >= 3 }, 60_000)
        assertEquals(IptvUploadResult.DONE, result.result)
        assertTrue(polls >= 3)
        assertEquals(1, files.rows.size)
    }

    @Test fun fullStorageKeepsTheSpoolAndLeavesNoPendingEntry() = runBlocking {
        val files = FakeMediaFiles(temp.newFolder()).apply { free = 100 }
        val dir = spool(bytes(400, 4))
        val clock = AtomicLong()
        val result = uploader(IptvMediaStoreTarget(files).connector(), clock).upload(dir, "a.ts", { Long.MAX_VALUE }, { true }, 30_000)
        assertEquals(IptvUploadResult.PENDING, result.result)
        assertEquals(IptvShareError.FULL, result.error)
        assertTrue(File(dir, RecordingUpload.spoolName(0)).exists())
        assertTrue(files.rows.isEmpty())
        files.free = Long.MAX_VALUE
        files.full = true
        val second = uploader(IptvMediaStoreTarget(files).connector(), clock).upload(dir, "a.ts", { Long.MAX_VALUE }, { true }, 0)
        assertEquals(IptvShareError.FULL, second.error)
        assertTrue(files.rows.isEmpty())
        files.full = false
        val connector = IptvMediaStoreTarget(files).connector()
        val third = uploader(connector, clock).upload(dir, "a.ts", { Long.MAX_VALUE }, { true }, 0)
        assertEquals(IptvUploadOutcome(IptvUploadResult.DONE, 400), third)
        assertEquals(listOf(connector.published), files.visible().map { it.id })
    }

    @Test fun failedPublishRetriesAndStaleUnpublishedEntriesAreReplaced() = runBlocking {
        val files = FakeMediaFiles(temp.newFolder()).apply { failPublish = true }
        files.rows[7] = FakeMediaFiles.Row(7, "a.ts", true)
        val dir = spool(bytes(150, 5))
        val clock = AtomicLong()
        val first = uploader(IptvMediaStoreTarget(files).connector(), clock).upload(dir, "a.ts", { Long.MAX_VALUE }, { true }, 10_000)
        assertEquals(IptvUploadResult.PENDING, first.result)
        assertTrue(files.rows.isEmpty())
        files.failPublish = false
        val connector = IptvMediaStoreTarget(files).connector()
        assertEquals(IptvUploadResult.DONE, uploader(connector, clock).upload(dir, "a.ts", { Long.MAX_VALUE }, { true }, 0).result)
        assertEquals(1, files.rows.size)
        assertFalse(files.rows.values.single().pending)
    }

    @Test fun copyThatFinishedBeforeARestartIsNotRepeated() = runBlocking {
        val files = FakeMediaFiles(temp.newFolder())
        val data = bytes(120, 6)
        val dir = spool(data)
        val first = IptvMediaStoreTarget(files).connector()
        assertEquals(IptvUploadResult.DONE, uploader(first).upload(dir, "a.ts", { Long.MAX_VALUE }, { true }, 0).result)
        val again = spool(data)
        val second = IptvMediaStoreTarget(files).connector()
        assertEquals(IptvUploadOutcome(IptvUploadResult.DONE, 120), uploader(second).upload(again, "a.ts", { Long.MAX_VALUE }, { true }, 0))
        assertEquals(first.published, second.published)
        assertEquals(1, files.rows.size)
        assertFalse(again.exists())
    }

    @Test fun missingVolumeIsRetriedLikeAnUnreachableShare() = runBlocking {
        val files = FakeMediaFiles(temp.newFolder()).apply { mounted = false }
        val target = IptvMediaStoreTarget(files)
        assertFalse(target.mounted())
        val dir = spool(bytes(50, 7))
        val result = uploader(target.connector()).upload(dir, "a.ts", { Long.MAX_VALUE }, { true }, 0)
        assertEquals(IptvUploadResult.PENDING, result.result)
        assertEquals(IptvShareError.UNREACHABLE, result.error)
        assertTrue(dir.exists())
    }

    @Test fun unavailableBelowAndroid10() = runBlocking {
        val target = IptvMediaStoreTarget(null)
        assertFalse(target.available)
        assertFalse(target.mounted())
        assertEquals(0L, target.freeBytes())
        assertNull(target.reader(5))
        assertTrue(target.present(listOf(5L)).isEmpty())
        assertEquals(IptvMediaDelete.FAILED, target.delete(5))
        val dir = spool(bytes(10, 8))
        val result = uploader(target.connector()).upload(dir, "a.ts", { Long.MAX_VALUE }, { true }, 0)
        assertEquals(IptvShareError.UNREACHABLE, result.error)
    }

    @Test fun deleteReportsEntriesOwnedByAnEarlierInstall() {
        val files = FakeMediaFiles(temp.newFolder())
        val target = IptvMediaStoreTarget(files)
        val mine = files.create("a.ts").also { files.publish(it) }
        val old = files.create("b.ts").also { files.publish(it); files.rows[it]!!.owned = false }
        assertEquals(IptvMediaDelete.DELETED, target.delete(mine))
        assertEquals(IptvMediaDelete.GONE, target.delete(mine))
        assertEquals(IptvMediaDelete.NOT_OWNED, target.delete(old))
        assertNull(target.reader(old))
        assertTrue(target.present(listOf(old)).isEmpty())
    }

    @Test fun readerTurnsLostAccessIntoAnIoError() {
        val files = FakeMediaFiles(temp.newFolder())
        val id = files.create("a.ts")
        files.publish(id)
        files.fill(id, bytes(20, 9))
        val reader = IptvMediaStoreTarget(files).reader(id)!!
        files.rows[id]!!.owned = false
        try { reader.length(); fail() } catch (_: IOException) { }
        reader.close()
    }
}
