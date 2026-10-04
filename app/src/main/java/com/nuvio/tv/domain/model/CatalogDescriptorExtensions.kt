package com.nuvio.tv.domain.model

private const val DEFAULT_SKIP_STEP = 100

/**
 * Whether this catalog declares support for an extra argument (e.g. "skip", "search").
 *
 * Stremio manifests declare extras in either form:
 *  - long form:  extra: [{ "name": "skip" }, ...]        (parsed into [extra])
 *  - short form: extraSupported: ["skip", ...] / extraRequired: [...]
 *
 * AddonMapper.parseCatalogExtras only populates [extra] from the long form, so
 * short-form catalogs need the extraSupported/extraRequired checks; without them
 * pagination is disabled (hasMore never set) and the catalog is hidden from search.
 */
fun CatalogDescriptor.supportsExtra(name: String): Boolean {
    if (extra.any { it.name.equals(name, ignoreCase = true) }) return true
    if (extraSupported.any { it.equals(name, ignoreCase = true) }) return true
    if (extraRequired.any { it.equals(name, ignoreCase = true) }) return true
    return false
}

fun CatalogDescriptor.skipStep(defaultStep: Int = DEFAULT_SKIP_STEP): Int {
    if (pageSize != null && pageSize > 0) return pageSize
    // Some manifests omit pageSize but enumerate the skip extra's own options
    // (e.g. ["0", "50", "100"]). The smallest positive gap between consecutive
    // options is the real page size; without it we assume DEFAULT_SKIP_STEP and
    // request skips the addon never serves.
    val skipOptions = extra
        .firstOrNull { it.name.equals("skip", ignoreCase = true) }
        ?.options
        .orEmpty()
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it >= 0 }
        .distinct()
        .sorted()
    if (skipOptions.size >= 2) {
        val inferred = skipOptions
            .zipWithNext()
            .mapNotNull { (lower, upper) -> (upper - lower).takeIf { gap -> gap > 0 } }
            .minOrNull()
        if (inferred != null && inferred > 0) return inferred
    }
    return defaultStep
}
