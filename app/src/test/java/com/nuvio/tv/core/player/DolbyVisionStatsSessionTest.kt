package com.nuvio.tv.core.player
import org.junit.Assert.*
import org.junit.Test
class DolbyVisionStatsSessionTest {
 private fun dm(value:Int)=DoviBridge.RpuDmInfo(29,2,0,value,100,0,0,0,0,7,2,10,10,null,0,0,0)
 @Test fun previousLoaderCannotPolluteNewPlayback() {
  DolbyVisionConversionStats.reset();val old=DolbyVisionConversionStats.session;old.recordElType(2)
  DolbyVisionConversionStats.reset();val current=DolbyVisionConversionStats.session
  old.recordElType(1);old.recordLiveDm(0,100,dm(100));assertNull(current.getLastElType());assertNull(current.liveDmAt(100))
 }
 @Test fun liveMetadataUsesPresentationTimeAndExpires() {
  val s=DolbyVisionStatsSession();val a=dm(100);val b=dm(200);s.recordLiveDm(0,1000000,a);s.recordLiveDm(0,4000000,b)
  assertNull(s.liveDmAt(999999));assertEquals(a,s.liveDmAt(1500000));assertNull(s.liveDmAt(3500000));assertEquals(b,s.liveDmAt(4000000))
 }
 @Test fun writerThrottlesAreIndependentAndBackwardSeekResamples() {
  val s=DolbyVisionStatsSession();s.recordLiveDm(0,10000000,dm(100))
  assertFalse(s.shouldSampleLiveDm(0,10001000));assertTrue(s.shouldSampleLiveDm(1,10001000));assertTrue(s.shouldSampleLiveDm(0,1000000))
 }
 @Test fun metadataHasStrictCapAndResetClearsIt() {
  val s=DolbyVisionStatsSession();repeat(5000){s.recordLiveDm(it%2,it.toLong(),dm(it))}
  assertNull(s.liveDmAt(0));assertNotNull(s.liveDmAt(4999));s.reset();assertNull(s.getFirstDm());assertNull(s.liveDmAt(4999))
 }
 @Test fun concurrentWritersRemainBoundedAndDoNotThrow() {
  val s=DolbyVisionStatsSession();val errors=java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
  val threads=(0..1).map { writer -> Thread { try { repeat(5000){s.recordLiveDm(writer,(it*2+writer).toLong(),dm(it));s.liveDmAt(it.toLong())} } catch(t:Throwable){errors.add(t)} }.apply{start()} }
  threads.forEach{it.join()};assertTrue(errors.toString(),errors.isEmpty());assertNotNull(s.liveDmAt(9999))
 }
 @Test fun nativeExemptionIsSpecificToProfile7() {
  val native=DolbyVisionConversionConfig(active=true,nativeProfile7=true,dv5Enabled=true,manualDv81=true)
  assertFalse(native.shouldConvert(7));assertTrue(native.shouldConvert(5));assertFalse(native.shouldConvert(8))
  assertTrue(native.copy(nativeProfile7=false).shouldConvert(7));assertFalse(native.copy(manualDv81=false).shouldConvert(5))
 }
 @Test fun backwardSeekCannotBeStarvedByFullFutureCache() {
  val s=DolbyVisionStatsSession();repeat(5000){s.recordLiveDm(0,100000000L+it*500000L,dm(it))}
  val sought=dm(42);s.recordLiveDm(0,0,sought)
  assertEquals(sought,s.liveDmAt(0));assertNull(s.liveDmAt(2500000000L))
 }
}
