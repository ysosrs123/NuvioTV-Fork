package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.Addon
import java.io.File

/**
 * How large this viewer's set-up is, for crash and problem reports: counts and sizes only, never a name of an
 * addon, a title or an address.
 */
object ArcSetupSize {
    private const val LARGEST_SHOWN = 5
    private const val MIN_LISTED_BYTES = 256L * 1024
    private val DATA_FOLDERS = listOf("shared_prefs", "files", "files/datastore", "databases", "no_backup")

    @Volatile private var dataDir: File? = null
    @Volatile private var addons: AddonCounts? = null
    @Volatile var manifestCacheBytes: Long = -1L
    @Volatile private var lists: ListCounts? = null

    data class AddonCounts(val installed: Int, val enabled: Int, val catalogs: Int, val largestCatalogs: Int, val extraOptions: Int)
    data class ListCounts(val library: Int, val watched: Int, val progress: Int)

    fun attach(dataDir: File?) {
        this.dataDir = dataDir
    }

    fun noteAddons(installed: List<Addon>) {
        addons = AddonCounts(
            installed = installed.size,
            enabled = installed.count { it.enabled },
            catalogs = installed.sumOf { it.catalogs.size },
            largestCatalogs = installed.maxOfOrNull { it.catalogs.size } ?: 0,
            extraOptions = installed.sumOf { addon -> addon.catalogs.sumOf { c -> c.extra.sumOf { it.options?.size ?: 0 } } }
        )
    }

    fun noteLists(library: Int, watched: Int, progress: Int) {
        lists = ListCounts(library, watched, progress)
    }

    fun facts(): Map<String, String> = buildMap {
        addons?.let { put("Add-ons", addonText(it)) }
        if (manifestCacheBytes >= 0) put("Add-on details saved", megabytes(manifestCacheBytes))
        lists?.let { put("Lists", listText(it)) }
        savedData(dataDir)?.let { put("Saved data", it) }
        openFiles()?.let { put("Open files", it.toString()) }
    }

    fun lines(): List<String> = runCatching { facts().map { (name, value) -> "$name: $value" } }.getOrDefault(emptyList())

    internal fun addonText(c: AddonCounts): String =
        "${c.installed} (${c.enabled} on), catalogues ${c.catalogs} (largest add-on ${c.largestCatalogs}), filter options ${c.extraOptions}"

    internal fun listText(c: ListCounts): String =
        "library ${c.library}, watched ${c.watched}, watch progress ${c.progress} (this profile)"

    internal fun savedData(dir: File?): String? {
        dir ?: return null
        val files = DATA_FOLDERS.flatMap { folder -> File(dir, folder).listFiles()?.filter { it.isFile }.orEmpty() }
        if (files.isEmpty()) return null
        val largest = files.filter { it.length() >= MIN_LISTED_BYTES }.sortedByDescending { it.length() }.take(LARGEST_SHOWN)
        return megabytes(files.sumOf { it.length() }) + " in ${files.size} files" +
            if (largest.isEmpty()) "" else "; largest " + largest.joinToString(", ") { "${it.name} ${megabytes(it.length())}" }
    }

    private fun openFiles(): Int? = runCatching { File("/proc/self/fd").list()?.size }.getOrNull()

    private fun megabytes(bytes: Long): String =
        if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "${"%.1f".format(java.util.Locale.US, bytes / 1048576.0)} MB"
}
