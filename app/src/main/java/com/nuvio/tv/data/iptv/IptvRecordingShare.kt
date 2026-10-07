package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.RecordingShareTarget
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

enum class IptvShareError { UNREACHABLE, TIMEOUT, LOGIN_REFUSED, GUEST_REFUSED, SHARE_NOT_FOUND, FOLDER_NOT_FOUND, ACCESS_DENIED, READ_ONLY, FULL, SMB1_ONLY, DISCONNECTED, LOST, OTHER }

class IptvShareException(val error: IptvShareError, cause: Throwable? = null) : IOException("Network share ${error.name.lowercase()}", cause)

interface IptvShareFile : Closeable {
    val length: Long
    fun write(offset: Long, buffer: ByteArray, start: Int, count: Int)
    fun read(offset: Long, buffer: ByteArray, start: Int, count: Int): Int
    fun flush()
}

interface IptvShareSession : Closeable {
    fun length(path: String): Long?
    fun openWrite(path: String): IptvShareFile
    fun openRead(path: String): IptvShareFile
    fun rename(from: String, to: String, replace: Boolean)
    fun delete(path: String): Boolean
    fun list(folder: String): List<String>
    fun ensureFolder(folder: String)
    fun freeBytes(): Long
}

fun interface IptvShareConnector {
    fun connect(): IptvShareSession
}

data class IptvShareSettings(val target: RecordingShareTarget, val username: String, val domain: String, val guest: Boolean, val id: String) {
    init { require(id.matches(Regex("[A-Za-z0-9]{8,32}")) && username.length <= 256 && domain.length <= 256) }
    val label: String get() = listOf(target.host, target.share).plus(target.folder.split('/').filter { it.isNotEmpty() }).joinToString("/")
    override fun toString(): String = "IptvShareSettings(withheld)"
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

    @Synchronized fun save(target: RecordingShareTarget, username: String, domain: String, guest: Boolean, password: String?): IptvShareSettings {
        val previous = load()
        val sameTarget = previous?.first?.target?.let { it.host.equals(target.host, true) && it.port == target.port &&
            it.share.equals(target.share, true) && it.folder.equals(target.folder, true) } == true
        val id = if (sameTarget) previous!!.first.id else newId()
        val settings = IptvShareSettings(target, username.trim(), domain.trim(), guest, id)
        val sealed = when {
            guest -> null
            password == null && sameTarget -> previous!!.second
            else -> box().seal(context(id), password.orEmpty())
        }
        val json = JSONObject().put("version", 1).put("id", id).put("host", target.host).putOpt("port", target.port).put("share", target.share)
            .put("folder", target.folder).put("username", settings.username).put("domain", settings.domain).put("guest", guest)
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
                val target = RecordingShareTarget(json.getString("host"), if (json.has("port")) json.getInt("port") else null,
                    json.getString("share"), json.optString("folder", ""))
                IptvShareSettings(target, json.optString("username", ""), json.optString("domain", ""), json.optBoolean("guest", false),
                    json.getString("id")) to (if (json.has("secret")) Base64.getDecoder().decode(json.getString("secret")) else null)
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
