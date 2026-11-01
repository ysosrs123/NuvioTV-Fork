package com.nuvio.tv.core.player.thumbnail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Index reads (Cues, moov) of [MIN_SPLIT] or more cut into up to [parts] ranges read at once over [inner], so a 4 MB
 * index comes in about the time of its largest part. Each part takes its own [ConnectionLedger] slot; a part that
 * finds no slot free is read again on its own afterwards. Smaller reads go straight through.
 */
internal class SplitRangeReader(private val inner: RangeReader, private val parts: Int) : RangeReader {
    companion object {
        const val MIN_SPLIT = 1024 * 1024
        const val MIN_PART = 512 * 1024

        /** The ranges ([offset], length) a read of [length] bytes at [offset] is cut into; one when not worth it. */
        fun plan(offset: Long, length: Int, parts: Int): List<Pair<Long, Int>> {
            if (parts <= 1 || length < MIN_SPLIT) return listOf(offset to length)
            val n = minOf(parts, length / MIN_PART).coerceAtLeast(1)
            val step = (length + n - 1) / n
            return (0 until n).mapNotNull { i ->
                val start = i * step
                val len = minOf(step, length - start)
                if (len > 0) (offset + start) to len else null
            }
        }
    }

    override val totalLength: Long get() = inner.totalLength

    override fun read(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        val n = readInto(offset, length, ByteBuffer.wrap(out))
        return if (n == length) out else out.copyOf(n)
    }

    override fun readInto(offset: Long, length: Int, dst: ByteBuffer): Int {
        val n = minOf(length, dst.remaining())
        val plan = plan(offset, n, parts)
        if (plan.size == 1) return inner.readInto(offset, n, dst)
        val results = arrayOfNulls<ByteArray>(plan.size)
        val failures = arrayOfNulls<IOException>(plan.size)
        runBlocking(Dispatchers.IO) {
            plan.mapIndexed { i, (at, len) ->
                async {
                    try {
                        results[i] = inner.read(at, len)
                    } catch (e: IOException) {
                        failures[i] = e
                    }
                }
            }.forEach { it.await() }
        }
        for (i in plan.indices) {
            val f = failures[i] ?: continue
            if (f !is ConnectionLedger.NoSlotException) throw f
            results[i] = inner.read(plan[i].first, plan[i].second)
        }
        var total = 0
        for (i in plan.indices) {
            val b = results[i]!!
            dst.put(b)
            total += b.size
            if (b.size < plan[i].second) break
        }
        return total
    }
}
