package com.nuvio.tv.core.backup

import android.content.Context
import java.io.IOException
import com.nuvio.tv.core.runtime.AppRestarter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class BackupSaved(val file: SavedBackup, val document: BackupDocument, val supporter: Boolean = false)

@Singleton
class BackupManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val stores: BackupStores,
    val files: BackupFiles,
    private val restarter: AppRestarter,
    private val signInOffer: BackupSignInOffer,
    private val partialNotice: BackupPartialNotice,
    sections: Set<@JvmSuppressWildcards BackupSection>
) {
    val sections: List<BackupSection> = sections.sortedBy { it.id }

    private val prefs by lazy { context.getSharedPreferences("backup_restore", Context.MODE_PRIVATE) }

    private val _includeSecrets = MutableStateFlow(true)
    val includeSecrets: StateFlow<Boolean> = _includeSecrets.asStateFlow()

    private val _lastBackupMs = MutableStateFlow(0L)
    val lastBackupMs: StateFlow<Long> = _lastBackupMs.asStateFlow()

    /** A backup sent from a phone, waiting for the viewer to choose what to restore. */
    private val _received = MutableStateFlow<BackupDocument?>(null)
    val received: StateFlow<BackupDocument?> = _received.asStateFlow()

    init {
        _includeSecrets.value = prefs.getBoolean(KEY_SECRETS, true)
        _lastBackupMs.value = prefs.getLong(KEY_LAST, 0L)
    }

    fun setIncludeSecrets(include: Boolean) {
        _includeSecrets.value = include
        prefs.edit().putBoolean(KEY_SECRETS, include).apply()
    }

    /** A copy without passwords and keys, written before an unexpected sign-out empties the box. */
    suspend fun saveSafetyCopy() {
        withContext(Dispatchers.IO) {
            val sectionData = sections.associate { it.id to it.collect() }
            val kept = BackupSupporter.placed(BackupSections.merged(stores.collect(), sectionData), sealed = false)
                .filterKeys { it != BackupPart.SECRETS }
            if (kept.values.all { it.isEmpty() }) return@withContext
            val now = System.currentTimeMillis()
            val parts = kept.filterValues { it.isNotEmpty() }.keys
            val document = BackupDocument(stores.header(parts, now, BackupSections.summary(sectionData, false)), kept, null)
            files.save(BackupFormat.encode(document), now, BackupKind.SAFETY)
        }
    }

    suspend fun backUp(pin: String?): BackupSaved = withContext(Dispatchers.IO) {
        val sectionData = sections.associate { it.id to it.collect() }
        val all = BackupSections.merged(stores.collect(), sectionData)
        val entries = BackupSupporter.placed(all, sealed = pin != null)
        val secrets = entries[BackupPart.SECRETS].orEmpty()
        val sealed = if (pin != null && secrets.isNotEmpty()) {
            BackupCrypto.seal(BackupFormat.encodeEntries(secrets).toString(), pin)
        } else null
        val kept = entries.filterKeys { it != BackupPart.SECRETS }
        val parts = kept.filterValues { it.isNotEmpty() }.keys + listOfNotNull(sealed?.let { BackupPart.SECRETS })
        val now = System.currentTimeMillis()
        val summary = BackupSections.summary(sectionData, sealed != null)
        val document = BackupDocument(stores.header(parts.toSet(), now, summary), kept, sealed)
        val saved = files.save(BackupFormat.encode(document), now)
        _lastBackupMs.value = now
        prefs.edit().putLong(KEY_LAST, now).apply()
        BackupSaved(saved, document, BackupSupporter.isSupporter(all))
    }

    fun read(id: String): BackupDocument? = files.read(id)?.let { runCatching { BackupFormat.decode(it) }.getOrNull() }

    /** Takes a file sent from a phone. Returns false when it is not an Arc backup. */
    fun receive(text: String): Boolean {
        val document = runCatching { BackupFormat.decode(text) }.getOrNull() ?: return false
        _received.value = document
        return true
    }

    fun clearReceived() {
        _received.value = null
    }

    fun opens(document: BackupDocument, pin: String): Boolean {
        val box = document.sealedSecrets ?: return false
        return runCatching { BackupCrypto.open(box, pin) }.isSuccess
    }

    /**
     * Writes the chosen parts, then restarts Arc. Restored looks and languages rebuild the screen, so it can't be
     * cancelled. Returns true when some items failed and the viewer is being told before the restart, false when
     * everything was written but Arc could not restart itself.
     */
    suspend fun restore(document: BackupDocument, parts: Set<BackupPart>, chosenSections: Set<String>, pin: String?): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            val entries = document.entries.toMutableMap()
            val box = document.sealedSecrets
            if (box != null && pin != null) {
                entries[BackupPart.SECRETS] = BackupFormat.decodeEntries(
                    kotlinx.serialization.json.Json.parseToJsonElement(BackupCrypto.open(box, pin))
                )
            }
            val chosen = parts.filter { it in entries }.toSet()
            BackupRestoreChecks.validate(entries, chosen)
            val applied = stores.apply(entries, chosen)
            var written = applied.written
            var failed = applied.failed
            sections.filter { it.id in chosenSections }.forEach { section ->
                try {
                    section.restore(BackupSections.read(section.id, entries, withDevice = BackupPart.DEVICE in parts))
                    written++
                } catch (error: Exception) {
                    failed++
                }
            }
            if (written == 0) throw IOException("nothing restored, $failed failed")
            _received.value = null
            val offerSignIn = BackupPart.SETTINGS in parts && !stores.isSignedIn
            if (BackupRestoreChecks.isPartial(written, failed)) {
                partialNotice.show(offerSignIn)
                return@withContext true
            }
            if (offerSignIn) signInOffer.arm()
            restarter.restart()
        }

    fun device(): BackupDevice = stores.currentDevice()

    val isSignedIn: Boolean get() = stores.isSignedIn

    private companion object {
        const val KEY_SECRETS = "include_secrets"
        const val KEY_LAST = "last_backup_ms"
    }
}
