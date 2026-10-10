package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.RecordingFiles
import com.nuvio.tv.core.iptv.RecordingParts
import com.nuvio.tv.core.iptv.RecordingPiece
import com.nuvio.tv.core.iptv.RecordingStorage
import com.nuvio.tv.core.iptv.RecordingUpload
import com.nuvio.tv.core.iptv.RecordingUploadStep
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class IptvUploadResult { DONE, PENDING, LOST }

data class IptvUploadOutcome(val result: IptvUploadResult, val bytes: Long, val error: IptvShareError? = null, val remote: String? = null)

class IptvRecordingUploader(
    private val connector: IptvShareConnector,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
    private val chunkBytes: Int = 1024 * 1024,
    private val pollMillis: Long = 1_000,
    private val minimumFreeBytes: Long = RecordingStorage.START_MARGIN_BYTES,
    private val onFree: (Long) -> Unit = { },
) {
    init { require(chunkBytes > 0 && pollMillis > 0 && minimumFreeBytes >= 0) }

    @Volatile var uploaded: Long = 0
        private set

    private class Local(val piece: RecordingPiece, val file: File)

    suspend fun upload(spool: File, remote: String, committed: () -> Long, finished: () -> Boolean,
        patienceMillis: Long): IptvUploadOutcome = withContext(Dispatchers.IO) {
        val partial = RecordingFiles.partial(remote)
        val buffer = ByteArray(chunkBytes)
        var failures = 0
        var failingSince: Long? = null
        var chosen: String? = null
        var lastError: IptvShareError? = null
        while (true) {
            val done = finished()
            if (committed() > 0L) break
            if (done) return@withContext IptvUploadOutcome(IptvUploadResult.DONE, 0)
            pause(pollMillis)
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            var session: IptvShareSession? = null
            var file: IptvShareFile? = null
            try {
                session = connector.connect()
                val folder = remote.substringBeforeLast('/', "")
                if (folder.isNotEmpty()) session.ensureFolder(folder)
                val initial = pieces(spool)
                if (session.length(partial) == null && finished()) {
                    val end = initial.maxOfOrNull { it.piece.end }
                    if (end == null) {
                        val existing = session.length(remote) ?: return@withContext IptvUploadOutcome(IptvUploadResult.LOST, 0, IptvShareError.LOST)
                        uploaded = existing
                        return@withContext IptvUploadOutcome(IptvUploadResult.DONE, existing, remote = remote)
                    }
                    found(session, remote, end)?.let { name ->
                        uploaded = end
                        return@withContext IptvUploadOutcome(IptvUploadResult.DONE, end, remote = name)
                    }
                }
                if (!session.append) {
                    if (!finished()) {
                        quietly(session)
                        session = null
                        while (!finished()) pause(pollMillis)
                        continue
                    }
                    val local = pieces(spool)
                    val total = RecordingUpload.whole(local.map { it.piece }, committed())
                        ?: return@withContext IptvUploadOutcome(IptvUploadResult.LOST, 0, IptvShareError.LOST)
                    checkFree(session, local, 0)
                    val target = free(session, chosen ?: remote, chosen?.let(RecordingFiles::partial) ?: partial).also { chosen = it }
                    val whole = RecordingFiles.partial(target)
                    val output = session.openWrite(whole, total).also { file = it }
                    var size = 0L
                    uploaded = 0
                    for (source in local) {
                        var offset = 0L
                        while (offset < source.piece.length && size < total) {
                            currentCoroutineContext().ensureActive()
                            val count = read(source.file, offset, buffer, minOf(chunkBytes.toLong(), source.piece.length - offset, total - size).toInt())
                            if (count <= 0) throw IptvShareException(IptvShareError.LOST)
                            output.write(size, buffer, 0, count)
                            size += count
                            offset += count
                            uploaded = size
                        }
                    }
                    output.flush()
                    if (output.length != total) throw IOException("Remote size mismatch")
                    output.close()
                    file = null
                    val name = publish(session, whole, target, total)
                    uploaded = total
                    return@withContext IptvUploadOutcome(IptvUploadResult.DONE, total, remote = name)
                }
                val output = session.openWrite(partial).also { file = it }
                var size = output.length
                uploaded = size
                checkFree(session, initial, size)
                failingSince = null
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val done = finished()
                    val available = committed()
                    val local = pieces(spool)
                    when (val step = RecordingUpload.next(local.map { it.piece }, size, available, done, chunkBytes)) {
                        RecordingUploadStep.Done -> {
                            output.flush()
                            if (output.length != size) throw IOException("Remote size mismatch")
                            output.close()
                            file = null
                            val name = publish(session, partial, remote, size)
                            uploaded = size
                            return@withContext IptvUploadOutcome(IptvUploadResult.DONE, size, remote = name)
                        }
                        RecordingUploadStep.Wait -> pause(pollMillis)
                        RecordingUploadStep.Lost -> return@withContext IptvUploadOutcome(IptvUploadResult.LOST, size, IptvShareError.LOST)
                        is RecordingUploadStep.Truncate -> throw IptvShareException(IptvShareError.LOST)
                        is RecordingUploadStep.Write -> {
                            val source = local[step.piece]
                            val count = read(source.file, step.offset, buffer, step.count)
                            if (count <= 0) { pause(pollMillis); continue }
                            output.write(size, buffer, 0, count)
                            size += count
                            uploaded = size
                            failures = 0
                            if (size == source.piece.end && (done || source !== local.last())) {
                                output.flush()
                                val confirmed = output.length
                                if (confirmed < size) throw IOException("Remote size behind")
                                val removable = RecordingUpload.removable(local.map { it.piece }, confirmed, false)
                                removable.forEach { local[it].file.delete() }
                                checkFree(session, pieces(spool), size)
                            }
                        }
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                lastError = (error as? IptvShareException)?.error ?: IptvShareError.OTHER
                if (lastError == IptvShareError.LOST) return@withContext IptvUploadOutcome(IptvUploadResult.LOST, uploaded, lastError)
                if (failures == 0) IptvLog.failure("recording upload", error)
                if (finished()) {
                    val since = failingSince ?: now().also { failingSince = it }
                    if (now() - since >= patienceMillis) return@withContext IptvUploadOutcome(IptvUploadResult.PENDING, uploaded, lastError)
                }
                pause(RecordingUpload.retryMillis(failures))
                failures += 1
            } finally {
                quietly(file)
                quietly(session)
            }
        }
        IptvUploadOutcome(IptvUploadResult.PENDING, uploaded, lastError)
    }

    private fun free(session: IptvShareSession, remote: String, own: String): String {
        var name = remote
        repeat(RecordingFiles.MAX_COPIES) {
            if (open(session, name, own)) return name
            name = RecordingFiles.next(name)
        }
        throw IptvShareException(IptvShareError.OTHER)
    }

    private fun publish(session: IptvShareSession, partial: String, remote: String, size: Long): String {
        var name = remote
        repeat(RecordingFiles.MAX_COPIES) {
            if (open(session, name, partial)) {
                try {
                    session.rename(partial, name, false)
                    return name
                } catch (error: IOException) {
                    if (session.length(partial) == null && session.length(name) == size) return name
                    if (session.length(name) == null) throw error
                }
            }
            name = RecordingFiles.next(name)
        }
        throw IptvShareException(IptvShareError.OTHER)
    }

    private fun open(session: IptvShareSession, name: String, own: String): Boolean =
        session.length(name) == null && RecordingFiles.partial(name).let { it == own || session.length(it) == null }

    private fun found(session: IptvShareSession, remote: String, size: Long): String? {
        var name = remote
        repeat(RecordingFiles.MAX_COPIES) {
            val length = session.length(name)
            if (length == size) return name
            if (length == null && session.length(RecordingFiles.partial(name)) == null) return null
            name = RecordingFiles.next(name)
        }
        return null
    }

    private fun checkFree(session: IptvShareSession, local: List<Local>, size: Long) {
        val pending = (local.maxOfOrNull { it.piece.end } ?: size) - size
        val free = session.freeBytes()
        onFree(free)
        if (free < minimumFreeBytes + pending.coerceAtLeast(0)) throw IptvShareException(IptvShareError.FULL)
    }

    private fun pieces(spool: File): List<Local> = (spool.listFiles() ?: emptyArray()).mapNotNull { file ->
        val start = RecordingUpload.spoolStart(file.name) ?: return@mapNotNull null
        if (!file.isFile) null else Local(RecordingPiece(start, file.length()), file)
    }.sortedBy { it.piece.start }

    private fun read(file: File, offset: Long, buffer: ByteArray, count: Int): Int = RandomAccessFile(file, "r").use { input ->
        input.seek(offset)
        var total = 0
        while (total < count) {
            val read = input.read(buffer, total, count - total)
            if (read < 0) break
            total += read
        }
        total
    }

    private fun quietly(closeable: Closeable?) { try { closeable?.close() } catch (_: Exception) { } }

    companion object {
        fun spoolOutput(spool: File): IptvRecordingOutput =
            IptvRecordingOutput(spool, { _, start -> File(spool, RecordingUpload.spoolName(start)) }, RecordingParts.SPOOL_PART_BYTES)
    }
}
