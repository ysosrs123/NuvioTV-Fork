package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.tracking.TrackingExternalIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

enum class ServerCapability {
    SEARCH,
    EXTERNAL_ID_LOOKUP,
    USER_STATE_READ,
    USER_STATE_WRITE,
    TRANSCODING,
    HOME_ROWS
}

interface ServerProvider {
    val id: String
    val displayName: String
    val capabilities: Set<ServerCapability>
    val minimumVersion: String

    suspend fun signIn(address: String, username: String, password: String): ServerSignIn = unsupported()

    /** Reads the public server identity at [address] without signing in. */
    suspend fun locate(address: String): ServerLocation? = null

    val supportsQuickConnect: Boolean
        get() = false

    /** Asks the server for a sign-in code; null when the server has Quick Connect switched off. */
    suspend fun quickConnectStart(address: String): ServerQuickConnect? = unsupported()

    /** True once the code was approved; [ServerFailure.NOT_FOUND] when it expired. */
    suspend fun quickConnectApproved(ticket: ServerQuickConnect): Boolean = unsupported()

    suspend fun quickConnectSignIn(ticket: ServerQuickConnect): ServerSignIn = unsupported()

    suspend fun libraries(session: ServerSession): List<ServerLibrary>

    suspend fun libraryPage(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int,
        sort: ServerSort = ServerSort.ADDED,
        genreId: String? = null,
        filter: ServerLibraryFilter = ServerLibraryFilter.NONE
    ): ServerPage<ServerTitle>

    /** The genres [library] can be narrowed to; empty when the server lists none. */
    suspend fun libraryGenres(session: ServerSession, library: ServerLibrary): List<ServerGenre> = emptyList()

    suspend fun details(session: ServerSession, itemId: String): ServerItemDetails

    suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate>

    suspend fun preparePlayback(session: ServerSession, request: ServerPlaybackRequest): ServerPlaybackSession

    suspend fun report(session: ServerSession, playback: ServerPlaybackSession, event: ServerPlaybackEvent) = Unit

    suspend fun signOut(session: ServerSession) = Unit

    suspend fun collectionPage(
        session: ServerSession,
        collectionId: String,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle> = unsupported()

    /** One search for [kind] across [libraries], which all hold that kind. */
    suspend fun search(
        session: ServerSession,
        kind: ServerMediaKind,
        libraries: List<ServerLibrary>,
        query: String,
        limit: Int
    ): List<ServerTitle> = unsupported()

    suspend fun resumeItems(session: ServerSession, limit: Int): List<ServerResumeEntry> = unsupported()

    suspend fun externalIds(session: ServerSession, itemId: String): TrackingExternalIds = unsupported()

    suspend fun setPlayed(session: ServerSession, itemId: String, played: Boolean): Unit = unsupported()

    suspend fun externalIdIndex(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerIndexEntry> = unsupported()

    /** Where the server is now according to its account service, for when no saved address answers; null without one. */
    suspend fun listedAddresses(session: ServerSession): ServerAddresses? = null

    /** Items of [library] that may be [query]; null when the server cannot look up a single title this way. */
    suspend fun lookupTitle(
        session: ServerSession,
        library: ServerLibrary,
        query: ServerTitleQuery
    ): List<ServerIndexEntry>? = null

    suspend fun findEpisode(
        session: ServerSession,
        seriesItemId: String,
        season: Int,
        episode: Int
    ): ServerEpisode? = unsupported()

    suspend fun episodeStates(session: ServerSession, seriesItemId: String): List<ServerUserState> = unsupported()

    suspend fun segments(session: ServerSession, itemId: String, mediaSourceId: String?): List<ServerSegment> = emptyList()

    suspend fun chapters(session: ServerSession, itemId: String, mediaSourceId: String?): List<ServerChapter> = emptyList()

    /** How this server serves seek thumbnails, null when it has none. */
    val thumbnailFormat: ServerThumbnailFormat?
        get() = null

    /** The trickplay widths made for [mediaSourceId]; null when none are made. */
    suspend fun trickplay(session: ServerSession, itemId: String, mediaSourceId: String): ServerTrickplaySet? = null

    suspend fun trickplaySheet(
        session: ServerSession,
        itemId: String,
        mediaSourceId: String,
        width: Int,
        index: Int,
        maxBytes: Long
    ): ByteArray = unsupported()

    /** The BIF file, or [range] of it; [ServerFailure.NOT_FOUND] when the server made none. */
    suspend fun bif(
        session: ServerSession,
        itemId: String,
        mediaSourceId: String,
        width: Int,
        range: LongRange?,
        maxBytes: Long
    ): ServerBytes = unsupported()

    /** Tracking services (storage ids such as "trakt") the server itself sends this profile's plays to. */
    suspend fun scrobbledServices(session: ServerSession): Set<String> = emptySet()

    /** The rows of the server's own home page; null when it has none to offer. */
    suspend fun homeRows(session: ServerSession): List<ServerHomeRow>? = null

    suspend fun homeRowItems(session: ServerSession, rowId: String): List<ServerTitle> = unsupported()

    fun authHeaders(session: ServerSession): Map<String, String> = emptyMap()

    /** True when [session]'s token runs out soon and is better renewed before the next call. */
    fun renewalDue(session: ServerSession): Boolean = false

    /**
     * A renewed token for [session], after [failure] or ahead of time when [failure] is null; null when this server
     * can't renew it. Throws [ServerFailure.AUTH_REQUIRED] once the server has ended the sign-in.
     */
    suspend fun renewCredential(session: ServerSession, failure: ServerException?): String? = null

    /** A link that reads [target]'s file as it is (range requests), without opening a playback session; null when none. */
    fun staticStreamUrl(session: ServerSession, target: ServerPlaybackTarget): String? = null
}

enum class ServerSort(val key: String) {
    ADDED("added"),
    TITLE("title"),
    RELEASED("released");

    companion object {
        fun fromKey(key: String?): ServerSort? = entries.firstOrNull { it.key == key }
    }
}

/** Narrows a library list: titles whose sort title starts with [letter], and only the ones not watched yet. */
data class ServerLibraryFilter(val letter: Char? = null, val unwatched: Boolean = false) {
    companion object {
        val NONE = ServerLibraryFilter()
        val LETTERS: List<Char> = ('A'..'Z').toList()

        fun of(letter: String?, unwatched: String?): ServerLibraryFilter = ServerLibraryFilter(
            letter = letter?.trim()?.singleOrNull()?.uppercaseChar()?.takeIf { it in LETTERS },
            unwatched = unwatched.toBoolean()
        )
    }
}

data class ServerGenre(
    val id: String,
    val name: String
)

data class GenreSummary(val count: Int?, val picture: String?)

data class ServerIndexEntry(
    val itemId: String,
    val ids: TrackingExternalIds,
    val year: Int? = null
)

data class ServerTitleQuery(
    val ids: TrackingExternalIds,
    val name: String?,
    val year: Int?,
    val originalName: String? = null
)

data class ServerEpisode(
    val itemId: String,
    val premiereDate: String?
)

fun ServerProvider.supports(capability: ServerCapability): Boolean = capability in capabilities

/** Runs [search] for every scope side by side; fails only when every scope failed. */
internal suspend fun <S, T> searchEach(scopes: List<S>, search: suspend (S) -> List<T>): List<T> {
    val results = coroutineScope {
        scopes.map { scope ->
            async {
                try {
                    Result.success(search(scope))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Result.failure<List<T>>(error)
                }
            }
        }.awaitAll()
    }
    results.firstOrNull { it.isFailure }?.takeIf { results.all { it.isFailure } }?.exceptionOrNull()?.let { throw it }
    return results.flatMap { it.getOrDefault(emptyList()) }
}

private fun unsupported(): Nothing = throw ServerException(ServerFailure.UNSUPPORTED)
