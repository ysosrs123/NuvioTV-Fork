package com.nuvio.tv.core.backup

import androidx.annotation.StringRes

/** One section's data, as its own text. [secrets] only ever reaches the file sealed with the backup PIN. */
data class BackupSectionData(
    val settings: String? = null,
    val secrets: String? = null,
    val device: String? = null
)

/** A feature that keeps its data in its own way and backs it up itself. It gets one row when restoring. */
interface BackupSection {
    val id: String

    @get:StringRes
    val titleRes: Int

    @get:StringRes
    val subtitleRes: Int

    /** The row's text when the backup holds this section's passwords, so restoring them asks for the PIN. */
    @get:StringRes
    val subtitleWithSecretsRes: Int

    suspend fun collect(): BackupSectionData

    /** [BackupSectionData.secrets] is null without the PIN, [BackupSectionData.device] unless that part was ticked. */
    suspend fun restore(data: BackupSectionData)
}

internal object BackupSections {
    fun merged(
        entries: Map<BackupPart, List<BackupEntry>>,
        sections: Map<String, BackupSectionData>
    ): Map<BackupPart, List<BackupEntry>> {
        val result = entries.mapValues { it.value.toMutableList() }.toMutableMap()
        fun add(part: BackupPart, id: String, text: String?) {
            if (text != null) result.getOrPut(part) { mutableListOf() } += BackupEntry(BackupStoreKind.SECTION, id, text = text)
        }
        sections.forEach { (id, data) ->
            add(BackupPart.SETTINGS, id, data.settings)
            add(BackupPart.SECRETS, id, data.secrets)
            add(BackupPart.DEVICE, id, data.device)
        }
        return result
    }

    /** Which sections the file really holds, and whether each one's passwords went in. */
    fun summary(sections: Map<String, BackupSectionData>, sealed: Boolean): Map<String, Boolean> =
        sections.filterValues { it.settings != null || it.device != null || (sealed && it.secrets != null) }
            .mapValues { sealed && it.value.secrets != null }

    fun read(id: String, entries: Map<BackupPart, List<BackupEntry>>, withDevice: Boolean): BackupSectionData {
        fun text(part: BackupPart) =
            entries[part].orEmpty().firstOrNull { it.kind == BackupStoreKind.SECTION && it.store == id }?.text
        return BackupSectionData(
            settings = text(BackupPart.SETTINGS),
            secrets = text(BackupPart.SECRETS),
            device = if (withDevice) text(BackupPart.DEVICE) else null
        )
    }
}
