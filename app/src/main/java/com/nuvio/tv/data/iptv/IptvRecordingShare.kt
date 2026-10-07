package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.RecordingShareAddress
import com.nuvio.tv.core.iptv.RecordingShareProtocol
import com.nuvio.tv.core.iptv.RecordingShareTarget
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.internal.tls.OkHostnameVerifier
import org.json.JSONObject

enum class IptvShareError { UNREACHABLE, TIMEOUT, LOGIN_REFUSED, GUEST_REFUSED, SHARE_NOT_FOUND, FOLDER_NOT_FOUND, ACCESS_DENIED, READ_ONLY, FULL, SMB1_ONLY,
    CERTIFICATE, NO_TLS, TLS_REUSE, DISCONNECTED, LOST, OTHER }

class IptvShareException(val error: IptvShareError, cause: Throwable? = null) : IOException("Network share ${error.name.lowercase()}", cause)

interface IptvShareFile : Closeable {
    val length: Long
    fun write(offset: Long, buffer: ByteArray, start: Int, count: Int)
    fun read(offset: Long, buffer: ByteArray, start: Int, count: Int): Int
    fun flush()
}

interface IptvShareSession : Closeable {
    val append: Boolean get() = true
    fun length(path: String): Long?
    fun openWrite(path: String): IptvShareFile
    fun openWrite(path: String, total: Long): IptvShareFile = openWrite(path)
    fun openRead(path: String): IptvShareFile
    fun rename(from: String, to: String, replace: Boolean)
    fun delete(path: String): Boolean
    fun list(folder: String): List<String>
    fun ensureFolder(folder: String)
    fun freeBytes(): Long
}

fun interface IptvShareConnector {
    fun connect(): IptvShareSession
    val certificate: String? get() = null
}

data class IptvShareSettings(val target: RecordingShareTarget, val username: String, val domain: String, val guest: Boolean, val id: String,
    val pin: String? = null) {
    init { require(id.matches(Regex("[A-Za-z0-9]{8,32}")) && username.length <= 256 && domain.length <= 256 && (pin == null || pin.matches(PIN))) }
    val label: String get() = RecordingShareAddress.label(target)
    override fun toString(): String = "IptvShareSettings(withheld)"

    companion object {
        val PIN = Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")
    }
}

class IptvShareTrust(private val pin: String?) : X509TrustManager {
    @Volatile var presented: String? = null
        private set
    private val system: X509TrustManager by lazy {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    val factory: SSLSocketFactory by lazy { SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@IptvShareTrust), null) }.socketFactory }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        try { system.checkServerTrusted(chain, authType) } catch (error: CertificateException) {
            val print = chain?.firstOrNull()?.let(::fingerprint)
            if (print != null && print == pin) return
            presented = print
            throw error
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    fun verify(host: String, session: SSLSession): Boolean {
        val leaf = try { session.peerCertificates.firstOrNull() as? X509Certificate } catch (_: Exception) { null } ?: return false
        val print = fingerprint(leaf)
        if (pin != null && print == pin) return true
        if (OkHostnameVerifier.verify(host, session)) return true
        presented = print
        return false
    }

    companion object {
        fun fingerprint(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }
    }
}

internal fun networkError(error: Throwable, trust: IptvShareTrust? = null): IptvShareError {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    chain.firstOrNull { it is IptvShareException }?.let { return (it as IptvShareException).error }
    val tried = chain + chain.flatMap { it.suppressed.flatMap { other -> generateSequence(other) { it.cause }.take(8).toList() } }
    if (tried.any { it is CertificateException } || trust?.presented != null && tried.any { it is SSLException }) return IptvShareError.CERTIFICATE
    for (cause in chain) {
        when (cause) {
            is SocketTimeoutException -> return IptvShareError.TIMEOUT
            is UnknownHostException, is ConnectException, is NoRouteToHostException, is PortUnreachableException -> return IptvShareError.UNREACHABLE
        }
    }
    return if (chain.any { it is EOFException || it is SSLException || it is java.net.SocketException || it is IOException && it.message?.contains("reset", true) == true })
        IptvShareError.DISCONNECTED else IptvShareError.OTHER
}

class IptvRecordingShareStore(private val file: File, private val box: () -> IptvSecretBox) {
    @Volatile private var cached: Pair<IptvShareSettings, ByteArray?>? = null
    @Volatile private var read = false

    @Synchronized fun settings(): IptvShareSettings? = load()?.first

    @Synchronized fun password(): String? {
        val (settings, sealed) = load() ?: return null
        if (settings.guest || sealed == null) return ""
        return box().open(context(settings.id), sealed)
    }

    @Synchronized fun save(target: RecordingShareTarget, username: String, domain: String, guest: Boolean, password: String?,
        pin: String? = null): IptvShareSettings {
        val previous = load()
        val sameTarget = previous?.first?.target?.same(target) == true
        val id = if (sameTarget) previous!!.first.id else newId()
        val settings = IptvShareSettings(target, username.trim(), domain.trim(), guest, id, pin)
        val sealed = when {
            guest -> null
            password == null && sameTarget -> previous!!.second
            else -> box().seal(context(id), password.orEmpty())
        }
        val json = JSONObject().put("version", 2).put("id", id).put("protocol", target.protocol.name.lowercase()).put("host", target.host)
            .putOpt("port", target.port).put("share", target.share).put("folder", target.folder).put("secure", target.secure)
            .put("username", settings.username).put("domain", settings.domain).put("guest", guest).putOpt("pin", pin)
            .putOpt("secret", sealed?.let { Base64.getEncoder().encodeToString(it) })
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw IOException("Share settings folder unavailable")
        val temporary = File(parent, file.name + ".tmp")
        temporary.writeText(json.toString())
        if (!temporary.renameTo(file)) { temporary.delete(); throw IOException("Share settings could not be saved") }
        cached = settings to sealed
        read = true
        return settings
    }

    @Synchronized fun clear() {
        file.delete()
        cached = null
        read = true
    }

    private fun load(): Pair<IptvShareSettings, ByteArray?>? {
        if (read) return cached
        read = true
        cached = try {
            if (!file.isFile || file.length() > 64 * 1024) null else {
                val json = JSONObject(file.readText())
                val protocol = when (json.optString("protocol", "smb")) {
                    "smb" -> RecordingShareProtocol.SMB
                    "webdav" -> RecordingShareProtocol.WEBDAV
                    "ftp" -> RecordingShareProtocol.FTP
                    else -> throw IOException("Unknown share protocol")
                }
                val target = RecordingShareTarget(json.getString("host"), if (json.has("port")) json.getInt("port") else null,
                    json.getString("share"), json.optString("folder", ""), protocol, json.optBoolean("secure", false))
                IptvShareSettings(target, json.optString("username", ""), json.optString("domain", ""), json.optBoolean("guest", false),
                    json.getString("id"), if (json.has("pin")) json.getString("pin") else null) to (if (json.has("secret")) Base64.getDecoder().decode(json.getString("secret")) else null)
            }
        } catch (error: Exception) {
            IptvLog.failure("share settings read", error)
            null
        }
        return cached
    }

    private fun context(id: String) = "nuvio-recording-share:$id"

    private fun newId(): String {
        val bytes = ByteArray(8).also(SecureRandom()::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
