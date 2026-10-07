package com.nuvio.tv.core.iptv

import java.util.Locale

enum class RecordingFileSystem(val label: String?, val largeFiles: Boolean) {
    FAT32("FAT32", false), EXFAT("exFAT", true), NTFS("NTFS", true), LARGE(null, true), UNKNOWN(null, false)
}

data class RecordingMount(val point: String, val type: String, val readOnly: Boolean)

object RecordingMounts {
    private val FAT = setOf("vfat", "msdos", "fat", "umsdos")
    private val EXFAT = setOf("exfat", "texfat")
    private val NTFS = setOf("ntfs", "ntfs3", "tntfs")
    private val LARGE = setOf("ext2", "ext3", "ext4", "f2fs", "btrfs", "xfs", "hfsplus", "fuseblk", "ufsd")

    fun parse(text: String): List<RecordingMount> = text.lineSequence().mapNotNull { line ->
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 4) null else RecordingMount(unescape(fields[1]), fields[2].lowercase(Locale.ROOT), "ro" in fields[3].split(','))
    }.toList()

    fun type(type: String): RecordingFileSystem = when (type.lowercase(Locale.ROOT)) {
        in FAT -> RecordingFileSystem.FAT32
        in EXFAT -> RecordingFileSystem.EXFAT
        in NTFS -> RecordingFileSystem.NTFS
        in LARGE -> RecordingFileSystem.LARGE
        else -> RecordingFileSystem.UNKNOWN
    }

    fun volumeRoot(path: String): String? {
        val clean = path.trimEnd('/')
        val index = clean.indexOf("/Android/data/")
        return (if (index > 0) clean.substring(0, index) else null)?.takeIf { it.count { c -> c == '/' } >= 2 }
    }

    fun find(mounts: List<RecordingMount>, volumeRoot: String): RecordingMount? {
        val root = volumeRoot.trimEnd('/')
        val name = root.substringAfterLast('/')
        val candidates = listOf("/mnt/media_rw/$name", root, "/mnt/user/0/$name", "/mnt/runtime/write/$name", "/mnt/pass_through/0/$name")
        val exact = candidates.flatMap { point -> mounts.filter { it.point == point } }
        return exact.firstOrNull { type(it.type) != RecordingFileSystem.UNKNOWN } ?: exact.firstOrNull()
    }

    fun fileSystem(mounts: List<RecordingMount>, volumeRoot: String): RecordingFileSystem =
        find(mounts, volumeRoot)?.let { type(it.type) } ?: RecordingFileSystem.UNKNOWN

    private fun unescape(value: String): String = Regex("\\\\([0-7]{3})").replace(value) { it.groupValues[1].toInt(8).toChar().toString() }
}

object RecordingParts {
    const val FAT_PART_BYTES = 4_000L * 1024 * 1024
    const val SPOOL_PART_BYTES = 256L * 1024 * 1024
    const val SEGMENT_HEADROOM_BYTES = 64L * 1024 * 1024
    const val MAX_PARTS = 999
    private const val PACKET = 188

    fun name(main: String, index: Int): String {
        require(index in 1..MAX_PARTS)
        if (index == 1) return main
        val base = main.removeSuffix(RecordingFiles.EXTENSION)
        return "$base (part $index)${if (base.length < main.length) RecordingFiles.EXTENSION else ""}"
    }

    fun partBytes(fileSystem: RecordingFileSystem): Long = if (fileSystem.largeFiles) Long.MAX_VALUE else FAT_PART_BYTES

    fun fit(partSize: Long, length: Int, limit: Long): Int {
        require(partSize >= 0 && length >= 0 && limit > 0)
        if (limit == Long.MAX_VALUE || partSize + length <= limit) return length
        val aligned = if (limit >= PACKET) limit - limit % PACKET else limit
        return (aligned - partSize).coerceIn(0, length.toLong()).toInt()
    }

    fun rollBeforeSegment(partSize: Long, limit: Long, headroom: Long): Boolean =
        partSize > 0 && limit != Long.MAX_VALUE && partSize >= limit - headroom.coerceIn(0, limit / 4)

    fun tooLarge(message: String?): Boolean = message != null && ("EFBIG" in message || message.contains("file too large", ignoreCase = true))
}

data class RecordingPiece(val start: Long, val length: Long) {
    init { require(start >= 0 && length >= 0) }
    val end: Long get() = start + length
}

sealed interface RecordingUploadStep {
    data object Done : RecordingUploadStep
    data object Wait : RecordingUploadStep
    data object Lost : RecordingUploadStep
    data class Truncate(val size: Long) : RecordingUploadStep
    data class Write(val piece: Int, val offset: Long, val count: Int) : RecordingUploadStep
}

object RecordingUpload {
    fun next(pieces: List<RecordingPiece>, remote: Long, available: Long, finished: Boolean, chunk: Int): RecordingUploadStep {
        require(remote >= 0 && chunk > 0)
        val sorted = pieces.sortedBy { it.start }
        val end = minOf(available, sorted.lastOrNull()?.end ?: remote)
        return when {
            remote > end -> if (finished && sorted.isNotEmpty() && sorted.first().start <= end) RecordingUploadStep.Truncate(end)
                else if (sorted.isEmpty() && finished) RecordingUploadStep.Done else RecordingUploadStep.Wait
            remote == end -> if (finished) RecordingUploadStep.Done else RecordingUploadStep.Wait
            else -> {
                val index = sorted.indexOfFirst { remote >= it.start && remote < it.end }
                if (index < 0) RecordingUploadStep.Lost
                else RecordingUploadStep.Write(pieces.indexOf(sorted[index]), remote - sorted[index].start,
                    minOf(chunk.toLong(), sorted[index].end - remote, end - remote).toInt())
            }
        }
    }

    fun removable(pieces: List<RecordingPiece>, confirmed: Long, finished: Boolean): List<Int> {
        val last = pieces.maxByOrNull { it.start }
        return pieces.indices.filter { pieces[it].end <= confirmed && (finished || pieces[it] !== last) }
    }

    fun whole(pieces: List<RecordingPiece>, available: Long): Long? {
        val sorted = pieces.sortedBy { it.start }
        if (sorted.isEmpty() || sorted.first().start != 0L) return null
        if (sorted.zipWithNext().any { (a, b) -> a.end != b.start }) return null
        return minOf(sorted.last().end, available).takeIf { it > 0 }
    }

    fun spoolName(start: Long): String = "%013d%s".format(Locale.ROOT, start, RecordingFiles.EXTENSION)

    fun spoolStart(name: String): Long? =
        name.takeIf { it.length == 13 + RecordingFiles.EXTENSION.length && it.endsWith(RecordingFiles.EXTENSION) }?.take(13)?.takeIf { s -> s.all { it in '0'..'9' } }?.toLong()

    private val DELAYS = longArrayOf(2_000, 4_000, 8_000, 15_000, 30_000, 60_000)
    fun retryMillis(attempt: Int): Long = DELAYS[attempt.coerceIn(0, DELAYS.size - 1)]
}

enum class RecordingShareProtocol { SMB, WEBDAV, FTP }

data class RecordingShareTarget(val host: String, val port: Int?, val share: String, val folder: String,
    val protocol: RecordingShareProtocol = RecordingShareProtocol.SMB, val secure: Boolean = false) {
    fun path(name: String): String = if (folder.isEmpty()) name else "$folder/$name"
    fun same(other: RecordingShareTarget): Boolean {
        if (protocol != other.protocol || !host.equals(other.host, true) || port != other.port || secure != other.secure) return false
        val caseless = protocol == RecordingShareProtocol.SMB
        return share.equals(other.share, caseless) && folder.equals(other.folder, caseless)
    }
    override fun toString(): String = "RecordingShareTarget(withheld)"
}

object RecordingShareAddress {
    private const val INVALID = "<>:\"/\\|?*"

    fun parse(server: String, share: String, folder: String): RecordingShareTarget? {
        var text = server.trim()
        if (text.startsWith("smb://", ignoreCase = true)) text = text.substring(6)
        text = text.trimStart('\\', '/').replace('\\', '/')
        val authority = text.substringBefore('/')
        val rest = text.substringAfter('/', "").split('/').filter { it.isNotBlank() }
        val shareName = share.trim().trim('/', '\\').ifEmpty { rest.firstOrNull().orEmpty() }
        val folderText = if (share.isBlank() && rest.isNotEmpty()) (rest.drop(1) + folder).joinToString("/") else folder
        val (host, port) = hostPort(authority) ?: return null
        if (shareName.isEmpty() || shareName.length > 80 || !segment(shareName)) return null
        val path = folder(folderText) ?: return null
        return RecordingShareTarget(host, port, shareName, path)
    }

    fun webDav(address: String, folder: String): RecordingShareTarget? {
        var text = address.trim()
        val scheme = text.substringBefore("://", "").lowercase()
        if (scheme.isNotEmpty()) text = text.substringAfter("://")
        val secure = when (scheme) {
            "", "http", "dav", "webdav" -> false
            "https", "davs", "webdavs" -> true
            else -> return null
        }
        text = text.substringBefore('#').substringBefore('?')
        val (host, port) = hostPort(text.substringBefore('/').substringAfterLast('@')) ?: return null
        val base = text.substringAfter('/', "").split('/').filter { it.isNotEmpty() }.map { decode(it) ?: return null }
        if (base.any { it == "." || it == ".." || !segment(it) } || base.joinToString("/").length > 300) return null
        val path = folder(folder) ?: return null
        return RecordingShareTarget(host, port, base.joinToString("/"), path, RecordingShareProtocol.WEBDAV, secure)
    }

    fun ftp(server: String, folder: String, secure: Boolean): RecordingShareTarget? {
        var text = server.trim()
        val scheme = text.substringBefore("://", "").lowercase()
        if (scheme.isNotEmpty()) text = text.substringAfter("://")
        if (scheme !in setOf("", "ftp", "ftps", "ftpes")) return null
        text = text.trimStart('/')
        val (host, port) = hostPort(text.substringBefore('/').substringAfterLast('@')) ?: return null
        val rest = text.substringAfter('/', "").split('/').filter { it.isNotBlank() }
        val path = folder((rest + folder).joinToString("/")) ?: return null
        return RecordingShareTarget(host, port, "", path, RecordingShareProtocol.FTP, secure || scheme != "" && scheme != "ftp")
    }

    fun address(target: RecordingShareTarget): String {
        val authority = (if (':' in target.host) "[${target.host}]" else target.host) + (target.port?.let { ":$it" } ?: "")
        return when (target.protocol) {
            RecordingShareProtocol.WEBDAV -> (if (target.secure) "https://" else "http://") + authority +
                target.share.split('/').filter { it.isNotEmpty() }.joinToString("") { "/" + encode(it) }
            else -> authority
        }
    }

    fun url(target: RecordingShareTarget, path: String, collection: Boolean = false): String {
        val parts = (target.share.split('/') + path.split('/')).filter { it.isNotEmpty() }
        val root = (if (target.secure) "https://" else "http://") + (if (':' in target.host) "[${target.host}]" else target.host) +
            (target.port?.let { ":$it" } ?: "")
        return root + "/" + parts.joinToString("/") { encode(it) } + if (collection && parts.isNotEmpty()) "/" else ""
    }

    fun label(target: RecordingShareTarget): String = (listOf(target.host) + (if (target.protocol == RecordingShareProtocol.SMB) listOf(target.share) else emptyList()) +
        target.folder.split('/').filter { it.isNotEmpty() }).joinToString("/")

    fun encode(segment: String): String {
        val result = StringBuilder()
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in "-._~!$&'()*,;=@")) result.append(c.toChar())
            else result.append('%').append(HEX[c shr 4]).append(HEX[c and 15])
        }
        return result.toString()
    }

    fun decode(segment: String): String? {
        if ('%' !in segment) return segment
        val bytes = java.io.ByteArrayOutputStream()
        var index = 0
        while (index < segment.length) {
            val c = segment[index]
            if (c == '%') {
                if (index + 2 >= segment.length) return null
                val value = segment.substring(index + 1, index + 3).toIntOrNull(16) ?: return null
                bytes.write(value)
                index += 3
            } else {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                index += 1
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
        return try { decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString() } catch (_: java.nio.charset.CharacterCodingException) { null }
    }

    private fun folder(text: String): String? {
        val parts = text.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." || !segment(it) || it.endsWith('.') }) return null
        return parts.joinToString("/").takeIf { it.length <= 200 }
    }

    private const val HEX = "0123456789ABCDEF"

    private fun hostPort(text: String): Pair<String, Int?>? {
        if (text.isEmpty() || text.length > 260) return null
        val host: String
        val portText: String?
        when {
            text.startsWith("[") -> {
                val close = text.indexOf(']')
                if (close < 2) return null
                host = text.substring(1, close)
                val after = text.substring(close + 1)
                if (after.isNotEmpty() && !after.startsWith(":")) return null
                portText = after.removePrefix(":").ifEmpty { null }
                if (!host.all { it.isLetterOrDigit() || it in ":.%" }) return null
            }
            text.count { it == ':' } > 1 -> {
                host = text
                portText = null
                if (!host.all { it.isLetterOrDigit() || it in ":.%" }) return null
            }
            else -> {
                host = text.substringBefore(':')
                portText = text.substringAfter(':', "").ifEmpty { null }
                if (!host.all { it.isLetterOrDigit() || it in ".-_" }) return null
            }
        }
        if (host.isEmpty() || host.length > 253) return null
        val port = if (portText == null) null else portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return host to port
    }

    private fun segment(value: String): Boolean = value.isNotEmpty() && value.length <= 120 && value.none { it < ' ' || it in INVALID }
}

object RecordingLocations {
    const val INTERNAL = "internal"
    const val SHARE = "share"
    const val MEDIA = "media"
    private const val VOLUME_PREFIX = "volume:"
    private const val SHARE_PREFIX = "share:"
    private val ID = Regex("[A-Za-z0-9-]{1,64}")

    fun volume(id: String): String { require(id.matches(ID)); return VOLUME_PREFIX + id }
    fun volumeId(value: String?): String? = value?.takeIf { it.startsWith(VOLUME_PREFIX) }?.substring(VOLUME_PREFIX.length)?.takeIf { it.matches(ID) }
    fun share(id: String): String { require(id.matches(ID)); return SHARE_PREFIX + id }
    fun shareId(value: String?): String? = value?.takeIf { it.startsWith(SHARE_PREFIX) }?.substring(SHARE_PREFIX.length)?.takeIf { it.matches(ID) }
    fun valid(value: String?): Boolean = value == null || value == MEDIA || volumeId(value) != null || shareId(value) != null
    fun choice(value: String?): String = value?.takeIf { it == INTERNAL || it == SHARE || it == MEDIA || volumeId(it) != null } ?: INTERNAL
}

enum class RecordingMediaState { COPYING, PRESENT, MISSING }

object RecordingMedia {
    const val MIN_SDK = 29
    const val RELATIVE_PATH = "Movies/Nuvio Recordings/"
    const val MIME_TYPE = "video/mp2t"

    fun supported(sdk: Int): Boolean = sdk >= MIN_SDK

    fun id(file: String?): Long? = file?.takeIf { it.length in 1..18 && it.all { c -> c in '0'..'9' } }?.toLong()?.takeIf { it > 0 }

    fun pending(path: String): Boolean = path.endsWith(RecordingFiles.PARTIAL_SUFFIX)

    fun displayName(path: String): String {
        val name = path.substringAfterLast('/').removeSuffix(RecordingFiles.PARTIAL_SUFFIX).trim()
        require(name.isNotEmpty() && name.none { it < ' ' || it in "<>:\"/\\|?*" })
        return RecordingFiles.safe(if (name.endsWith(RecordingFiles.EXTENSION)) name else name + RecordingFiles.EXTENSION)
    }

    fun state(upload: Boolean, file: String?, present: Set<Long>): RecordingMediaState = when {
        upload -> RecordingMediaState.COPYING
        id(file)?.let { it in present } == true -> RecordingMediaState.PRESENT
        else -> RecordingMediaState.MISSING
    }
}
