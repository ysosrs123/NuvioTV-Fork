package com.nuvio.tv.core.formatcheck

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The clips ship inside the app. They are unpacked when a run starts and deleted when it ends. */
object FormatCheckPack {
    private const val ASSET = "format-check/clips.zip"
    private const val MAX_BYTES = 30L * 1024 * 1024

    fun folder(context: Context) = File(context.cacheDir, "format-check")

    fun delete(context: Context) {
        folder(context).deleteRecursively()
    }

    suspend fun unpack(context: Context): File = withContext(Dispatchers.IO) {
        val dir = folder(context)
        dir.deleteRecursively()
        dir.mkdirs()
        ZipInputStream(context.assets.open(ASSET).buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (!entry.isDirectory && isSafeEntryName(entry.name)) {
                    File(dir, entry.name).outputStream().use { out -> copyCapped(input, out) }
                }
                input.closeEntry()
            }
        }
        dir
    }

    private fun copyCapped(input: InputStream, out: java.io.OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            total += read
            check(total <= MAX_BYTES) { "too large" }
            out.write(buffer, 0, read)
        }
    }

    internal fun isSafeEntryName(name: String): Boolean =
        name.isNotBlank() && !name.contains('/') && !name.contains('\\') && !name.contains("..")
}
