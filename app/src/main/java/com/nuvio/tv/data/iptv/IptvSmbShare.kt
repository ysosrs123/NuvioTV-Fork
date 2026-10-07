package com.nuvio.tv.data.iptv

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb.SMB1NotSupportedException
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.socket.ProxySocketFactory
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import com.hierynomus.smbj.share.File as SmbFile

class IptvSmbConnector(private val settings: IptvShareSettings, private val password: String) : IptvShareConnector {
    override fun connect(): IptvShareSession {
        try { return open(false) } catch (error: IptvShareException) {
            if (error.error != IptvShareError.OTHER && error.error != IptvShareError.DISCONNECTED) throw error
            val retry = try { return open(true) } catch (second: IptvShareException) { second }
            throw if (retry.error == IptvShareError.SMB1_ONLY) retry else error
        }
    }

    private fun open(probe: Boolean): IptvShareSession {
        val client = SMBClient(config(probe))
        try {
            val connection = smb { client.connect(settings.target.host, settings.target.port ?: SMBClient.DEFAULT_PORT) }
            val session = smb {
                if (!settings.guest) connection.authenticate(AuthenticationContext(settings.username, password.toCharArray(), settings.domain.ifEmpty { null }))
                else try { connection.authenticate(AuthenticationContext.guest()) } catch (error: SMBApiException) {
                    if (error.status != NtStatus.STATUS_LOGON_FAILURE && error.status != NtStatus.STATUS_ACCOUNT_DISABLED) throw error
                    connection.authenticate(AuthenticationContext.anonymous())
                }
            }
            val share = smb { session.connectShare(settings.target.share) } as? DiskShare ?: throw IptvShareException(IptvShareError.SHARE_NOT_FOUND)
            return IptvSmbSession(client, session, share)
        } catch (error: Throwable) {
            try { client.close() } catch (_: Exception) { }
            throw error
        }
    }

    private fun config(probe: Boolean): SmbConfig = SmbConfig.builder()
        .withSocketFactory(ProxySocketFactory(CONNECT_TIMEOUT_MS))
        .withTimeout(IO_TIMEOUT_S, TimeUnit.SECONDS)
        .withDfsEnabled(false)
        .withMultiProtocolNegotiate(probe)
        .build()

    override fun toString(): String = "IptvSmbConnector(withheld)"

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val IO_TIMEOUT_S = 60L
    }
}

private class IptvSmbSession(private val client: SMBClient, private val session: Session, private val share: DiskShare) : IptvShareSession {
    private val readLimit = session.connection.negotiatedProtocol.maxReadSize.coerceIn(16 * 1024, 1024 * 1024)

    override fun length(path: String): Long? = smb {
        try { share.getFileInformation(smbPath(path)).standardInformation.endOfFile } catch (error: SMBApiException) {
            if (error.status in MISSING) null else throw error
        }
    }

    override fun openWrite(path: String): IptvShareFile = smb {
        IptvSmbFile(share.openFile(smbPath(path), EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN_IF, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)), readLimit)
    }

    override fun openRead(path: String): IptvShareFile = smb {
        IptvSmbFile(share.openFile(smbPath(path), EnumSet.of(AccessMask.GENERIC_READ), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
            SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)), readLimit)
    }

    override fun rename(from: String, to: String, replace: Boolean) = smb {
        share.openFile(smbPath(from), EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_DELETE), SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)).use { it.rename(smbPath(to), replace) }
    }

    override fun delete(path: String): Boolean = smb {
        try { share.rm(smbPath(path)); true } catch (error: SMBApiException) { if (error.status in MISSING) false else throw error }
    }

    override fun list(folder: String): List<String> = smb {
        share.list(smbPath(folder)).map { it.fileName }.filter { it != "." && it != ".." }
    }

    override fun ensureFolder(folder: String) = smb {
        var path = ""
        for (part in folder.split('/').filter { it.isNotEmpty() }) {
            path = if (path.isEmpty()) part else "$path\\$part"
            if (!share.folderExists(path)) share.mkdir(path)
        }
    }

    override fun freeBytes(): Long = smb { share.shareInformation.callerFreeSpace }

    override fun close() {
        try { share.close() } catch (_: Exception) { }
        try { session.close() } catch (_: Exception) { }
        try { client.close() } catch (_: Exception) { }
    }

    private fun smbPath(path: String) = path.trim('/').replace('/', '\\')

    private companion object {
        val MISSING = setOf(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_NO_SUCH_FILE)
    }
}

private class IptvSmbFile(private val file: SmbFile, private val readLimit: Int) : IptvShareFile {
    override val length: Long get() = smb { file.length }
    override fun write(offset: Long, buffer: ByteArray, start: Int, count: Int) { smb { file.write(buffer, offset, start, count) } }
    override fun read(offset: Long, buffer: ByteArray, start: Int, count: Int): Int = smb { file.read(buffer, offset, start, minOf(count, readLimit)) }
    override fun flush() = smb { file.flush() }
    override fun close() { try { file.close() } catch (_: Exception) { } }
}

private inline fun <T> smb(block: () -> T): T = try { block() } catch (error: IptvShareException) { throw error } catch (error: Exception) {
    throw IptvShareException(smbError(error), error)
}

internal fun smbError(error: Throwable): IptvShareError {
    for (cause in generateSequence(error) { it.cause }.take(8)) {
        when (cause) {
            is IptvShareException -> return cause.error
            is SMB1NotSupportedException -> return IptvShareError.SMB1_ONLY
            is SMB2GuestSigningRequiredException -> return IptvShareError.GUEST_REFUSED
            is SMBApiException -> return when (cause.status) {
                NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_PASSWORD_EXPIRED, NtStatus.STATUS_ACCOUNT_DISABLED,
                NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED -> IptvShareError.LOGIN_REFUSED
                NtStatus.STATUS_BAD_NETWORK_NAME, NtStatus.STATUS_BAD_NETWORK_PATH -> IptvShareError.SHARE_NOT_FOUND
                NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_OBJECT_NAME_NOT_FOUND -> IptvShareError.FOLDER_NOT_FOUND
                NtStatus.STATUS_ACCESS_DENIED -> IptvShareError.ACCESS_DENIED
                NtStatus.STATUS_DISK_FULL -> IptvShareError.FULL
                NtStatus.STATUS_IO_TIMEOUT -> IptvShareError.TIMEOUT
                NtStatus.STATUS_NETWORK_NAME_DELETED, NtStatus.STATUS_USER_SESSION_DELETED -> IptvShareError.DISCONNECTED
                else -> IptvShareError.OTHER
            }
            is SocketTimeoutException, is TimeoutException -> return IptvShareError.TIMEOUT
            is UnknownHostException, is ConnectException, is NoRouteToHostException, is PortUnreachableException -> return IptvShareError.UNREACHABLE
        }
    }
    return if (generateSequence(error) { it.cause }.take(8).any { it is EOFException || it is IOException && it.message?.contains("reset", true) == true }) IptvShareError.DISCONNECTED
    else IptvShareError.OTHER
}

data class IptvShareCheck(val error: IptvShareError?, val freeBytes: Long?)

object IptvShareProbe {
    fun run(connector: IptvShareConnector, folder: String, random: () -> Long = { System.nanoTime() }): IptvShareCheck {
        var free: Long? = null
        val session = try { connector.connect() } catch (error: Exception) { return IptvShareCheck(classify(error), null) }
        try {
            try { session.list(folder) } catch (error: Exception) {
                if (classify(error) != IptvShareError.FOLDER_NOT_FOUND) return IptvShareCheck(classify(error), null)
                try { session.ensureFolder(folder) } catch (create: Exception) { return IptvShareCheck(writeError(create), null) }
            }
            free = try { session.freeBytes() } catch (_: Exception) { null }
            val name = (if (folder.isEmpty()) "" else "$folder/") + ".nuvio-test-${java.lang.Long.toHexString(random())}.tmp"
            try {
                session.openWrite(name).use { file ->
                    val data = ByteArray(TEST_BYTES) { it.toByte() }
                    file.write(0, data, 0, data.size)
                    file.flush()
                    if (file.length != TEST_BYTES.toLong()) return IptvShareCheck(IptvShareError.OTHER, free)
                }
            } catch (error: Exception) {
                runCatching { session.delete(name) }
                return IptvShareCheck(writeError(error), free)
            }
            try { session.delete(name) } catch (error: Exception) { return IptvShareCheck(writeError(error), free) }
            if (free != null && free < MINIMUM_FREE_BYTES) return IptvShareCheck(IptvShareError.FULL, free)
            return IptvShareCheck(null, free)
        } finally { session.close() }
    }

    private fun classify(error: Exception): IptvShareError = (error as? IptvShareException)?.error ?: IptvShareError.OTHER
    private fun writeError(error: Exception): IptvShareError = classify(error).let { if (it == IptvShareError.ACCESS_DENIED) IptvShareError.READ_ONLY else it }

    private const val TEST_BYTES = 64 * 1024
    private const val MINIMUM_FREE_BYTES = 600L * 1024 * 1024
}
