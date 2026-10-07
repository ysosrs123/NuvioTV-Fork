package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.RecordingParts
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class IptvRecordingPartFullException : IOException("Recording part is full")

class IptvRecordingOutput(
    val directory: File,
    private val partFile: (index: Int, start: Long) -> File,
    private val partBytes: Long = Long.MAX_VALUE,
    private val segmentHeadroom: Long = 0,
) : Closeable {
    init { require(partBytes > 0 && segmentHeadroom >= 0) }
    private var stream: FileOutputStream? = null
    private var full = false
    var parts = 0
        private set
    var bytes = 0L
        private set
    var partStart = 0L
        private set
    val partSize: Long get() = bytes - partStart

    fun open() {
        if (stream != null) return
        var index = 1
        var start = 0L
        while (index < RecordingParts.MAX_PARTS) {
            val current = partFile(index, start)
            if (!current.isFile) break
            val length = current.length()
            if (!partFile(index + 1, start + length).isFile) break
            start += length
            index += 1
        }
        val file = partFile(index, start)
        stream = FileOutputStream(file, true)
        parts = index
        partStart = start
        bytes = start + file.length()
    }

    fun files(): List<File> {
        val result = ArrayList<File>(parts)
        var start = 0L
        for (index in 1..parts) {
            val file = partFile(index, start)
            result += file
            start += if (index == parts) 0 else file.length()
        }
        return result
    }

    fun write(buffer: ByteArray, offset: Int, length: Int, split: Boolean) {
        var at = offset
        var left = length
        while (left > 0) {
            if (full) { if (!split) throw IptvRecordingPartFullException(); roll() }
            val count = if (split) RecordingParts.fit(partSize, left, partBytes) else left
            if (count == 0) {
                if (partSize == 0L) throw IOException("Recording part limit too small")
                roll()
                continue
            }
            val out = stream ?: throw IOException("Recording output closed")
            try { out.write(buffer, at, count) } catch (error: IOException) {
                if (!RecordingParts.tooLarge(error.message) || partSize == 0L) throw error
                try { out.flush(); out.channel.truncate(partSize) } catch (_: IOException) { }
                full = true
                if (!split) throw IptvRecordingPartFullException()
                continue
            }
            bytes += count
            at += count
            left -= count
        }
    }

    fun beforeSegment() {
        if (full || RecordingParts.rollBeforeSegment(partSize, partBytes, segmentHeadroom)) roll()
    }

    fun rollback(position: Long): Boolean {
        if (position >= bytes) return true
        if (position < partStart) return false
        val out = stream ?: return false
        try { out.flush(); out.channel.truncate(position - partStart) } catch (_: IOException) { return false }
        bytes = position
        return true
    }

    fun sync() {
        try { stream?.let { it.flush(); it.fd.sync() } } catch (_: IOException) { }
    }

    override fun close() {
        val out = stream ?: return
        stream = null
        sync()
        try { out.close() } catch (_: IOException) { }
    }

    private fun roll() {
        if (parts >= RecordingParts.MAX_PARTS) throw IOException("Too many recording parts")
        stream?.let { out -> sync(); try { out.close() } catch (_: IOException) { } }
        stream = null
        partStart = bytes
        parts += 1
        full = false
        stream = FileOutputStream(partFile(parts, partStart), false)
    }
}
