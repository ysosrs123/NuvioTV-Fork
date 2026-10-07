package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.RecordingShareAddress
import com.nuvio.tv.core.iptv.RecordingShareProtocol
import com.nuvio.tv.core.iptv.RecordingShareTarget
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IptvNetworkShareStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private class PlainBox : IptvSecretBox {
        override fun seal(context: String, plaintext: String) = "$context|$plaintext".reversed().toByteArray()
        override fun open(context: String, ciphertext: ByteArray): String = String(ciphertext).reversed().substringAfter("$context|")
    }

    private val pin = (0 until 32).joinToString(":") { "%02X".format(it * 7) }

    @Test fun protocolSecureFlagAndPinRoundTrip() {
        val file = File(temp.root, "share.json")
        val store = IptvRecordingShareStore(file) { PlainBox() }
        val target = RecordingShareAddress.webDav("https://nas:5006/remote.php/dav/files/alice", "TV")!!
        val saved = store.save(target, "alice", "", false, "pw", pin)
        val json = JSONObject(file.readText())
        assertEquals(2, json.getInt("version"))
        assertEquals("webdav", json.getString("protocol"))
        val reopened = IptvRecordingShareStore(file) { PlainBox() }
        assertEquals(saved, reopened.settings())
        assertEquals(pin, reopened.settings()!!.pin)
        assertEquals("pw", reopened.password())
        assertEquals("nas/TV", saved.label)
        assertEquals(saved.id, reopened.save(target, "alice", "", false, null).id)
        assertEquals("pw", reopened.password())
        assertNull(reopened.settings()!!.pin)
        val ftp = reopened.save(RecordingShareTarget("nas", null, "", "TV", RecordingShareProtocol.FTP, true), "", "", true, "")
        assertNotEquals(saved.id, ftp.id)
        assertEquals("", reopened.password())
        val plainFtp = reopened.save(RecordingShareTarget("nas", null, "", "TV", RecordingShareProtocol.FTP, false), "", "", true, "")
        assertNotEquals(ftp.id, plainFtp.id)
        assertTrue(runCatching { IptvShareSettings(target, "a", "", false, "probe0000", "zz") }.isFailure)
    }

    @Test fun versionOneFilesReadAsSmb() {
        val file = File(temp.root, "old.json")
        file.writeText(JSONObject().put("version", 1).put("id", "0123456789abcdef").put("host", "nas").put("share", "Media").put("folder", "TV")
            .put("username", "bob").put("domain", "").put("guest", false).toString())
        val settings = IptvRecordingShareStore(file) { PlainBox() }.settings()!!
        assertEquals(RecordingShareTarget("nas", null, "Media", "TV"), settings.target)
        assertEquals(RecordingShareProtocol.SMB, settings.target.protocol)
        assertNull(settings.pin)
        file.writeText(JSONObject(file.readText()).put("version", 2).put("protocol", "gopher").toString())
        assertNull(IptvRecordingShareStore(file) { PlainBox() }.settings())
    }
}
