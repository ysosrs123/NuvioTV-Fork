package com.nuvio.tv.core.player.dvmkv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import org.junit.Test;

public class DtsStreamTypeTest {
  private static final int XLL = 0x41A29547;
  private static final int XBR = 0x655E315E;
  private static final int LBR = 0x0A801921;
  private static final int X = 0x02000850;
  private static final int X_IMAX = 0xF14000D0;

  @Test public void readsTheTypeFromTheFirstComponent() {
    assertEquals(DtsStreamType.MASTER_AUDIO, read(unit(XLL, 400, -1, 0)));
    assertEquals(DtsStreamType.HIGH_RESOLUTION, read(unit(XBR, 400, -1, 0)));
    assertEquals(DtsStreamType.EXPRESS, read(unit(LBR, 400, -1, 0)));
  }

  @Test public void findsTheDtsXMarkerWhereverTheAudioEnds() {
    assertEquals(DtsStreamType.DTS_X, read(unit(XLL, 400, 84, X)));
    assertEquals(DtsStreamType.DTS_X, read(unit(XLL, 8000, 7256, X)));
    assertEquals(DtsStreamType.DTS_X_IMAX, read(unit(XLL, 400, 84, X_IMAX)));
    assertEquals(DtsStreamType.DTS_X_IMAX, read(unit(XLL, 400, 84, X_IMAX | 1)));
  }

  @Test public void aMarkerOffTheDwordGridIsNotDtsX() {
    assertEquals(DtsStreamType.MASTER_AUDIO, read(unit(XLL, 400, 86, X)));
  }

  @Test public void theDtsXMarkerMeansNothingOutsideLossless() {
    assertEquals(DtsStreamType.HIGH_RESOLUTION, read(unit(XBR, 400, 84, X)));
  }

  @Test public void readsPastACoreFrame() {
    byte[] unit = unit(XLL, 400, 84, X);
    byte[] withCore = new byte[2013 + unit.length];
    withCore[0] = 0x7F;
    withCore[1] = (byte) 0xFE;
    withCore[2] = (byte) 0x80;
    withCore[3] = 0x01;
    System.arraycopy(unit, 0, withCore, 2013, unit.length);
    assertEquals(DtsStreamType.DTS_X, read(withCore));
  }

  @Test public void saysNothingWhenTheFrameDoesNotSay() {
    assertNull(read(new byte[64]));
    assertNull(read(unit(0x12345678, 400, -1, 0)));
    assertNull(read(java.util.Arrays.copyOf(unit(XLL, 400, -1, 0), 12)));
  }

  @Test public void wideSizeFieldsMoveTheComponent() {
    byte[] unit = new byte[800];
    put(unit, 0, 0x64582025);
    int bits = (1 << 21) | ((300 - 1) << 9);
    unit[5] = (byte) (bits >> 16);
    unit[6] = (byte) (bits >> 8);
    unit[7] = (byte) bits;
    put(unit, 300, XLL);
    assertEquals(DtsStreamType.MASTER_AUDIO, read(unit));
  }

  @Test public void refineKeepsTheMimeAndOtherMetadataRules() {
    Format hd = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS_HD).build();
    byte[] lossless = unit(XLL, 400, 84, X);
    Format refined = DtsStreamType.refineFormat(hd, lossless, 0, lossless.length);
    assertEquals(MimeTypes.AUDIO_DTS_HD, refined.sampleMimeType);
    assertEquals(DtsStreamType.DTS_X, DtsStreamType.of(refined));
    assertSame(refined, DtsStreamType.refineFormat(refined, lossless, 0, lossless.length));

    byte[] unclear = new byte[64];
    assertSame(hd, DtsStreamType.refineFormat(hd, unclear, 0, unclear.length));

    Format core = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS).build();
    assertSame(core, DtsStreamType.refineFormat(core, lossless, 0, lossless.length));
    assertNull(DtsStreamType.of(core));
    assertNull(DtsStreamType.of(null));
  }

  private static DtsStreamType read(byte[] data) {
    return DtsStreamType.read(data, 0, data.length);
  }

  /** An extension substream with a 32-byte header, then a component; the marker is relative to it. */
  private static byte[] unit(int component, int componentSize, int markerAt, int marker) {
    byte[] unit = new byte[32 + componentSize];
    put(unit, 0, 0x64582025);
    int bits = (32 - 1) << 13;
    unit[5] = (byte) (bits >> 16);
    unit[6] = (byte) (bits >> 8);
    unit[7] = (byte) bits;
    put(unit, 32, component);
    if (markerAt >= 0) {
      put(unit, 32 + markerAt, marker);
    }
    return unit;
  }

  private static void put(byte[] data, int at, int value) {
    data[at] = (byte) (value >>> 24);
    data[at + 1] = (byte) (value >>> 16);
    data[at + 2] = (byte) (value >>> 8);
    data[at + 3] = (byte) value;
  }
}
