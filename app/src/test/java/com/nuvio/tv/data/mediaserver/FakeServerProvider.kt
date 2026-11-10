package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay

internal class FakeServerProvider(
    private val movieCount: Int = 120,
    private val transcodes: Boolean = false,
    private val offersTranscode: Boolean = false,
    private val transcodeFailure: ServerFailure? = null,
    override val id: String = "fake",
    homeRows: Boolean = false
) : ServerProvider {
    override val displayName: String = "Fake"
    override val minimumVersion: String = "1.0"
    override val capabilities: Set<ServerCapability> = setOf(
        ServerCapability.SEARCH,
        ServerCapability.EXTERNAL_ID_LOOKUP,
        ServerCapability.USER_STATE_READ,
        ServerCapability.USER_STATE_WRITE
    ) + listOfNotNull(ServerCapability.HOME_ROWS.takeIf { homeRows })

    val indexedIds = mutableMapOf<String, TrackingExternalIds>()
    val episodes = mutableMapOf<Pair<Int, Int>, ServerEpisode>()
    val seriesEpisodes = mutableMapOf<String, Map<Pair<Int, Int>, ServerEpisode>>()
    val goneItems = mutableSetOf<String>()
    val reported = mutableListOf<ServerPlaybackEventType>()
    val reportedPositions = mutableListOf<Long>()
    val playbackRequests = mutableListOf<ServerPlaybackRequest>()
    val playedChanges = mutableListOf<Pair<String, Boolean>>()
    val failingPlayed = mutableSetOf<String>()
    val failingLibraries = mutableSetOf<String>()
    val userStates = mutableMapOf<String, List<ServerUserState>>()
    val seriesStates = mutableMapOf<String, List<ServerUserState>>()
    var failingSeriesStates = false
    val segmentRequests = mutableListOf<Pair<String, String?>>()
    var segments: List<ServerSegment> = emptyList()
    val itemCandidates = mutableMapOf<String, List<ServerCandidate>>()
    var detailsDelayMs = 0L
    var failingDetails = false
    var detailsRequests = 0
    var homeRowsToReturn: List<ServerHomeRow> = emptyList()
    @Volatile var homeRowReads = 0

    override suspend fun homeRows(session: ServerSession): List<ServerHomeRow> {
        homeRowReads++
        return homeRowsToReturn
    }

    val downAddresses = mutableSetOf<String>()
    var streamLimit: Int? = null
    var streamsElsewhere = 0
    var playbackFailure: ServerFailure? = null
    var stopReportDelayMs = 0L
    val playing = mutableSetOf<String>()
    val failingWithStatus = mutableSetOf<String>()
    val locations = mutableMapOf<String, ServerLocation>()
    val libraryCallAddresses = mutableListOf<String>()
    val libraryConnectLimits = mutableListOf<Long?>()
    val playbackAddresses = mutableListOf<String>()
    val reportAddresses = mutableListOf<String>()
    var titleLookup: (suspend (ServerLibrary, ServerTitleQuery) -> List<ServerIndexEntry>?)? = null
    val titleQueries = mutableListOf<ServerTitleQuery>()
    val signIns = mutableListOf<Pair<String, String>>()
    var indexRequests = 0
    val libraryRequests = mutableListOf<LibraryRequest>()
    val genres = mutableMapOf<String, List<ServerGenre>>()
    var failingGenres = false
    private val genreReadCount = java.util.concurrent.atomic.AtomicInteger()
    val genreReads: Int get() = genreReadCount.get()

    var listed: ServerAddresses? = null
    var listedRequests = 0

    override suspend fun listedAddresses(session: ServerSession): ServerAddresses? {
        listedRequests++
        return listed
    }

    override suspend fun locate(address: String): ServerLocation? = locations[address]?.takeIf { address !in downAddresses }

    override suspend fun libraries(session: ServerSession): List<ServerLibrary> {
        libraryCallAddresses += session.address
        libraryConnectLimits += currentCoroutineContext()[ServerConnectTimeout]?.millis
        if (session.address in downAddresses) throw ServerException(ServerFailure.UNREACHABLE, network = true)
        if (session.address in failingWithStatus) throw ServerException(ServerFailure.UNREACHABLE, "HTTP 502")
        return listOf(MOVIE_LIBRARY, SERIES_LIBRARY)
    }

    override suspend fun libraryPage(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int,
        sort: ServerSort,
        genreId: String?,
        filter: ServerLibraryFilter
    ): ServerPage<ServerTitle> {
        if (library.id in failingLibraries) throw ServerException(ServerFailure.UNREACHABLE)
        libraryRequests += LibraryRequest(library.id, sort, genreId)
        libraryFilters += filter
        val count = if (genreId == null) movieCount else GENRE_ITEMS
        val ids = (start until minOf(start + limit, count)).map { it.toString() }
        return ServerPage(ids.map { title(session, it) }, count)
    }

    override suspend fun libraryGenres(session: ServerSession, library: ServerLibrary): List<ServerGenre> {
        genreReadCount.incrementAndGet()
        if (failingGenres) throw ServerException(ServerFailure.UNREACHABLE)
        return genres[library.id].orEmpty()
    }

    var artworkBase: String? = null

    val libraryFilters = mutableListOf<ServerLibraryFilter>()

    data class LibraryRequest(val libraryId: String, val sort: ServerSort, val genreId: String?)

    override suspend fun collectionPage(
        session: ServerSession,
        collectionId: String,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle> = ServerPage(
        listOf(title(session, "7"), title(session, SHOW_ID, ServerMediaKind.SERIES)),
        2
    )

    var searchDelayMs = 0L
    val searchQueries: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val searchEvents: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    private val searchesRunning = java.util.concurrent.atomic.AtomicInteger()
    private val mostSearches = java.util.concurrent.atomic.AtomicInteger()
    val mostSearchesAtOnce: Int get() = mostSearches.get()
    val searches: MutableList<Pair<ServerMediaKind, List<Pair<String, ServerMediaKind>>>> =
        java.util.Collections.synchronizedList(mutableListOf())

    override suspend fun search(
        session: ServerSession,
        kind: ServerMediaKind,
        libraries: List<ServerLibrary>,
        query: String,
        limit: Int
    ): List<ServerTitle> {
        searches += kind to libraries.map { it.id to it.kind }
        searchQueries += query
        searchEvents += "start $query"
        mostSearches.accumulateAndGet(searchesRunning.incrementAndGet(), ::maxOf)
        try {
            delay(searchDelayMs)
            searchEvents += "end $query"
        } catch (error: kotlinx.coroutines.CancellationException) {
            searchEvents += "dropped $query"
            throw error
        } finally {
            searchesRunning.decrementAndGet()
        }
        return listOf(title(session, "7"))
    }

    val resumeEntries = mutableListOf<ServerResumeEntry>()
    var resumeRequests = 0

    override suspend fun resumeItems(session: ServerSession, limit: Int): List<ServerResumeEntry> {
        resumeRequests++
        return resumeEntries.toList()
    }

    override suspend fun externalIds(session: ServerSession, itemId: String): TrackingExternalIds =
        indexedIds[itemId] ?: TrackingExternalIds()

    fun resumeMovie(connection: ServerConnection, itemId: String, positionMs: Long, lastPlayed: Long?) = ServerResumeEntry(
        title = title(ServerSession(connection, "token"), itemId),
        state = ServerUserState(
            videoId = ServerItemRef(connection.id, itemId).encode(),
            positionMs = positionMs,
            durationMs = 6_000_000L,
            played = false,
            lastPlayedEpochMs = lastPlayed
        )
    )

    fun resumeEpisode(connection: ServerConnection, episodeItemId: String, season: Int?, episode: Int?, positionMs: Long, lastPlayed: Long?) =
        ServerResumeEntry(
            title = ServerTitle(title(ServerSession(connection, "token"), SHOW_ID, ServerMediaKind.SERIES).preview),
            state = ServerUserState(
                videoId = ServerItemRef(connection.id, episodeItemId).encode(),
                positionMs = positionMs,
                durationMs = 2_400_000L,
                played = false,
                lastPlayedEpochMs = lastPlayed,
                season = season,
                episode = episode,
                title = "Episode $episode"
            )
        )

    override suspend fun details(session: ServerSession, itemId: String): ServerItemDetails {
        detailsRequests++
        if (detailsDelayMs > 0L) delay(detailsDelayMs)
        if (failingDetails) throw ServerException(ServerFailure.UNREACHABLE, network = true)
        val kind = if (itemId == SHOW_ID) ServerMediaKind.SERIES else ServerMediaKind.MOVIE
        return ServerItemDetails(
            meta = meta(
                id = ServerItemRef(session.connection.id, itemId).encode(),
                kind = kind,
                name = "Item $itemId",
                videos = if (kind == ServerMediaKind.SERIES) {
                    listOf(video(ServerItemRef(session.connection.id, EPISODE_ID).encode(), season = 1, episode = 1))
                } else {
                    emptyList()
                }
            ),
            externalIds = indexedIds[itemId] ?: TrackingExternalIds(),
            userStates = userStates[itemId].orEmpty()
        )
    }

    override suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate> = itemCandidates[itemId] ?: listOf(
        ServerCandidate(
            target = ServerPlaybackTarget(ServerItemRef(session.connection.id, itemId), mediaSourceId = "src-$itemId"),
            title = "Original",
            filename = "item-$itemId.mkv",
            sizeBytes = null
        )
    )

    override suspend fun preparePlayback(session: ServerSession, request: ServerPlaybackRequest): ServerPlaybackSession {
        playbackRequests += request
        playbackAddresses += session.address
        if (session.address in downAddresses) throw ServerException(ServerFailure.UNREACHABLE, network = true)
        playbackFailure?.let { throw ServerException(it) }
        streamLimit?.let { limit ->
            if (synchronized(playing) { playing.size } + streamsElsewhere >= limit) throw ServerException(ServerFailure.STREAM_LIMIT)
        }
        if (transcodes) {
            val audio = request.audioStreamIndex ?: 1
            val subtitle = request.subtitleStreamIndex
            return ServerPlaybackSession(
                target = request.target,
                mediaSourceId = request.target.mediaSourceId ?: "default",
                url = "https://fake.example/${request.target.item.itemId}/master.m3u8?AudioStreamIndex=$audio&SubtitleStreamIndex=$subtitle",
                headers = emptyMap(),
                subtitles = emptyList(),
                playSessionId = "ps$audio",
                playMethod = ServerPlayMethod.TRANSCODE,
                audioTracks = listOf(
                    ServerTrack(index = 1, label = "English", language = "eng", selected = audio == 1),
                    ServerTrack(index = 2, label = "Japanese", language = "jpn", selected = audio == 2)
                ),
                burnInSubtitles = listOf(
                    ServerTrack(index = 3, label = "English PGS", language = "eng", selected = subtitle == 3)
                )
            )
        }
        if (!request.capabilities.allowDirectPlay) {
            if (!offersTranscode || transcodeFailure != null) {
                throw ServerException(transcodeFailure ?: ServerFailure.UNSUPPORTED)
            }
            return ServerPlaybackSession(
                target = request.target,
                mediaSourceId = request.target.mediaSourceId ?: "default",
                url = "https://fake.example/${request.target.item.itemId}/master.m3u8",
                headers = emptyMap(),
                subtitles = emptyList(),
                playSessionId = "transcode",
                playMethod = ServerPlayMethod.TRANSCODE
            )
        }
        return ServerPlaybackSession(
            target = request.target,
            mediaSourceId = request.target.mediaSourceId ?: "default",
            url = "https://fake.example/${request.target.item.itemId}",
            headers = emptyMap(),
            subtitles = emptyList(),
            playSessionId = null,
            playMethod = ServerPlayMethod.DIRECT_PLAY,
            transcodeOffered = offersTranscode
        )
    }

    override suspend fun setPlayed(session: ServerSession, itemId: String, played: Boolean) {
        synchronized(playedChanges) { playedChanges += itemId to played }
        if (itemId in failingPlayed) throw ServerException(ServerFailure.FORBIDDEN)
    }

    override suspend fun report(session: ServerSession, playback: ServerPlaybackSession, event: ServerPlaybackEvent) {
        if (event.type == ServerPlaybackEventType.STOP && stopReportDelayMs > 0L) kotlinx.coroutines.delay(stopReportDelayMs)
        synchronized(reported) {
            reported += event.type
            reportedPositions += event.positionMs
            reportAddresses += session.address
        }
        synchronized(playing) {
            when (event.type) {
                ServerPlaybackEventType.START -> playing += playback.url
                ServerPlaybackEventType.STOP -> playing -= playback.url
                else -> Unit
            }
        }
    }

    override suspend fun externalIdIndex(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerIndexEntry> {
        synchronized(this) { indexRequests++ }
        val entries = indexedIds.entries
            .filter { (itemId, _) -> (itemId == SHOW_ID) == (library.kind == ServerMediaKind.SERIES) }
            .map { ServerIndexEntry(it.key, it.value) }
        return ServerPage(entries.drop(start).take(limit), entries.size)
    }

    override suspend fun lookupTitle(
        session: ServerSession,
        library: ServerLibrary,
        query: ServerTitleQuery
    ): List<ServerIndexEntry>? {
        val lookup = titleLookup ?: return null
        synchronized(titleQueries) { titleQueries += query }
        return lookup(library, query)
    }

    override suspend fun findEpisode(session: ServerSession, seriesItemId: String, season: Int, episode: Int): ServerEpisode? {
        if (seriesItemId in goneItems) throw ServerException(ServerFailure.NOT_FOUND)
        seriesEpisodes[seriesItemId]?.let { return it[season to episode] }
        return episodes[season to episode]
    }

    override fun authHeaders(session: ServerSession): Map<String, String> = mapOf("X-Test-Token" to session.token)

    override suspend fun signIn(address: String, username: String, password: String): ServerSignIn = run {
        signIns += username to password
        signInResult(address, username)
    }

    private fun signInResult(address: String, username: String) = ServerSignIn(
        address = address,
        serverName = "Box",
        serverId = "server-1",
        userId = "user-1",
        userName = username,
        token = "password-token"
    )

    override suspend fun quickConnectSignIn(ticket: ServerQuickConnect): ServerSignIn = ServerSignIn(
        address = ticket.address,
        serverName = ticket.serverName,
        serverId = ticket.serverId ?: "server-1",
        userId = "user-1",
        userName = "viewer",
        token = "code-token"
    )

    override suspend fun segments(session: ServerSession, itemId: String, mediaSourceId: String?): List<ServerSegment> {
        segmentRequests += itemId to mediaSourceId
        return segments
    }

    override suspend fun episodeStates(session: ServerSession, seriesItemId: String): List<ServerUserState> {
        if (failingSeriesStates) throw ServerException(ServerFailure.UNREACHABLE)
        return seriesStates[seriesItemId].orEmpty()
    }

    private fun title(session: ServerSession, itemId: String, kind: ServerMediaKind = ServerMediaKind.MOVIE) = ServerTitle(
        preview = MetaPreview(
            id = ServerItemRef(session.connection.id, itemId).encode(),
            type = kind.domainType(),
            rawType = kind.contentType,
            name = "Item $itemId",
            poster = artworkBase?.let { "$it/poster/$itemId.jpg" },
            posterShape = PosterShape.POSTER,
            background = artworkBase?.let { "$it/backdrop/$itemId.jpg" },
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        ),
        externalIds = indexedIds[itemId] ?: TrackingExternalIds()
    )

    companion object {
        const val SHOW_ID = "500"
        const val EPISODE_ID = "901"
        const val GENRE_ITEMS = 3
        val MOVIE_LIBRARY = ServerLibrary(id = "10", name = "Movies", kind = ServerMediaKind.MOVIE)
        val SERIES_LIBRARY = ServerLibrary(id = "20", name = "Shows", kind = ServerMediaKind.SERIES)
        val COLLECTION_LIBRARY = ServerLibrary(id = "30", name = "Featured", kind = ServerMediaKind.COLLECTION)
    }
}

internal class MemoryServerPersistence : ServerPersistence {
    val values = mutableMapOf<Int, String>()
    override fun read(profileId: Int): String? = values[profileId]
    override fun write(profileId: Int, value: String?) {
        if (value == null) values.remove(profileId) else values[profileId] = value
    }
    override fun clear() = values.clear()
}

internal fun fakeServerRepository(
    provider: FakeServerProvider = FakeServerProvider(),
    connectionId: String = "cfake",
    libraries: List<ServerLibrary> = listOf(FakeServerProvider.MOVIE_LIBRARY, FakeServerProvider.SERIES_LIBRARY)
): Pair<ServerRepository, ServerConnection> {
    val repository = ServerRepository(MemoryServerPersistence(), listOf(provider), CoroutineScope(Dispatchers.Unconfined))
    val connection = ServerConnection(
        id = connectionId,
        providerId = provider.id,
        name = "Box",
        address = "https://fake.example",
        remoteServerId = "server-1",
        remoteUserId = "user-1",
        userName = "viewer",
        credentialRef = "k$connectionId",
        libraries = libraries
    )
    repository.store(connection, token = "token")
    return repository to connection
}

internal fun meta(id: String, kind: ServerMediaKind, name: String, videos: List<Video> = emptyList(), imdbId: String? = null) = Meta(
    id = id,
    type = kind.domainType(),
    rawType = kind.contentType,
    name = name,
    poster = null,
    posterShape = PosterShape.POSTER,
    background = null,
    logo = null,
    description = null,
    releaseInfo = null,
    imdbRating = null,
    genres = emptyList(),
    runtime = null,
    director = emptyList(),
    cast = emptyList(),
    videos = videos,
    country = null,
    awards = null,
    language = null,
    links = emptyList(),
    imdbId = imdbId
)

internal fun video(id: String, season: Int, episode: Int) = Video(
    id = id,
    title = "Episode $episode",
    released = null,
    thumbnail = null,
    season = season,
    episode = episode,
    overview = null
)
