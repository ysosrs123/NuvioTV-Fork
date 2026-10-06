package com.nuvio.tv.core.iptv

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

data class TsCaptureInspection(
    val sha256: String, val initializationSha256: String, val bytes: Long,
    val program: Int, val pmtPid: Int, val videoPid: Int, val audioPid: Int,
    val videoFirstPts90k: Long, val videoLastPts90k: Long, val videoStep90k: Long,
    val videoFrames: Int, val audioFirstPts90k: Long, val audioEndPts90k: Long,
    val audioFrames: Int, val audioSampleRate: Int, val audioChannels: Int,
)

enum class TsCaptureRejection { LIMIT, TRANSPORT, PROGRAM, PES, INITIALIZATION, VIDEO, AUDIO, TIMESTAMP }
class TsCaptureInspectionException(val reason: TsCaptureRejection) : IOException("Capture TS inspection: $reason")

class TsCaptureInspector(private val maxBytes: Long = 8L * 1024 * 1024,
    private val maxPesBytes: Int = 1024 * 1024) {
    init { require(maxBytes in 188..(64L * 1024 * 1024)); require(maxPesBytes in 64..(4 * 1024 * 1024)) }

    fun inspect(input: InputStream, checkCancellation: () -> Unit = {}): TsCaptureInspection =
        Scan(input, checkCancellation).run()

    private inner class Scan(val input: InputStream, val cancellation: () -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        var program = -1; var pmtPid = -1; var videoPid = -1; var audioPid = -1
        var pat: ByteArray? = null; var pmt: ByteArray? = null
        val counters = mutableMapOf<Int, Int>()
        val video = ByteArrayOutputStream(); val audio = ByteArrayOutputStream()
        var sps: ByteArray? = null; var pps: ByteArray? = null
        var spsId = -1; var ppsId = -1
        var videoFirst = -1L; var videoLast = -1L; var videoStep = -1L; var videoFrames = 0
        var audioFirst = -1L; var audioEnd = -1L; var audioFrames = 0
        var audioRate = 0; var audioChannels = 0

        fun run(): TsCaptureInspection {
            val packet = ByteArray(188)
            while (true) {
                cancellation()
                var filled = 0
                while (filled < packet.size) {
                    cancellation()
                    val n = input.read(packet, filled, packet.size - filled)
                    if (n < 0) break
                    need(n > 0, TsCaptureRejection.TRANSPORT)
                    filled += n
                    count += n
                    need(count <= maxBytes, TsCaptureRejection.LIMIT)
                }
                if (filled == 0) break
                need(filled == packet.size, TsCaptureRejection.TRANSPORT)
                digest.update(packet)
                packet(packet)
            }
            need(pat != null && pmt != null, TsCaptureRejection.PROGRAM)
            finish(video, true); finish(audio, false)
            need(videoFrames >= 2 && videoStep > 0 && audioFrames > 0, TsCaptureRejection.TIMESTAMP)

            val audioOffset = signedDelta(audioFirst, videoFirst)
            need(kotlin.math.abs(audioOffset) <= 90_000, TsCaptureRejection.TIMESTAMP)
            val audioSpan = audioEnd - audioFirst
            val alignedAudioFirst = videoFirst + audioOffset
            need(kotlin.math.abs(alignedAudioFirst + audioSpan - (videoLast + videoStep)) <= 90_000,
                TsCaptureRejection.TIMESTAMP)
            val init = MessageDigest.getInstance("SHA-256")
            init.update(requireNotNull(pat)); init.update(requireNotNull(pmt))
            init.update(requireNotNull(sps)); init.update(requireNotNull(pps))
            init.update(byteArrayOf((audioRate shr 16).toByte(), (audioRate shr 8).toByte(), audioRate.toByte(), audioChannels.toByte()))
            return TsCaptureInspection(hex(digest.digest()), hex(init.digest()), count, program, pmtPid,
                videoPid, audioPid, videoFirst, videoLast, videoStep, videoFrames, alignedAudioFirst,
                alignedAudioFirst + audioSpan, audioFrames, audioRate, audioChannels)
        }

        fun packet(b: ByteArray) {
            need(u(b, 0) == 0x47 && u(b, 1) and 0x80 == 0 && u(b, 3) and 0xc0 == 0, TsCaptureRejection.TRANSPORT)
            val pid = (u(b, 1) and 31) * 256 + u(b, 2)
            val start = u(b, 1) and 0x40 != 0
            val control = u(b, 3) shr 4 and 3
            need(control != 0, TsCaptureRejection.TRANSPORT)
            var offset = 4
            if (control and 2 != 0) {
                val length = u(b, offset++)
                need(offset + length <= 188, TsCaptureRejection.TRANSPORT)
                if (length > 0) {

                    need(u(b, offset) and 0x80 == 0, TsCaptureRejection.TRANSPORT)
                    need(u(b, offset) and 0x10 == 0 || length >= 7, TsCaptureRejection.TRANSPORT)
                }
                offset += length
            }
            if (control and 1 == 0) { need(offset == 188, TsCaptureRejection.TRANSPORT); return }
            need(offset < 188, TsCaptureRejection.TRANSPORT)

            if (pid == 17 || pid == 8191) return
            need(pid == 0 || pid == pmtPid || pid == videoPid || pid == audioPid, TsCaptureRejection.PROGRAM)
            val counter = u(b, 3) and 15
            counters.put(pid, counter)?.let { need(counter == (it + 1) % 16, TsCaptureRejection.TRANSPORT) }
            when (pid) {
                0 -> parsePat(section(b, offset, start, 0))
                pmtPid -> parsePmt(section(b, offset, start, 2))
                else -> {
                    need(pmt != null, TsCaptureRejection.PROGRAM)
                    val target = if (pid == videoPid) video else audio
                    if (start) finish(target, pid == videoPid)
                    else need(target.size() > 0, TsCaptureRejection.PES)
                    need(target.size() <= maxPesBytes - (188 - offset), TsCaptureRejection.LIMIT)
                    target.write(b, offset, 188 - offset)
                }
            }
        }

        fun section(b: ByteArray, offset: Int, start: Boolean, table: Int): ByteArray {
            need(start && u(b, offset) == 0, TsCaptureRejection.PROGRAM)
            val at = offset + 1
            need(at + 8 <= b.size && u(b, at) == table && u(b, at + 1) and 0xf0 == 0xb0, TsCaptureRejection.PROGRAM)
            val length = (u(b, at + 1) and 15) * 256 + u(b, at + 2) + 3
            need(length >= 12 && at + length <= b.size, TsCaptureRejection.PROGRAM)
            need(u(b, at + 5) and 0xc1 == 0xc1 && u(b, at + 6) == 0 && u(b, at + 7) == 0, TsCaptureRejection.PROGRAM)
            need((at + length until b.size).all { u(b, it) == 255 }, TsCaptureRejection.PROGRAM)
            val data = b.copyOfRange(at, at + length)
            var crc = -1
            for (byte in data) {
                crc = crc xor ((byte.toInt() and 255) shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04c11db7 else crc shl 1 }
            }
            need(crc == 0, TsCaptureRejection.PROGRAM)
            return data
        }

        fun parsePat(data: ByteArray) {
            need(data.size == 16, TsCaptureRejection.PROGRAM)
            val number = u(data, 8) * 256 + u(data, 9)
            val pid = (u(data, 10) and 31) * 256 + u(data, 11)
            need(number != 0 && pid in 32..8190, TsCaptureRejection.PROGRAM)
            pat?.let { need(it.contentEquals(data), TsCaptureRejection.PROGRAM) }
            pat = data; program = number; pmtPid = pid
        }

        fun parsePmt(data: ByteArray) {
            need(pat != null && data.size >= 26 && u(data, 3) * 256 + u(data, 4) == program, TsCaptureRejection.PROGRAM)
            need((u(data, 10) and 15) * 256 + u(data, 11) == 0, TsCaptureRejection.PROGRAM)
            var at = 12; var v = -1; var a = -1
            while (at < data.size - 4) {
                need(at + 5 <= data.size - 4, TsCaptureRejection.PROGRAM)
                val pid = (u(data, at + 1) and 31) * 256 + u(data, at + 2)
                need(pid in 32..8190 && pid != pmtPid, TsCaptureRejection.PROGRAM)
                need((u(data, at + 3) and 15) * 256 + u(data, at + 4) == 0, TsCaptureRejection.PROGRAM)
                when (u(data, at)) {
                    0x1b -> { need(v == -1, TsCaptureRejection.PROGRAM); v = pid }
                    0x0f -> { need(a == -1, TsCaptureRejection.PROGRAM); a = pid }
                    else -> reject(TsCaptureRejection.PROGRAM)
                }
                at += 5
            }
            need(at == data.size - 4 && v >= 0 && a >= 0 && a != v, TsCaptureRejection.PROGRAM)
            need((u(data, 8) and 31) * 256 + u(data, 9) == v, TsCaptureRejection.PROGRAM)
            pmt?.let { need(it.contentEquals(data), TsCaptureRejection.PROGRAM) }
            pmt = data; videoPid = v; audioPid = a
        }

        fun finish(target: ByteArrayOutputStream, isVideo: Boolean) {
            if (target.size() == 0) return
            val b = target.toByteArray(); target.reset()
            need(b.size >= 14 && u(b, 0) == 0 && u(b, 1) == 0 && u(b, 2) == 1, TsCaptureRejection.PES)
            need(if (isVideo) u(b, 3) in 0xe0..0xef else u(b, 3) in 0xc0..0xdf, TsCaptureRejection.PES)
            val length = u(b, 4) * 256 + u(b, 5)
            need((isVideo && length == 0) || length + 6 == b.size, TsCaptureRejection.PES)
            need(u(b, 6) and 0xf0 == 0x80, TsCaptureRejection.PES)
            val flags = u(b, 7); val header = u(b, 8)
            need((flags == 0x80 && header == 5) || (flags == 0xc0 && header == 10), TsCaptureRejection.PES)
            need(9 + header < b.size, TsCaptureRejection.PES)
            val pts = pts(b, 9, if (flags == 0xc0) 3 else 2)
            if (flags == 0xc0) need(pts(b, 14, 1) == pts, TsCaptureRejection.TIMESTAMP)
            val payload = b.copyOfRange(9 + header, b.size)
            if (isVideo) video(payload, pts) else audio(payload, pts)
        }

        fun video(b: ByteArray, pts: Long) {
            val nals = nals(b)
            need(nals.isNotEmpty(), TsCaptureRejection.VIDEO)
            var slices = 0; var aud = false
            nals.forEach { nal ->
                need(nal.isNotEmpty() && u(nal, 0) and 0x80 == 0, TsCaptureRejection.VIDEO)
                when (val type = u(nal, 0) and 31) {
                    9 -> { need(!aud && slices == 0, TsCaptureRejection.VIDEO); aud = true }
                    7 -> {
                        need(slices == 0 && nal.size >= 5 && u(nal, 1) == 66, TsCaptureRejection.INITIALIZATION)
                        sps?.let { need(it.contentEquals(nal), TsCaptureRejection.INITIALIZATION) }
                        val bits = Bits(rbsp(nal)); bits.read(24); val id = bits.ue()
                        need(id <= 31, TsCaptureRejection.INITIALIZATION)
                        spsId = id; sps = nal
                    }
                    8 -> {
                        need(slices == 0 && sps != null, TsCaptureRejection.INITIALIZATION)
                        pps?.let { need(it.contentEquals(nal), TsCaptureRejection.INITIALIZATION) }
                        val bits = Bits(rbsp(nal)); val id = bits.ue()
                        need(id <= 255 && bits.ue() == spsId && bits.read(1) == 0, TsCaptureRejection.INITIALIZATION)
                        bits.read(1); need(bits.ue() == 0, TsCaptureRejection.INITIALIZATION)
                        ppsId = id; pps = nal
                    }
                    1, 5 -> {
                        need(aud && sps != null && pps != null, TsCaptureRejection.INITIALIZATION)
                        need(++slices == 1 && (videoFrames != 0 || type == 5), TsCaptureRejection.VIDEO)
                        val bits = Bits(rbsp(nal))
                        need(bits.ue() == 0, TsCaptureRejection.VIDEO)
                        val sliceType = bits.ue()
                        need(sliceType in 0..9 && sliceType % 5 in setOf(0, 2), TsCaptureRejection.VIDEO)
                        need(type != 5 || (sliceType % 5 == 2 && u(nal, 0) and 0x60 != 0), TsCaptureRejection.VIDEO)
                        need(bits.ue() == ppsId, TsCaptureRejection.INITIALIZATION)
                    }
                    6 -> need(slices == 0, TsCaptureRejection.VIDEO)
                    else -> reject(TsCaptureRejection.VIDEO)
                }
            }
            need(slices == 1, TsCaptureRejection.VIDEO)
            if (videoFrames == 0) { videoFirst = pts; videoLast = pts }
            else {
                val delta = forwardDelta(pts, videoLast and MASK)
                if (videoStep < 0) videoStep = delta else need(delta == videoStep, TsCaptureRejection.TIMESTAMP)
                videoLast += delta
            }
            videoFrames++
        }

        fun audio(b: ByteArray, pts: Long) {
            if (audioFrames == 0) audioFirst = pts
            else need(kotlin.math.abs(signedDelta(pts, audioEnd and MASK)) <= 1, TsCaptureRejection.TIMESTAMP)
            var at = 0; var frames = 0
            while (at < b.size) {
                need(at + 7 <= b.size && u(b, at) == 255 && u(b, at + 1) == 0xf1, TsCaptureRejection.AUDIO)
                val rateIndex = u(b, at + 2) shr 2 and 15
                need(u(b, at + 2) shr 6 == 1 && rateIndex in 3..4, TsCaptureRejection.AUDIO)
                val rate = if (rateIndex == 3) 48000 else 44100
                val channels = (u(b, at + 2) and 1) * 4 + (u(b, at + 3) shr 6)
                need(channels in 1..2 && u(b, at + 6) and 3 == 0, TsCaptureRejection.AUDIO)
                if (audioRate == 0) { audioRate = rate; audioChannels = channels }
                need(rate == audioRate && channels == audioChannels, TsCaptureRejection.INITIALIZATION)
                val length = (u(b, at + 3) and 3) * 2048 + u(b, at + 4) * 8 + (u(b, at + 5) shr 5)
                need(length > 7 && at + length <= b.size, TsCaptureRejection.AUDIO)
                at += length; frames++
            }
            need(frames > 0, TsCaptureRejection.AUDIO)
            audioFrames += frames
            audioEnd = audioFirst + audioFrames * 1024L * 90_000 / audioRate
        }
    }

    private companion object {
        const val MASK = (1L shl 33) - 1
        fun u(b: ByteArray, i: Int) = b[i].toInt() and 255
        fun reject(reason: TsCaptureRejection): Nothing = throw TsCaptureInspectionException(reason)
        fun need(ok: Boolean, reason: TsCaptureRejection) { if (!ok) reject(reason) }
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }
        fun signedDelta(a: Long, b: Long): Long = ((a - b + (1L shl 32)) and MASK) - (1L shl 32)
        fun forwardDelta(a: Long, b: Long): Long = ((a - b) and MASK).also {
            need(it in 1..90_000, TsCaptureRejection.TIMESTAMP)
        }
        fun pts(b: ByteArray, at: Int, prefix: Int): Long {
            need(at + 5 <= b.size && u(b, at) shr 4 == prefix &&
                u(b, at) and 1 == 1 && u(b, at + 2) and 1 == 1 && u(b, at + 4) and 1 == 1, TsCaptureRejection.TIMESTAMP)
            return ((u(b, at).toLong() and 14) shl 29) or (u(b, at + 1).toLong() shl 22) or
                ((u(b, at + 2).toLong() and 254) shl 14) or (u(b, at + 3).toLong() shl 7) or (u(b, at + 4).toLong() shr 1)
        }
        fun nals(b: ByteArray): List<ByteArray> {
            val starts = mutableListOf<Pair<Int, Int>>()
            var i = 0
            while (i + 2 < b.size) {
                val length = if (b[i] == 0.toByte() && b[i + 1] == 0.toByte()) {
                    if (b[i + 2] == 1.toByte()) 3
                    else if (i + 3 < b.size && b[i + 2] == 0.toByte() && b[i + 3] == 1.toByte()) 4 else 0
                } else 0
                if (length > 0) { starts += i to length; need(starts.size <= 32, TsCaptureRejection.LIMIT); i += length } else i++
            }
            need(starts.firstOrNull()?.first == 0, TsCaptureRejection.VIDEO)
            return starts.mapIndexed { index, (at, length) ->
                var end = starts.getOrNull(index + 1)?.first ?: b.size
                while (end > at + length && b[end - 1] == 0.toByte()) end--
                need(end > at + length, TsCaptureRejection.VIDEO)
                b.copyOfRange(at + length, end)
            }
        }
        fun rbsp(nal: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(); var zeros = 0
            for (i in 1 until nal.size) {
                val v = u(nal, i)
                if (zeros >= 2 && v == 3) {
                    need(i + 1 < nal.size && u(nal, i + 1) <= 3, TsCaptureRejection.VIDEO)
                    zeros = 0; continue
                }
                out.write(v); zeros = if (v == 0) zeros + 1 else 0
            }
            return out.toByteArray()
        }
    }

    private class Bits(val bytes: ByteArray) {
        var at = 0
        fun read(n: Int): Int {
            need(n in 0..24 && at + n <= bytes.size * 8, TsCaptureRejection.VIDEO)
            var result = 0
            repeat(n) { result = result * 2 + (u(bytes, at / 8) shr (7 - at % 8) and 1); at++ }
            return result
        }
        fun ue(): Int {
            var zeros = 0
            while (read(1) == 0) { zeros++; need(zeros <= 20, TsCaptureRejection.VIDEO) }
            return (1 shl zeros) - 1 + read(zeros)
        }
    }
}
