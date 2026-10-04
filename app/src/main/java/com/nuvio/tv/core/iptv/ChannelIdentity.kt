package com.nuvio.tv.core.iptv

import java.util.UUID

/** Local source and account IDs are opaque persisted IDs, never endpoint/credential strings. */
data class ChannelCandidate(
    val name: String,
    val locator: String,
    val providerId: String? = null,
    val guideId: String? = null,
    val variant: String? = null,
) {
    override fun toString(): String = "ChannelCandidate(metadata withheld)"
}

data class StoredChannel(
    val id: String,
    val sourceId: String,
    val data: ChannelCandidate,
    val available: Boolean = true,
)

enum class IdentityMatch { PROVIDER_ID, GUIDE_VARIANT, EXACT_LOCATOR, NEW }
data class ReconciledChannel(val channel: StoredChannel, val match: IdentityMatch)
data class ChannelReconciliation(val channels: List<ReconciledChannel>, val unavailable: List<StoredChannel>)

/** Pure reconciliation; the repository must commit this with its generation fence in one transaction. */
class ChannelIdentityReconciler(private val newId: () -> String = { UUID.randomUUID().toString() }) {
    fun reconcile(sourceId: String, previous: List<StoredChannel>, incoming: List<ChannelCandidate>): ChannelReconciliation {
        require(sourceId.isNotBlank())
        require(previous.all { it.sourceId == sourceId }) { "Cross-source reconciliation is not allowed" }
        require(previous.map { it.id }.distinct().size == previous.size)
        val candidates = incoming.distinct()
        val providerGroups = candidates.mapNotNull { c -> c.providerId?.takeIf(String::isNotBlank)?.let { it to c } }.groupBy({ it.first }, { it.second })
        require(providerGroups.values.none { it.size > 1 }) { "Conflicting provider identities" }
        val oldProviderGroups = previous.mapNotNull { c -> c.data.providerId?.takeIf(String::isNotBlank)?.let { it to c } }.groupBy({ it.first }, { it.second })
        require(oldProviderGroups.values.none { it.size > 1 }) { "Ambiguous stored provider identities" }
        val used = mutableSetOf<String>()
        val allIds = previous.mapTo(mutableSetOf()) { it.id }
        fun compatible(old: StoredChannel, candidate: ChannelCandidate): Boolean {
            val oldProvider = old.data.providerId?.takeIf(String::isNotBlank)
            val newProvider = candidate.providerId?.takeIf(String::isNotBlank)
            // A missing ID cannot steal a row whose authoritative ID is present elsewhere in this refresh.
            if (oldProvider != null && oldProvider in providerGroups && newProvider != oldProvider) return false
            return oldProvider == null || newProvider == null || oldProvider == newProvider
        }
        fun guideKey(c: ChannelCandidate): Pair<String, String>? =
            c.guideId?.takeIf(String::isNotBlank)?.let { guide -> c.variant?.takeIf(String::isNotBlank)?.let { guide to it } }
        val newGuideGroups = candidates.groupBy(::guideKey)
        val oldGuideGroups = previous.groupBy { guideKey(it.data) }
        val newLocatorGroups = candidates.groupBy { it.locator }
        val oldLocatorGroups = previous.groupBy { it.data.locator }

        val reconciled = candidates.map { candidate ->
            var match: StoredChannel? = null
            var reason = IdentityMatch.NEW
            candidate.providerId?.takeIf(String::isNotBlank)?.let { id ->
                match = oldProviderGroups[id]?.singleOrNull()
                if (match != null) reason = IdentityMatch.PROVIDER_ID
            }
            if (match == null) {
                val key = guideKey(candidate)
                if (key != null && newGuideGroups[key]?.size == 1) {
                    val old = oldGuideGroups[key]?.singleOrNull()
                    if (old != null && compatible(old, candidate)) { match = old; reason = IdentityMatch.GUIDE_VARIANT }
                }
            }
            if (match == null && newLocatorGroups[candidate.locator]?.size == 1) {
                val old = oldLocatorGroups[candidate.locator]?.singleOrNull()
                val strongerGuideCandidate = old?.let { guideKey(it.data) }?.let { newGuideGroups[it]?.singleOrNull() }
                if (old != null && compatible(old, candidate) && (strongerGuideCandidate == null || strongerGuideCandidate == candidate || !compatible(old, strongerGuideCandidate))) {
                    match = old; reason = IdentityMatch.EXACT_LOCATOR
                }
            }
            // Never transfer one saved favourite to two new rows.
            if (match?.id in used) { match = null; reason = IdentityMatch.NEW }
            val id = match?.id ?: newId().also { require(it.isNotBlank() && allIds.add(it)) { "Duplicate generated identity" } }
            used += id
            ReconciledChannel(StoredChannel(id, sourceId, candidate), reason)
        }
        return ChannelReconciliation(reconciled, previous.filter { it.id !in used }.map { it.copy(available = false) })
    }
}

data class RefreshTicket(val sourceId: String, val configurationVersion: Long, val requestGeneration: Long)
enum class RefreshDecision { PUBLISH, STALE, INVALID, EMPTY_REQUIRES_REVIEW, SHRINK_REQUIRES_REVIEW }

/** No I/O: used again inside the database transaction, not merely after downloading. */
fun decideCatalogueRefresh(
    candidate: RefreshTicket,
    latest: RefreshTicket,
    complete: Boolean,
    previousCount: Int,
    candidateCount: Int,
    acceptedLargeChange: Boolean = false,
): RefreshDecision {
    require(previousCount >= 0 && candidateCount >= 0)
    if (candidate != latest) return RefreshDecision.STALE
    if (!complete) return RefreshDecision.INVALID
    if (candidateCount == 0 && !acceptedLargeChange) return RefreshDecision.EMPTY_REQUIRES_REVIEW
    // Conservative review threshold; expose review to the user rather than authorising deletions.
    if (previousCount > 0 && candidateCount.toLong() * 2 < previousCount && !acceptedLargeChange) return RefreshDecision.SHRINK_REQUIRES_REVIEW
    return RefreshDecision.PUBLISH
}
