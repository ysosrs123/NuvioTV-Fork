package com.nuvio.tv.data.iptv

import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.iptv.*
import com.nuvio.tv.core.iptv.RetainedCaptureFixtures.add
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@UnstableApi
class LocalTsSegmentExtractorTest {
    @get:Rule val temp=TemporaryFolder()
    private fun u(b:ByteArray,i:Int)=b[i].toInt() and 255

    private fun declaredVideoLengths(source:ByteArray):Pair<ByteArray,Int> {
        val b=source.copyOf(); var declared=0
        val starts=mutableListOf<Int>(); val sizes=mutableListOf<Int>()
        for(at in b.indices step 188) {
            val pid=(u(b,at+1) and 31)*256+u(b,at+2)
            val control=(u(b,at+3) shr 4) and 3
            if(pid!=256 || control and 1==0) continue
            val payload=at+4+if(control and 2!=0) 1+u(b,at+4) else 0
            if(u(b,at+1) and 0x40!=0) { starts+=payload; sizes+=0 }
            if(sizes.isNotEmpty()) sizes[sizes.lastIndex]+=at+188-payload
        }
        for(i in starts.indices) {
            val length=sizes[i]-6
            if(length in 1..65535) { b[starts[i]+4]=(length shr 8).toByte(); b[starts[i]+5]=length.toByte(); declared++ }
        }
        assertEquals(0,u(source,starts.last()+4)*256+u(source,starts.last()+5))
        assertTrue(u(b,starts.last()+4)*256+u(b,starts.last()+5)>0)
        return b to declared
    }

    private fun stage(media:ByteArray):CapturedSampleBatch {
        val store=CaptureSegmentStore(temp.newFolder(),420000,210000)
        try {
            store.add(0,media=media)
            val index=CaptureTsInspectionIndex(store); val timeline=CaptureSampleTimeline(index)
            val queue=CaptureSampleBatchQueue(CaptureSampleLoadCursor(store,index,timeline,0,{CaptureTransportState.COMPLETE}),
                CaptureSampleStagingLimits(2L*1024*1024),4L*1024*1024)
            try {
                val result=queue.loadNext(); assertEquals(CaptureSampleLoadState.READY,result.state)
                return result.batch!!.samples
            } finally { queue.close() }
        } finally { store.close() }
    }

    @Test fun declaredLengthVideoPesKeepsTheFinalAccessUnit() {
        val original=RetainedCaptureFixtures.bytes(0)
        val (declared,count)=declaredVideoLengths(original)
        assertTrue(count>=40)
        val a=stage(original); val b=stage(declared)
        assertEquals(50,a.video.samples.size); assertEquals(50,b.video.samples.size)
        assertEquals(a.video.samples.map { it.timeUs },b.video.samples.map { it.timeUs })
        assertEquals(a.video.samples.map { it.flags },b.video.samples.map { it.flags })
        assertEquals(a.video.samples.map { it.size },b.video.samples.map { it.size })
        assertEquals(a.audio.samples.map { it.timeUs },b.audio.samples.map { it.timeUs })
    }
}
