package com.nuvio.tv.core.player

import android.content.Context
import android.graphics.Typeface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A font the viewer sent from a phone. [family] is the name inside the file, which libass looks fonts up by. */
@Serializable
data class ImportedSubtitleFont(
    val id: String,
    val name: String,
    val family: String,
    val extension: String = "ttf",
    val lineHeightEm: Double = DEFAULT_LINE_HEIGHT_EM
)

private const val DEFAULT_LINE_HEIGHT_EM = 1.2

object ImportedSubtitleFonts {
    const val MAX_BYTES = 10 * 1024 * 1024
    private val idPattern = Regex("custom-[a-f0-9-]{36}")

    fun isImportedId(id: String?): Boolean = id != null && idPattern.matches(id)
}

class ImportedSubtitleFontStore private constructor(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val typefaces = ConcurrentHashMap<String, Typeface>()
    private val mutableFonts = MutableStateFlow(readIndex())
    val fonts: StateFlow<List<ImportedSubtitleFont>> = mutableFonts.asStateFlow()

    fun font(id: String?): ImportedSubtitleFont? = id?.let { wanted -> mutableFonts.value.firstOrNull { it.id == wanted } }

    fun cachedTypeface(id: String): Typeface? = typefaces[id]

    fun typeface(id: String): Typeface? = typefaces[id] ?: load(id)

    fun fontFile(id: String): File? = if (ImportedSubtitleFonts.isImportedId(id)) file(id).takeIf { it.isFile } else null

    @Synchronized
    fun import(fileName: String, bytes: ByteArray): ImportedSubtitleFont {
        require(isSupportedSubtitleFont(fileName, bytes))
        val current = mutableFonts.value
        current.firstOrNull { file(it.id).takeIf(File::isFile)?.readBytes()?.contentEquals(bytes) == true }
            ?.let { return it }
        val name = fileName.substringBeforeLast('.').trim().take(80).ifBlank { fileName }
        val family = subtitleFontFamilyName(bytes) ?: name
        val extension = fileName.substringAfterLast('.', "ttf").lowercase()
        val font = ImportedSubtitleFont(
            "custom-${UUID.randomUUID()}", name, family, extension,
            subtitleFontLineHeightEm(bytes) ?: DEFAULT_LINE_HEIGHT_EM
        )
        write(file(font.id), bytes)
        try {
            requireNotNull(load(font.id))
            writeIndex(current + font)
        } catch (error: Exception) {
            typefaces.remove(font.id)
            file(font.id).delete()
            throw error
        }
        mutableFonts.value = current + font
        return font
    }

    @Synchronized
    fun remove(id: String) {
        val remaining = mutableFonts.value.filterNot { it.id == id }
        writeIndex(remaining)
        typefaces.remove(id)
        if (ImportedSubtitleFonts.isImportedId(id)) file(id).delete()
        mutableFonts.value = remaining
    }

    private fun load(id: String): Typeface? = fontFile(id)
        ?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
        ?.also { typefaces[id] = it }

    private fun readIndex(): List<ImportedSubtitleFont> = runCatching {
        json.decodeFromString<List<ImportedSubtitleFont>>(indexFile().readText())
            .filter { ImportedSubtitleFonts.isImportedId(it.id) && file(it.id).isFile }
    }.getOrDefault(emptyList())

    private fun writeIndex(fonts: List<ImportedSubtitleFont>) {
        write(indexFile(), json.encodeToString(fonts).encodeToByteArray())
    }

    private fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.path + ".tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(target))
    }

    private fun indexFile() = File(dir, "fonts.json")

    private fun file(id: String): File {
        require(ImportedSubtitleFonts.isImportedId(id))
        return File(dir, id)
    }

    companion object {
        @Volatile
        private var instance: ImportedSubtitleFontStore? = null

        fun get(context: Context): ImportedSubtitleFontStore = instance ?: synchronized(this) {
            instance ?: ImportedSubtitleFontStore(File(context.applicationContext.filesDir, "subtitle-fonts-imported"))
                .also { instance = it }
        }
    }
}

fun isSupportedSubtitleFont(fileName: String, bytes: ByteArray): Boolean {
    if (fileName.substringAfterLast('.', "").lowercase() !in setOf("ttf", "otf")) return false
    if (bytes.size !in 12..ImportedSubtitleFonts.MAX_BYTES) return false
    val signature = bytes.take(4).map { it.toInt() and 255 }
    if (signature != listOf(0, 1, 0, 0) && bytes.decodeToString(0, 4) != "OTTO") return false
    val tables = uint16(bytes, 4)
    if (tables == 0 || 12L + tables * 16L > bytes.size) return false
    return (0 until tables).all { index ->
        val offset = 12 + index * 16
        val start = uint32(bytes, offset + 8)
        val length = uint32(bytes, offset + 12)
        start <= bytes.size && length <= bytes.size - start
    }
}

/**
 * The family name stored in the font (name ID 1), as libass reads it: Windows platform first, US English preferred,
 * then the Macintosh Roman entry. Null when the file has no usable name.
 */
fun subtitleFontFamilyName(bytes: ByteArray): String? = runCatching {
    val tables = uint16(bytes, 4)
    val nameTable = (0 until tables).map { 12 + it * 16 }
        .firstOrNull { bytes.decodeToString(it, it + 4) == "name" }
        ?.let { uint32(bytes, it + 8).toInt() } ?: return null
    val count = uint16(bytes, nameTable + 2)
    val storage = nameTable + uint16(bytes, nameTable + 4)
    data class Entry(val platform: Int, val language: Int, val text: String)
    val entries = (0 until count).mapNotNull { index ->
        val record = nameTable + 6 + index * 12
        if (uint16(bytes, record + 6) != 1) return@mapNotNull null
        val platform = uint16(bytes, record)
        val language = uint16(bytes, record + 4)
        val length = uint16(bytes, record + 8)
        val start = storage + uint16(bytes, record + 10)
        if (start + length > bytes.size) return@mapNotNull null
        val raw = bytes.copyOfRange(start, start + length)
        val text = when (platform) {
            0, 3 -> String(raw, Charsets.UTF_16BE)
            1 -> String(raw, Charsets.ISO_8859_1)
            else -> return@mapNotNull null
        }.trim()
        text.takeIf { it.isNotEmpty() }?.let { Entry(platform, language, it) }
    }
    (entries.firstOrNull { it.platform == 3 && it.language == 0x409 }
        ?: entries.firstOrNull { it.platform == 3 }
        ?: entries.firstOrNull { it.platform == 1 }
        ?: entries.firstOrNull())?.text
}.getOrNull()

/** Win ascent plus win descent (OS/2) in font sizes, the height libass fits into the size it is asked for. */
fun subtitleFontLineHeightEm(bytes: ByteArray): Double? = runCatching {
    val tables = uint16(bytes, 4)
    fun table(tag: String): Int? = (0 until tables).map { 12 + it * 16 }
        .firstOrNull { bytes.decodeToString(it, it + 4) == tag }
        ?.let { uint32(bytes, it + 8).toInt() }
    val head = table("head") ?: return null
    val os2 = table("OS/2") ?: return null
    val unitsPerEm = uint16(bytes, head + 18).takeIf { it > 0 } ?: return null
    val height = uint16(bytes, os2 + 74) + uint16(bytes, os2 + 76)
    (height.toDouble() / unitsPerEm).takeIf { it in 0.5..3.0 }
}.getOrNull()

private fun uint16(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)

private fun uint32(bytes: ByteArray, offset: Int): Long =
    (0..3).fold(0L) { value, i -> (value shl 8) or (bytes[offset + i].toLong() and 255) }
