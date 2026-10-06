package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.RecordingFailure
import com.nuvio.tv.core.iptv.RecordingSlot
import com.nuvio.tv.core.iptv.RecordingStatus
import com.nuvio.tv.core.iptv.RecordingTransitions
import com.nuvio.tv.core.iptv.RecordingWindow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

data class IptvRecording(
    val id: String, val profileId: Int, val sourceId: String, val accountId: String, val channelId: String,
    val channelName: String, val title: String?, val description: String?,
    val startMillis: Long, val stopMillis: Long, val status: RecordingStatus, val failure: RecordingFailure? = null,
    val file: String? = null, val bytes: Long = 0, val gaps: Int = 0,
    val programmeStartMillis: Long? = null, val programmeStopMillis: Long? = null,
    val createdAtMillis: Long, val startedAtMillis: Long? = null, val finishedAtMillis: Long? = null,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9-]{8,64}")) && profileId >= 0 && stopMillis > startMillis && bytes >= 0 && gaps >= 0)
        require(channelId.isNotEmpty() && channelId.length <= 1024 && accountId.isNotEmpty() && accountId.length <= 80)
    }
    val source: IptvSourceRef get() = IptvSourceRef(profileId, sourceId)
    val window: RecordingWindow get() = RecordingWindow(startMillis, stopMillis)
    val slot: RecordingSlot get() = RecordingSlot(accountId, startMillis, stopMillis)
    override fun toString(): String = "IptvRecording(id=$id, status=$status, failure=$failure, bytes=$bytes)"
}

class IptvRecordingStore(private val file: File, private val maxEntries: Int = 2_000) {
    init { require(maxEntries > 0) }
    private var loaded: LinkedHashMap<String, IptvRecording>? = null

    @Synchronized fun all(): List<IptvRecording> = entries().values.toList()

    @Synchronized fun get(id: String): IptvRecording? = entries()[id]

    @Synchronized fun insert(recording: IptvRecording) {
        val entries = entries()
        require(recording.id !in entries)
        check(entries.size < maxEntries) { "Recording list is full" }
        save(LinkedHashMap(entries).apply { put(recording.id, recording) })
    }

    @Synchronized fun update(id: String, transform: (IptvRecording) -> IptvRecording): IptvRecording? {
        val entries = entries()
        val current = entries[id] ?: return null
        val next = transform(current)
        require(next.id == current.id && next.profileId == current.profileId)
        require(next.status == current.status || RecordingTransitions.allowed(current.status, next.status)) { "Invalid recording transition" }
        if (next == current) return current
        save(LinkedHashMap(entries).apply { put(id, next) })
        return next
    }

    @Synchronized fun remove(id: String): IptvRecording? {
        val entries = entries()
        val removed = entries[id] ?: return null
        save(LinkedHashMap(entries).apply { remove(id) })
        return removed
    }

    @Synchronized fun removeProfile(profileId: Int): List<IptvRecording> {
        val entries = entries()
        val removed = entries.values.filter { it.profileId == profileId }
        if (removed.isNotEmpty()) save(LinkedHashMap(entries.filterValues { it.profileId != profileId }))
        return removed
    }

    @Synchronized fun clear(): List<IptvRecording> {
        val removed = entries().values.toList()
        save(LinkedHashMap())
        return removed
    }

    private fun entries(): LinkedHashMap<String, IptvRecording> = loaded ?: read().also { loaded = it }

    private fun read(): LinkedHashMap<String, IptvRecording> {
        val result = LinkedHashMap<String, IptvRecording>()
        if (!file.exists()) return result
        try {
            check(file.length() <= MAX_FILE_BYTES)
            val root = JSONObject(file.readText())
            check(root.getInt("version") == VERSION)
            val items = root.getJSONArray("recordings")
            for (index in 0 until minOf(items.length(), maxEntries)) {
                val entry = try { decode(items.getJSONObject(index)) } catch (_: Exception) { null } ?: continue
                result[entry.id] = entry
            }
        } catch (error: Exception) {
            IptvLog.failure("recordings read", error)
            file.renameTo(File(file.parentFile, file.name + ".bad"))
        }
        return result
    }

    private fun save(entries: LinkedHashMap<String, IptvRecording>) {
        val text = JSONObject().put("version", VERSION).put("recordings", JSONArray().apply { entries.values.forEach { put(encode(it)) } }).toString()
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw IOException("Recording list folder unavailable")
        val temporary = File(parent, file.name + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("Recording list could not be saved")
        }
        loaded = entries
    }

    private fun encode(entry: IptvRecording) = JSONObject().apply {
        put("id", entry.id); put("profile", entry.profileId); put("source", entry.sourceId); put("account", entry.accountId)
        put("channel", entry.channelId); put("channelName", entry.channelName); putOpt("title", entry.title); putOpt("description", entry.description)
        put("start", entry.startMillis); put("stop", entry.stopMillis); put("status", entry.status.name); putOpt("failure", entry.failure?.name)
        putOpt("file", entry.file); put("bytes", entry.bytes); put("gaps", entry.gaps)
        putOpt("programmeStart", entry.programmeStartMillis); putOpt("programmeStop", entry.programmeStopMillis)
        put("created", entry.createdAtMillis); putOpt("started", entry.startedAtMillis); putOpt("finished", entry.finishedAtMillis)
    }

    private fun decode(json: JSONObject) = IptvRecording(
        id = json.getString("id"), profileId = json.getInt("profile"), sourceId = json.getString("source"), accountId = json.getString("account"),
        channelId = json.getString("channel"), channelName = json.getString("channelName"), title = json.text("title"), description = json.text("description"),
        startMillis = json.getLong("start"), stopMillis = json.getLong("stop"), status = RecordingStatus.valueOf(json.getString("status")),
        failure = json.text("failure")?.let { name -> RecordingFailure.entries.firstOrNull { it.name == name } ?: RecordingFailure.INTERRUPTED },
        file = json.text("file"), bytes = json.optLong("bytes", 0), gaps = json.optInt("gaps", 0),
        programmeStartMillis = json.number("programmeStart"), programmeStopMillis = json.number("programmeStop"),
        createdAtMillis = json.getLong("created"), startedAtMillis = json.number("started"), finishedAtMillis = json.number("finished"),
    ).also { IptvSourceRef(it.profileId, it.sourceId) }

    private fun JSONObject.text(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONObject.number(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null

    private companion object {
        const val VERSION = 1
        const val MAX_FILE_BYTES = 16L * 1024 * 1024
    }
}
