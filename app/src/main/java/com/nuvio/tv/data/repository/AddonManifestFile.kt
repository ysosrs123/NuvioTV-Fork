package com.nuvio.tv.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.domain.model.Addon
import java.io.File

/**
 * The manifest cache on disk, read and written as a stream. Many large manifests add up to tens of MB of JSON,
 * which must never exist as one string.
 */
internal class AddonManifestFile(private val file: File, private val gson: Gson = Gson()) {
    private val type = object : TypeToken<Map<String, Addon>>() {}.type

    fun exists(): Boolean = file.isFile

    fun read(): Map<String, Addon>? {
        if (!file.isFile) return null
        return file.bufferedReader().use { gson.fromJson<Map<String, Addon>>(it, type) }
    }

    fun write(manifests: Map<String, Addon>) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.bufferedWriter().use { gson.toJson(manifests, type, it) }
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw java.io.IOException("could not replace ${file.name}")
        }
    }

    fun sizeBytes(): Long = if (file.isFile) file.length() else 0L
}
