package com.nuvio.tv.data.repository

import com.nuvio.tv.core.logging.ReportHosts
import com.nuvio.tv.data.remote.dto.AuthDiagnosticAppDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticDeviceDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticEnvironmentDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticEventDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticFlowDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticReportRequestDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticRequestDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticResponseDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticTerminalDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ReportTextTest {
    @Test fun `links, keys, addresses and email addresses are taken out and useful detail stays`() {
        val text = ReportText.clean(
            "HTTP 403 from https://eu1.tb-cdn.st/dl/abc?token=SECRET123 apikey=Zx81Qw7 mail me@example.com box 192.168.1.60 " +
                "version 1.1.0-nt.12 nonce=a1b2c3d4e5f6a7b8c9d0 state=READY id 9f8e7d6c5b4a39281706f5e4d3c2b1a0 Authorization: Bearer abc.def.ghi",
            1000
        )!!
        assertFalse(text, text.contains("SECRET123") || text.contains("abc.def") || text.contains("Zx81Qw7"))
        assertFalse(text, text.contains("a1b2c3d4") || text.contains("9f8e7d6c5b4a"))
        assertTrue(text, text.contains("[link: TorBox]"))
        assertFalse(text.contains("me@example.com") || text.contains("192.168.1.60"))
        assertTrue(text, text.contains("HTTP 403") && text.contains("1.1.0-nt.12") && text.contains("state=READY"))
    }

    @Test fun `raw log lines keep the host and path shape but lose the secret parts`() {
        val line = ReportText.rawLine("LINK_SELECTED https://api.torbox.app/v1/api/torrents/requestdl?token=abcd1234efgh5678 Bearer xyz987654321", 2000)!!
        assertFalse(line, line.contains("abcd1234efgh5678") || line.contains("xyz987654321"))
        val paths = ReportText.rawLine("VIEW dat=content://com.nuvio.tv.test.files/updates/abc123def456.apk and file:///data/x/y.apk", 2000)!!
        assertFalse(paths, paths.contains("abc123def456") || paths.contains("/data/x"))
    }

    @Test fun `server names and addresses in error messages do not survive`() {
        val private = listOf(
            "Unable to resolve host \"home.duckdns.org\": No address associated with hostname" to "duckdns",
            "Unable to resolve host \"mynas\": No address" to "mynas",
            "Failed to connect to my.server.net/203.0.113.9:443" to "my.server",
            "failed to connect to /192.168.1.60 (port 8096)" to "192.168.1.60",
            "Hostname plex.example.family not verified" to "example.family",
            "host=jelly.myhouse.synology.me error" to "myhouse",
            "bad reply from jelly.myhouse.xyz today" to "myhouse",
            "connected via 2001:db8:85a3::8a2e:370:7334 ok" to "2001:db8",
            "connected via 2001:0db8:85a3:0000:0000:8a2e:0370:7334 ok" to "0db8",
            "from [fd12:3456::1]:8096" to "fd12",
            "from fe80::1%wlan0" to "fe80",
            "wifi aa:bb:cc:dd:ee:ff" to "aa:bb"
        )
        for ((input, secret) in private) {
            val text = ReportText.clean(input, 1000)!!
            assertFalse(text, text.contains(secret))
        }
        assertTrue(ReportText.clean("Unable to resolve host \"cdn.torbox.app\"", 100)!!.contains("[host: TorBox]"))
        for (keep in listOf(
            "java.net.UnknownHostException", "com.nuvio.tv.test", "com.nuvio.tv", "c2.amlogic.avc.decoder", "OMX.qcom.video.decoder.hevc",
            "Movie.Name.2024.mkv", "PlayerScreen.kt", "at 23:23:32.123", "Foo::bar", "Failed to connect to server", "avc1.640028"
        )) assertEquals(keep, ReportText.clean(keep, 200))
    }

    @Test fun `iptv logins and addresses do not survive`() {
        val private = listOf(
            "Unable to open /live/fakeuser/fakepass/12345.ts" to "fakepass",
            "GET /timeshift/fakeuser/fakepass/120/2026-11-08:20-00/777.ts failed" to "fakeuser",
            "/movie/fakeuser/fakepass/99.mkv not found" to "fakepass",
            "/series/fakeuser/fakepass/99.mkv not found" to "fakeuser",
            "open /fakeuser/fakepass/12345.ts" to "fakepass",
            "open /fakeuser/fakepass/12345 failed" to "fakeuser",
            "rtmp://fakeuser:fakepass@cdn.example.xyz/live/ch1" to "fakeuser",
            "rtmps://cdn.example.xyz/live?u=fakeuser" to "fakeuser",
            "udp://@239.1.1.1:1234" to "239.1.1.1",
            "rtp://239.1.1.1:5000" to "239.1.1.1",
            "mms://fakehost.example.xyz/ch" to "fakehost",
            "srt://fakehost.example.xyz:9000?passphrase=fakepass" to "fakepass",
            "login username=fakeuser&password=fakepass&action=get_live_streams" to "fakeuser",
            "user=fakeuser pass=fakepass" to "fakepass",
            "user=fakeuser pass=fakepass" to "fakeuser",
            "login=fakeuser&device_id=FAKEDEVICE" to "FAKEDEVICE",
            "login=fakeuser&device_id=FAKEDEVICE" to "fakeuser",
            "portal sn=FAKESERIAL" to "FAKESERIAL",
            "mac=00%3A1A%3A79%3A12%3A34%3A56" to "79%3A12",
            "\\\\fakenas\\recordings\\Game.ts" to "fakenas"
        )
        for ((input, secret) in private) {
            val text = ReportText.clean(input, 1000)!!
            assertFalse(text, text.contains(secret))
        }
        for (keep in listOf("Login failed: timeout", "user cancelled", "passed 3 checks", "live stream ended", "/proc/self/status")) {
            assertEquals(keep, ReportText.clean(keep, 200))
        }
    }

    @Test fun `iptv links are recognised by their shape`() {
        assertTrue(ReportText.isIptvLink("http://example.xyz:8080/movie/fakeuser/fakepass/99.mkv"))
        assertTrue(ReportText.isIptvLink("http://example.xyz/live/fakeuser/fakepass/12345.ts"))
        assertFalse(ReportText.isIptvLink("https://cdn.torbox.app/dl/abc/film.mkv"))
        assertFalse(ReportText.isIptvLink(null))
    }

    @Test fun `a password with an escaped quote or a cut-off body does not leak its tail`() {
        for (body in listOf("""{"password":"abc\"TAILSECRET","x":1}""", """{"password":"abcTAILSECRET""", """{"password":"abcTAILSECRET\""")) {
            val event = AuthDiagnosticEventDto(type = "http", timeMs = 1, elapsedMs = 1, request = AuthDiagnosticRequestDto(headers = emptyMap(), body = body))
            assertFalse(body, reportWith(event).forUpload().toString().contains("TAILSECRET"))
        }
    }

    @Test fun `a sign-in report does not keep the server address`() {
        val event = AuthDiagnosticEventDto(type = "http", timeMs = 1, elapsedMs = 1, url = "https://myproject.example.org/auth/v1/token?grant=x")
        assertFalse(reportWith(event).forUpload().toString().contains("myproject"))
    }

    private fun reportWith(event: AuthDiagnosticEventDto) = AuthDiagnosticReportRequestDto(
        schemaVersion = 1, startedAtMs = 0, endedAtMs = 1, elapsedMs = 1,
        app = AuthDiagnosticAppDto("com.nuvio.tv.test", "1.1.0-nt.12", 1593, false),
        device = AuthDiagnosticDeviceDto("Amazon", "Amazon", "AFTKRT", "kara", "11", 30, listOf("arm64-v8a")),
        environment = AuthDiagnosticEnvironmentDto("https://myproject.example.org", "myproject.example.org", "https://myproject.example.org/tv", "myproject.example.org", null, true),
        flow = AuthDiagnosticFlowDto("qr", "a1", null),
        terminal = AuthDiagnosticTerminalDto("failed", "timeout", null, null, null),
        timeline = listOf(event), exceptions = emptyList(), rawLogs = emptyList()
    )

    @Test fun `server names become service names or only the kind of address`() {
        assertEquals("TorBox", ReportHosts.label("eu1.tb-cdn.st"))
        assertEquals("Real-Debrid", ReportHosts.label("download.real-debrid.com"))
        assertEquals("local network address", ReportHosts.label("192.168.1.240"))
        assertEquals("local network address", ReportHosts.label("[fd00::1]"))
        assertEquals("internet address", ReportHosts.label("203.0.113.9"))
        assertEquals("local network name", ReportHosts.label("nas.local"))
        assertEquals("other server", ReportHosts.label("jellyfin.myhouse.duckdns.org"))
        assertEquals("other server", ReportHosts.label("cafe.be"))
        assertEquals(null, ReportHosts.label(" "))
    }

    @Test fun `sign-in reports lose tokens, codes, passwords and secret headers`() {
        val event = AuthDiagnosticEventDto(
            type = "http", timeMs = 1, elapsedMs = 1, url = "https://auth.example.org/token?refresh=abc123SECRET",
            request = AuthDiagnosticRequestDto(
                headers = mapOf("Authorization" to "Bearer eyJhbGciOiJIUzI1NiJ9.payloadpart.sig", "Content-Type" to "application/json"),
                body = """{"refresh_token":"r3fr3sh-SECRET","email":"me@example.com","grant_type":"refresh"}"""
            ),
            response = AuthDiagnosticResponseDto(
                statusCode = 200, isSuccessful = true, headers = mapOf("Set-Cookie" to "sid=SECRETCOOKIE", "Date" to "Sat"),
                body = """{"access_token":"eyJabc.def.ghi","user":{"id":"u1"}}""", bodyBytes = 50, contentType = "application/json"
            )
        )
        val report = AuthDiagnosticReportRequestDto(
            schemaVersion = 1, startedAtMs = 0, endedAtMs = 1, elapsedMs = 1,
            app = AuthDiagnosticAppDto("com.nuvio.tv.test", "1.1.0-nt.12", 1593, false),
            device = AuthDiagnosticDeviceDto("Amazon", "Amazon", "AFTKRT", "kara", "11", 30, listOf("arm64-v8a")),
            environment = AuthDiagnosticEnvironmentDto("", null, "", null, null, true),
            flow = AuthDiagnosticFlowDto("qr", "a1", null),
            terminal = AuthDiagnosticTerminalDto("failed", "timeout", null, null, null),
            timeline = listOf(event), exceptions = emptyList(),
            rawLogs = listOf("1 refresh failed token=abcdef123456 for me@example.com")
        )
        val cleaned = report.forUpload().toString()
        for (secret in listOf("abc123SECRET", "payloadpart", "r3fr3sh-SECRET", "me@example.com", "SECRETCOOKIE", "eyJabc", "abcdef123456")) {
            assertFalse("$secret survived", cleaned.contains(secret))
        }
        assertTrue(cleaned.contains("application/json") && cleaned.contains("grant_type"))
    }

    /**
     * Runs over real logs and link files on this PC, named in ARC_REDACTION_CORPUS (separated by ';'). Every secret-looking
     * part of every link in them must be gone after cleaning. Nothing from the files is printed.
     */
    @Test fun `real logs and links keep no secrets after cleaning`() {
        val files = System.getenv("ARC_REDACTION_CORPUS")?.split(';')?.map(::File)?.filter { it.isFile }.orEmpty()
        assumeTrue("ARC_REDACTION_CORPUS not set", files.isNotEmpty())
        var lines = 0
        var failures = 0
        for (file in files) {
            file.useLines { seq ->
                seq.filter { it.contains("://") }.forEach { line ->
                    lines++
                    val secrets = SECRET_PART.findAll(line).map { it.value }.filter { it.any(Char::isDigit) && it.any(Char::isLetter) }.toList()
                    val clean = ReportText.clean(line, 4000).orEmpty()
                    val raw = ReportText.rawLine(line, 4000).orEmpty()
                    if (secrets.any { clean.contains(it) || raw.contains(it) }) failures++
                }
            }
        }
        assertTrue("no lines with links in the corpus", lines > 0)
        assertEquals("lines with a secret left after cleaning (of $lines)", 0, failures)
    }

    private companion object {
        val SECRET_PART = Regex("""(?<=[=/])[A-Za-z0-9_-]{20,}""")
    }
}
