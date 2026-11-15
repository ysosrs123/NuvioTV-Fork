#pragma once

// Checks a Dolby Vision RPU before libdovi sees it. The bundled libdovi is built to abort the process on a panic,
// and some damaged or unusual RPUs make it panic: one that is almost all zero bytes, a curve type other than
// polynomial or MMR, a linear-interpolation polynomial, a huge pivot or block count, and level 8, 9 or 10 blocks of
// a length it has no layout for. It also reads the whole RPU before it compares the checksum, so this checks the
// checksum first. Anything refused here plays without its RPU, the same as an RPU libdovi rejects.
//
// No allocation and no library calls, so it builds for the app and for a PC test alike.

#include <stddef.h>
#include <stdint.h>

namespace dovi_guard {

enum Verdict : int {
    kOk = 0,
    kTooShort = 1,
    kBadStart = 2,
    kNoEndByte = 3,
    kBadCrc = 4,
    kBadHeader = 5,
    kBadCurve = 6,
    kBadPieces = 7,
    kBadBlocks = 8,
    kBadBlockSize = 9,
    kUnreadable = 10,
    kTooLarge = 11,
};

constexpr size_t kMaxRpuBytes = 16384;
constexpr uint64_t kMaxPivotsMinus2 = 7;
constexpr uint64_t kMaxExtBlocks = 255;

class BitReader {
public:
    BitReader(const uint8_t* data, size_t bytes) : data_(data), bits_(bytes * 8) {}

    bool failed() const { return failed_; }
    size_t remaining() const { return bits_ - pos_; }

    uint64_t bits(unsigned n) {
        if (failed_ || n > remaining()) {
            fail();
            return 0;
        }
        uint64_t v = 0;
        for (unsigned i = 0; i < n; ++i) {
            v = (v << 1) | ((data_[pos_ >> 3] >> (7 - (pos_ & 7))) & 1U);
            ++pos_;
        }
        return v;
    }

    bool bit() { return bits(1) != 0; }

    // Exp-Golomb, as libdovi reads ue(v) and se(v). Longer than 32 leading zeros is refused.
    uint64_t ue() {
        unsigned zeros = 0;
        while (!failed_ && bits(1) == 0) {
            if (++zeros > 32) {
                fail();
                return 0;
            }
        }
        if (failed_) return 0;
        return ((uint64_t{1} << zeros) - 1) + bits(zeros);
    }

    void skip(uint64_t n) {
        if (failed_ || n > remaining()) {
            fail();
            return;
        }
        pos_ += static_cast<size_t>(n);
    }

    void align() { skip((8 - (pos_ & 7)) & 7); }

private:
    void fail() {
        failed_ = true;
        pos_ = bits_;
    }

    const uint8_t* data_;
    size_t bits_;
    size_t pos_ = 0;
    bool failed_ = false;
};

// CRC-32/MPEG-2, the checksum an RPU carries.
inline uint32_t crc32_mpeg2(const uint8_t* data, size_t len) {
    uint32_t crc = 0xFFFFFFFFU;
    for (size_t i = 0; i < len; ++i) {
        crc ^= static_cast<uint32_t>(data[i]) << 24;
        for (int k = 0; k < 8; ++k) {
            crc = (crc & 0x80000000U) ? (crc << 1) ^ 0x04C11DB7U : (crc << 1);
        }
    }
    return crc;
}

// Where the RPU starts, using the same start patterns libdovi accepts. -1 when none fits.
inline int rpu_start(const uint8_t* d) {
    if (d[0] == 0 && d[1] == 0 && d[2] == 0 && d[3] == 1 && d[4] == 25) return 4;
    if (d[0] == 0 && d[1] == 0 && d[2] == 1 && d[3] == 25 && d[4] == 8) return 3;
    if ((d[0] == 0 || d[0] == 124) && d[1] == 1 && d[2] == 25 && d[3] == 8 && d[4] == 9) return 2;
    if (d[0] == 1 && d[1] == 25 && d[2] == 8 && d[3] == 9) return 1;
    if (d[0] == 25 && d[1] == 8 && d[2] == 9) return 0;
    return -1;
}

inline Verdict check_ext_blocks(BitReader& r, bool cm_v40) {
    const uint64_t count = r.ue();
    if (r.failed()) return kUnreadable;
    if (count > kMaxExtBlocks) return kBadBlocks;
    r.align();
    for (uint64_t i = 0; i < count; ++i) {
        const uint64_t length = r.ue();
        const uint64_t level = r.bits(8);
        if (r.failed()) return kUnreadable;
        if (cm_v40) {
            if (level == 8 && length != 10 && length != 12 && length != 13 && length != 19 && length != 25) {
                return kBadBlockSize;
            }
            if (level == 9 && length != 1 && length != 17) return kBadBlockSize;
            if (level == 10 && length != 5 && length != 21) return kBadBlockSize;
        }
        if (length > r.remaining() / 8) return kUnreadable;
        r.skip(length * 8);
    }
    return kOk;
}

// [escaped]: the buffer is a type 62 NAL with emulation prevention bytes (libdovi's unspec62 parser), otherwise a
// bare RPU (libdovi's plain parser). [profile_out] gets the profile the RPU describes (5, 7, 8, ...) when the
// verdict is kOk, -1 otherwise.
inline Verdict check(const uint8_t* data, size_t len, bool escaped, int* profile_out) {
    if (profile_out != nullptr) *profile_out = -1;
    if (data == nullptr || len < 25) return kTooShort;
    if (len > kMaxRpuBytes) return kTooLarge;
    const int start = rpu_start(data);
    if (start < 0) return kBadStart;

    uint8_t buf[kMaxRpuBytes];
    const uint8_t* src = data + start;
    const size_t src_len = len - static_cast<size_t>(start);
    size_t n = 0;
    for (size_t i = 0; i < src_len; ++i) {
        if (escaped && i >= 2 && src[i - 2] == 0 && src[i - 1] == 0 && src[i] == 3) continue;
        buf[n++] = src[i];
    }

    size_t end = n;
    while (end > 0 && buf[end - 1] == 0) --end;
    if (end < 10) return kTooShort;
    if (buf[end - 1] != 0x80) return kNoEndByte;
    const uint32_t stored = (static_cast<uint32_t>(buf[end - 5]) << 24) | (static_cast<uint32_t>(buf[end - 4]) << 16) |
        (static_cast<uint32_t>(buf[end - 3]) << 8) | static_cast<uint32_t>(buf[end - 2]);
    if (crc32_mpeg2(buf + 1, end - 6) != stored) return kBadCrc;

    BitReader r(buf, end - 5);
    if (r.bits(8) != 25) return kBadStart;

    const uint64_t rpu_type = r.bits(6);
    const uint64_t rpu_format = r.bits(11);
    const uint64_t vdr_rpu_profile = r.bits(4);
    const uint64_t vdr_rpu_level = r.bits(4);
    const bool seq_info = r.bit();
    uint64_t coefficient_data_type = 0;
    uint64_t log2_denom = 0;
    uint64_t bl_bit_depth_minus8 = 0;
    uint64_t el_bit_depth_minus8 = 0;
    uint64_t vdr_bit_depth_minus8 = 0;
    uint64_t reserved_zero_3bits = 0;
    bool full_range = false;
    bool el_spatial_resampling = false;
    bool disable_residual = false;
    unsigned coef_bits = 0;
    if (seq_info) {
        r.bit();
        coefficient_data_type = r.bits(2);
        if (coefficient_data_type == 0) log2_denom = r.ue();
        r.bits(2);
        full_range = r.bit();
        if ((rpu_format & 0x700) == 0) {
            bl_bit_depth_minus8 = r.ue();
            el_bit_depth_minus8 = r.ue() & 0xFF;
            vdr_bit_depth_minus8 = r.ue();
            r.bit();
            reserved_zero_3bits = r.bits(3);
            el_spatial_resampling = r.bit();
            disable_residual = r.bit();
        }
        if (!r.failed() && coefficient_data_type > 1) return kBadHeader;
    }
    const bool dm_present = r.bit();
    const bool use_prev = r.bit();
    if (use_prev) r.ue();
    if (r.failed()) return kUnreadable;
    // The same header limits libdovi enforces before it reads further.
    if (rpu_type != 2 || vdr_rpu_level != 0 || bl_bit_depth_minus8 != 2 || el_bit_depth_minus8 != 2 ||
        vdr_bit_depth_minus8 > 6 || log2_denom > 23) {
        return kBadHeader;
    }
    coef_bits = coefficient_data_type == 0 ? static_cast<unsigned>(log2_denom) : 32U;
    const bool cdt0 = coefficient_data_type == 0;
    const unsigned bl_bits = static_cast<unsigned>(bl_bit_depth_minus8) + 8U;
    const unsigned el_bits = static_cast<unsigned>(el_bit_depth_minus8) + 8U;

    int profile = 0;
    if (vdr_rpu_profile == 0) {
        profile = full_range ? 5 : 0;
    } else if (vdr_rpu_profile == 1) {
        profile = (el_spatial_resampling && !disable_residual) ? (vdr_bit_depth_minus8 == 4 ? 7 : 4) : 8;
    }

    if (!use_prev) {
        r.ue();
        r.ue();
        r.ue();
        uint64_t pieces[3] = {0, 0, 0};
        for (uint64_t& count : pieces) {
            const uint64_t pivots_minus2 = r.ue();
            if (r.failed()) return kUnreadable;
            if (pivots_minus2 > kMaxPivotsMinus2) return kBadPieces;
            count = pivots_minus2 + 1;
            r.skip((pivots_minus2 + 2) * bl_bits);
        }
        const bool nlq = (rpu_format & 0x700) == 0 && !disable_residual;
        if (nlq) {
            if (r.bits(3) != 0) return kBadHeader;
            r.skip(2ULL * bl_bits);
        }
        r.ue();
        r.ue();
        for (const uint64_t count : pieces) {
            for (uint64_t p = 0; p < count; ++p) {
                const uint64_t mapping_idc = r.ue();
                if (r.failed()) return kUnreadable;
                if (mapping_idc == 0) {
                    const uint64_t order_minus1 = r.ue();
                    if (r.failed()) return kUnreadable;
                    if (order_minus1 > 1) return kBadCurve;
                    if (order_minus1 == 0 && r.bit()) return kBadCurve;
                    for (uint64_t k = 0; k < order_minus1 + 2; ++k) {
                        if (cdt0) r.ue();
                        r.skip(coef_bits);
                    }
                } else if (mapping_idc == 1) {
                    const uint64_t order_minus1 = r.bits(2);
                    if (r.failed()) return kUnreadable;
                    if (order_minus1 > 2) return kBadCurve;
                    if (cdt0) r.ue();
                    r.skip(coef_bits);
                    for (uint64_t k = 0; k < (order_minus1 + 1) * 7; ++k) {
                        if (cdt0) r.ue();
                        r.skip(coef_bits);
                    }
                } else {
                    return kBadCurve;
                }
            }
        }
        if (nlq) {
            for (int c = 0; c < 3; ++c) {
                r.skip(el_bits);
                for (int k = 0; k < 3; ++k) {
                    if (cdt0) r.ue();
                    r.skip(coef_bits);
                }
            }
        }
        if (r.failed()) return kUnreadable;
    }

    if (dm_present) {
        r.ue();
        r.ue();
        r.ue();
        if (reserved_zero_3bits != 1) {
            r.skip(9 * 16 + 3 * 32 + 9 * 16 + 3 * 16 + 32 + 5 + 2 + 2 + 2 + 12 + 12 + 10);
        }
        if (r.failed()) return kUnreadable;
        Verdict blocks = check_ext_blocks(r, false);
        if (blocks != kOk) return blocks;
        // libdovi reads a CM v4.0 part when at least 56 bits are left, counting the checksum and end byte (40).
        if (r.remaining() + 40 >= 56) {
            blocks = check_ext_blocks(r, true);
            if (blocks != kOk) return blocks;
        }
    }

    if (profile_out != nullptr) *profile_out = profile;
    return kOk;
}

}  // namespace dovi_guard
