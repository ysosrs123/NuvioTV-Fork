package com.nuvio.tv.core.server

import com.nuvio.tv.core.iptv.RecordingStatus
import com.nuvio.tv.core.iptv.SetupRecordingDownloads
import com.nuvio.tv.core.iptv.SetupRecordingEntry
import com.nuvio.tv.core.recording.IptvRecorder
import com.nuvio.tv.core.recording.IptvRecordingAvailability
import com.nuvio.tv.data.iptv.IptvRecording
import com.nuvio.tv.data.iptv.IptvRecordingReader
import java.io.Closeable

class SetupRecordingFile(val entry: SetupRecordingEntry, val reader: IptvRecordingReader) : Closeable {
    override fun close() = reader.close()
    override fun toString() = "SetupRecordingFile(withheld)"
}

interface SetupRecordingSource {
    fun list(profile: Int): List<SetupRecordingEntry>
    fun open(profile: Int, id: String): SetupRecordingFile?
}

class SetupRecorderSource(private val recorder: IptvRecorder) : SetupRecordingSource {
    override fun list(profile: Int): List<SetupRecordingEntry> {
        val found = recorder.all.value.filter { it.profileId == profile && listed(it) }
            .sortedByDescending { it.startedAtMillis ?: it.startMillis }.take(SetupRecordingDownloads.MAX_LISTED)
        val availability = recorder.availability(found)
        return found.map { entry(it, availability[it.id] == IptvRecordingAvailability.PLAYABLE) }
    }

    override fun open(profile: Int, id: String): SetupRecordingFile? {
        val recording = recorder.all.value.firstOrNull { it.id == id && it.profileId == profile && listed(it) } ?: return null
        val reader = recorder.reader(recording) ?: return null
        return SetupRecordingFile(entry(recording, true), reader)
    }

    private fun listed(recording: IptvRecording): Boolean =
        (recording.status == RecordingStatus.DONE || recording.status == RecordingStatus.PARTIAL) && recording.file != null

    private fun entry(recording: IptvRecording, available: Boolean): SetupRecordingEntry {
        val start = recording.startedAtMillis ?: recording.startMillis
        val stop = recording.finishedAtMillis ?: recording.stopMillis
        return SetupRecordingEntry(recording.id, recording.title?.takeIf { it.isNotBlank() } ?: recording.channelName, recording.channelName,
            start, (stop - start).coerceAtLeast(0), recording.bytes.takeIf { it > 0 }, recording.status == RecordingStatus.PARTIAL, available)
    }
}
