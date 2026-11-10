package com.nuvio.tv.core.backup

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A backup file in the box's Downloads folder. */
data class SavedBackup(
    val id: String,
    val displayName: String,
    val modifiedMs: Long,
    val sizeBytes: Long
)

/**
 * Backups live in Downloads/Arc backups, outside the app's own data, so uninstalling Arc or clearing its data
 * keeps them. After a reinstall Android 11 and newer hide the old files from Arc; they are then restored from
 * a phone instead.
 */
@Singleton
class BackupFiles @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val usesMediaStore get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Android 9 and older need the storage permission before writing to Downloads. */
    fun needsStoragePermission(): Boolean =
        !usesMediaStore && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    fun fileName(nowMs: Long = System.currentTimeMillis()): String =
        "Arc backup ${SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date(nowMs))}.json"

    fun save(text: String, nowMs: Long = System.currentTimeMillis()): SavedBackup {
        val saved = if (usesMediaStore) saveMediaStore(text, fileName(nowMs)) else saveLegacy(text, fileName(nowMs))
        trim()
        return saved
    }

    fun list(): List<SavedBackup> = runCatching {
        if (usesMediaStore) listMediaStore() else listLegacy()
    }.getOrDefault(emptyList()).sortedByDescending { it.modifiedMs }

    fun read(id: String): String? = runCatching {
        if (usesMediaStore) {
            context.contentResolver.openInputStream(mediaUri(id))?.use { it.readBytes().toString(Charsets.UTF_8) }
        } else {
            legacyFolder().resolve(id).takeIf { it.isFile }?.readText()
        }
    }.getOrNull()

    fun readBytes(id: String): ByteArray? = read(id)?.toByteArray(Charsets.UTF_8)

    private fun trim() {
        list().drop(KEEP).forEach { old ->
            runCatching {
                if (usesMediaStore) context.contentResolver.delete(mediaUri(old.id), null, null)
                else legacyFolder().resolve(old.id).delete()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveMediaStore(text: String, name: String): SavedBackup {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Downloads unavailable")
        try {
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("Downloads unavailable")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        val id = ContentUris.parseId(uri).toString()
        return SavedBackup(id, name, System.currentTimeMillis(), text.length.toLong())
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun listMediaStore(): List<SavedBackup> {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.DATE_MODIFIED,
            MediaStore.Downloads.SIZE
        )
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$FOLDER%", "Arc backup%")
        val result = mutableListOf<SavedBackup>()
        context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            while (cursor.moveToNext()) {
                result += SavedBackup(
                    id = cursor.getLong(idColumn).toString(),
                    displayName = cursor.getString(nameColumn).orEmpty(),
                    modifiedMs = cursor.getLong(dateColumn) * 1000L,
                    sizeBytes = cursor.getLong(sizeColumn)
                )
            }
        }
        return result
    }

    private fun mediaUri(id: String): Uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toLong())

    @Suppress("DEPRECATION")
    private fun legacyFolder(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)

    private fun saveLegacy(text: String, name: String): SavedBackup {
        val folder = legacyFolder().apply { mkdirs() }
        val file = File(folder, name)
        val temp = File(folder, "$name.part")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw IllegalStateException("Downloads unavailable")
        }
        return SavedBackup(file.name, file.name, file.lastModified(), file.length())
    }

    private fun listLegacy(): List<SavedBackup> =
        legacyFolder().listFiles { file -> file.isFile && file.name.startsWith("Arc backup") && file.name.endsWith(".json") }
            .orEmpty()
            .map { SavedBackup(it.name, it.name, it.lastModified(), it.length()) }

    companion object {
        const val FOLDER = "Arc backups"
        const val MIME = "application/json"
        const val KEEP = 5
    }
}
