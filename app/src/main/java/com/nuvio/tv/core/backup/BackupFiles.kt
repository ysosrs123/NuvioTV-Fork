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

/** Each kind of backup keeps its own newest files, so one kind never pushes out another. */
enum class BackupKind(val prefix: String, val keep: Int) {
    MANUAL("Arc backup", 2),
    SAFETY("Arc safety copy", 2);

    companion object {
        fun of(displayName: String): BackupKind? = entries.firstOrNull { displayName.startsWith(it.prefix) }
    }
}

/** A backup file in the box's Downloads folder. */
data class SavedBackup(
    val id: String,
    val displayName: String,
    val modifiedMs: Long,
    val sizeBytes: Long
) {
    val kind: BackupKind get() = BackupKind.of(displayName) ?: BackupKind.MANUAL
}

/** The files to delete so every kind keeps only its newest ones. */
internal fun backupsToTrim(backups: List<SavedBackup>): List<SavedBackup> =
    backups.groupBy { it.kind }.flatMap { (kind, ofKind) ->
        ofKind.sortedByDescending { it.modifiedMs }.drop(kind.keep)
    }

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

    fun fileName(nowMs: Long = System.currentTimeMillis(), kind: BackupKind = BackupKind.MANUAL): String =
        "${kind.prefix} ${SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date(nowMs))}.json"

    fun save(text: String, nowMs: Long = System.currentTimeMillis(), kind: BackupKind = BackupKind.MANUAL): SavedBackup {
        val name = fileName(nowMs, kind)
        val saved = if (kind == BackupKind.SAFETY) {
            runCatching {
                check(!needsStoragePermission())
                saveInDownloads(text, name)
            }.getOrElse { saveInAppFolder(text, name) }
        } else {
            saveInDownloads(text, name)
        }
        trim()
        return saved
    }

    fun list(): List<SavedBackup> {
        val inDownloads = runCatching {
            if (usesMediaStore) listMediaStore() else listLegacy()
        }.getOrDefault(emptyList())
        return (inDownloads + listAppFolder()).sortedByDescending { it.modifiedMs }
    }

    fun read(id: String): String? = runCatching {
        if (id.startsWith(APP_FOLDER_ID)) {
            appFolderFile(id)?.readText()
        } else if (usesMediaStore) {
            context.contentResolver.openInputStream(mediaUri(id))?.use { it.readBytes().toString(Charsets.UTF_8) }
        } else {
            legacyFolder().resolve(id).takeIf { it.isFile }?.readText()
        }
    }.getOrNull()

    fun readBytes(id: String): ByteArray? = read(id)?.toByteArray(Charsets.UTF_8)

    private fun trim() {
        backupsToTrim(list()).forEach { old ->
            runCatching {
                if (old.id.startsWith(APP_FOLDER_ID)) appFolderFile(old.id)?.delete()
                else if (usesMediaStore) context.contentResolver.delete(mediaUri(old.id), null, null)
                else legacyFolder().resolve(old.id).delete()
            }
        }
        if (!usesMediaStore) {
            runCatching {
                legacyFolder().listFiles { file -> file.isFile && file.name.endsWith(".json.part") }
                    ?.forEach { it.delete() }
            }
        }
    }

    private fun saveInDownloads(text: String, name: String): SavedBackup =
        if (usesMediaStore) saveMediaStore(text, name) else saveLegacy(text, name)

    /** Arc's own folders need no permission and are not emptied when the box is cleared after a sign-out. */
    private fun appFolders(): List<File> =
        listOfNotNull(context.getExternalFilesDir(null), context.filesDir).map { File(it, FOLDER) }

    private fun saveInAppFolder(text: String, name: String): SavedBackup {
        var failure: Exception? = null
        for (folder in appFolders()) {
            try {
                folder.mkdirs()
                val file = File(folder, name)
                val temp = File(folder, "$name.part")
                temp.writeText(text)
                if (!temp.renameTo(file)) {
                    file.delete()
                    if (!temp.renameTo(file)) throw IllegalStateException("App folder unavailable")
                }
                return SavedBackup(APP_FOLDER_ID + file.name, file.name, file.lastModified(), file.length())
            } catch (error: Exception) {
                failure = error
            }
        }
        throw failure ?: IllegalStateException("App folder unavailable")
    }

    private fun appFolderFiles(): List<File> = appFolders().flatMap { folder ->
        runCatching {
            folder.listFiles { file -> file.isFile && BackupKind.of(file.name) != null && file.name.endsWith(".json") }
        }.getOrNull().orEmpty().toList()
    }

    private fun listAppFolder(): List<SavedBackup> =
        appFolderFiles().map { SavedBackup(APP_FOLDER_ID + it.name, it.name, it.lastModified(), it.length()) }

    private fun appFolderFile(id: String): File? =
        appFolderFiles().firstOrNull { APP_FOLDER_ID + it.name == id }

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
        val names = BackupKind.entries.joinToString(" OR ") { "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?" }
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ($names)"
        val args = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$FOLDER%") + BackupKind.entries.map { "${it.prefix}%" }
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
        legacyFolder().listFiles { file -> file.isFile && BackupKind.of(file.name) != null && file.name.endsWith(".json") }
            .orEmpty()
            .map { SavedBackup(it.name, it.name, it.lastModified(), it.length()) }

    companion object {
        const val FOLDER = "Arc backups"
        const val MIME = "application/json"
        private const val APP_FOLDER_ID = "inapp-"
    }
}
