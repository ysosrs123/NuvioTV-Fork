package com.nuvio.tv.data.iptv

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.nuvio.tv.core.iptv.RecordingMedia
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

data class IptvMediaEntry(val id: Long, val name: String, val pending: Boolean)

enum class IptvMediaDelete { DELETED, GONE, NOT_OWNED, FAILED }

interface IptvMediaFiles {
    fun entries(name: String?): List<IptvMediaEntry>
    fun present(ids: Collection<Long>): Set<Long>
    fun size(id: Long): Long?
    fun create(name: String): Long
    fun publish(id: Long)
    fun openWrite(id: Long): FileChannel
    fun openRead(id: Long): FileChannel
    fun delete(id: Long): Boolean
    fun freeBytes(): Long
    fun mounted(): Boolean
}

class IptvMediaStoreTarget(private val files: IptvMediaFiles?) {
    val available: Boolean get() = files != null

    fun mounted(): Boolean = files?.let { runCatching { it.mounted() }.getOrDefault(false) } == true

    fun freeBytes(): Long = files?.let { runCatching { it.freeBytes() }.getOrDefault(0L) } ?: 0L

    fun connector(): IptvMediaStoreConnector = IptvMediaStoreConnector(files)

    fun present(ids: Collection<Long>): Set<Long> {
        val store = files ?: return emptySet()
        if (ids.isEmpty()) return emptySet()
        return try { store.present(ids) } catch (error: Exception) { IptvLog.failure("media recordings check", error); emptySet() }
    }

    fun reader(id: Long): IptvRecordingReader? {
        val store = files ?: return null
        val size = try { store.size(id) } catch (error: Exception) { IptvLog.failure("media recording open", error); null }
        return if (size != null && size > 0) IptvMediaStoreReader(store, id) else null
    }

    fun delete(id: Long): IptvMediaDelete {
        val store = files ?: return IptvMediaDelete.FAILED
        return try { if (store.delete(id)) IptvMediaDelete.DELETED else IptvMediaDelete.GONE } catch (error: SecurityException) {
            IptvLog.failure("media recording delete", error)
            IptvMediaDelete.NOT_OWNED
        } catch (error: Exception) {
            IptvLog.failure("media recording delete", error)
            IptvMediaDelete.FAILED
        }
    }

    companion object {
        fun create(context: Context): IptvMediaStoreTarget =
            IptvMediaStoreTarget(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) AndroidIptvMediaFiles(context.applicationContext) else null)
    }
}

class IptvMediaStoreConnector(private val files: IptvMediaFiles?) : IptvShareConnector {
    @Volatile var published: Long? = null
        internal set

    override fun connect(): IptvShareSession = IptvMediaStoreSession(files ?: throw IptvShareException(IptvShareError.UNREACHABLE), this)
}

private class IptvMediaStoreSession(private val files: IptvMediaFiles, private val connector: IptvMediaStoreConnector) : IptvShareSession {
    private var pendingId: Long? = null
    override val append: Boolean get() = false

    override fun length(path: String): Long? {
        if (RecordingMedia.pending(path)) return null
        val id = RecordingMedia.id(path) ?: guard { files.entries(RecordingMedia.displayName(path)) }.firstOrNull { !it.pending }?.id ?: return null
        return guard { files.size(id) }?.also { connector.published = id }
    }

    override fun openWrite(path: String): IptvShareFile = openWrite(path, 0)

    override fun openWrite(path: String, total: Long): IptvShareFile {
        val name = RecordingMedia.displayName(path)
        dropPending()
        guard { files.entries(name) }.filter { it.pending }.forEach { quietly { files.delete(it.id) } }
        val id = guard { files.create(name) }
        pendingId = id
        return IptvMediaFile(guard { files.openWrite(id) })
    }

    override fun openRead(path: String): IptvShareFile {
        val id = RecordingMedia.id(path) ?: guard { files.entries(RecordingMedia.displayName(path)) }.firstOrNull { !it.pending }?.id
            ?: throw IptvShareException(IptvShareError.FOLDER_NOT_FOUND)
        return IptvMediaFile(guard { files.openRead(id) })
    }

    override fun rename(from: String, to: String, replace: Boolean) {
        val id = pendingId ?: throw IptvShareException(IptvShareError.LOST)
        if (replace) guard { files.entries(RecordingMedia.displayName(to)) }.filter { !it.pending && it.id != id }.forEach { quietly { files.delete(it.id) } }
        guard { files.publish(id) }
        pendingId = null
        connector.published = id
    }

    override fun delete(path: String): Boolean {
        RecordingMedia.id(path)?.let { return guard { files.delete(it) } }
        val pending = RecordingMedia.pending(path)
        val found = guard { files.entries(RecordingMedia.displayName(path)) }.filter { it.pending == pending }
        return found.map { guard { files.delete(it.id) } }.any { it }
    }

    override fun list(folder: String): List<String> = guard { files.entries(null) }.filter { !it.pending }.map { it.name }

    override fun ensureFolder(folder: String) = Unit

    override fun freeBytes(): Long = guard { files.freeBytes() }

    override fun close() = dropPending()

    private fun dropPending() {
        val id = pendingId ?: return
        pendingId = null
        quietly { files.delete(id) }
    }

    private inline fun quietly(block: () -> Unit) { try { block() } catch (_: Exception) { } }

    private inline fun <T> guard(block: () -> T): T = try { block() } catch (error: IptvShareException) { throw error }
        catch (error: IOException) { throw IptvShareException(mediaError(error), error) }
        catch (error: SecurityException) { throw IptvShareException(IptvShareError.ACCESS_DENIED, error) }
        catch (error: IllegalArgumentException) { throw IptvShareException(IptvShareError.UNREACHABLE, error) }
        catch (error: IllegalStateException) { throw IptvShareException(IptvShareError.UNREACHABLE, error) }
}

private class IptvMediaFile(private val channel: FileChannel) : IptvShareFile {
    override val length: Long get() = channel.size()

    override fun write(offset: Long, buffer: ByteArray, start: Int, count: Int) {
        val data = ByteBuffer.wrap(buffer, start, count)
        try {
            while (data.hasRemaining()) channel.write(data, offset + data.position() - start)
        } catch (error: IOException) { throw IptvShareException(mediaError(error), error) }
    }

    override fun read(offset: Long, buffer: ByteArray, start: Int, count: Int): Int = channel.read(ByteBuffer.wrap(buffer, start, count), offset)

    override fun flush() = channel.force(false)

    override fun close() = channel.close()
}

internal fun mediaError(error: IOException): IptvShareError =
    if (generateSequence<Throwable>(error) { it.cause }.take(6).any { cause -> cause.message?.let { "ENOSPC" in it || it.contains("no space", true) } == true })
        IptvShareError.FULL else IptvShareError.OTHER

class IptvMediaStoreReader(private val files: IptvMediaFiles, private val id: Long) : IptvRecordingReader {
    private var channel: FileChannel? = null
    override val network: Boolean get() = false

    @Synchronized override fun length(): Long = opened().size()

    @Synchronized override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        return opened().read(ByteBuffer.wrap(buffer, offset, length), position)
    }

    @Synchronized override fun close() {
        try { channel?.close() } catch (_: IOException) { }
        channel = null
    }

    private fun opened(): FileChannel = channel ?: try { files.openRead(id) } catch (error: SecurityException) {
        throw IOException("Media recording not accessible", error)
    }.also { channel = it }

    override fun toString(): String = "IptvMediaStoreReader(withheld)"
}

@RequiresApi(Build.VERSION_CODES.Q)
class AndroidIptvMediaFiles(private val context: Context) : IptvMediaFiles {
    private val resolver: ContentResolver get() = context.contentResolver
    private val collection: Uri get() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override fun entries(name: String?): List<IptvMediaEntry> {
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=?" + if (name != null) " AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?" else ""
        val args = listOfNotNull(RecordingMedia.RELATIVE_PATH, name).toTypedArray()
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING)
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            resolver.query(collection, projection, Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }, null)
        } else {
            @Suppress("DEPRECATION")
            resolver.query(MediaStore.setIncludePending(collection), projection, selection, args, null)
        }
        return cursor?.use {
            val result = ArrayList<IptvMediaEntry>()
            while (it.moveToNext()) result += IptvMediaEntry(it.getLong(0), it.getString(1).orEmpty(), it.getInt(2) != 0)
            result
        }.orEmpty()
    }

    override fun present(ids: Collection<Long>): Set<Long> {
        val result = HashSet<Long>()
        ids.distinct().chunked(200).forEach { chunk ->
            val selection = "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",") { "?" }})"
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, chunk.map { it.toString() }.toTypedArray(), null)?.use {
                while (it.moveToNext()) result += it.getLong(0)
            }
        }
        return result
    }

    override fun size(id: Long): Long? = try {
        resolver.openFileDescriptor(uri(id), "r")?.use { it.statSize }
    } catch (_: java.io.FileNotFoundException) { null }

    override fun create(name: String): Long {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, RecordingMedia.MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RecordingMedia.RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val created = resolver.insert(collection, values) ?: throw IOException("Media entry not created")
        return ContentUris.parseId(created)
    }

    override fun publish(id: Long) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        if (resolver.update(uri(id), values, null, null) < 1) throw IOException("Media entry not published")
    }

    override fun openWrite(id: Long): FileChannel {
        val descriptor = resolver.openFileDescriptor(uri(id), "w") ?: throw IOException("Media entry not writable")
        return ParcelFileDescriptor.AutoCloseOutputStream(descriptor).channel
    }

    override fun openRead(id: Long): FileChannel {
        val descriptor = resolver.openFileDescriptor(uri(id), "r") ?: throw IOException("Media entry not readable")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).channel
    }

    override fun delete(id: Long): Boolean = resolver.delete(uri(id), null, null) > 0

    override fun freeBytes(): Long = context.getExternalFilesDir(null)?.usableSpace ?: 0L

    override fun mounted(): Boolean = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED

    private fun uri(id: Long): Uri = ContentUris.withAppendedId(collection, id)
}
