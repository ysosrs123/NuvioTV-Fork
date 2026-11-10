package com.nuvio.tv.core.player.dvmkv;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;

/** DTS-HD type confirmed from the frames; display and badges only, the MIME type stays DTS-HD. */
@UnstableApi
public enum DtsStreamType implements Metadata.Entry {
  MASTER_AUDIO("DTS-HD MA"),
  DTS_X("DTS:X"),
  DTS_X_IMAX("DTS:X IMAX"),
  HIGH_RESOLUTION("DTS-HD HRA"),
  EXPRESS("DTS Express");

  private static final int EXSS_SYNC = 0x64582025;
  private static final int XLL_SYNC = 0x41A29547;
  private static final int XBR_SYNC = 0x655E315E;
  private static final int LBR_SYNC = 0x0A801921;
  private static final int X_SYNC = 0x02000850;
  private static final int X_IMAX_SYNC = 0xF14000D0;
  private static final int EXSS_MIN_SIZE = 8;

  public final String label;

  DtsStreamType(String label) {
    this.label = label;
  }

  public boolean isLossless() {
    return this == MASTER_AUDIO || this == DTS_X || this == DTS_X_IMAX;
  }

  public boolean isDtsX() {
    return this == DTS_X || this == DTS_X_IMAX;
  }

  @Nullable
  public static DtsStreamType of(@Nullable Format format) {
    if (format == null || !MimeTypes.AUDIO_DTS_HD.equals(format.sampleMimeType)
        || format.metadata == null) {
      return null;
    }
    for (int i = 0; i < format.metadata.length(); i++) {
      if (format.metadata.get(i) instanceof DtsStreamType) {
        return (DtsStreamType) format.metadata.get(i);
      }
    }
    return null;
  }

  /** Null when the frame does not say; some real frames do not, so callers may read another. */
  @Nullable
  static DtsStreamType read(byte[] data, int offset, int limit) {
    if (offset < 0 || limit > data.length) {
      return null;
    }
    DtsStreamType found = null;
    for (int i = offset; i + EXSS_MIN_SIZE <= limit; i++) {
      if (data[i] != 0x64 || word(data, i) != EXSS_SYNC) {
        continue;
      }
      int component = i + exssHeaderSize(data, i);
      if (component + 4 > limit) {
        continue;
      }
      switch (word(data, component)) {
        case XLL_SYNC:
          return readLossless(data, component, limit);
        case XBR_SYNC:
          found = HIGH_RESOLUTION;
          break;
        case LBR_SYNC:
          found = EXPRESS;
          break;
        default:
          break;
      }
    }
    return found;
  }

  // The DTS:X marker follows the audio data on a dword boundary of the lossless frame, so its
  // place moves with the sound.
  private static DtsStreamType readLossless(byte[] data, int xll, int limit) {
    for (int i = xll + 4; i + 4 <= limit; i += 4) {
      int value = word(data, i);
      if (value == X_SYNC) {
        return DTS_X;
      }
      if ((value >>> 1) == (X_IMAX_SYNC >>> 1)) {
        return DTS_X_IMAX;
      }
    }
    return MASTER_AUDIO;
  }

  private static int exssHeaderSize(byte[] data, int exss) {
    int bits = (data[exss + 5] & 0xff) << 16 | (data[exss + 6] & 0xff) << 8 | (data[exss + 7] & 0xff);
    boolean wideSizeFields = (bits & (1 << 21)) != 0;
    return (wideSizeFields ? (bits >> 9) & 0xfff : (bits >> 13) & 0xff) + 1;
  }

  private static int word(byte[] data, int i) {
    return (data[i] & 0xff) << 24 | (data[i + 1] & 0xff) << 16
        | (data[i + 2] & 0xff) << 8 | (data[i + 3] & 0xff);
  }

  /** Returns the format with the type added, or the same format when the frame does not say. */
  static Format refineFormat(Format format, byte[] data, int offset, int limit) {
    if (!MimeTypes.AUDIO_DTS_HD.equals(format.sampleMimeType) || of(format) != null) {
      return format;
    }
    DtsStreamType type = read(data, offset, limit);
    if (type == null) {
      return format;
    }
    Metadata metadata = format.metadata == null ? new Metadata(type)
        : format.metadata.copyWithAppendedEntries(type);
    return format.buildUpon().setMetadata(metadata).build();
  }
}
