package com.nuvio.tv.data.iptv

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb.SMB1NotSupportedException
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.nuvio.tv.core.iptv.RecordingShareTarget
import com.nuvio.tv.core.iptv.RecordingStatus
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IptvRecordingShareTest {
    @get:Rule val temp = TemporaryFolder()

    private class XorBox : IptvSecretBox {
        override fun seal(context: String, plaintext: String) = (context + "|" + plaintext).toByteArray().map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun open(context: String, ciphertext: ByteArray): String {
            val text = String(ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray())
            if (!text.startsWith("$context|")) throw IOException("wrong context")
            return text.substringAfter('|')
        }
    }

    private val target = RecordingShareTarget("nas.local", null, "recordings", "TV")

    @Test fun settingsRoundTripWithSealedPassword() {
        val file = File(temp.root, "iptv/recording-share.json")
        val store = IptvRecordingShareStore(file) { XorBox() }
        assertNull(store.settings())
        val saved = store.save(target, " alice ", "", false, "pa55-wörd")
        assertEquals("alice", saved.username)
        assertFalse(file.readText().contains("pa55"))
        assertFalse(saved.toString().contains("nas"))
        val reopened = IptvRecordingShareStore(file) { XorBox() }
        assertEquals(saved, reopened.settings())
        assertEquals("pa55-wörd", reopened.password())
        assertEquals("nas.local/recordings/TV", saved.label)
        val renamed = reopened.save(target, "bob", "WORKGROUP", false, null)
        assertEquals(saved.id, renamed.id)
        assertEquals("pa55-wörd", reopened.password())
        val moved = reopened.save(target.copy(host = "other"), "bob", "", false, "x")
        assertNotEquals(saved.id, moved.id)
        assertEquals("x", IptvRecordingShareStore(file) { XorBox() }.password())
        val guest = reopened.save(target, "", "", true, "ignored")
        assertEquals("", reopened.password())
        assertFalse(JSONObject(file.readText()).has("secret"))
        assertTrue(guest.guest)
        reopened.clear()
        assertNull(IptvRecordingShareStore(file) { XorBox() }.settings())
    }

    @Test fun corruptSettingsAreIgnored() {
        val file = File(temp.root, "share.json").apply { writeText("{broken") }
        assertNull(IptvRecordingShareStore(file) { XorBox() }.settings())
    }

    @Test fun probeChecksListingWritingAndSpace() {
        val share = FakeShare()
        assertEquals(IptvShareCheck(null, Long.MAX_VALUE), IptvShareProbe.run(share, "TV/Live"))
        assertTrue("TV/Live" in share.folders)
        assertTrue(share.files.isEmpty())
        share.readOnly = true
        assertEquals(IptvShareError.READ_ONLY, IptvShareProbe.run(share, "TV/Live").error)
        assertEquals(IptvShareError.READ_ONLY, IptvShareProbe.run(share, "Other").error)
        share.readOnly = false
        share.free = 1024
        assertEquals(IptvShareCheck(IptvShareError.FULL, 1024), IptvShareProbe.run(share, ""))
        share.refusal = IptvShareError.SMB1_ONLY
        assertEquals(IptvShareCheck(IptvShareError.SMB1_ONLY, null), IptvShareProbe.run(share, ""))
        share.refusal = IptvShareError.LOGIN_REFUSED
        assertEquals(IptvShareError.LOGIN_REFUSED, IptvShareProbe.run(share, "").error)
    }

    @Test fun smbFailuresMapToSpecificErrors() {
        fun api(status: NtStatus) = SMBApiException(status.value, SMB2MessageCommandCode.SMB2_SESSION_SETUP, null)
        assertEquals(IptvShareError.LOGIN_REFUSED, smbError(api(NtStatus.STATUS_LOGON_FAILURE)))
        assertEquals(IptvShareError.LOGIN_REFUSED, smbError(api(NtStatus.STATUS_ACCOUNT_DISABLED)))
        assertEquals(IptvShareError.SHARE_NOT_FOUND, smbError(api(NtStatus.STATUS_BAD_NETWORK_NAME)))
        assertEquals(IptvShareError.FOLDER_NOT_FOUND, smbError(api(NtStatus.STATUS_OBJECT_PATH_NOT_FOUND)))
        assertEquals(IptvShareError.ACCESS_DENIED, smbError(api(NtStatus.STATUS_ACCESS_DENIED)))
        assertEquals(IptvShareError.FULL, smbError(api(NtStatus.STATUS_DISK_FULL)))
        assertEquals(IptvShareError.SMB1_ONLY, smbError(TransportException(SMB1NotSupportedException())))
        assertEquals(IptvShareError.UNREACHABLE, smbError(ConnectException("refused")))
        assertEquals(IptvShareError.UNREACHABLE, smbError(UnknownHostException("x")))
        assertEquals(IptvShareError.TIMEOUT, smbError(TransportException(TimeoutException())))
        assertEquals(IptvShareError.TIMEOUT, smbError(SocketTimeoutException()))
        assertEquals(IptvShareError.DISCONNECTED, smbError(TransportException(java.io.EOFException())))
        assertEquals(IptvShareError.OTHER, smbError(IllegalStateException("x")))
    }

    @Test fun storedRecordingsWithoutLocationFieldsLoadAsInternalSinglePart() {
        val file = File(temp.root, "recordings.json")
        file.writeText("""{"version":1,"recordings":[{"id":"aaaaaaaa-1","profile":1,"source":"source-1","account":"shared-default",
            "channel":"channel:1","channelName":"News","start":1000,"stop":2000,"status":"DONE","file":"/x/a.ts","bytes":5,"gaps":0,"created":500}]}""")
        val old = IptvRecordingStore(file).all().single()
        assertNull(old.storage)
        assertEquals(1, old.parts)
        assertFalse(old.upload)
        assertFalse(old.onShare)
        val store = IptvRecordingStore(file)
        store.update("aaaaaaaa-1") { it.copy(storage = "volume:1234-ABCD", storageLabel = "SanDisk", parts = 3) }
        val shared = old.copy(id = "bbbbbbbb-2", storage = "share:ab12cd34", upload = true, status = RecordingStatus.PARTIAL)
        store.insert(shared)
        val reopened = IptvRecordingStore(file).all()
        assertEquals(listOf("volume:1234-ABCD", "share:ab12cd34"), reopened.map { it.storage })
        assertEquals(listOf(3, 1), reopened.map { it.parts })
        assertEquals(listOf(false, true), reopened.map { it.upload })
        assertEquals("SanDisk", reopened.first().storageLabel)
        assertTrue(reopened.last().onShare)
        assertFalse(JSONObject(file.readText()).getJSONArray("recordings").getJSONObject(1).has("parts"))
        try { old.copy(storage = "volume:../x"); fail() } catch (_: IllegalArgumentException) { }
        try { old.copy(parts = 0); fail() } catch (_: IllegalArgumentException) { }
    }
}
