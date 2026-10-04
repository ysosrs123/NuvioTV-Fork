package com.nuvio.tv.ui.screens.player
import androidx.media3.common.C
import com.nuvio.tv.core.player.AudioPassthroughPolicy
import org.junit.Assert.*
import org.junit.Test
class PlaybackRecoveryGateTest {
 @Test fun reconstructionPreservesBoundedAttempts() {
  val g=PlaybackRecoveryGate();g.begin("one", emptyMap());assertEquals(400L,g.nextAudioDelayMs())
  val first=g.generation;assertFalse(g.begin("one",emptyMap()));assertFalse(g.isCurrent(first,"one",emptyMap()))
  assertEquals(800L,g.nextAudioDelayMs());g.begin("one",emptyMap());assertNull(g.nextAudioDelayMs())
 }
 @Test fun newTitleAndHeadersResetBudgetAndInvalidateOldWork() {
  val g=PlaybackRecoveryGate();g.begin("one",mapOf("key" to "a"));g.nextAudioDelayMs();g.nextAudioDelayMs();val generation=g.generation
  assertTrue(g.begin("one",mapOf("key" to "b")));assertFalse(g.isCurrent(generation,"one",mapOf("key" to "a")));assertEquals(400L,g.nextAudioDelayMs())
  assertTrue(g.begin("two",emptyMap()));assertEquals(400L,g.nextAudioDelayMs())
 }
 @Test fun cancellationInvalidatesRecoveryWithoutRestoringRetryAllowance() {
  val g=PlaybackRecoveryGate();g.begin("one",emptyMap());g.nextAudioDelayMs();val generation=g.generation;g.cancel()
  assertFalse(g.isCurrent(generation,"one",emptyMap()));g.begin("one",emptyMap());assertEquals(800L,g.nextAudioDelayMs())
 }
 @Test fun mutableHeadersCannotChangeCapturedIdentity() {
  val headers=mutableMapOf("key" to "a");val g=PlaybackRecoveryGate();g.begin("one",headers);val generation=g.generation;headers["key"]="b"
  assertTrue(g.isCurrent(generation,"one",mapOf("key" to "a")));assertFalse(g.isCurrent(generation,"one",headers))
 }
 @Test fun explicitSessionEndAllowsFreshPlaybackOfSameUrl() {
  val g=PlaybackRecoveryGate();g.begin("one",emptyMap());g.nextAudioDelayMs();g.nextAudioDelayMs();val generation=g.generation
  g.cancel(resetIdentity=true);assertFalse(g.isCurrent(generation,"one",emptyMap()))
  assertTrue(g.begin("one",emptyMap()));assertEquals(400L,g.nextAudioDelayMs())
 }
 @Test fun formatThatOpenedEarlierInThePlaybackIsNotLearnedAsRejected() {
  val g=PlaybackRecoveryGate();g.begin("one",emptyMap())
  assertTrue(g.shouldLearnAudioRejection(AudioPassthroughPolicy.Group.DTS))
  g.noteAudioOutputOpened(passthroughGroupOfEncoding(C.ENCODING_DTS))
  assertFalse(g.shouldLearnAudioRejection(AudioPassthroughPolicy.Group.DTS))
  assertTrue(g.shouldLearnAudioRejection(AudioPassthroughPolicy.Group.DTS_HD))
  g.begin("one",emptyMap())
  assertFalse(g.shouldLearnAudioRejection(AudioPassthroughPolicy.Group.DTS))
 }
 @Test fun openedFormatsAreForgottenForANewLinkOrAFullRelease() {
  val g=PlaybackRecoveryGate();g.begin("one",emptyMap());g.noteAudioOutputOpened(AudioPassthroughPolicy.Group.TRUEHD)
  g.begin("two",emptyMap());assertTrue(g.shouldLearnAudioRejection(AudioPassthroughPolicy.Group.TRUEHD))
  g.noteAudioOutputOpened(AudioPassthroughPolicy.Group.TRUEHD);g.cancel(resetIdentity=true);g.begin("two",emptyMap())
  assertTrue(g.shouldLearnAudioRejection(AudioPassthroughPolicy.Group.TRUEHD))
 }
 @Test fun aMinuteOfCleanPlaybackRestoresTheAudioRetries() {
  val g=PlaybackRecoveryGate();g.begin("one",emptyMap());g.nextAudioDelayMs();g.nextAudioDelayMs();assertFalse(g.canRetryAudio())
  assertFalse(g.noteStablePlayback(playingSinceMs=1_000L,nowMs=60_999L));assertFalse(g.canRetryAudio())
  assertTrue(g.noteStablePlayback(playingSinceMs=1_000L,nowMs=61_000L));assertEquals(400L,g.nextAudioDelayMs())
 }
 @Test fun anAudioSinkErrorRestartsTheCleanPlaybackStretch() {
  val g=PlaybackRecoveryGate();g.begin("one",emptyMap());g.nextAudioDelayMs();g.nextAudioDelayMs();g.noteAudioSinkError(30_000L)
  assertFalse(g.noteStablePlayback(playingSinceMs=0L,nowMs=61_000L));assertNull(g.nextAudioDelayMs())
  assertTrue(g.noteStablePlayback(playingSinceMs=0L,nowMs=90_000L));assertEquals(400L,g.nextAudioDelayMs())
 }
 @Test fun stablePlaybackWithNothingSpentReportsNoReset() {
  val g=PlaybackRecoveryGate();g.begin("one",emptyMap())
  assertFalse(g.noteStablePlayback(playingSinceMs=0L,nowMs=120_000L));assertEquals(400L,g.nextAudioDelayMs())
 }
 @Test fun pcmAndUnknownOutputsNeverCountAsAnOpenedBitstream() {
  assertNull(passthroughGroupOfEncoding(C.ENCODING_PCM_16BIT));assertNull(passthroughGroupOfEncoding(C.ENCODING_PCM_FLOAT))
  assertEquals(AudioPassthroughPolicy.Group.EAC3,passthroughGroupOfEncoding(C.ENCODING_E_AC3_JOC))
  assertEquals(AudioPassthroughPolicy.Group.DTS_HD,passthroughGroupOfEncoding(C.ENCODING_DTS_HD))
  val g=PlaybackRecoveryGate();g.begin("one",emptyMap());g.noteAudioOutputOpened(null)
  assertFalse(g.shouldLearnAudioRejection(null));assertTrue(g.shouldLearnAudioRejection(AudioPassthroughPolicy.Group.AC3))
 }
}
