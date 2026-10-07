package com.nuvio.tv.data.iptv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.nuvio.tv.core.iptv.LocalTimeshiftPackets
import com.nuvio.tv.core.iptv.LocalTimeshiftSizing
import java.io.IOException

class IptvLocalTimeshiftDataSource(private val session: () -> IptvLocalTimeshiftSession?) : BaseDataSource(false) {
    private var current: IptvLocalTimeshiftSession? = null
    private var uri: Uri? = null
    private var position = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val (id, anchor) = IptvLocalTimeshiftSession.parse(dataSpec.uri.toString()) ?: throw IOException("Unknown local timeshift address")
        val owner = session()?.takeIf { it.id == id } ?: throw IptvLocalTimeshiftClosedException(null)
        transferInitializing(dataSpec)
        current = owner; uri = dataSpec.uri
        position = if (dataSpec.position == 0L) synced(owner, anchor) else anchor + dataSpec.position
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    private fun synced(owner: IptvLocalTimeshiftSession, anchor: Long): Long {
        val probe = ByteArray(LocalTimeshiftSizing.PACKET * 2 + 1)
        var filled = 0
        while (filled < probe.size) filled += owner.read(anchor + filled, probe, filled, probe.size - filled)
        val at = LocalTimeshiftPackets.sync(probe, 0, filled)
        return if (at <= 0) anchor else anchor + at
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val owner = current ?: throw IOException("Local timeshift source is not open")
        val count = owner.read(position, buffer, offset, length)
        position += count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null; current = null
        if (opened) { opened = false; transferEnded() }
    }
}

class IptvLocalTimeshiftRouter(private val direct: DataSource, session: () -> IptvLocalTimeshiftSession?) : DataSource {
    private val local = IptvLocalTimeshiftDataSource(session)
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        direct.addTransferListener(transferListener); local.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = if (dataSpec.uri.scheme == IptvLocalTimeshiftSession.SCHEME) local else direct
        active = target
        return target.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (active ?: throw IOException("Source is not open")).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        val closing = active
        active = null
        closing?.close()
    }
}
