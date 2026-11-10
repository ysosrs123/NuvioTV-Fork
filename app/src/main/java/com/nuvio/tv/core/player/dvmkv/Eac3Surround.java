package com.nuvio.tv.core.player.dvmkv;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.extractor.Ac3Util;
import java.util.Arrays;

/** Identifies Dolby Atmos in Matroska E-AC-3 from the frame headers of the first sample. */
final class Eac3Surround {
  private Eac3Surround() {}

  static Format refineFormat(Format format, byte[] data, int offset, int limit) {
    if (offset < 0 || limit > data.length || limit - offset < 6
        || !hasSyncword(data, offset)) {
      return format;
    }
    int frameSize = Ac3Util.parseAc3SyncframeSize(
        Arrays.copyOfRange(data, offset, offset + 6));
    if (frameSize < 6 || frameSize > limit - offset) {
      return format;
    }
    try {
      Ac3Util.SyncFrameInfo frame = parseFrame(data, offset, offset + frameSize);
      boolean atmos = MimeTypes.AUDIO_E_AC3_JOC.equals(frame.mimeType)
          && frame.streamType != Ac3Util.SyncFrameInfo.STREAM_TYPE_TYPE1;
      int next = offset + frameSize;
      if (!atmos && limit - next >= 6 && isDependentFrame(data, next)) {
        int nextSize = Ac3Util.parseAc3SyncframeSize(Arrays.copyOfRange(data, next, next + 6));
        atmos = nextSize >= 6 && nextSize <= limit - next
            && MimeTypes.AUDIO_E_AC3_JOC.equals(parseFrame(data, next, next + nextSize).mimeType);
      }
      if (!atmos) {
        return format;
      }
      return format.buildUpon()
          .setSampleMimeType(MimeTypes.AUDIO_E_AC3_JOC)
          .setChannelCount(has71Extension(data, offset, limit) ? 8 : frame.channelCount)
          .build();
    } catch (IllegalStateException | IllegalArgumentException | IndexOutOfBoundsException e) {
      return format;
    }
  }

  static boolean has71Extension(byte[] data, int offset, int limit) {
    if (limit - offset < 6 || !hasSyncword(data, offset)) {
      return false;
    }
    int baseSize = Ac3Util.parseAc3SyncframeSize(Arrays.copyOfRange(data, offset, offset + 6));
    if (baseSize < 6 || baseSize > limit - offset - 10) {
      return false;
    }
    int extension = offset + baseSize;
    int bitstreamId = (data[extension + 5] & 0xff) >> 3;
    if (!isDependentFrame(data, extension) || bitstreamId < 11 || bitstreamId > 16) {
      return false;
    }
    int extensionSize = Ac3Util.parseAc3SyncframeSize(
        Arrays.copyOfRange(data, extension, extension + 6));
    if (extensionSize < 10 || extensionSize > limit - extension) {
      return false;
    }
    try {
      if (parseFrame(data, offset, extension).channelCount != 6) {
        return false;
      }
    } catch (IllegalStateException | IndexOutOfBoundsException e) {
      return false;
    }
    ParsableBitArray header = new ParsableBitArray(data, extension + extensionSize);
    header.setPosition(extension * 8 + 50); // After bsid and dialnorm.
    if (header.readBit()) {
      header.skipBits(8); // Compression gain.
    }
    if (!header.readBit()) {
      return false;
    }
    int channelMap = header.readBits(16);
    int channelMode = (data[extension + 4] >> 1) & 7;
    return ((channelMode == 5 || channelMode == 6) && channelMap == 0x1a00)
        || (channelMode == 2 && channelMap == 0x0200);
  }

  private static Ac3Util.SyncFrameInfo parseFrame(byte[] data, int start, int end) {
    return Ac3Util.parseAc3SyncframeInfo(
        new ParsableBitArray(Arrays.copyOfRange(data, start, end)));
  }

  private static boolean isDependentFrame(byte[] data, int offset) {
    return hasSyncword(data, offset) && ((data[offset + 2] & 0xff) >> 6) == 1;
  }

  private static boolean hasSyncword(byte[] data, int offset) {
    return data[offset] == 0x0b && data[offset + 1] == 0x77;
  }
}
