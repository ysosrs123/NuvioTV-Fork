package com.nuvio.tv.data.mediaserver.silo

import com.nuvio.tv.data.mediaserver.ServerChapter
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerPlayMethod
import com.nuvio.tv.data.mediaserver.ServerPlaybackEvent
import com.nuvio.tv.data.mediaserver.ServerPlaybackEventType
import com.nuvio.tv.data.mediaserver.ServerPlaybackRequest
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.data.mediaserver.ServerPlayerCapabilities
import com.nuvio.tv.data.mediaserver.ServerSegment
import com.nuvio.tv.data.mediaserver.ServerSegmentKind
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.ServerSignInIssue
import com.nuvio.tv.data.mediaserver.mediabrowser.TestHttp
import com.nuvio.tv.data.mediaserver.mediabrowser.testIdentity
import com.nuvio.tv.data.mediaserver.mediabrowser.text
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SiloNativePlaybackTest {
    private val base = "http://silo.local:8090"
    private val credential = """{"access":"acc","refresh":"ref","expires_at_ms":${Long.MAX_VALUE},"profile_id":"p1","profile_name":"Laura"}"""
    private val capabilities = """{"revision":"r","state":"available","allowed":true,"installation_id":"11111111-1111-4111-8111-111111111111",
        "protocol_versions":[3],"features":["playback_plan_v3","sequenced_progress_v1"],"deliveries":["original_http","server_transcode_hls"]}"""
    private val watch = """{"content_id":"movie-tmdb-603","type":"movie","title":"The Matrix","subtitles":[],
        "user_data":{"played":false,"watched_count":0,"unplayed_count":0,"in_progress_count":0,"last_file_id":"43"},
        "versions":[
          {"file_id":"42","resolution":"1080p","codec_video":"h264","codec_audio":"ac3","hdr":false,"container":"mkv","file_size":4000000000,
           "bitrate":9000,"duration_seconds":8160,"added_at":"2026-01-01T00:00:00.000Z","marker_segments":[],"trickplay_available":false,
           "audio_tracks":[{"codec":"ac3","layout":"5.1(side)","channels":6,"language":"eng","default":true}]},
          {"file_id":"43","resolution":"2160p","codec_video":"hevc","codec_audio":"truehd","hdr":true,"container":"matroska","file_size":60000000000,
           "bitrate":60000,"duration_seconds":8160,"added_at":"2026-01-01T00:00:00.000Z","edition_raw":"Remastered","trickplay_available":false,
           "video_tracks":[{"codec":"hevc","dv_profile":7,"dv_bl_compat_id":6,"video_range_type":"DOVIWithEL","interlaced":false,"dv_config_present":true,"dv_bl_compat_id_present":true}],
           "audio_tracks":[{"codec":"truehd","profile":"Dolby TrueHD + Dolby Atmos","layout":"7.1","channels":8,"language":"eng","default":true},
                           {"codec":"ac3","layout":"stereo","channels":2,"language":"ita","default":false}],
           "marker_segments":[{"kind":"intro","start_seconds":30.5,"end_seconds":95},{"kind":"credits","start_seconds":7900,"end_seconds":8160},{"kind":"chapter","start_seconds":0,"end_seconds":1}],
           "chapters":[{"index":1,"title":"Opening","start_seconds":0,"end_seconds":300,"source":"embedded"},
                       {"index":2,"title":" The Lobby ","start_seconds":300.25,"end_seconds":900,"source":"embedded"},
                       {"index":3,"title":"","start_seconds":900,"end_seconds":8160,"source":"embedded"}]}]}"""

    private fun decision(delivery: String = "original_http", seekable: Boolean = true) = """{"protocol_version":3,"server_features":[],"outcome":"playable","session_id":"s-1",
        "playback_plan":{"protocol_version":3,"plan_id":"plan:1","plan_attempt_key":"v3:1","session_id":"s-1","delivery":"$delivery",
          "stream":{"url":"/api/v2/stream/s-1?st=tok","protocol":"http_progressive","headers":{},"header_refresh":"none"},
          "timeline":{"source_start_seconds":0,"stream_origin_seconds":0,"player_start_seconds":0,"timeline_offset_seconds":${if (delivery == "server_remux_hls") 12.0 else 0.0},"can_seek_anywhere":$seekable,"seek_restoration":"player_position"},
          "selected_tracks":{"audio":{"id":"file:43:audio:0","index":0}},
          "subtitle":{"mode":"off","inventory":[
            {"track_id":"file:43:subtitle:0","combined_index":0,"source":"external","codec":"srt","language":"eng","label":"English","forced":false,"default":false,"hearing_impaired":false,"delivery":"sidecar","url":"/api/v2/stream/s-1/subtitles/0.vtt?file_id=43"},
            {"track_id":"file:43:subtitle:1","combined_index":1,"source":"embedded","codec":"subrip","language":"fre","label":"French","forced":false,"default":false,"hearing_impaired":false,"delivery":"sidecar","url":"/api/v2/stream/s-1/subtitles/1.vtt?file_id=43"},
            {"track_id":"file:43:subtitle:2","combined_index":2,"source":"embedded","codec":"dvd_subtitle","language":"ger","label":"German","forced":false,"default":false,"hearing_impaired":false,"delivery":"burn_in_only"}]},
          "available_qualities":[{"label":"original","preserves_source":true}],"degradation_warnings":[${if (delivery == "original_http") "" else """{"code":"audio_converted","message":"m"}"""}],
          "decision_reason":"${if (delivery == "original_http") "client_managed_dynamic_range" else "container_normalization"}",
          "requested_media_file_id":"43","effective_media_file_id":"43","source":{"media_file_id":"43","duration_seconds":8160}}}"""

    private class Reply(val status: Int, val body: String)

    private fun silo(start: String = decision(), routes: (Request) -> Reply? = { null }): Pair<SiloNativeProvider, TestHttp> {
        val reply = { request: Request ->
            routes(request) ?: when (request.url.encodedPath) {
                "/api/v2/playback/capabilities" -> Reply(200, capabilities)
                "/api/v2/watch/movie-tmdb-603" -> Reply(200, watch)
                "/api/v2/playback/start" -> Reply(201, start)
                "/api/v2/playback/s-1/progress" -> Reply(200, """{"outcome":"applied"}""")
                "/api/v2/playback/s-1" -> Reply(200, """{"outcome":"stopped"}""")
                else -> Reply(404, """{"type":"https://siloserver.org/docs/api/v2/problems/not_found","title":"x","status":404,"detail":"","instance":"i"}""")
            }
        }
        val http = TestHttp(status = { reply(it).status }, responder = { reply(it).body })
        return SiloNativeProvider(http.client, testIdentity, now = { 1_000L }) to http
    }

    private fun session() = ServerSession(
        ServerConnection("c1", SiloNativeProvider.ID, "Silo", base, "srv", "1", "laura", "k1"),
        credential,
        base
    )

    private fun request(fileId: String? = null, direct: Boolean = true) = ServerPlaybackRequest(
        ServerPlaybackTarget(ServerItemRef("c1", "movie-tmdb-603"), fileId),
        ServerPlayerCapabilities(allowDirectPlay = direct)
    )

    @Test
    fun versionsShowTheirPictureSoundAndEdition() = runTest {
        val (provider, _) = silo()

        val candidates = provider.candidates(session(), "movie-tmdb-603")

        assertEquals(listOf("42", "43"), candidates.map { it.target.mediaSourceId })
        val uhd = candidates.last()
        assertEquals("2160p", uhd.title)
        assertEquals("HEVC • DV P7.6", uhd.video)
        assertEquals("eng TrueHD Atmos 7.1", uhd.audio)
        assertEquals("MKV", uhd.container)
        assertEquals("Remastered", uhd.versionName)
        assertEquals(8_160_000L, uhd.durationMs)
        assertEquals(60_000_000L, uhd.bitrateBps)
        assertEquals("AVC", candidates.first().video)
    }

    @Test
    fun directPlayAsksForTheOriginalFileAndLetsThePlayerHandleDolbyVision() = runTest {
        val (provider, http) = silo()

        val playback = provider.preparePlayback(session(), request())

        assertEquals(ServerPlayMethod.DIRECT_PLAY, playback.playMethod)
        assertEquals("http://silo.local:8090/api/v2/stream/s-1?st=tok", playback.url)
        assertEquals("43", playback.mediaSourceId)
        assertEquals(mapOf("Authorization" to "Bearer acc", "X-Profile-Id" to "p1"), playback.headers)
        assertEquals(listOf("silo:file:43:subtitle:0"), playback.subtitles.map { it.id })
        assertEquals(mapOf("Authorization" to "Bearer acc", "X-Profile-Id" to "p1"), playback.subtitles.single().headers)
        assertEquals(listOf("German"), playback.burnInSubtitles.map { it.label })
        assertEquals(listOf(true, false), playback.audioTracks.map { it.selected })
        assertTrue(playback.transcodeOffered)
        assertTrue(playback.transcodeReasons.isEmpty())

        val start = http.requests.single { it.url.encodedPath == "/api/v2/playback/start" }
        val body = Json.parseToJsonElement(start.text).jsonObject
        assertEquals("11111111-1111-4111-8111-111111111111", body["installation_id"]!!.jsonPrimitive.content)
        assertEquals("43", body["file_id"]!!.jsonPrimitive.content)
        assertEquals("p1", body["profile_id"]!!.jsonPrimitive.content)
        assertEquals("p1", start.header("X-Profile-Id"))
        assertEquals("device-1", start.header("X-Device-ID"))
        val original = body["client_playback_context"]!!.jsonObject["deliveries"]!!.jsonObject["original_http"]!!.jsonObject
        assertEquals(
            listOf("client_managed_dynamic_range_v1", "client_selected_audio_track_v1"),
            original["validated_claims"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        val hdr = body["client_capabilities"]!!.jsonObject["hdr_details"]!!.jsonObject
        assertEquals(listOf("4", "5", "7", "8"), hdr["dolby_vision_profiles"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse("null" in start.text)
    }

    @Test
    fun silosAutoQualityIsAskedForWithoutTheRetiredMeteredFlag() = runTest {
        val (provider, http) = silo()

        provider.preparePlayback(session(), request())

        val body = Json.parseToJsonElement(http.requests.single { it.url.encodedPath == "/api/v2/playback/start" }.text).jsonObject
        assertEquals("auto", body["quality_preference"]!!.jsonPrimitive.content)
        assertFalse("metered" in body)
        assertFalse("allow_alternate_versions" in body)
    }

    @Test
    fun anOlderSiloThatRejectsTheNewStartGetsTheOldOneOnceAndFromThenOn() = runTest {
        val rejected = """{"type":"https://siloserver.org/docs/api/v2/problems/validation_failed","title":"Validation failed","status":422,
            "detail":"The request did not pass validation; see errors.","instance":"i","errors":[{"message":"expected required property metered to be present","location":"body"}]}"""
        val (provider, http) = silo { request ->
            if (request.url.encodedPath == "/api/v2/playback/start" && "\"metered\"" !in request.text) Reply(422, rejected) else null
        }

        assertEquals(ServerPlayMethod.DIRECT_PLAY, provider.preparePlayback(session(), request()).playMethod)
        provider.preparePlayback(session(), request())

        val starts = http.requests.filter { it.url.encodedPath == "/api/v2/playback/start" }.map { Json.parseToJsonElement(it.text).jsonObject }
        assertEquals(listOf("auto", "original", "original"), starts.map { it["quality_preference"]!!.jsonPrimitive.content })
        assertEquals(listOf(null, "false", "false"), starts.map { it["metered"]?.jsonPrimitive?.content })
        assertEquals(starts[0]["playback_attempt_id"], starts[1]["playback_attempt_id"])
    }

    @Test
    fun aCurrentSiloThatRejectsAStartForAnotherReasonIsNotMarkedOld() = runTest {
        val rejected = """{"type":"https://siloserver.org/docs/api/v2/problems/validation_failed","title":"Validation failed","status":422,
            "detail":"The request did not pass validation; see errors.","instance":"i","errors":[{"message":"audio_track_index out of range","location":"body"}]}"""
        var refuse = true
        val (provider, http) = silo { request ->
            if (refuse && request.url.encodedPath == "/api/v2/playback/start") Reply(422, rejected) else null
        }

        try {
            provider.preparePlayback(session(), request())
            fail("expected the start to fail")
        } catch (_: ServerException) {
        }
        refuse = false
        provider.preparePlayback(session(), request())

        val starts = http.requests.filter { it.url.encodedPath == "/api/v2/playback/start" }.map { Json.parseToJsonElement(it.text).jsonObject }
        assertEquals(listOf("auto", "original", "auto"), starts.map { it["quality_preference"]!!.jsonPrimitive.content })
    }

    @Test
    fun aStartSiloRefusesForAnotherReasonIsNotRetried() = runTest {
        val (provider, http) = silo { request ->
            if (request.url.encodedPath == "/api/v2/playback/start") {
                Reply(409, """{"type":"https://siloserver.org/docs/api/v2/problems/installation_changed","title":"x","status":409,"detail":"","instance":"i"}""")
            } else null
        }

        try {
            provider.preparePlayback(session(), request())
            fail("expected a refusal")
        } catch (_: ServerException) {
        }
        assertEquals(1, http.requests.count { it.url.encodedPath == "/api/v2/playback/start" })
    }

    @Test
    fun theFallbackAsksForAConvertedStreamOnly() = runTest {
        val (provider, http) = silo(start = decision("server_remux_hls"))

        val playback = provider.preparePlayback(session(), request(fileId = "43", direct = false))

        assertEquals(ServerPlayMethod.DIRECT_STREAM, playback.playMethod)
        assertEquals(listOf("silo:file:43:subtitle:0", "silo:file:43:subtitle:1"), playback.subtitles.map { it.id })
        assertEquals(listOf("container_normalization", "audio_converted"), playback.transcodeReasons)
        assertFalse(playback.transcodeOffered)
        val body = Json.parseToJsonElement(http.requests.single { it.url.encodedPath == "/api/v2/playback/start" }.text).jsonObject
        val deliveries = body["client_playback_context"]!!.jsonObject["deliveries"]!!.jsonObject
        assertEquals(setOf("hls"), deliveries.keys)
    }

    @Test
    fun aCopyThePlayerCannotSeekInIsSwappedForAConversion() = runTest {
        val converted = decision("server_transcode_hls").replace("plan:1", "plan:2").replace("v3:1", "v3:2")
        val (provider, http) = silo(start = decision("server_remux_hls", seekable = false)) { request ->
            if (request.url.encodedPath == "/api/v2/playback/s-1/replan") Reply(200, converted) else null
        }

        val playback = provider.preparePlayback(session(), request(fileId = "43", direct = false))

        assertEquals(ServerPlayMethod.TRANSCODE, playback.playMethod)
        val start = Json.parseToJsonElement(http.requests.single { it.url.encodedPath == "/api/v2/playback/start" }.text).jsonObject
        val replan = Json.parseToJsonElement(http.requests.single { it.url.encodedPath == "/api/v2/playback/s-1/replan" }.text).jsonObject
        assertEquals("failure_recovery", replan["operation"]!!.jsonPrimitive.content)
        assertEquals("plan:1", replan["failed_plan_id"]!!.jsonPrimitive.content)
        assertEquals("v3:1", replan["plan_attempt_key"]!!.jsonPrimitive.content)
        assertEquals(listOf("v3:1"), replan["attempted_plan_keys"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("file:43:audio:0", replan["selected_tracks"]!!.jsonObject["audio"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("player_failure", replan["failure"]!!.jsonObject["classification"]!!.jsonPrimitive.content)
        assertEquals("auto", replan["quality_preference"]!!.jsonPrimitive.content)
        assertFalse("metered" in replan)
        listOf("installation_id", "playback_attempt_id", "client_features", "client_capabilities", "client_playback_context").forEach {
            assertEquals(it, start[it], replan[it])
        }
        assertFalse("null" in http.requests.single { it.url.encodedPath == "/api/v2/playback/s-1/replan" }.text)
        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.PROGRESS, 10_000L, false))
        val progress = Json.parseToJsonElement(http.requests.last().text).jsonObject
        assertEquals(10.0, progress["position"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun withoutAnotherRouteTheCopyIsKept() = runTest {
        val (provider, http) = silo(start = decision("server_remux_hls", seekable = false)) { request ->
            if (request.url.encodedPath == "/api/v2/playback/s-1/replan") {
                Reply(200, """{"protocol_version":3,"server_features":[],"outcome":"adaptation_unavailable",
                    "terminal":{"reason":"no_route","message":"m","retryable":false}}""")
            } else {
                null
            }
        }

        val playback = provider.preparePlayback(session(), request(fileId = "43", direct = false))

        assertEquals(ServerPlayMethod.DIRECT_STREAM, playback.playMethod)
        assertEquals("http://silo.local:8090/api/v2/stream/s-1?st=tok", playback.url)
        assertEquals(1, http.requests.count { it.url.encodedPath == "/api/v2/playback/s-1/replan" })
    }

    @Test
    fun aConversionSiloCannotSeekEitherIsNotReplanned() = runTest {
        val (provider, http) = silo(start = decision("server_transcode_hls", seekable = false))

        assertEquals(ServerPlayMethod.TRANSCODE, provider.preparePlayback(session(), request(direct = false)).playMethod)
        assertTrue(http.requests.none { it.url.encodedPath.endsWith("/replan") })
    }

    @Test
    fun aReplanEndsOnceSiloOffersSomethingOtherThanACopy() = runTest {
        val converted = decision("server_transcode_hls", seekable = false).replace("plan:1", "plan:2").replace("v3:1", "v3:2")
        val (provider, http) = silo(start = decision("server_remux_hls", seekable = false)) { request ->
            if (request.url.encodedPath == "/api/v2/playback/s-1/replan") Reply(200, converted) else null
        }

        assertEquals(ServerPlayMethod.TRANSCODE, provider.preparePlayback(session(), request(direct = false)).playMethod)
        assertEquals(1, http.requests.count { it.url.encodedPath.endsWith("/replan") })
    }

    @Test
    fun aSeekableStartIsNotReplanned() = runTest {
        val (provider, http) = silo()

        provider.preparePlayback(session(), request())

        assertTrue(http.requests.none { it.url.encodedPath.endsWith("/replan") })
    }

    @Test
    fun silosThumbnailsAreReadFromItsSheetsWithoutTheSignIn() = runTest {
        val manifest = """{"file_id":"43","interval_ms":10000,"thumbnail_width":300,"thumbnail_height":126,"tile_columns":10,"tile_rows":10,
            "thumbnail_count":816,"sheets":[{"index":0,"url":"/api/v2/artwork/trickplay/43/9913/0.9913.jpg?exp=1&sig=a"},
            {"index":1,"url":"/api/v2/artwork/trickplay/43/9913/1.9913.jpg?exp=1&sig=b"}],"expires_at":"2030-01-01T00:00:00.000Z"}"""
        val (provider, http) = silo { request ->
            when {
                request.url.encodedPath == "/api/v2/watch/movie-tmdb-603" ->
                    Reply(200, watch.replace(""""edition_raw":"Remastered","trickplay_available":false""", """"edition_raw":"Remastered","trickplay_available":true"""))
                request.url.encodedPath == "/api/v2/watch/movie-tmdb-603/trickplay" -> Reply(200, manifest)
                request.url.encodedPath.startsWith("/api/v2/artwork/trickplay/") -> Reply(200, "sheet")
                else -> null
            }
        }

        val set = provider.trickplay(session(), "movie-tmdb-603", "43")!!
        val sheet = provider.trickplaySheet(session(), "movie-tmdb-603", "43", 300, 1, 1_000_000L)

        assertEquals("43", set.mediaSourceId)
        val level = set.levels.single()
        assertEquals(listOf(300, 126, 10, 10, 816), listOf(level.width, level.height, level.tileWidth, level.tileHeight, level.thumbnailCount))
        assertEquals(10_000L, level.intervalMs)
        assertEquals("sheet", String(sheet))
        val manifestRequest = http.requests.single { it.url.encodedPath.endsWith("/trickplay") }
        assertEquals("43", manifestRequest.url.queryParameter("file_id"))
        assertEquals("Bearer acc", manifestRequest.header("Authorization"))
        val sheetRequest = http.requests.single { it.url.encodedPath.startsWith("/api/v2/artwork/") }
        assertEquals("/api/v2/artwork/trickplay/43/9913/1.9913.jpg", sheetRequest.url.encodedPath)
        assertEquals("b", sheetRequest.url.queryParameter("sig"))
        assertNull(sheetRequest.header("Authorization"))
    }

    @Test
    fun aVersionWithoutSilosThumbnailsAsksForNone() = runTest {
        val (provider, http) = silo()

        assertNull(provider.trickplay(session(), "movie-tmdb-603", "43"))
        assertTrue(http.requests.none { it.url.encodedPath.endsWith("/trickplay") })
    }

    @Test
    fun aProfileSiloWantsCheckedAgainAsksForANewSignInNotATokenRenewal() = runTest {
        val (provider, http) = silo { request ->
            if (request.url.encodedPath == "/api/v2/watch/movie-tmdb-603") {
                Reply(403, """{"type":"https://siloserver.org/docs/api/v2/problems/profile_verification_required","title":"Profile verification required",
                    "status":403,"detail":"The declared profile is locked; verify it and retry with X-Profile-Token.","instance":"i"}""")
            } else {
                null
            }
        }

        val error = try {
            provider.candidates(session(), "movie-tmdb-603")
            fail("expected a sign-in request")
            return@runTest
        } catch (error: ServerException) {
            error
        }

        assertEquals(ServerFailure.AUTH_REQUIRED, error.failure)
        assertEquals(ServerSignInIssue.PROFILE_PIN, error.issue)
        assertNull(provider.renewCredential(session(), error))
        assertTrue(http.requests.none { it.url.encodedPath == "/api/v2/auth/refresh" })
    }

    @Test
    fun aRefusalCarriesSilosOwnMessage() = runTest {
        val (provider, _) = silo(
            start = """{"protocol_version":3,"server_features":[],"outcome":"adaptation_unavailable",
                "terminal":{"reason":"transcoding_disabled","message":"Transcoding is turned off on this server.","retryable":false}}"""
        )

        try {
            provider.preparePlayback(session(), request())
            fail("expected a refusal")
        } catch (error: ServerException) {
            assertEquals(ServerFailure.UNSUPPORTED, error.failure)
            assertEquals("Transcoding is turned off on this server.", error.detail)
        }
    }

    @Test
    fun noStreamLeftForTheAccountIsAStreamLimit() = runTest {
        val (provider, _) = silo(
            start = """{"protocol_version":3,"server_features":[],"outcome":"adaptation_unavailable",
                "terminal":{"reason":"capacity_unavailable","message":"Playback capacity is currently unavailable.","retryable":true}}"""
        )

        try {
            provider.preparePlayback(session(), request())
            fail("expected a refusal")
        } catch (error: ServerException) {
            assertEquals(ServerFailure.STREAM_LIMIT, error.failure)
            assertEquals("capacity_unavailable", error.code)
        }
    }

    @Test
    fun anAccountsOwnLimitIsAStreamLimit() = runTest {
        listOf("stream_limit_reached", "transcode_limit_reached").forEach { reason ->
            val (provider, _) = silo(
                start = """{"protocol_version":3,"server_features":[],"outcome":"adaptation_unavailable",
                    "terminal":{"reason":"$reason","message":"Limit reached.","retryable":true}}"""
            )
            try {
                provider.preparePlayback(session(), request())
                fail("expected a refusal")
            } catch (error: ServerException) {
                assertEquals(ServerFailure.STREAM_LIMIT, error.failure)
                assertEquals(reason, error.code)
            }
        }
    }

    @Test
    fun subtitlesNoRouteCanShowAreNotOffered() = runTest {
        val start = decision().replace(
            """{"track_id":"file:43:subtitle:2",""",
            """{"track_id":"file:43:subtitle:3","combined_index":3,"source":"embedded","codec":"weird","language":"spa","label":"Spanish","forced":false,"default":false,"hearing_impaired":false,"delivery":"burn_in_only","unplayable":true},
            {"track_id":"file:43:subtitle:4","combined_index":4,"source":"embedded","language":"jpn","label":"Japanese","forced":false,"default":false,"hearing_impaired":false,"delivery":"burn_in_only"},
            {"track_id":"file:43:subtitle:2","""
        )
        val (provider, _) = silo(start = start)

        assertEquals(listOf("German"), provider.preparePlayback(session(), request()).burnInSubtitles.map { it.label })
    }

    @Test
    fun playbackIsOffWhenSilosPlaybackIsNotSetUp() = runTest {
        val (provider, http) = silo { request ->
            if (request.url.encodedPath == "/api/v2/playback/capabilities") {
                Reply(200, """{"revision":"r","state":"not_configured","allowed":false,"protocol_versions":[],"features":[],"deliveries":[]}""")
            } else {
                null
            }
        }

        try {
            provider.preparePlayback(session(), request())
            fail("expected unsupported")
        } catch (error: ServerException) {
            assertEquals(ServerFailure.UNSUPPORTED, error.failure)
        }
        assertTrue(http.requests.none { it.url.encodedPath == "/api/v2/playback/start" })
    }

    @Test
    fun progressAndStopCountUpAndReportTheSourcePosition() = runTest {
        val (provider, http) = silo(start = decision("server_remux_hls"))
        val playback = provider.preparePlayback(session(), request(direct = false))

        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.START, 0L, false))
        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.PROGRESS, 10_000L, false))
        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.PAUSE, 20_500L, true))
        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.STOP, 21_000L, true))
        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.PROGRESS, 22_000L, false))

        val reports = http.requests.filter { it.url.encodedPath.startsWith("/api/v2/playback/s-1") }
        assertEquals(listOf("POST", "POST", "DELETE"), reports.map { it.method })
        val bodies = reports.map { Json.parseToJsonElement(it.text).jsonObject }
        assertEquals(listOf("1", "2", "3"), bodies.map { it["sequence"]!!.jsonPrimitive.content })
        assertEquals(listOf("22.0", "32.5", "33.0"), bodies.map { it["position"]!!.jsonPrimitive.content })
        assertEquals("true", bodies[1]["is_paused"]!!.jsonPrimitive.content)
        assertTrue(bodies.last().containsKey("stop_id"))
    }

    @Test
    fun aSessionEndedByAnAdministratorStopsTheReports() = runTest {
        val (provider, http) = silo { request ->
            if (request.url.encodedPath == "/api/v2/playback/s-1/progress") {
                Reply(410, """{"type":"https://siloserver.org/docs/api/v2/problems/playback_session_ended","title":"x","status":410,"detail":"","instance":"i"}""")
            } else null
        }
        val playback = provider.preparePlayback(session(), request())

        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.PROGRESS, 10_000L, false))
        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.PROGRESS, 20_000L, false))

        assertEquals(1, http.requests.count { it.url.encodedPath == "/api/v2/playback/s-1/progress" })
    }

    @Test
    fun markersAndChaptersNameThePlayedFile() = runTest {
        val (provider, http) = silo()

        provider.segments(session(), "movie-tmdb-603", "43")
        provider.chapters(session(), "movie-tmdb-603", "43")

        assertEquals(
            listOf("43", "43"),
            http.requests.filter { it.url.encodedPath == "/api/v2/watch/movie-tmdb-603" }.map { it.url.queryParameter("file_id") }
        )
    }

    @Test
    fun aServerThatRefusesTheFileParameterStillGivesMarkers() = runTest {
        val (provider, _) = silo { request ->
            if (request.url.encodedPath == "/api/v2/watch/movie-tmdb-603" && request.url.queryParameter("file_id") != null) {
                Reply(422, """{"type":"https://siloserver.org/docs/api/v2/problems/validation_failed","title":"x","status":422,"detail":"","instance":"i"}""")
            } else null
        }

        assertEquals(2, provider.segments(session(), "movie-tmdb-603", "43").size)
    }

    @Test
    fun markersComeFromThePlayedVersionInMilliseconds() = runTest {
        val (provider, _) = silo()

        val segments = provider.segments(session(), "movie-tmdb-603", "43")

        assertEquals(
            listOf(ServerSegment(ServerSegmentKind.INTRO, 30_500L, 95_000L), ServerSegment(ServerSegmentKind.OUTRO, 7_900_000L, 8_160_000L)),
            segments
        )
        assertTrue(provider.segments(session(), "movie-unknown", "1").isEmpty())
    }

    @Test
    fun chaptersComeFromThePlayedVersion() = runTest {
        val (provider, _) = silo()

        assertEquals(
            listOf(ServerChapter(0L, "Opening"), ServerChapter(300_250L, "The Lobby"), ServerChapter(900_000L, null)),
            provider.chapters(session(), "movie-tmdb-603", "43")
        )
        assertTrue(provider.chapters(session(), "movie-tmdb-603", "42").isEmpty())
        assertTrue(provider.chapters(session(), "movie-unknown", "1").isEmpty())
    }

    @Test
    fun onlyServicesConnectedWithScrobblingInSiloAreLeftToSilo() = runTest {
        val (provider, _) = silo { request ->
            when (request.url.encodedPath) {
                "/api/v2/watch-providers/trakt/connection" -> Reply(200, """{"connected":true,"scrobble_enabled":true}""")
                "/api/v2/watch-providers/simkl/connection" -> Reply(200, """{"connected":true,"scrobble_enabled":false}""")
                else -> null
            }
        }

        assertEquals(setOf("trakt"), provider.scrobbledServices(session()))
    }

    @Test
    fun aTitleBeingPlayedIsNotMarkedWatchedByNuvio() = runTest {
        val (provider, http) = silo { request -> if (request.url.encodedPath.startsWith("/api/v2/watched/")) Reply(204, "") else null }
        val playback = provider.preparePlayback(session(), request())
        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.START, 0L, false))

        provider.setPlayed(session(), "movie-tmdb-603", true)
        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.STOP, 8_000_000L, true))
        provider.setPlayed(session(), "movie-tmdb-603", true)

        provider.setPlayed(session(), "movie-tmdb-603", false)

        assertEquals(listOf("DELETE"), http.requests.filter { it.url.encodedPath == "/api/v2/watched/movie-tmdb-603" }.map { it.method })
        assertNull(http.requests.firstOrNull { it.url.encodedPath == "/api/v2/playback/s-1/progress" })
    }

    @Test
    fun aTitleStoppedPartwayCanBeMarkedWatchedStraightAway() = runTest {
        val (provider, http) = silo { request -> if (request.url.encodedPath.startsWith("/api/v2/watched/")) Reply(204, "") else null }
        val playback = provider.preparePlayback(session(), request())
        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.START, 0L, false))
        provider.report(session(), playback, ServerPlaybackEvent(ServerPlaybackEventType.STOP, 1_200_000L, true))

        provider.setPlayed(session(), "movie-tmdb-603", true)

        assertEquals(listOf("POST"), http.requests.filter { it.url.encodedPath == "/api/v2/watched/movie-tmdb-603" }.map { it.method })
    }

    @Test
    fun aTitleThatNeverStartedPlayingCanStillBeMarkedWatched() = runTest {
        val (provider, http) = silo { request -> if (request.url.encodedPath.startsWith("/api/v2/watched/")) Reply(204, "") else null }
        provider.preparePlayback(session(), request())

        provider.setPlayed(session(), "movie-tmdb-603", true)

        assertEquals(listOf("POST"), http.requests.filter { it.url.encodedPath == "/api/v2/watched/movie-tmdb-603" }.map { it.method })
        assertTrue(http.requests.none { it.url.encodedPath.startsWith("/api/v2/playback/s-1") })
    }
}
