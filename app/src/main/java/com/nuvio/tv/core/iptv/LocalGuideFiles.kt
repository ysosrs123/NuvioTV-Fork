package com.nuvio.tv.core.iptv

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI
import java.util.Locale

data class LocalGuideFile(val uri: String, val name: String, val bytes: Long, val modifiedMillis: Long, val rootIndex: Int)

class LocalGuideFiles(private val roots: () -> List<File>, private val maxBytes: Long = 256L * 1024 * 1024,
    private val maxFiles: Int = 100) {
    init { require(maxBytes > 0 && maxFiles in 1..1000) }

    fun list(): List<LocalGuideFile> = roots().withIndex().flatMap { (index, root) ->
        val base = canonical(root) ?: return@flatMap emptyList()
        (base.listFiles() ?: emptyArray()).asSequence().mapNotNull { file ->
            val real = canonical(file) ?: return@mapNotNull null
            if (real.parentFile != base || !accepted(real)) return@mapNotNull null
            LocalGuideFile(real.toURI().toString(), real.name, real.length(), real.lastModified(), index)
        }.toList()
    }.sortedWith(compareByDescending<LocalGuideFile> { it.modifiedMillis }.thenBy { it.name }).take(maxFiles)

    fun open(uri: String): InputStream {
        val file = resolve(uri) ?: throw SecurityException("Guide file is not in an import folder")
        return FileInputStream(file)
    }

    fun resolve(uri: String): File? {
        val parsed = try { URI(uri) } catch (_: Exception) { return null }
        if (parsed.scheme != "file" || parsed.rawAuthority != null || parsed.rawQuery != null || parsed.rawFragment != null) return null
        val file = canonical(try { File(parsed) } catch (_: Exception) { return null }) ?: return null
        val inside = roots().mapNotNull(::canonical).any { file.parentFile == it }
        return file.takeIf { inside && accepted(it) }
    }

    private fun accepted(file: File): Boolean {
        val name = file.name.lowercase(Locale.ROOT)
        return file.isFile && !name.startsWith(".") && EXTENSIONS.any(name::endsWith) && file.length() in 1..maxBytes
    }

    private fun canonical(file: File): File? = try { file.canonicalFile } catch (_: Exception) { null }

    companion object {
        private val EXTENSIONS = listOf(".xml", ".xmltv", ".xml.gz", ".gz")
        fun isLocalGuide(endpoint: String): Boolean = endpoint.startsWith("file:")
    }
}
