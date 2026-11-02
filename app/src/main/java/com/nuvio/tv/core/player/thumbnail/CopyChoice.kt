package com.nuvio.tv.core.player.thumbnail

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

internal enum class CopyKind {
    /** Another version on a media server. */
    SERVER,
    /** A ready http(s) link. */
    LINK,
    /** A copy already in the user's TorBox list. */
    DEBRID,
    /** A cached TorBox copy added to the user's list for the pass and removed again. */
    TORBOX_ADD,
    /** A cached Premiumize copy through a direct download link (uses fair-use points). */
    PREMIUMIZE,
    /** A Usenet link streamed by a local AIOStreams (each open may count as a grab at the indexer). */
    USENET,
    /** A cached debrid copy through its resolve link or the app's key: the title may stay in that debrid account. */
    RESOLVE,
}

internal enum class CopyCodec { H264, HEVC, OTHER, UNKNOWN }

/** One entry of the player's source list as a possible copy of the playing title. */
internal class CopyOffer(
    val id: String,
    val label: String,
    val kind: CopyKind,
    /** Picture height class (1080, 720, ...), 0 when unknown. */
    val height: Int,
    val codec: CopyCodec,
    val hdr: Boolean,
    val web: Boolean,
    val sizeBytes: Long?,
    /** File extension or the server's container, lower case; null when unknown. */
    val container: String?,
    val durationMs: Long?,
    /** Names, description and file name: the edition words are read from it. */
    val text: String,
    /** A media server away from the home network. */
    val remote: Boolean = false,
    /** The add-on or server it comes from, for what is learned about copies ([CopySourceBook]). */
    val source: String = "",
)

/** Which copy of a 4K title, or of a title playing from Usenet, its thumbnails are made from. */
internal object CopyChoice {
    /** Copies checked by pictures before falling back to the 4K file. */
    const val MAX_TRIES = 3
    /** How long a smaller box's own 4K pass waits while the copy pass is still looking for a copy. */
    const val LOOK_HOLD_MS = 90_000L
    private val CONTAINERS = setOf("mkv", "mp4", "m4v")

    /**
     * A smaller box decodes the 4K film itself only when no copy will do: while the copy pass is still [looking] its
     * own pass waits, up to [LOOK_HOLD_MS] after the copy pass started. A strong box carries on meanwhile.
     */
    fun ownPassWaitsForLook(strongBox: Boolean, looking: Boolean, sinceStartMs: Long, holdMs: Long = LOOK_HOLD_MS): Boolean =
        !strongBox && looking && sinceStartMs < holdMs
    private val HEIGHTS = listOf(1080, 720)
    const val FOUR_K = 2160

    enum class Edition(val word: Regex) {
        EXTENDED(Regex("(?<![a-z])extended(?![a-z])")),
        DIRECTORS(Regex("(?<![a-z])director'?s?(?![a-z])")),
        UNRATED(Regex("(?<![a-z])unrated(?![a-z])")),
        IMAX(Regex("(?<![a-z])imax(?![a-z])")),
        UNCUT(Regex("(?<![a-z])uncut(?![a-z])")),
        SPECIAL(Regex("(?<![a-z])special[ ._-]?edition(?![a-z])")),
        FINAL_CUT(Regex("(?<![a-z])final[ ._-]?cut(?![a-z])")),
        REMASTERED(Regex("(?<![a-z])remastered(?![a-z])")),
    }

    private val RESOLVE_SEGMENTS = setOf(
        "resolve", "playback", "debrid", "realdebrid", "alldebrid", "torbox", "premiumize", "debridlink",
        "easydebrid", "offcloud", "pikpak",
    )
    private const val SERVICES = "rd|tb|ad|pm|dl|ed|oc|pkp"
    private val DEBRID_TAG = Regex("""\[($SERVICES)(?![a-z])|(?<![a-z])($SERVICES)\+""")
    private val CACHED_MARK = Regex("""⚡|(?<![a-z])($SERVICES)\+|(?<![a-z])(cached|instant)(?![a-z])""")
    private val UNCACHED_MARK = Regex("""⏳|⬇|(?<![a-z])uncached(?![a-z])|\[($SERVICES)(?![a-z])[^\]]*download""")
    private val HASH = Regex("[0-9a-f]{40}")

    private fun pathOf(url: String): String =
        url.substringAfter("://", url).substringAfter('/', "").substringBefore('?').substringBefore('#').lowercase()

    /** A link that resolves through a debrid service: by its path or by the add-on's service tag. */
    private fun debridLink(url: String, text: String): Boolean =
        pathOf(url).split('/').any { it in RESOLVE_SEGMENTS } || DEBRID_TAG.containsMatchIn(text.lowercase())

    /**
     * Why a ready link may not be read for a copy, null when it may: a plain file link (HTTP providers too, whatever
     * their names say about downloads), a Usenet link streamed by a local AIOStreams, or a debrid resolve link marked
     * as cached ([cached]: the add-on's or the app's cache answer).
     */
    fun linkRejection(url: String, text: String, cached: Boolean? = null): String? {
        if (isUsenet(url)) return null
        val t = text.lowercase()
        if (!debridLink(url, t)) return null
        if (cached == false || UNCACHED_MARK.containsMatchIn(t)) return "a debrid link that is not cached"
        if (cached != true && !CACHED_MARK.containsMatchIn(t)) return "a debrid link with no cached mark"
        return null
    }

    /** How a ready link that [linkRejection] lets through is read. */
    fun linkKind(url: String, text: String): CopyKind = when {
        isUsenet(url) -> CopyKind.USENET
        debridLink(url, text) -> CopyKind.RESOLVE
        else -> CopyKind.LINK
    }

    /** The torrent a debrid resolve link names in its path, lower case; null for any other link. */
    fun linkHash(url: String?, text: String): String? {
        if (url == null || isUsenet(url) || !debridLink(url, text)) return null
        return pathOf(url).split('/').firstOrNull { HASH.matches(it) }
    }

    /** A Usenet link streamed by a local AIOStreams. */
    fun isUsenet(url: String): Boolean {
        val path = url.substringAfter("://", url).substringAfter('/', "").substringBefore('?').substringBefore('#')
            .lowercase()
        return "/usenet/stream/" in "/$path"
    }

    fun editions(text: String): Set<Edition> {
        val t = text.lowercase().replace('’', '\'')
        return Edition.entries.filterTo(LinkedHashSet()) { it.word.containsMatchIn(t) }
    }

    /** A source marked as Usenet by its add-on. */
    fun usenetMarked(type: String?, service: String?): Boolean =
        listOfNotNull(type, service).any { it.trim().lowercase() in USENET_MARKS }

    private val USENET_MARKS = setOf("usenet", "nzb")

    /** Picture height class of a [w] x [h] picture: a wide film has fewer lines than its class. */
    fun heightClass(w: Int, h: Int): Int = when {
        w >= 3000 || h >= 1500 -> 2160
        w >= 1600 || h >= 900 -> 1080
        w >= 1100 || h >= 600 -> 720
        else -> 480
    }

    /** AIOStreams' parsed duration: seconds for a film-length value, else milliseconds. */
    fun parsedDurationMs(raw: Long?): Long? = when {
        raw == null || raw <= 0L -> null
        raw < 100_000L -> raw * 1000L
        else -> raw
    }

    fun containerOf(fileName: String?): String? =
        fileName?.substringBefore('?')?.substringAfterLast('/')?.substringAfterLast('.', "")
            ?.lowercase()?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }

    /**
     * Why [offer] cannot stand in for the playing title, null when it can. Nothing is opened for this. [maxHeight]: the
     * playing picture's height class. [usenet]: the title plays from Usenet, so another Usenet link (another grab) is
     * never used. Edition words count only when the copy's length or [ownMs] is not known (0): a matching length
     * settles it. [ownEditions]: [editions] of [playingText], when worked out once for a whole list.
     */
    fun rejection(
        offer: CopyOffer, playingText: String, ownMs: Long, maxHeight: Int = 2160, usenet: Boolean = false,
        fourKCopies: Boolean = false, ownEditions: Set<Edition>? = null,
    ): String? {
        if (usenet && offer.kind == CopyKind.USENET) return "another Usenet link"
        offer.container?.let { if (it !in CONTAINERS) return "container $it" }
        when {
            offer.height == 0 -> return "resolution not stated"
            offer.height > maxHeight -> return "${offer.height}p is above the playing ${maxHeight}p"
            offer.height == FOUR_K && fourKCopies -> Unit
            offer.height > HEIGHTS.first() -> return "${offer.height}p is not a smaller copy"
            offer.height !in HEIGHTS -> return "${offer.height}p is too small"
        }
        offer.durationMs?.takeIf { ownMs > 0L }?.let { ms ->
            if (ReleaseMatch.ratio(ownMs, ms) != null) return null
            if (!ReleaseMatch.shiftCandidate(ownMs, ms)) return "length ${ms / 1000} s against ${ownMs / 1000} s"
        }
        val own = ownEditions ?: editions(playingText)
        val theirs = editions(offer.text)
        if (own != theirs) {
            val diff = (own - theirs) + (theirs - own)
            return "another edition (${diff.joinToString { it.name.lowercase() }})"
        }
        return null
    }

    /** A copy above this many Mbit/s (a disc remux) costs several times the bytes per thumbnail of a WEB copy. */
    const val HEAVY_MBPS = 20.0
    /** With no length to work the bitrate out from, a disc copy this large counts as heavy. */
    const val HEAVY_DISC_BYTES = 15_000_000_000L

    /**
     * A disc remux or anything else above [HEAVY_MBPS]: its keyframes are 3 to 5 times the size of a WEB copy's for
     * the same 480-wide thumbnail. The bitrate comes from the size and the copy's length, else the playing title's
     * length [ownMs].
     */
    fun heavy(offer: CopyOffer, ownMs: Long?): Boolean {
        val bytes = offer.sizeBytes?.takeIf { it > 0L } ?: return false
        val ms = offer.durationMs?.takeIf { it > 0L } ?: ownMs?.takeIf { it > 0L }
        if (ms == null) return !offer.web && bytes >= HEAVY_DISC_BYTES
        return bytes * 8.0 / (ms / 1000.0) / 1_000_000.0 > HEAVY_MBPS
    }

    /**
     * Every copy a link or a key reaches first (TorBox list, server versions, ready links, a TorBox copy added and
     * removed again, Premiumize, a debrid resolve link), then a version on a media server away from home, then Usenet
     * (an indexer grab). Within that copies from sources that failed the picture check or were slow lately
     * ([penalised]) last, then the smallest picture first: 720p, then 1080p (a thumbnail is 480 wide), a 4K copy last.
     * Then light copies before [heavy] ones (fewer bytes per
     * thumbnail), then servers known to send many keyframes in one request ([batched]: TorBox and the home network
     * always, a TorBox resolve link too, Real-Debrid and Premiumize never, others as remembered) before unknown ones and those known to refuse, then the cheaper
     * source (nothing added before added and removed again), SDR before HDR, H.264 before HEVC, WEB first, smaller
     * files first. For a title playing from Usenet ([usenet]) the source comes first: server versions, the TorBox list,
     * Premiumize, ready links, a TorBox copy added and removed again, then a debrid resolve link.
     */
    fun order(
        offers: List<CopyOffer>, usenet: Boolean = false, ownMs: Long? = null, awayServer: Boolean = false,
        penalised: (CopyOffer) -> Boolean = { false },
        batched: (CopyOffer) -> Boolean? = { null },
    ): List<CopyOffer> = offers.filter { !awayServer || awayUsable(it) }
        .map { o -> o to rankKey(o, usenet, ownMs, penalised(o), batched(o)) }
        .sortedWith { a, b -> compareKeys(a.second, b.second) }
        .map { it.first }

    /** [order]'s facts of one offer, worked out once: the sort compares these instead of asking again per comparison. */
    private fun rankKey(o: CopyOffer, usenet: Boolean, ownMs: Long?, penalised: Boolean, batched: Boolean?): LongArray =
        longArrayOf(
            (if (usenet) usenetRank(o.kind) else sideEffects(o)).toLong(),
            if (penalised) 1L else 0L,
            SMALLEST_FIRST.indexOf(o.height).let { i -> if (i < 0) SMALLEST_FIRST.size else i }.toLong(),
            if (heavy(o, ownMs)) 1L else 0L,
            batchedRank(o, batched).toLong(),
            costRank(o.kind).toLong(),
            if (o.hdr) 1L else 0L,
            codecRank(o.codec).toLong(),
            if (o.web) 0L else 1L,
            o.sizeBytes ?: Long.MAX_VALUE,
        )

    private fun compareKeys(a: LongArray, b: LongArray): Int {
        for (i in a.indices) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return 0
    }

    private val SMALLEST_FIRST = HEIGHTS.sorted() + FOUR_K

    private fun sideEffects(o: CopyOffer): Int = when (o.kind) {
        CopyKind.SERVER -> if (o.remote) 1 else 0
        CopyKind.DEBRID, CopyKind.LINK, CopyKind.TORBOX_ADD, CopyKind.PREMIUMIZE, CopyKind.RESOLVE -> 0
        CopyKind.USENET -> 2
    }

    /** 0 = many keyframes per request (or a home server, fast anyway), 1 = not known, 2 = known to refuse. */
    internal fun batchedRank(o: CopyOffer, known: Boolean?): Int = when {
        o.kind == CopyKind.DEBRID || o.kind == CopyKind.TORBOX_ADD -> 0
        o.kind == CopyKind.SERVER && !o.remote -> 0
        o.kind == CopyKind.PREMIUMIZE -> 2
        known == true -> 0
        known == false -> 2
        else -> 1
    }

    /** Nothing added, then added and removed again, then a title that may stay in the account. */
    private fun costRank(k: CopyKind): Int = when (k) {
        CopyKind.TORBOX_ADD -> 1
        CopyKind.PREMIUMIZE -> 2
        CopyKind.RESOLVE -> 3
        else -> 0
    }

    private val TORBOX_TEXT = Regex("""torbox|\[tb(?![a-z])|(?<![a-z])tb\+""")

    /** Where a copy comes from: one provider, one set of servers. */
    fun providerOf(o: CopyOffer): CopyProvider = when (o.kind) {
        CopyKind.SERVER -> if (o.remote) CopyProvider.AWAY_SERVER else CopyProvider.HOME_SERVER
        CopyKind.DEBRID, CopyKind.TORBOX_ADD -> CopyProvider.TORBOX
        CopyKind.PREMIUMIZE -> CopyProvider.PREMIUMIZE
        CopyKind.USENET -> CopyProvider.USENET
        CopyKind.LINK -> CopyProvider.LINK
        CopyKind.RESOLVE -> if (TORBOX_TEXT.containsMatchIn(o.text.lowercase())) CopyProvider.TORBOX else CopyProvider.OTHER_DEBRID
    }

    /**
     * Why a 4K copy of a 4K title may not be read, null when it may (any other copy: null). Only from another provider
     * than the playing stream ([playing]; not known: never) and from a server known to send many keyframes in one
     * request ([batched]): its keyframes are as large as the film's, so it only helps where it is reached faster.
     */
    fun fourKCopyRejection(o: CopyOffer, playing: CopyOffer?, batched: Boolean?): String? {
        if (o.height != FOUR_K) return null
        if (playing == null) return "a 4K copy, and the playing source is not known"
        if (providerOf(o) == providerOf(playing)) return "a 4K copy from the same source as the film"
        if (batchedRank(o, batched) != 0) return "a 4K copy from a server not known to batch keyframes"
        return null
    }

    /**
     * Whether a debrid resolve link's server sends many keyframes in one request, from the service named in its path or
     * the add-on's tag: TorBox yes, Real-Debrid and Premiumize no, otherwise not known.
     */
    fun resolveBatched(url: String, text: String): Boolean? {
        val segments = pathOf(url).split('/')
        val t = text.lowercase()
        return when {
            "torbox" in segments || TORBOX_TAG.containsMatchIn(t) -> true
            segments.any { it in SINGLE_READ_SEGMENTS } || SINGLE_READ_TAG.containsMatchIn(t) -> false
            else -> null
        }
    }

    private val TORBOX_TAG = Regex("""\[tb(?![a-z])|(?<![a-z])tb\+""")
    private val SINGLE_READ_SEGMENTS = setOf("realdebrid", "premiumize")
    private val SINGLE_READ_TAG = Regex("""\[(rd|pm)(?![a-z])|(?<![a-z])(rd|pm)\+""")

    /**
     * The title plays from a media server away from home: a copy may come from anywhere but a server away from home.
     * Those are only read by the playing server's own capped pass.
     */
    fun awayUsable(o: CopyOffer): Boolean = !(o.kind == CopyKind.SERVER && o.remote)

    /**
     * Whether any offer may stand in for the playing title by [rejection], with the playing picture's height not known
     * yet. Never while the playing server transcodes.
     */
    fun anyFits(offers: CopyOffers, ownMs: Long): Boolean =
        !offers.playingTranscoded && awayVerdicts(offers, ownMs).any { it.second == null }

    /** Each offer with why it cannot stand in for a title playing from a server away from home, null when it can. */
    fun awayVerdicts(offers: CopyOffers, ownMs: Long): List<Pair<CopyOffer, String?>> {
        val own = editions(offers.playingText)
        return offers.offers.map {
            it to (if (!awayUsable(it)) "a media server away from home"
                else rejection(it, offers.playingText, ownMs, ownEditions = own))
        }
    }

    private val LENGTH_REASON = Regex("^length \\d+ s against \\d+ s$")

    /** The reasons counted, most common first; length mismatches count as one reason. */
    fun reasonTally(reasons: List<String>): String = reasons
        .groupingBy { if (LENGTH_REASON.matches(it)) "another length" else it }.eachCount()
        .entries.sortedByDescending { it.value }.joinToString("; ") { "${it.value} x ${it.key}" }

    private fun usenetRank(k: CopyKind): Int = when (k) {
        CopyKind.SERVER -> 0
        CopyKind.DEBRID -> 1
        CopyKind.PREMIUMIZE -> 2
        CopyKind.LINK -> 3
        CopyKind.TORBOX_ADD -> 4
        CopyKind.RESOLVE -> 5
        CopyKind.USENET -> 6
    }

    private fun codecRank(c: CopyCodec): Int = when (c) {
        CopyCodec.H264 -> 0
        CopyCodec.HEVC -> 1
        CopyCodec.UNKNOWN -> 2
        CopyCodec.OTHER -> 3
    }

    /** Copy slot -> the own slots it serves (same moment, slot centres), for a copy of [ratio] (copy / own time). */
    fun ownSlotsByCopySlot(ownSlotCount: Int, ratio: Double, copySlotCount: Int): Map<Int, IntArray> =
        ownSlotsByCopySlot(ownSlotCount, ReleaseMatch.Timing(ratio), copySlotCount, SeekThumbnails.SPACING_MS)

    fun ownSlotsByCopySlot(
        ownSlotCount: Int, timing: ReleaseMatch.Timing, copySlotCount: Int, spacingMs: Long,
    ): Map<Int, IntArray> {
        val m = LinkedHashMap<Int, MutableList<Int>>()
        for (s in 0 until ownSlotCount) {
            val c = timing.mapSlot(s, copySlotCount, spacingMs)
            if (c >= 0) m.getOrPut(c) { ArrayList() }.add(s)
        }
        return m.mapValues { it.value.toIntArray() }
    }
}

/** Fits a copy's picture to the playing title's picture size, so both can be compared and shown alike. */
internal object CopyFit {
    /** [renderW] x [renderH] is drawn at [top] inside the own picture: bars added when positive, the copy's bars cut when negative. */
    class Plan(val renderW: Int, val renderH: Int, val top: Int)

    private const val MAX_SHAPE_CHANGE = 1.6

    /** The copy's [w] x [h] picture scaled to the own width [ow] and centred in [ow] x [oh]; null when the shapes are too far apart. */
    fun plan(w: Int, h: Int, ow: Int, oh: Int): Plan? {
        if (w <= 0 || h <= 0 || ow <= 0 || oh <= 0) return null
        if (w == ow && h == oh) return Plan(ow, oh, 0)
        val rh = ((ow.toDouble() * h / w).roundToInt() and 1.inv()).coerceAtLeast(2)
        val change = maxOf(rh, oh).toDouble() / minOf(rh, oh)
        if (change > MAX_SHAPE_CHANGE) return null
        return Plan(ow, rh, (oh - rh) / 2)
    }
}

/**
 * Servers a copy is not read from for now: one that turned a copy request away ([refused], for [REFUSED_MS]), and
 * one whose playback stalled or failed soon after a copy request ([trouble], for the rest of the day).
 */
internal class CopyHostBlocks {
    companion object {
        const val REFUSED_MS = 30L * 60 * 1000

        val app = CopyHostBlocks()

        /** Local midnight after [nowMs]. */
        fun endOfDayMs(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Long =
            Calendar.getInstance(zone).apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
    }

    private class Block(val untilMs: Long, val why: String)

    private val blocks = java.util.concurrent.ConcurrentHashMap<String, Block>()

    fun refused(host: String, nowMs: Long) {
        blocks.merge(host, Block(nowMs + REFUSED_MS, "turned a copy request away")) { a, b -> if (a.untilMs >= b.untilMs) a else b }
    }

    fun trouble(host: String, nowMs: Long) {
        blocks[host] = Block(maxOf(endOfDayMs(nowMs), blocks[host]?.untilMs ?: 0L), "playback had trouble soon after a copy request")
    }

    /** Why [host] is left alone at [nowMs], null when it is not. */
    fun blocked(host: String, nowMs: Long): String? {
        val b = blocks[host] ?: return null
        if (nowMs < b.untilMs) return b.why
        blocks.remove(host, b)
        return null
    }

    /** Playback trouble at [troubleAtMs] during a copy request or within [windowMs] after it: the copy is blamed. */
    fun blames(requestStartMs: Long, requestEndMs: Long, troubleAtMs: Long, windowMs: Long): Boolean =
        requestStartMs > 0L && troubleAtMs >= requestStartMs && troubleAtMs <= maxOf(requestStartMs, requestEndMs) + windowMs
}
