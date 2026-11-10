package com.nuvio.tv.data.repository

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import com.nuvio.tv.core.report.FRAME_BUCKET_COUNT
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.core.report.FRAME_BUCKET_EDGES_MS
import com.nuvio.tv.core.report.RECORDER_UNKNOWN
import com.nuvio.tv.core.report.RecorderSnapshot
import com.nuvio.tv.data.remote.dto.PlaybackIssueBoxDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueMarkDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueNetworkDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueRecordingDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueRecorderDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueRecorderSamplesDto
import java.io.File

internal const val RECORDER_LINE_MAX_CHARS = 300
internal const val RECORDER_LINES_MAX_CHARS = 150_000

internal const val RECORDING_EVENT_LINES_MAX_CHARS = 400_000
internal const val RELAY_MAX_GZIP_BYTES = 1_400_000

internal fun RecorderSnapshot.toDto(
    settings: Map<String, String>? = null,
    route: Map<String, String>? = null
): PlaybackIssueRecorderDto {
    val kept = samples?.takeIf { it.count > 0 }
    val lines = events.mapNotNull { ReportText.eventLine(it.text, RECORDER_LINE_MAX_CHARS) }
    val recorded = recording != null
    return PlaybackIssueRecorderDto(
        enabled = enabled,
        sampleCount = kept?.count ?: 0,
        coveredMs = kept?.coveredMs ?: 0L,
        samples = kept?.let {
            PlaybackIssueRecorderSamplesDto(
                ageMs = it.ageMs.toList(),
                readyAheadMs = it.readyAheadMs.known(),
                droppedFrames = it.droppedFrames.known(),
                audioUnderruns = it.audioUnderruns.known(),
                ownCpuTenthsOfPercent = it.ownCpuTenths.known(),
                rssKb = it.rssKb.known(),
                javaHeapKb = it.javaHeapKb.known(),
                nativeHeapKb = it.nativeHeapKb.known(),
                memAvailableKb = it.memAvailableKb.known(),
                bufferCapKb = it.bufferCapKb.known(),
                thermalStatus = it.thermalStatus.known(),
                loaderActive = it.loaderActive.known(),
                tunnelled = it.tunnelled.known(),
                playing = it.playing.known(),
                skippedFrames = it.skippedFrames.known(),
                frameLeadUs = it.frameLeadUs.known(),
                audioJitterAvgMs = it.audioJitterAvgMs.known(),
                audioJitterMaxMs = it.audioJitterMaxMs.known(),
                fillerCount = it.fillerCount.known(),
                audioPositionMs = it.audioPositionMs.known(),
                speedPercent = it.speedPercent.known(),
                connections = it.connections.known(),
                watchedMs = it.watchedMs.known(),
                frameBuckets = it.frameBuckets?.let { flat ->
                    List(it.count) { row -> List(FRAME_BUCKET_COUNT) { flat[row * FRAME_BUCKET_COUNT + it] } }
                }.orEmpty()
            )
        },
        eventLines = if (recorded) newestWithinBudget(lines, RECORDING_EVENT_LINES_MAX_CHARS) else newestWithinBudget(lines),
        recording = recording?.let {
            PlaybackIssueRecordingDto(
                startedAtMs = it.startedAtWallMs,
                lengthMs = it.lengthMs,
                endedBy = it.end?.label,
                frameBucketEdgesMs = FRAME_BUCKET_EDGES_MS.toList()
            )
        },
        marks = marks.map {
            PlaybackIssueMarkDto(
                wallMs = it.wallMs,
                positionMs = it.positionMs.takeIf { position -> position >= 0L },
                elapsedMs = it.elapsedMs
            )
        },
        settings = settings,
        route = route
    )
}

/** Lines of the audio capability text the Diagnostics card shows, as one fact each. */
internal fun routeFactsFromCapabilities(text: String?): Map<String, String> {
    if (text.isNullOrBlank()) return emptyMap()
    val facts = LinkedHashMap<String, String>()
    for (line in text.lineSequence()) {
        val name = line.substringBefore(": ", "").trim()
        val value = line.substringAfter(": ", "").trim()
        if (name.isEmpty() || value.isEmpty() || name.length > 40) continue
        val key = name.split(' ', '-').map { part -> part.filter { it.isLetterOrDigit() }.lowercase() }.filter { it.isNotEmpty() }
            .mapIndexed { index, part -> if (index == 0) part else part.replaceFirstChar { c -> c.uppercase() } }
            .joinToString("")
        if (key.isEmpty()) continue
        ReportText.clean(value, 240)?.let { facts[key] = it }
    }
    return facts
}

/** The settings that decide how sound and picture are sent, as they are while the report is built. */
internal fun recorderSettingsFacts(settings: PlayerSettings, audioDelayMs: Int): Map<String, String> = cleanedFacts(
    linkedMapOf(
        "audioDelayMs" to audioDelayMs.toString(),
        "surroundFormatMode" to settings.surroundFormatMode.name,
        "arcSoundbar" to settings.surroundTvArcSoundbar.toString(),
        "systemPassthrough" to settings.useSystemPassthrough.toString(),
        "dvMode" to settings.dv7HandlingMode.name,
        "customBuffers" to if (settings.bufferEngineEnabled) {
            "on min=${settings.bufferSettings.minBufferMs} max=${settings.bufferSettings.maxBufferMs} start=${settings.bufferSettings.bufferForPlaybackMs} " +
                "restart=${settings.bufferSettings.bufferForPlaybackAfterRebufferMs} targetMb=${settings.bufferSettings.targetBufferSizeMb} " +
                "backMs=${settings.bufferSettings.backBufferDurationMs}"
        } else {
            "off"
        },
        "diskCache" to if (settings.vodCacheEnabled) {
            "on mode=${settings.vodCacheSizeMode.name} mb=${settings.vodCacheSizeMb}"
        } else {
            "off"
        }
    )
)

internal fun cleanedFacts(facts: Map<String, String?>): Map<String, String> =
    facts.mapNotNull { (key, value) -> ReportText.clean(value, 240)?.let { key to it } }.toMap()

internal fun PlaybackIssueRecorderSamplesDto.dropOldest(count: Int): PlaybackIssueRecorderSamplesDto {
    if (count <= 0) return this
    fun <T> List<T>.rest() = if (isEmpty()) this else drop(count)
    return copy(
        ageMs = ageMs.rest(), readyAheadMs = readyAheadMs.rest(), droppedFrames = droppedFrames.rest(),
        audioUnderruns = audioUnderruns.rest(), ownCpuTenthsOfPercent = ownCpuTenthsOfPercent.rest(),
        rssKb = rssKb.rest(), javaHeapKb = javaHeapKb.rest(), nativeHeapKb = nativeHeapKb.rest(),
        memAvailableKb = memAvailableKb.rest(), bufferCapKb = bufferCapKb.rest(), thermalStatus = thermalStatus.rest(),
        loaderActive = loaderActive.rest(), tunnelled = tunnelled.rest(), playing = playing.rest(),
        skippedFrames = skippedFrames.rest(), frameLeadUs = frameLeadUs.rest(),
        audioJitterAvgMs = audioJitterAvgMs.rest(), audioJitterMaxMs = audioJitterMaxMs.rest(),
        fillerCount = fillerCount.rest(), audioPositionMs = audioPositionMs.rest(), speedPercent = speedPercent.rest(),
        connections = connections.rest(), watchedMs = watchedMs.rest(), frameBuckets = frameBuckets.rest()
    )
}

/**
 * Drops the oldest samples and event lines, a quarter at a time, until [fits] says the report is small enough.
 * Marks and the newest data stay; with nothing left to drop the marks alone are returned.
 */
internal fun fitRecorderToBudget(
    recorder: PlaybackIssueRecorderDto,
    fits: (PlaybackIssueRecorderDto) -> Boolean
): PlaybackIssueRecorderDto {
    var current = recorder
    while (!fits(current)) {
        val samples = current.samples
        val sampleCount = samples?.ageMs?.size ?: 0
        val lineCount = current.eventLines.size
        if (sampleCount == 0 && lineCount == 0) return current
        val dropSamples = if (sampleCount == 0) 0 else maxOf(1, sampleCount / 4)
        val dropLines = if (lineCount == 0) 0 else maxOf(1, lineCount / 4)
        val trimmed = samples?.dropOldest(dropSamples)
        val remaining = trimmed?.ageMs.orEmpty()
        current = current.copy(
            samples = trimmed?.takeIf { remaining.isNotEmpty() },
            sampleCount = remaining.size,
            coveredMs = if (remaining.size > 1) remaining.first() - remaining.last() else 0L,
            eventLines = current.eventLines.drop(dropLines),
            trimmedSamples = current.trimmedSamples + dropSamples,
            trimmedEventLines = current.trimmedEventLines + dropLines
        )
    }
    return current
}

private fun IntArray.known(): List<Int?> = map { value -> value.takeUnless { it == RECORDER_UNKNOWN } }

private fun ByteArray.known(): List<Int?> = map { value -> value.toInt().takeUnless { it == RECORDER_UNKNOWN } }

internal fun newestWithinBudget(lines: List<String>, maxChars: Int = RECORDER_LINES_MAX_CHARS): List<String> {
    var used = 0
    var first = lines.size
    while (first > 0 && used + lines[first - 1].length <= maxChars) {
        first -= 1
        used += lines[first].length
    }
    return lines.subList(first, lines.size)
}

internal fun wifiGeneration(standard: Int): Int? = when (standard) {
    4 -> 4
    5 -> 5
    6 -> 6
    8 -> 7
    else -> null
}

internal fun wifiBandGhz(frequencyMhz: Int): String? = when (frequencyMhz) {
    in 2400..2499 -> "2.4"
    in 4900..5924 -> "5"
    in 5925..7125 -> "6"
    else -> null
}

internal fun connectionKind(vpn: Boolean, wifi: Boolean, ethernet: Boolean): String = when {
    wifi -> "wifi"
    ethernet -> "ethernet"
    vpn -> "vpn"
    else -> "other"
}

internal fun parseEthernetSpeedMbps(text: String?): Int? =
    text?.trim()?.toIntOrNull()?.takeIf { it in 1..400_000 }

internal fun networkKindForRecorder(capabilities: NetworkCapabilities): String = connectionKind(
    vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
    wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
    ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
)

/** Read once, when the viewer sends a report. Only numbers and kinds: never the network name or any address. */
internal object PlaybackReportFacts {
    private const val ETHERNET_SPEED_FILE = "/sys/class/net/eth0/speed"
    private const val MB = 1024L * 1024L

    fun network(context: Context): PlaybackIssueNetworkDto? = runCatching {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching null
        val capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
            ?: return@runCatching PlaybackIssueNetworkDto(connection = "none", viaVpn = false)
        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val viaVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val wifi = if (isWifi) wifiLink(context) else null
        PlaybackIssueNetworkDto(
            connection = connectionKind(viaVpn, isWifi, isEthernet),
            viaVpn = viaVpn,
            wifiGeneration = wifi?.generation,
            wifiBandGhz = wifi?.bandGhz,
            wifiSignalDbm = wifi?.signalDbm,
            wifiLinkMbps = wifi?.linkMbps,
            ethernetSpeedMbps = if (isEthernet) readEthernetSpeedMbps() else null
        )
    }.getOrNull()

    fun box(context: Context): PlaybackIssueBoxDto? = runCatching {
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo().also { activity?.getMemoryInfo(it) }
        PlaybackIssueBoxDto(
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL.takeUnless { it.isNullOrBlank() || it.equals(Build.UNKNOWN, ignoreCase = true) }
            } else {
                null
            },
            totalMemoryMb = memory.totalMem.takeIf { it > 0L }?.div(MB),
            lowRamDevice = activity?.isLowRamDevice,
            freeStorageMb = runCatching { StatFs(context.filesDir.path).availableBytes / MB }.getOrNull(),
            uptimeSeconds = SystemClock.elapsedRealtime() / 1000L
        )
    }.getOrNull()

    private class WifiLink(val generation: Int?, val bandGhz: String?, val signalDbm: Int?, val linkMbps: Int?)

    @Suppress("DEPRECATION")
    private fun wifiLink(context: Context): WifiLink? {
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val info = manager.connectionInfo ?: return null
        return WifiLink(
            generation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wifiGeneration(info.wifiStandard) else null,
            bandGhz = wifiBandGhz(info.frequency),
            signalDbm = info.rssi.takeIf { it in -126..-1 },
            linkMbps = info.linkSpeed.takeIf { it > 0 }
        )
    }

    private fun readEthernetSpeedMbps(): Int? =
        parseEthernetSpeedMbps(runCatching { File(ETHERNET_SPEED_FILE).readText() }.getOrNull())
}
