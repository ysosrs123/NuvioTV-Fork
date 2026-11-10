package com.nuvio.tv.core.player.dvmkv;

/** Locates the first sample after the lace-count and size fields in a bounded block prefix. */
final class MatroskaBlockLacing {
  private MatroskaBlockLacing() {}

  static int sampleDataOffset(byte[] data, int offset, int limit, int lacing) {
    if (offset < 0 || offset > limit || limit > data.length) return -1;
    if (lacing == 0) return offset;
    if (offset == limit || lacing < 1 || lacing > 3) return -1;
    int sizes = data[offset++] & 0xff; // Frame count minus one: the last size is implicit.
    if (lacing == 2) return offset;
    for (int i = 0; i < sizes; i++) {
      if (offset >= limit) return -1;
      if (lacing == 1) {
        while (offset < limit && (data[offset] & 0xff) == 0xff) offset++;
        if (offset == limit) return -1;
        offset++;
      } else {
        int mask = 0x80;
        int length = 1;
        while (length <= 8 && (data[offset] & mask) == 0) {
          mask >>= 1;
          length++;
        }
        if (length > 8 || length > limit - offset) return -1;
        offset += length; // First absolute size, then all signed EBML size deltas.
      }
    }
    return offset;
  }
}
