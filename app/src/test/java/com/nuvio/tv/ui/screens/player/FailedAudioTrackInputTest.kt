package com.nuvio.tv.ui.screens.player
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.*
import org.junit.Test
class FailedAudioTrackInputTest {
 private fun failure(mime:String)=AudioSink.InitializationException("test",0,Format.Builder().setSampleMimeType(mime).build(),false,null)
 @Test fun preservesActualPcmSinkInputUnderEncodedSourceFailure() {
  val error=RuntimeException("compressed source failed",failure("audio/raw"))
  assertEquals("audio/raw",failedAudioTrackInputFormat(error)?.sampleMimeType)
 }
 @Test fun preservesActualTranscodedInputRatherThanSourceMime() {
  val error=RuntimeException("TrueHD source",failure("audio/ac3"))
  assertEquals("audio/ac3",failedAudioTrackInputFormat(error)?.sampleMimeType)
 }
 @Test fun unrelatedErrorsCannotBeLearnedAsAudioTrackRejection() { assertNull(failedAudioTrackInputFormat(RuntimeException("AudioTrack init failed"))) }
 @Test fun causeTraversalIsBounded() {
  var error:Throwable=failure("audio/true-hd");repeat(10){error=RuntimeException(error)};assertNull(failedAudioTrackInputFormat(error))
 }
}
