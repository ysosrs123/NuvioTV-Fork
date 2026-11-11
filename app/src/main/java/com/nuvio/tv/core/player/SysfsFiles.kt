package com.nuvio.tv.core.player

import java.io.File

/** Small, bounded reads of sysfs / procfs files (shared by the native FEL capability checks). */
internal object SysfsFiles {
    /** The first [max] bytes of [path], trimmed; "" for an empty file, null if unreadable. */
    fun read(path: String, max: Int = 4096): String? = runCatching {
        File(path).inputStream().use { input ->
            val bytes = ByteArray(max)
            val n = input.read(bytes)
            if (n <= 0) "" else String(bytes, 0, n).trim()
        }
    }.getOrNull()
}
