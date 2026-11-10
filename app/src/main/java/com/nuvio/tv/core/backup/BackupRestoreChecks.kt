package com.nuvio.tv.core.backup

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

data class BackupApplyResult(val written: Int, val failed: Int)

internal object BackupRestoreChecks {
    /** Throws before anything is written when an entry of the chosen [parts] could not be restored as saved. */
    fun validate(entries: Map<BackupPart, List<BackupEntry>>, parts: Set<BackupPart>) {
        parts.forEach { part ->
            entries[part].orEmpty().forEach { entry ->
                val spec = BackupCatalog.spec(entry.kind, entry.store) ?: return@forEach
                when (entry.kind) {
                    BackupStoreKind.PROFILE_STORE -> requireProfile(entry)
                    BackupStoreKind.PROTECTED, BackupStoreKind.FILE -> {
                        requireProfile(entry)
                        if (entry.text == null) throw BackupFormatException("no text in ${spec.name}")
                    }
                    BackupStoreKind.BOX_STORE, BackupStoreKind.PREFS, BackupStoreKind.SECTION -> Unit
                }
                (entry.values["profiles_json"] as? BackupValue.Text)?.let { profiles ->
                    val parsed = runCatching { Json.parseToJsonElement(profiles.value) }.getOrNull()
                    if (parsed !is JsonArray) throw BackupFormatException("bad profiles")
                }
            }
        }
    }

    fun isPartial(written: Int, failed: Int): Boolean = written > 0 && failed > 0

    /** The old file stays whole until the new one is complete. */
    fun writeWhole(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.part")
        try {
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) throw IOException("could not replace ${file.name}")
            }
        } finally {
            temp.delete()
        }
    }

    private fun requireProfile(entry: BackupEntry) {
        if ((entry.profileId ?: 0) < 1) throw BackupFormatException("no profile for ${entry.store}")
    }
}
