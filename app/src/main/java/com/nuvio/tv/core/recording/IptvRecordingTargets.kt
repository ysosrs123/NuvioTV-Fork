package com.nuvio.tv.core.recording

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import com.nuvio.tv.core.iptv.RecordingFileSystem
import com.nuvio.tv.core.iptv.RecordingFiles
import com.nuvio.tv.core.iptv.RecordingLocations
import com.nuvio.tv.core.iptv.RecordingMounts
import com.nuvio.tv.core.iptv.RecordingParts
import com.nuvio.tv.core.iptv.RecordingShareTarget
import com.nuvio.tv.data.iptv.AndroidIptvSecretBox
import com.nuvio.tv.data.iptv.EnvelopeIptvSecretBox
import com.nuvio.tv.data.iptv.IptvLivePreferences
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvRecordingShareStore
import com.nuvio.tv.data.iptv.IptvShareConnector
import com.nuvio.tv.data.iptv.IptvShareSettings
import com.nuvio.tv.data.iptv.IptvSmbConnector
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class IptvRecordingVolume(val id: String, val label: String, val directory: File, val freeBytes: Long,
    val fileSystem: RecordingFileSystem, val mounted: Boolean, val readOnly: Boolean)

enum class IptvVolumeCheck { OK, READ_ONLY, MISSING, FAILED }

sealed interface IptvRecordingPlace {
    val storage: String?
    val label: String?
    data class Local(override val storage: String?, override val label: String?, val directory: File, val partBytes: Long) : IptvRecordingPlace
    class Share(override val storage: String, override val label: String, val settings: IptvShareSettings, val connector: IptvShareConnector) : IptvRecordingPlace {
        override fun toString(): String = "Share(withheld)"
    }
}

sealed interface IptvPlaceResult {
    data class Ready(val place: IptvRecordingPlace) : IptvPlaceResult
    data object Missing : IptvPlaceResult
    data object ReadOnly : IptvPlaceResult
    data object ShareMissing : IptvPlaceResult
}

@Singleton
class IptvRecordingTargets @Inject constructor(@ApplicationContext private val context: Context, private val preferences: IptvLivePreferences) {
    private val box by lazy { EnvelopeIptvSecretBox(AndroidIptvSecretBox(SHARE_KEY_ALIAS)) }
    val shares = IptvRecordingShareStore(File(context.filesDir, "iptv/recording-share.json")) { box }

    fun internalDirectory(): File {
        val external = context.getExternalFilesDir(DIRECTORY)?.takeIf {
            Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED && (it.isDirectory || it.mkdirs())
        }
        return external ?: File(context.filesDir, DIRECTORY).also { it.mkdirs() }
    }

    fun spool(id: String): File = File(context.noBackupFilesDir, "iptv/spool/$id")

    fun spoolFreeBytes(): Long = runCatching { context.noBackupFilesDir.usableSpace }.getOrDefault(0L)

    fun owns(directory: File): Boolean {
        val path = directory.absoluteFile.path
        if (directory.absoluteFile.name != DIRECTORY) return false
        return path == File(context.filesDir, DIRECTORY).absolutePath || path.endsWith("/Android/data/${context.packageName}/files/$DIRECTORY")
    }

    fun volumes(): List<IptvRecordingVolume> {
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        val mounts = try { RecordingMounts.parse(File("/proc/self/mounts").readText()) } catch (_: Exception) { emptyList() }
        val dirs = try { context.getExternalFilesDirs(null).toList() } catch (_: Exception) { emptyList() }
        val result = LinkedHashMap<String, IptvRecordingVolume>()
        dirs.drop(1).filterNotNull().forEach { dir ->
            val volume = try { manager.getStorageVolume(dir) } catch (_: Exception) { null }
            if (volume?.isPrimary == true) return@forEach
            val root = RecordingMounts.volumeRoot(dir.absolutePath)
            val id = volume?.uuid ?: root?.substringAfterLast('/') ?: return@forEach
            if (!id.matches(ID)) return@forEach
            val state = Environment.getExternalStorageState(dir)
            result[id] = IptvRecordingVolume(id, volume?.getDescription(context)?.takeIf { it.isNotBlank() } ?: id, File(dir, DIRECTORY),
                runCatching { dir.usableSpace }.getOrDefault(0L), root?.let { RecordingMounts.fileSystem(mounts, it) } ?: RecordingFileSystem.UNKNOWN,
                state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY,
                state == Environment.MEDIA_MOUNTED_READ_ONLY || root?.let { RecordingMounts.find(mounts, it)?.readOnly } == true)
        }
        val volumes = try { manager.storageVolumes } catch (_: Exception) { emptyList() }
        volumes.filter { !it.isPrimary && it.isRemovable }.forEach { volume ->
            val id = volume.uuid?.takeIf { it.matches(ID) } ?: return@forEach
            if (id in result) return@forEach
            val state = volume.state
            if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return@forEach
            val root = "/storage/$id"
            result[id] = IptvRecordingVolume(id, volume.getDescription(context)?.takeIf { it.isNotBlank() } ?: id,
                File("$root/Android/data/${context.packageName}/files/$DIRECTORY"), runCatching { File(root).usableSpace }.getOrDefault(0L),
                RecordingMounts.fileSystem(mounts, root), true, true)
        }
        return result.values.toList()
    }

    fun check(volume: IptvRecordingVolume): IptvVolumeCheck {
        if (!volume.mounted) return IptvVolumeCheck.MISSING
        if (volume.readOnly) return IptvVolumeCheck.READ_ONLY
        val directory = volume.directory
        if (!directory.isDirectory && !directory.mkdirs()) return IptvVolumeCheck.READ_ONLY
        val probe = File(directory, ".nuvio-test-${System.nanoTime()}.tmp")
        return try {
            FileOutputStream(probe).use { output ->
                output.write(ByteArray(TEST_BYTES))
                output.flush()
                output.fd.sync()
            }
            if (probe.length() == TEST_BYTES.toLong()) IptvVolumeCheck.OK else IptvVolumeCheck.FAILED
        } catch (error: IOException) {
            IptvLog.failure("recording drive check", error)
            if (error.message?.let { "EROFS" in it || it.contains("read-only", true) } == true) IptvVolumeCheck.READ_ONLY else IptvVolumeCheck.FAILED
        } finally { probe.delete() }
    }

    fun connector(settings: IptvShareSettings): IptvShareConnector? {
        val password = try { shares.password() } catch (error: Exception) { IptvLog.failure("share password", error); return null } ?: return null
        return IptvSmbConnector(settings, password)
    }

    fun connector(target: RecordingShareTarget, username: String, domain: String, guest: Boolean, password: String): IptvShareConnector =
        IptvSmbConnector(IptvShareSettings(target, username.trim(), domain.trim(), guest, PROBE_ID), password)

    fun preferred(): IptvPlaceResult {
        val choice = preferences.recordLocation
        return when {
            choice == RecordingLocations.SHARE -> shares.settings()?.let { resolve(RecordingLocations.share(it.id)) } ?: IptvPlaceResult.ShareMissing
            RecordingLocations.volumeId(choice) != null -> resolve(choice)
            else -> resolve(null)
        }
    }

    fun resolve(storage: String?): IptvPlaceResult {
        RecordingLocations.shareId(storage)?.let { id ->
            val settings = shares.settings()?.takeIf { it.id == id } ?: return IptvPlaceResult.ShareMissing
            val connector = connector(settings) ?: return IptvPlaceResult.ShareMissing
            return IptvPlaceResult.Ready(IptvRecordingPlace.Share(RecordingLocations.share(id), settings.label, settings, connector))
        }
        val volumeId = RecordingLocations.volumeId(storage)
            ?: return IptvPlaceResult.Ready(IptvRecordingPlace.Local(null, null, internalDirectory(), Long.MAX_VALUE))
        val volume = volumes().firstOrNull { it.id == volumeId } ?: return IptvPlaceResult.Missing
        if (!volume.mounted) return IptvPlaceResult.Missing
        if (volume.readOnly || (!volume.directory.isDirectory && !volume.directory.mkdirs()) || !volume.directory.canWrite()) return IptvPlaceResult.ReadOnly
        return IptvPlaceResult.Ready(IptvRecordingPlace.Local(RecordingLocations.volume(volumeId), volume.label, volume.directory,
            RecordingParts.partBytes(volume.fileSystem)))
    }

    fun freeBytes(place: IptvRecordingPlace): Long = when (place) {
        is IptvRecordingPlace.Local -> runCatching { place.directory.usableSpace }.getOrDefault(0L)
        is IptvRecordingPlace.Share -> spoolFreeBytes()
    }

    fun mountedVolumeIds(): Set<String> = volumes().filter { it.mounted }.map { it.id }.toSet()

    fun localParts(main: File): List<File> {
        val result = ArrayList<File>()
        for (index in 1..RecordingParts.MAX_PARTS) {
            val final = File(main.parentFile, RecordingParts.name(main.name, index))
            val partial = File(RecordingFiles.partial(final.path))
            result += when {
                final.isFile -> final
                partial.isFile -> partial
                else -> break
            }
        }
        return result
    }

    companion object {
        const val DIRECTORY = "recordings"
        private const val SHARE_KEY_ALIAS = "nuvio.iptv.recording.share"
        private const val PROBE_ID = "probe0000"
        private const val TEST_BYTES = 64 * 1024
        private val ID = Regex("[A-Za-z0-9-]{1,64}")
    }
}
