package com.nuvio.tv.core.player.dvmkv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MatroskaDvProfileRuleTest {

  @Test
  public void av1KeepsDolbyVisionOnlyForProfileTen() {
    assertTrue(MatroskaExtractor.Track.dolbyVisionProfileMatchesCodec("V_AV1", 10));
    assertFalse(MatroskaExtractor.Track.dolbyVisionProfileMatchesCodec("V_AV1", 8));
    assertFalse(MatroskaExtractor.Track.dolbyVisionProfileMatchesCodec("V_AV1", 5));
  }

  @Test
  public void h264KeepsDolbyVisionOnlyForProfileNine() {
    assertTrue(MatroskaExtractor.Track.dolbyVisionProfileMatchesCodec("V_MPEG4/ISO/AVC", 9));
    assertFalse(MatroskaExtractor.Track.dolbyVisionProfileMatchesCodec("V_MPEG4/ISO/AVC", 8));
  }

  @Test
  public void hevcKeepsEveryProfile() {
    for (int profile : new int[] {4, 5, 7, 8}) {
      assertTrue(MatroskaExtractor.Track.dolbyVisionProfileMatchesCodec("V_MPEGH/ISO/HEVC", profile));
    }
  }
}
