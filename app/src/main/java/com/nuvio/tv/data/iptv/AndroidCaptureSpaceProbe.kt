package com.nuvio.tv.data.iptv

import android.system.Os
import com.nuvio.tv.core.iptv.CaptureSpaceProbe
import com.nuvio.tv.core.iptv.CaptureSpaceReading
import java.io.File

internal object AndroidCaptureSpaceProbe : CaptureSpaceProbe {
    override fun read(directory: File): CaptureSpaceReading {
        val before = Os.stat(directory.absolutePath).st_dev
        val stats = Os.statvfs(directory.absolutePath)
        val after = Os.stat(directory.absolutePath).st_dev
        check(before == after)
        val unit = stats.f_frsize.takeIf { it > 0 } ?: stats.f_bsize
        require(unit > 0 && stats.f_bavail >= 0)
        return CaptureSpaceReading(Math.multiplyExact(stats.f_bavail, unit), unit, before.toString())
    }
}
