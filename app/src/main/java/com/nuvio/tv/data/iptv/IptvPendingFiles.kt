package com.nuvio.tv.data.iptv

import android.content.Context
import com.nuvio.tv.core.iptv.CatalogueReview
import com.nuvio.tv.core.iptv.HeldCandidate
import com.nuvio.tv.core.iptv.HeldCatalogue
import com.nuvio.tv.core.iptv.HeldContent
import com.nuvio.tv.core.iptv.SetupMerge
import com.nuvio.tv.core.iptv.SetupPendingImport
import java.io.File

class IptvPendingFiles(context: Context, databaseName: String, private val secrets: IptvSecretBox, private val now: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = 48L * 1024 * 1024) {
    private val root = File(context.noBackupFilesDir, "iptv-pending-" + databaseName.substringBeforeLast('.'))

    @Synchronized fun hold(ref: IptvSourceRef, summary: HeldCatalogue, records: List<IptvCatalogueRecord>, validators: IptvCacheValidators, guideUrls: List<String>): Boolean {
        val content = CatalogueReview.encodeContent(HeldContent(records.map { HeldCandidate(it.data, it.attributes) }, validators.etag, validators.lastModified, guideUrls))
        if (content.length * 2L > maxBytes) { dropHeld(ref); return false }
        write(file(ref, CONTENT), secrets.seal(aad(ref, CONTENT), content))
        write(file(ref, SUMMARY), secrets.seal(aad(ref, SUMMARY), CatalogueReview.encodeSummary(summary)))
        return true
    }

    @Synchronized fun held(ref: IptvSourceRef, configurationVersion: Long): HeldCatalogue? {
        val stored = read(ref, SUMMARY)?.let(CatalogueReview::decodeSummary)
        val current = CatalogueReview.current(stored, configurationVersion, now())
        if (stored != null && current == null) dropHeld(ref)
        return current?.takeIf { file(ref, CONTENT).isFile }
    }

    @Synchronized fun heldContent(ref: IptvSourceRef): HeldContent? = read(ref, CONTENT)?.let { runCatching { CatalogueReview.decodeContent(it) }.getOrNull() }

    @Synchronized fun dropHeld(ref: IptvSourceRef) { file(ref, SUMMARY).delete(); file(ref, CONTENT).delete() }

    @Synchronized fun saveImport(ref: IptvSourceRef, pending: SetupPendingImport) =
        write(file(ref, IMPORT), secrets.seal(aad(ref, IMPORT), SetupMerge.encodePending(pending)))

    @Synchronized fun imported(ref: IptvSourceRef): SetupPendingImport? = read(ref, IMPORT)?.let { runCatching { SetupMerge.decodePending(it) }.getOrNull() }

    @Synchronized fun dropImport(ref: IptvSourceRef) { file(ref, IMPORT).delete() }

    @Synchronized fun removeSource(ref: IptvSourceRef) { dropHeld(ref); dropImport(ref) }

    @Synchronized fun prune(profileId: Int, sources: Set<String>) {
        val prefix = "$profileId-"
        root.listFiles()?.filter { it.name.startsWith(prefix) && it.name.substringAfter(prefix).substringBeforeLast('.') !in sources }?.forEach { it.delete() }
    }

    @Synchronized fun clear() { root.listFiles()?.forEach { it.delete() } }

    private fun file(ref: IptvSourceRef, kind: String) = File(root, "${ref.profileId}-${ref.sourceId}.$kind")

    private fun read(ref: IptvSourceRef, kind: String): String? {
        val file = file(ref, kind)
        if (!file.isFile || file.length() > maxBytes) return null
        return runCatching { secrets.open(aad(ref, kind), file.readBytes()) }.getOrElse { file.delete(); null }
    }

    private fun write(target: File, bytes: ByteArray) {
        root.mkdirs()
        val temporary = File(root, target.name + ".tmp")
        temporary.outputStream().use { it.write(bytes); it.fd.sync() }
        if (!temporary.renameTo(target)) { temporary.delete(); throw java.io.IOException("IPTV pending file not saved") }
    }

    private companion object {
        const val SUMMARY = "held"
        const val CONTENT = "candidate"
        const val IMPORT = "import"
        fun aad(ref: IptvSourceRef, kind: String) = "iptv.pending.v1:${ref.profileId}:${ref.sourceId}:$kind"
    }
}
