package com.nuvio.tv.core.player.dvmkv;

import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;

/** Bitstream-confirmed TrueHD spatial presentation; does not change decoder MIME or channels. */
@UnstableApi
public final class TrueHdAtmos implements Metadata.Entry {
  public static final TrueHdAtmos INSTANCE = new TrueHdAtmos();

  private TrueHdAtmos() {}

  public static boolean isPresent(Format format) {
    if (format == null || !MimeTypes.AUDIO_TRUEHD.equals(format.sampleMimeType)
        || format.metadata == null) {
      return false;
    }
    for (int i = 0; i < format.metadata.length(); i++) {
      if (format.metadata.get(i) instanceof TrueHdAtmos) {
        return true;
      }
    }
    return false;
  }

  // -1: no complete, valid major sync; 0: plain TrueHD; 1: Atmos.
  static int probe(byte[] data, int offset, int limit) {
    if (offset < 0 || limit > data.length || offset > limit) {
      return -1;
    }
    for (int start = offset; start <= limit - 28; start++) {
      if ((data[start] & 0xff) != 0xf8 || data[start + 1] != 0x72
          || data[start + 2] != 0x6f || (data[start + 3] & 0xff) != 0xba) {
        continue;
      }
      int size = 28;
      if ((data[start + 25] & 1) != 0) {
        size += 2 + ((data[start + 26] & 0xf0) >> 4) * 2;
      }
      if (size > limit - start || !validChecksum(data, start, size)) {
        continue;
      }
      // FFmpeg mlpdec: four substreams and a 16-channel spatial presentation.
      return (data[start + 16] & 0xf0) == 0x40 && (data[start + 17] & 0x80) != 0
          ? 1 : 0;
    }
    return -1;
  }

  private static boolean validChecksum(byte[] data, int start, int size) {
    // MLP major-sync CRC-16, polynomial 0x002d, followed by two XOR bytes.
    int crc = 0;
    for (int i = start; i < start + size - 4; i++) {
      crc ^= (data[i] & 0xff) << 8;
      for (int bit = 0; bit < 8; bit++) {
        crc = ((crc << 1) ^ ((crc & 0x8000) != 0 ? 0x002d : 0)) & 0xffff;
      }
    }
    crc = ((crc >>> 8) | (crc << 8)) & 0xffff;
    crc ^= littleEndian16(data, start + size - 4);
    return crc == littleEndian16(data, start + size - 2);
  }

  private static int littleEndian16(byte[] data, int offset) {
    return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
  }

  static Format refineFormat(Format format, byte[] data, int offset, int limit) {
    if (!MimeTypes.AUDIO_TRUEHD.equals(format.sampleMimeType) || isPresent(format)
        || probe(data, offset, limit) != 1) {
      return format;
    }
    Metadata metadata = format.metadata == null ? new Metadata(INSTANCE)
        : format.metadata.copyWithAppendedEntries(INSTANCE);
    return format.buildUpon().setMetadata(metadata).build();
  }

  @Override public boolean equals(Object other) { return other instanceof TrueHdAtmos; }
  @Override public int hashCode() { return 0x54484441; }
  @Override public String toString() { return "TrueHD Atmos"; }
}
