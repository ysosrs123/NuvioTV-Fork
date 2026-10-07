package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class RecordingNetworkTest {
    private val webdav = RecordingShareProtocol.WEBDAV
    private val ftp = RecordingShareProtocol.FTP

    @Test fun webDavAddressesKeepTheBasePathAndDecodeSegments() {
        assertEquals(RecordingShareTarget("nas.local", 5006, "remote.php/dav/files/alice", "TV/Live", webdav, true),
            RecordingShareAddress.webDav(" https://nas.local:5006/remote.php/dav/files/alice/ ", "TV/Live"))
        assertEquals(RecordingShareTarget("nas", null, "my dav/ü", "", webdav, false), RecordingShareAddress.webDav("nas/my%20dav/%C3%BC", ""))
        assertEquals(RecordingShareTarget("nas", null, "", "a b", webdav, true), RecordingShareAddress.webDav("davs://user:secret@nas?x=1#y", "a b"))
        assertEquals(RecordingShareTarget("fd00::5", 8080, "dav", "", webdav, false), RecordingShareAddress.webDav("http://[fd00::5]:8080/dav", ""))
        assertNull(RecordingShareAddress.webDav("ftp://nas/dav", ""))
        assertNull(RecordingShareAddress.webDav("http://nas/%zz", ""))
        assertNull(RecordingShareAddress.webDav("http://nas/a/../b", ""))
        assertNull(RecordingShareAddress.webDav("http://nas/%2E%2E", ""))
        assertNull(RecordingShareAddress.webDav("http://nas/dav", "x|y"))
        assertNull(RecordingShareAddress.webDav("http://", ""))
    }

    @Test fun webDavUrlsEncodeEachSegment() {
        val target = RecordingShareAddress.webDav("https://nas:5006/my%20dav", "TV Shows")!!
        assertEquals("https://nas:5006/my%20dav/TV%20Shows/News%20%2310%20%C3%A9.ts", RecordingShareAddress.url(target, target.path("News #10 é.ts")))
        assertEquals("https://nas:5006/my%20dav/TV%20Shows/", RecordingShareAddress.url(target, target.folder, collection = true))
        assertEquals("http://[fd00::5]/", RecordingShareAddress.url(RecordingShareTarget("fd00::5", null, "", "", webdav), "", collection = true))
        assertEquals("https://nas:5006/my%20dav", RecordingShareAddress.address(target))
        assertEquals(target, RecordingShareAddress.webDav(RecordingShareAddress.address(target), "TV Shows"))
        assertEquals("a%2Bb%3Ac", RecordingShareAddress.encode("a+b:c"))
        assertEquals("a+b c", RecordingShareAddress.decode("a+b%20c"))
    }

    @Test fun ftpAddressesTakeHostPortAndFolder() {
        assertEquals(RecordingShareTarget("nas", 2121, "", "TV/Live", ftp, false), RecordingShareAddress.ftp("nas:2121", "TV/Live", false))
        assertEquals(RecordingShareTarget("nas", null, "", "media/TV", ftp, true), RecordingShareAddress.ftp("ftp://bob@nas/media", "TV", true))
        assertEquals(RecordingShareTarget("nas", null, "", "", ftp, true), RecordingShareAddress.ftp("ftpes://nas", "", false))
        assertEquals("[fe80::1]:21", RecordingShareAddress.address(RecordingShareAddress.ftp("[fe80::1]:21", "", false)!!))
        assertNull(RecordingShareAddress.ftp("http://nas", "", false))
        assertNull(RecordingShareAddress.ftp("nas", "a\r\nDELE x", false))
        assertNull(RecordingShareAddress.ftp("nas:99999", "", false))
    }

    @Test fun labelsAreShortAndTargetsCompareByProtocol() {
        val smb = RecordingShareTarget("NAS", null, "Media", "TV")
        assertEquals("NAS/Media/TV", RecordingShareAddress.label(smb))
        assertEquals("nas/TV", RecordingShareAddress.label(RecordingShareAddress.webDav("https://bob:pw@nas/remote.php/dav/files/bob", "TV")!!))
        assertTrue(smb.same(smb.copy(host = "nas", share = "media", folder = "tv")))
        assertFalse(smb.same(smb.copy(protocol = webdav)))
        val dav = RecordingShareTarget("nas", null, "dav", "TV", webdav)
        assertFalse(dav.same(dav.copy(folder = "tv")))
        assertFalse(dav.same(dav.copy(secure = true)))
        assertFalse(smb.toString().contains("NAS"))
    }

    @Test fun passiveRepliesAndDataHosts() {
        assertEquals(50123, FtpProtocol.extendedPort("Entering Extended Passive Mode (|||50123|)"))
        assertNull(FtpProtocol.extendedPort("Entering Extended Passive Mode (|||0|)"))
        assertEquals("10.0.0.9" to 51234, FtpProtocol.passive("Entering Passive Mode (10,0,0,9,200,34)."))
        assertNull(FtpProtocol.passive("Entering Passive Mode (300,0,0,9,200,34)"))
        val lan = byteArrayOf(192.toByte(), 168.toByte(), 1, 20)
        val wan = byteArrayOf(203.toByte(), 0, 113, 5)
        assertEquals("nas", FtpProtocol.dataHost("172.17.0.2", "nas", lan))
        assertEquals("nas", FtpProtocol.dataHost("0.0.0.0", "nas", lan))
        assertEquals("nas", FtpProtocol.dataHost("198.51.100.7", "nas", lan))
        assertEquals("ftp.example", FtpProtocol.dataHost("10.1.1.1", "ftp.example", wan))
        assertEquals("198.51.100.7", FtpProtocol.dataHost("198.51.100.7", "ftp.example", wan))
        assertEquals("/home/a \"b\"", FtpProtocol.directory("\"/home/a \"\"b\"\"\" is the current directory"))
        assertNull(FtpProtocol.directory("no quotes"))
    }

    @Test fun listingsSkipDirectoryEntries() {
        val machine = listOf("type=cdir;perm=el; .", "type=pdir; ..", "type=file;size=10;modify=20261007; show one.ts\r", "Type=dir;perm=el; Sub", "")
        assertEquals(listOf("show one.ts", "Sub"), FtpProtocol.names(machine, true))
        assertEquals(listOf("a.ts", "b.ts"), FtpProtocol.names(listOf("TV/a.ts\r", "b.ts", "."), false))
        assertEquals("1024", FtpProtocol.facts(" size=1024;type=file; x.ts")["size"])
        assertTrue(FtpProtocol.reuseRequired(FtpReply(522, "SSL connection failed: session reuse required")))
        assertTrue(FtpProtocol.reuseRequired(FtpReply(450, "TLS session of data connection not resumed.")))
        assertFalse(FtpProtocol.reuseRequired(FtpReply(550, "Permission denied")))
    }

    @Test fun challengesAndDigestFollowRfc2617() {
        val parsed = WebDavAuth.challenges(listOf("Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", " +
            "opaque=\"5ccc069c403ebaf9f0171e9517f40e41\", Basic realm=\"a, b\"", "Negotiate abc=="))
        assertEquals(listOf("digest", "basic", "negotiate"), parsed.map { it.scheme })
        assertEquals("a, b", parsed[1].params["realm"])
        val header = WebDavAuth.digest(parsed[0], "Mufasa", "Circle Of Life", "GET", "/dir/index.html", 1, "0a4f113b")!!
        assertTrue(header, header.contains("response=\"6629fae49393a05397450978507c4ef1\""))
        assertTrue(header.contains("nc=00000001") && header.contains("qop=auth") && header.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""))
        assertNull(WebDavAuth.digest(WebDavChallenge("digest", mapOf("realm" to "r", "nonce" to "n", "algorithm" to "SHA-512-256")), "u", "p", "GET", "/", 1, "c"))
        assertEquals("Basic dXNlcjpww6Rzcw==", WebDavAuth.basic("user", "päss"))
    }

    @Test fun multistatusGivesPathsLengthsAndQuota() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns">
            <d:response><d:href>/remote.php/dav/files/a/TV/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype>
            <d:quota-available-bytes>123456</d:quota-available-bytes></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
            <d:propstat><d:prop><d:getcontentlength/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>
            <d:response><d:href>http://nas/remote.php/dav/files/a/TV/News%20%26%20Sport.ts</d:href><d:propstat><d:prop><d:resourcetype/>
            <d:getcontentlength>42</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        assertEquals(listOf(WebDavEntry("remote.php/dav/files/a/TV", true, null, 123456), WebDavEntry("remote.php/dav/files/a/TV/News & Sport.ts", false, 42, null)),
            WebDavXml.parse(xml))
        assertTrue(runCatching { WebDavXml.parse("<!DOCTYPE x [<!ENTITY e \"x\">]><d:multistatus xmlns:d=\"DAV:\"/>") }.isFailure)
    }

    @Test fun wholeFileNeedsContiguousPiecesFromZero() {
        assertEquals(300L, RecordingUpload.whole(listOf(RecordingPiece(100, 200), RecordingPiece(0, 100)), Long.MAX_VALUE))
        assertEquals(250L, RecordingUpload.whole(listOf(RecordingPiece(0, 100), RecordingPiece(100, 200)), 250))
        assertNull(RecordingUpload.whole(listOf(RecordingPiece(100, 200)), Long.MAX_VALUE))
        assertNull(RecordingUpload.whole(listOf(RecordingPiece(0, 100), RecordingPiece(150, 50)), Long.MAX_VALUE))
        assertNull(RecordingUpload.whole(emptyList(), Long.MAX_VALUE))
    }
}
