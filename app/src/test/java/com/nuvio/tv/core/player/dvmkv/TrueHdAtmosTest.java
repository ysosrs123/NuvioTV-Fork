package com.nuvio.tv.core.player.dvmkv;

import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrueHdAtmosTest {
  private static final byte[] ATMOS = hex("f8726fba0017804fb7521000000087ef43fc00004aefe307e3011fc6fc0053da");
  private static final byte[] PLAIN = hex("f8726fba0017804fb75200000000879c307c00003f1fe307e30036c8");

  @Test public void identifiesSpatialPresentationFromRealHeader() {
    assertEquals(1, TrueHdAtmos.probe(ATMOS, 0, ATMOS.length));
    assertEquals(0, TrueHdAtmos.probe(PLAIN, 0, PLAIN.length));
  }

  @Test public void refusesTruncatedCorruptAndMlpHeaders() {
    for (int limit = 0; limit < ATMOS.length; limit++) {
      assertEquals(-1, TrueHdAtmos.probe(ATMOS, 0, limit));
    }
    byte[] corrupt = ATMOS.clone();
    corrupt[17] ^= 1;
    assertEquals(-1, TrueHdAtmos.probe(corrupt, 0, corrupt.length));
    corrupt = ATMOS.clone();
    corrupt[3] = (byte) 0xbb;
    assertEquals(-1, TrueHdAtmos.probe(corrupt, 0, corrupt.length));
    assertEquals(-1, TrueHdAtmos.probe(ATMOS, -1, ATMOS.length));
    assertEquals(-1, TrueHdAtmos.probe(ATMOS, 0, ATMOS.length + 1));
  }

  @Test public void scansPastMinorFramesAndRespectsSlice() {
    byte[] packet = new byte[ATMOS.length + 50];
    System.arraycopy(ATMOS, 0, packet, 50, ATMOS.length);
    assertEquals(1, TrueHdAtmos.probe(packet, 4, packet.length));
    assertEquals(-1, TrueHdAtmos.probe(packet, 51, packet.length));
    assertEquals(-1, TrueHdAtmos.probe(packet, 0, 50));
  }

  @Test public void addsEvidencePreservingMimeChannelsAndExistingMetadata() {
    Metadata metadata = new Metadata(new ThumbnailMetadata(42));
    Format source = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
        .setChannelCount(8).setSampleRate(48000).setMetadata(metadata).build();
    Format refined = TrueHdAtmos.refineFormat(source, ATMOS, 0, ATMOS.length);
    assertTrue(TrueHdAtmos.isPresent(refined));
    assertFalse(TrueHdAtmos.isPresent(source));
    assertEquals(MimeTypes.AUDIO_TRUEHD, refined.sampleMimeType);
    assertEquals(8, refined.channelCount);
    assertEquals(metadata.get(0), refined.metadata.get(0));
    assertSame(refined, TrueHdAtmos.refineFormat(refined, ATMOS, 0, ATMOS.length));
    assertSame(source, TrueHdAtmos.refineFormat(source, PLAIN, 0, PLAIN.length));
    assertFalse(TrueHdAtmos.isPresent(refined.buildUpon().setSampleMimeType(MimeTypes.AUDIO_E_AC3).build()));
  }

  private static byte[] hex(String value) {
    byte[] bytes = new byte[value.length() / 2];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
    }
    return bytes;
  }
}
