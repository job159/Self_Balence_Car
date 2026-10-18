// b570_wificfg.h - Wi-Fi 設定：SSID/密碼規則、WIFI_SET 解碼、EEPROM 紀錄、預設值、選頻
// 規格：docs/PROTOCOL.md §3.4（SSID / 密碼限制）、§3.5（CRED）、§3.6（熱點模式設定一起存）
// 純 C++（無 Arduino 相依），可在 PC 上測試。
#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "b570_proto.h"
#include "b570_sta.h"

namespace b570 {

constexpr uint8_t SSID_MIN = 1, SSID_MAX = 12;
constexpr uint8_t PW_MIN = 8, PW_MAX = 16;
constexpr uint8_t CHANNEL_MAX = 13;   // 0 = 自動
constexpr uint8_t GEN_PW_LEN = 8;
// 產生密碼用字元集（去掉易混淆的 i l o 0 1）
constexpr char PW_ALPHABET[] = "abcdefghjkmnpqrstuvwxyz23456789";
constexpr uint8_t PW_ALPHABET_LEN = sizeof(PW_ALPHABET) - 1;   // 31

struct WifiCfg {
    char ssid[SSID_MAX + 1];   // 以 0 結尾
    char pw[PW_MAX + 1];
    uint8_t ssidLen;
    uint8_t pwLen;
    uint8_t channel;           // 0 = 自動（開機掃描選 1/6/11），1..13 固定
};

// ---------------------------------------------------------------- 字元規則
inline bool ssidCharOk(uint8_t c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
}
inline bool pwCharOk(uint8_t c) {
    return c >= 0x21 && c <= 0x7E && c != ';' && c != ',' && c != ':' && c != '\\' && c != '"';
}
inline bool ssidOk(const uint8_t* s, size_t n) {
    if (n < SSID_MIN || n > SSID_MAX) return false;
    for (size_t i = 0; i < n; ++i) if (!ssidCharOk(s[i])) return false;
    return true;
}
inline bool pwOk(const uint8_t* s, size_t n) {
    if (n < PW_MIN || n > PW_MAX) return false;
    for (size_t i = 0; i < n; ++i) if (!pwCharOk(s[i])) return false;
    return true;
}
inline bool cfgOk(const WifiCfg& c) {
    return ssidOk((const uint8_t*)c.ssid, c.ssidLen) && pwOk((const uint8_t*)c.pw, c.pwLen) &&
           c.channel <= CHANNEL_MAX && c.ssid[c.ssidLen] == 0 && c.pw[c.pwLen] == 0;
}

inline void cfgSet(WifiCfg& c, const uint8_t* ssid, uint8_t sl, const uint8_t* pw, uint8_t pl, uint8_t ch) {
    memset(&c, 0, sizeof(c));
    memcpy(c.ssid, ssid, sl);
    memcpy(c.pw, pw, pl);
    c.ssidLen = sl;
    c.pwLen = pl;
    c.channel = ch;
}

// ---------------------------------------------------------------- 預設值（首次開機 / WIFI_FACTORY）
// SSID = "B570-XXXX"（晶片 ID 低 16 bit，大寫 hex）
inline void defaultSsid(char out[10], uint32_t chipId) {
    static const char kHex[] = "0123456789ABCDEF";
    memcpy(out, "B570-", 5);
    for (int i = 0; i < 4; ++i) out[5 + i] = kHex[(chipId >> (12 - 4 * i)) & 0xF];
    out[9] = 0;
}

// 8 碼隨機密碼；rng 回傳 32 bit 隨機數（ESP.random() 硬體 RNG）。拒絕取樣避免取模偏差。
template <class Rng>
inline void genPassword(char out[GEN_PW_LEN + 1], Rng&& rng) {
    constexpr uint8_t LIMIT = 256 - (256 % PW_ALPHABET_LEN);   // 248
    int k = 0;
    while (k < GEN_PW_LEN) {
        uint32_t r = rng();
        for (int b = 0; b < 4 && k < GEN_PW_LEN; ++b, r >>= 8) {
            const uint8_t v = (uint8_t)r;
            if (v < LIMIT) out[k++] = PW_ALPHABET[v % PW_ALPHABET_LEN];
        }
    }
    out[GEN_PW_LEN] = 0;
}

template <class Rng>
inline void factoryCfg(WifiCfg& c, uint32_t chipId, Rng&& rng) {
    memset(&c, 0, sizeof(c));
    defaultSsid(c.ssid, chipId);
    c.ssidLen = 9;
    genPassword(c.pw, rng);
    c.pwLen = GEN_PW_LEN;
    c.channel = 0;
}

// ---------------------------------------------------------------- WIFI_SET / CRED payload
// 格式：u8 ssid_len, ssid[], u8 pw_len, pw[], u8 channel（長度必須剛好吻合）
inline bool decodeWifiSet(const uint8_t* p, size_t len, WifiCfg& out) {
    if (len < 1) return false;
    const uint8_t sl = p[0];
    if (len < (size_t)1 + sl + 1) return false;
    const uint8_t pl = p[1 + sl];
    if (len != (size_t)1 + sl + 1 + pl + 1) return false;
    const uint8_t ch = p[2 + sl + pl];
    if (!ssidOk(p + 1, sl) || !pwOk(p + 2 + sl, pl) || ch > CHANNEL_MAX) return false;
    cfgSet(out, p + 1, sl, p + 2 + sl, pl, ch);
    return true;
}

// 寫出 u8 ssid_len, ssid, u8 pw_len, pw（CRED 與 WIFI_INFO 共用），回傳長度
inline size_t putSsidPw(uint8_t* out, const WifiCfg& c) {
    size_t k = 0;
    out[k++] = c.ssidLen;
    memcpy(out + k, c.ssid, c.ssidLen);
    k += c.ssidLen;
    out[k++] = c.pwLen;
    memcpy(out + k, c.pw, c.pwLen);
    k += c.pwLen;
    return k;
}

// CRED payload（§3.5）：ssid_len, ssid, pw_len, pw, channel
inline size_t encodeCred(uint8_t* out, const WifiCfg& c, uint8_t channel) {
    size_t k = putSsidPw(out, c);
    out[k++] = channel;
    return k;
}

// ---------------------------------------------------------------- EEPROM 紀錄
// v1（韌體 1.0，38 bytes）：
//   0 u32 magic | 4 u8 version=1 | 5 u8 ssid_len | 6 ssid[12] | 18 u8 pw_len | 19 pw[16] | 35 u8 channel
//   36 u16 crc16(0..35) little-endian
// v2（韌體 1.1 起，136 bytes）：0..35 與 v1 相同（version=2），之後接熱點模式設定
//   36 u8 sta_enabled | 37 u8 sta_ssid_len | 38 sta_ssid[32] | 70 u8 sta_pw_len | 71 sta_pw[63]
//   134 u16 crc16(0..133) little-endian
// 讀到 v1 → AP 帳密照用、熱點模式關閉（不會重新產生密碼）；下次存檔時寫成 v2。
constexpr uint32_t REC_MAGIC = 0xB5701A5Au;
constexpr uint8_t  REC_VERSION = 2;
constexpr size_t   REC_V1_SIZE = 38;
constexpr size_t   REC_SIZE = 136;
constexpr size_t   REC_STA = 36;            // v2 熱點設定起點
constexpr size_t   REC_CRC = REC_SIZE - 2;  // v2 CRC 位置

inline void encodeRecord(uint8_t rec[REC_SIZE], const WifiCfg& c, const StaCfg& s) {
    memset(rec, 0, REC_SIZE);
    putU32(rec, REC_MAGIC);
    rec[4] = REC_VERSION;
    rec[5] = c.ssidLen;
    memcpy(rec + 6, c.ssid, c.ssidLen);
    rec[18] = c.pwLen;
    memcpy(rec + 19, c.pw, c.pwLen);
    rec[35] = c.channel;
    rec[REC_STA] = s.enabled;
    rec[REC_STA + 1] = s.ssidLen;
    memcpy(rec + REC_STA + 2, s.ssid, s.ssidLen);
    rec[REC_STA + 34] = s.pwLen;
    memcpy(rec + REC_STA + 35, s.pw, s.pwLen);
    putU16(rec + REC_CRC, crc16(rec, REC_CRC));
}

// 熱點模式關閉、沒有熱點帳密（相容舊呼叫）
inline void encodeRecord(uint8_t rec[REC_SIZE], const WifiCfg& c) {
    StaCfg s;
    staClear(s);
    encodeRecord(rec, c, s);
}

// 回傳紀錄版本：2 = 目前版本、1 = 舊版（已轉換：熱點模式關閉）、0 = 無效（需要出廠設定）。
// AP 部分損壞 = 整筆無效；CRC 正確但熱點欄位不合法（不應發生）→ 只清掉熱點設定，AP 帳密照用。
inline uint8_t decodeRecord(const uint8_t rec[REC_SIZE], WifiCfg& out, StaCfg& sta) {
    if (getU32(rec) != REC_MAGIC) return 0;
    const uint8_t ver = rec[4];
    if (ver == 1) {
        if (crc16(rec, 36) != (uint16_t)(rec[36] | (rec[37] << 8))) return 0;
    } else if (ver == REC_VERSION) {
        if (crc16(rec, REC_CRC) != (uint16_t)(rec[REC_CRC] | (rec[REC_CRC + 1] << 8))) return 0;
    } else {
        return 0;
    }
    const uint8_t sl = rec[5], pl = rec[18], ch = rec[35];
    if (!ssidOk(rec + 6, sl) || !pwOk(rec + 19, pl) || ch > CHANNEL_MAX) return 0;
    cfgSet(out, rec + 6, sl, rec + 19, pl, ch);
    staClear(sta);
    if (ver == REC_VERSION) {
        const uint8_t en = rec[REC_STA], ssl = rec[REC_STA + 1], spl = rec[REC_STA + 34];
        if (ssl <= STA_SSID_MAX && spl <= STA_PW_MAX) {
            StaCfg s;
            staSet(s, en, rec + REC_STA + 2, ssl, rec + REC_STA + 35, spl);
            if (staCfgOk(s)) sta = s;
        }
    }
    return ver;
}

inline bool decodeRecord(const uint8_t rec[REC_SIZE], WifiCfg& out) {
    StaCfg s;
    return decodeRecord(rec, out, s) != 0;
}

// ---------------------------------------------------------------- 自動選頻（1 / 6 / 11）
// 每個掃到的 AP 依「與候選頻道的重疊程度 × 訊號強度權重」加分，分數最低者勝。
// 20 MHz 頻寬下相距 >= 5 個頻道才不重疊。
struct ChannelScore {
    static constexpr uint8_t CANDIDATES[3] = {1, 6, 11};
    uint32_t score[3] = {0, 0, 0};
    uint16_t apCount = 0;

    void add(uint8_t ch, int rssi) {
        if (ch < 1 || ch > 14) return;
        ++apCount;
        int w = rssi + 100;                 // -100 dBm → 0，-30 dBm → 70
        if (w < 0) w = 0;
        if (w > 70) w = 70;
        w += 10;                            // 弱訊號也算「擁擠」
        for (int k = 0; k < 3; ++k) {
            const int d = ch > CANDIDATES[k] ? ch - CANDIDATES[k] : CANDIDATES[k] - ch;
            if (d < 5) score[k] += (uint32_t)((5 - d) * w);
        }
    }
    // tieBreak：隨機數，分數相同時隨機挑（避免兩台車同時開機選到同一頻道）
    uint8_t pick(uint32_t tieBreak) const {
        uint32_t best = score[0];
        for (int k = 1; k < 3; ++k) if (score[k] < best) best = score[k];
        uint8_t idx[3];
        uint8_t n = 0;
        for (uint8_t k = 0; k < 3; ++k) if (score[k] == best) idx[n++] = k;
        return CANDIDATES[idx[tieBreak % n]];
    }
};

}  // namespace b570
