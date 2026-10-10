package com.nuvio.tv.data.iptv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

interface IptvRecordingReader : Closeable {
    val network: Boolean
    fun length(): Long
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

class IptvPartsReader(private val files: List<File>) : IptvRecordingReader {
    init { require(files.isNotEmpty()) }
    private var opened: List<RandomAccessFile>? = null
    private var starts = LongArray(0)
    private var total = 0L
    override val network: Boolean get() = false

    @Synchronized override fun length(): Long { open(); return total }

    @Synchronized override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val parts = open()
        if (length == 0) return 0
        if (position >= total) return -1
        var index = starts.binarySearch(position).let { if (it >= 0) it else -it - 2 }
        while (index < parts.size - 1 && position >= starts[index + 1]) index += 1
        val file = parts[index]
        val end = if (index == parts.size - 1) total else starts[index + 1]
        file.seek(position - starts[index])
        return file.read(buffer, offset, minOf(length.toLong(), end - position).toInt())
    }

    @Synchronized override fun close() {
        opened?.forEach { try { it.close() } catch (_: IOException) { } }
        opened = null
    }

    private fun open(): List<RandomAccessFile> {
        opened?.let { return it }
        val result = ArrayList<RandomAccessFile>(files.size)
        try { files.forEach { result += RandomAccessFile(it, "r") } } catch (error: IOException) {
            result.forEach { try { it.close() } catch (_: IOException) { } }
            throw error
        }
        starts = LongArray(result.size)
        var position = 0L
        result.forEachIndexed { index, file -> starts[index] = position; position += file.length() }
        total = position
        opened = result
        return result
    }
}

class IptvShareReader(private val connector: IptvShareConnector, private val path: String, private val windowBytes: Int = 512 * 1024) : IptvRecordingReader {
    init { require(windowBytes > 0) }
    private var session: IptvShareSession? = null
    private var file: IptvShareFile? = null
    private var size = -1L
    private val window = ByteArray(windowBytes)
    private var windowStart = 0L
    private var windowLength = 0
    override val network: Boolean get() = true

    @Synchronized override fun length(): Long = attempt { if (size < 0) size = opened().length; size }

    @Synchronized override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= length()) return -1
        if (position < windowStart || position >= windowStart + windowLength) {
            val sequential = windowLength > 0 && position == windowStart + windowLength
            windowStart = position
            windowLength = 0
            var filled = 0
            val wanted = minOf((if (sequential) windowBytes else minOf(windowBytes, maxOf(length, JUMP_BYTES))).toLong(), size - position).toInt()
            while (filled < wanted) {
                val count = attempt { opened().read(position + filled, window, filled, wanted - filled) }
                if (count <= 0) break
                filled += count
            }
            windowLength = filled
            if (filled == 0) return -1
        }
        val from = (position - windowStart).toInt()
        val count = minOf(length, windowLength - from)
        System.arraycopy(window, from, buffer, offset, count)
        return count
    }

    @Synchronized override fun close() = drop()

    private fun opened(): IptvShareFile {
        file?.let { return it }
        val current = session ?: connector.connect().also { session = it }
        return current.openRead(path).also { file = it }
    }

    private fun <T> attempt(block: () -> T): T = try { block() } catch (first: IOException) {
        if (first is IptvShareException && first.error == IptvShareError.FOLDER_NOT_FOUND) throw first
        drop()
        try { block() } catch (second: IOException) { drop(); throw second }
    }

    private fun drop() {
        try { file?.close() } catch (_: Exception) { }
        try { session?.close() } catch (_: Exception) { }
        file = null
        session = null
    }

    override fun toString(): String = "IptvShareReader(withheld)"

    private companion object {
        const val JUMP_BYTES = 128 * 1024
    }
}

fun IptvRecordingReader.window(position: Long, size: Int): ByteArray {
    val buffer = ByteArray(size)
    var filled = 0
    while (filled < size) {
        val count = read(position + filled, buffer, filled, size - filled)
        if (count <= 0) break
        filled += count
    }
    return if (filled == size) buffer else buffer.copyOf(filled)
}

class IptvRecordingDataSource(private val reader: IptvRecordingReader) : BaseDataSource(reader.network) {
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val length = start(dataSpec.position, dataSpec.length)
        opened = true
        transferStarted(dataSpec)
        return length
    }

    internal fun start(from: Long, requested: Long): Long {
        val length = try { reader.length() } catch (error: IOException) {
            throw DataSourceException(error, if (reader.network) PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                else PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        }
        if (from > length) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        position = from
        remaining = if (requested != C.LENGTH_UNSET.toLong()) minOf(requested, length - from) else length - from
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = try { reader.read(position, buffer, offset, minOf(length.toLong(), remaining).toInt()) } catch (error: IOException) {
            throw DataSourceException(error, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (count < 0) return C.RESULT_END_OF_INPUT
        position += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        if (opened) { opened = false; transferEnded() }
    }

    class Factory(private val reader: IptvRecordingReader) : DataSource.Factory {
        override fun createDataSource(): DataSource = IptvRecordingDataSource(reader)
    }
}
