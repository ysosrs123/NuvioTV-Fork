package com.nuvio.tv.core.backup

import com.nuvio.tv.data.local.SupporterMarkerStore

/** Where the supporter marker goes in a backup: sealed with the passwords when there is a PIN, with the settings when not. */
internal object BackupSupporter {
    const val STORE = SupporterMarkerStore.STORE

    fun placed(entries: Map<BackupPart, List<BackupEntry>>, sealed: Boolean): Map<BackupPart, List<BackupEntry>> {
        if (sealed) return entries
        val (marker, secrets) = entries[BackupPart.SECRETS].orEmpty().partition(::isMarker)
        if (marker.isEmpty()) return entries
        return entries + mapOf(
            BackupPart.SECRETS to secrets,
            BackupPart.SETTINGS to entries[BackupPart.SETTINGS].orEmpty() + marker
        )
    }

    fun isSupporter(entries: Map<BackupPart, List<BackupEntry>>): Boolean =
        entries.values.flatten().any { entry ->
            isMarker(entry) && (entry.values[SupporterMarkerStore.KEY_TIER] as? BackupValue.Text)?.value?.isNotBlank() == true
        }

    private fun isMarker(entry: BackupEntry) = entry.kind == BackupStoreKind.PREFS && entry.store == STORE
}
