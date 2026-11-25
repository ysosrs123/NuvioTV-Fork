// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>
namespace nuviofel {
inline size_t startCode(const uint8_t *b, size_t n, size_t p) {
  if (p + 3 <= n && b[p] == 0 && b[p + 1] == 0) {
    if (b[p + 2] == 1)
      return 3;
    if (p + 4 <= n && b[p + 2] == 0 && b[p + 3] == 1)
      return 4;
  }
  return 0;
}
// MEL has no residual picture contribution. Keep the original P7 RPU/BL,
// removing only the nested EL NALs from the base-layer decoder input.
inline size_t stripEl(uint8_t *b, size_t n) {
  size_t write = 0, begin = 0;
  while (begin < n) {
    size_t prefix = startCode(b, n, begin);
    if (!prefix) {
      ++begin;
      continue;
    }
    size_t next = begin + prefix;
    while (next < n && !startCode(b, n, next))
      ++next;
    if (begin + prefix + 2 <= next && ((b[begin + prefix] >> 1) & 63) != 63) {
      memmove(b + write, b + begin, next - begin);
      write += next - begin;
    }
    begin = next;
  }
  return write;
}
} // namespace nuviofel
