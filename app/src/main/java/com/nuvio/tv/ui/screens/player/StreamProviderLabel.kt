package com.nuvio.tv.ui.screens.player

/**
 * Resolves a human "provider" label for a playing stream: the debrid store that
 * served it (TorBox, AllDebrid, ...) if one is named, else the library back-end
 * (Emby, Jellyfin, ...), else the serving host. Used by the stats HUD and the
 * start-of-play loading overlay.
 *
 * The store lives inside the add-on's marketing label as a short token, so this
 * parses the stream name + description; add-on name and host are fallbacks only.
 * Token positions vary by add-on ("\u26a1 [TB]", "[TB \u26a1]", "(TB)",
 * "TB Instant", "[AD+]"), so a store CODE is matched only as a bracket/paren/space
 * -delimited token, never a bare substring, otherwise "WEB-DL" would read as
 * Debrid-Link and "HDR" as Debrider. The full store name is always tried first.
 *
 * Store set taken from AIOStreams' own provider list.
 */

private data class DebridStore(
    val display: String,
    val fullNames: List<String>,
    val code: String
)

private val DEBRID_STORES = listOf(
    DebridStore("TorBox", listOf("torbox"), "TB"),
    DebridStore("Real-Debrid", listOf("real-debrid", "realdebrid"), "RD"),
    DebridStore("AllDebrid", listOf("alldebrid"), "AD"),
    DebridStore("Premiumize", listOf("premiumize"), "PM"),
    DebridStore("Debrid-Link", listOf("debrid-link"), "DL"),
    DebridStore("Debrider", listOf("debrider"), "DR"),
    DebridStore("EasyDebrid", listOf("easydebrid"), "ED"),
    DebridStore("Offcloud", listOf("offcloud"), "OC"),
    DebridStore("PikPak", listOf("pikpak"), "PKP"),
    DebridStore("put.io", listOf("put.io", "putio"), "P.IO"),
    DebridStore("Seedr", listOf("seedr"), "SDR")
)

private val LIBRARY_BACKENDS = listOf(
    "emby" to "Emby",
    "jellyfin" to "Jellyfin",
    "plex" to "Plex",
    "google drive" to "Google Drive",
    "gdrive" to "Google Drive"
)

// Chars that may sit immediately before/after a store code for it to count as a
// delimited token (plus string start/end). '\u26a1' is the lightning bolt some
// add-ons prefix to the tag.
private const val CODE_BEFORE = "[(\u26a1 "
private const val CODE_AFTER = "])+\u26a1 "

private fun containsDebridCode(haystack: String, code: String): Boolean {
    var from = 0
    while (true) {
        val i = haystack.indexOf(code, from)
        if (i < 0) return false
        val before = if (i > 0) haystack[i - 1] else null
        val after = if (i + code.length < haystack.length) haystack[i + code.length] else null
        val okBefore = before == null || before in CODE_BEFORE
        val okAfter = after == null || after in CODE_AFTER
        if (okBefore && okAfter) return true
        from = i + 1
    }
}

// AIOStreams native (Usenet / other) results carry an "[AIO...] <indexer> ..."
// marketing label. The indexer is the first whitespace-delimited token after the
// "[AIO...]" prefix, e.g. "[AIO\u26a1] DrunkenSlug 2160p" and
// "[AIO\u26a1] Newznab (Your Media) 2160p" both -> the first token. The trailing
// "(Your Media)" group is present on some labels and absent on others, so it is
// NOT relied on. Anchored to the literal "[AIO" so it can never fire on an
// ordinary bracketed tag. When the indexer is not individually identifiable the
// label reads a protocol name (e.g. "Newznab") -- AIOStreams' own generalisation,
// shown as-is. (A space-containing indexer name would be truncated to its first
// word, but no such name is emitted in practice.)
private val AIOSTREAMS_LABEL = Regex("""\[AIO\S*]\s*(\S+)""")

private fun aiostreamsIndexer(label: String): String? =
    AIOSTREAMS_LABEL.find(label)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

/**
 * @return the provider label, or null when nothing is known (the row is then omitted).
 */
fun resolveStreamProvider(
    streamName: String?,
    streamDescription: String?,
    addonName: String?,
    host: String?
): String? {
    val label = listOfNotNull(streamName, streamDescription).joinToString("\n")
    val lower = label.lowercase()
    val upper = label.uppercase()

    // 1. Debrid store: full name first (unambiguous), then delimited code.
    DEBRID_STORES.firstOrNull { store -> store.fullNames.any { lower.contains(it) } }
        ?.let { return it.display }
    DEBRID_STORES.firstOrNull { containsDebridCode(upper, it.code) }
        ?.let { return it.display }

    // 2. Library back-end, from the label or the add-on name.
    val libHaystack = lower + "\n" + (addonName ?: "").lowercase()
    LIBRARY_BACKENDS.firstOrNull { libHaystack.contains(it.first) }
        ?.let { return it.second }

    // 3. AIOStreams native (Usenet/other): show the indexer named in the label.
    //    Reached only when no debrid store and no library keyword matched, so
    //    debrid and library sources are unaffected.
    aiostreamsIndexer(label)?.let { return it }

    // 4. Serving host.
    return host?.takeIf { it.isNotBlank() }
}
