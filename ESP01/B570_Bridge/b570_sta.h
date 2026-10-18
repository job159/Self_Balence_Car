// b570_sta.h - 熱點模式（ESP 以 STA 加入手機個人熱點）：帳密規則、STA_SET 解碼、
//              STA_INFO / DISCOVER_ACK / NET payload 編碼
// 規格：docs/PROTOCOL.md §3.4（0x46 STA_SET、0x47 STA_GET、0xC4 STA_INFO、0x48 DISCOVER、
//       0xC5 DISCOVER_ACK）、§3.5（0x53 NET）、§3.6；測試向量：tools/b570_proto.py vectors
// 純 C++（無 Arduino 相依），ESP8266 韌體與 PC 單元測試共用。
//
// IP 位址一律用 lwIP / IPAddress 的 u32（網路位元組序存放，記憶體順序 = a.b.c.d），
// 以 putU32() 寫出即為協定要求的 ip[4] = {a, b, c, d}。
#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "b570_proto.h"

namespace b570 {

constexpr uint8_t STA_SSID_MAX = 32;               // 802.11 SSID 上限（bytes，UTF-8）
constexpr uint8_t STA_PW_MIN = 8, STA_PW_MAX = 63; // WPA2 passphrase；0 = 開放網路

// NET.mode（§3.5）＝ STA_INFO.state（§3.4）
enum NetMode : uint8_t {
    NET_AP = 0,        // 直連（熱點模式未啟用）
    NET_JOINING = 1,   // 搜尋 / 加入熱點中（開機 12 s 內，或熱點斷線後 15 s 內）
    NET_STA = 2,       // 已連上熱點（AP 已關閉）
    NET_FALLBACK = 3,  // 找不到熱點 → 已改回直連 AP（閒置時再試：前 3 分鐘每 6 s，之後每 30 s）
};

// DISCOVER_ACK.mode：0 直連 AP、1 熱點 STA
inline uint8_t discoverMode(uint8_t netMode) { return netMode == NET_STA ? 1 : 0; }

struct StaCfg {
    uint8_t enabled;               // 0 / 1
    uint8_t ssidLen;               // 0 = 沒有存熱點帳密
    uint8_t pwLen;                 // 0（開放）或 8..63
    char ssid[STA_SSID_MAX + 1];   // 以 0 結尾
    char pw[STA_PW_MAX + 1];
};

inline void staClear(StaCfg& c) { memset(&c, 0, sizeof(c)); }

// ---------------------------------------------------------------- 字元規則
// 嚴格 UTF-8（RFC 3629）：拒絕 NUL（SDK 以 C 字串傳 SSID）、過長編碼、代理區、> U+10FFFF、截斷序列
inline bool utf8Ok(const uint8_t* s, size_t n) {
    size_t i = 0;
    while (i < n) {
        const uint8_t c = s[i];
        if (c == 0) return false;
        if (c < 0x80) { ++i; continue; }
        size_t k;
        uint32_t cp, lo;
        if ((c & 0xE0u) == 0xC0u)      { k = 1; cp = c & 0x1Fu; lo = 0x80u; }
        else if ((c & 0xF0u) == 0xE0u) { k = 2; cp = c & 0x0Fu; lo = 0x800u; }
        else if ((c & 0xF8u) == 0xF0u) { k = 3; cp = c & 0x07u; lo = 0x10000u; }
        else return false;                                   // 孤立的延續位元組或 F8..FF
        if (n - i <= k) return false;                        // 截斷
        for (size_t j = 1; j <= k; ++j) {
            const uint8_t d = s[i + j];
            if ((d & 0xC0u) != 0x80u) return false;
            cp = (cp << 6) | (d & 0x3Fu);
        }
        if (cp < lo || cp > 0x10FFFFu || (cp >= 0xD800u && cp <= 0xDFFFu)) return false;
        i += k + 1;
    }
    return true;
}

inline bool staSsidOk(const uint8_t* s, size_t n) { return n >= 1 && n <= STA_SSID_MAX && utf8Ok(s, n); }
inline bool staPwCharOk(uint8_t c) { return c >= 0x20 && c <= 0x7E; }
inline bool staPwOk(const uint8_t* s, size_t n) {
    if (n == 0) return true;                                 // 開放網路
    if (n < STA_PW_MIN || n > STA_PW_MAX) return false;
    for (size_t i = 0; i < n; ++i) if (!staPwCharOk(s[i])) return false;
    return true;
}

// 存放內容是否自洽：啟用時一定有 SSID；沒有 SSID 時也不會有密碼
inline bool staCfgOk(const StaCfg& c) {
    if (c.enabled > 1) return false;
    if (c.ssidLen == 0) return c.enabled == 0 && c.pwLen == 0 && c.ssid[0] == 0 && c.pw[0] == 0;
    return c.ssidLen <= STA_SSID_MAX && c.pwLen <= STA_PW_MAX &&
           staSsidOk((const uint8_t*)c.ssid, c.ssidLen) && staPwOk((const uint8_t*)c.pw, c.pwLen) &&
           c.ssid[c.ssidLen] == 0 && c.pw[c.pwLen] == 0;
}

inline void staSet(StaCfg& c, uint8_t en, const uint8_t* ssid, uint8_t sl, const uint8_t* pw, uint8_t pl) {
    staClear(c);
    c.enabled = en;
    c.ssidLen = sl;
    c.pwLen = pl;
    if (sl) memcpy(c.ssid, ssid, sl);
    if (pl) memcpy(c.pw, pw, pl);
}

// ---------------------------------------------------------------- STA_SET（0x46）
// payload：u8 enable(0/1), u8 ssid_len(0..32), ssid[], u8 pw_len(0 或 8..63), pw[]（長度必須剛好吻合）
//  - enable=1：ssid_len >= 1
//  - enable=0 且 ssid_len=0：保留原本存的熱點帳密（此時 pw_len 也必須是 0），只是停用
// 回傳 ESP_ACK status（ST_OK / ST_BAD_ARG）；ST_OK 時 out = 要存入的新設定（cur = 目前設定）。
inline uint8_t decodeStaSet(const uint8_t* p, size_t len, const StaCfg& cur, StaCfg& out) {
    if (len < 3) return ST_BAD_ARG;
    const uint8_t en = p[0], sl = p[1];
    if (en > 1 || sl > STA_SSID_MAX) return ST_BAD_ARG;
    if (len < (size_t)3 + sl) return ST_BAD_ARG;
    const uint8_t pl = p[2 + sl];
    if (len != (size_t)3 + sl + pl) return ST_BAD_ARG;
    const uint8_t* ssid = p + 2;
    const uint8_t* pw = p + 3 + sl;
    if (sl == 0) {
        if (en != 0 || pl != 0) return ST_BAD_ARG;
        out = cur;
        out.enabled = 0;
        return ST_OK;
    }
    if (!staSsidOk(ssid, sl) || !staPwOk(pw, pl)) return ST_BAD_ARG;
    staSet(out, en, ssid, sl, pw, pl);
    return ST_OK;
}

// ---------------------------------------------------------------- 編碼（回傳 payload 長度）
constexpr size_t NET_MAX = 1 + 4 + 1 + STA_SSID_MAX;                              // 38
constexpr size_t DISCOVER_ACK_MAX = 4 + 4 + 1 + 4 + 1 + 1 + STA_SSID_MAX;         // 47
constexpr size_t STA_INFO_MAX = 1 + 1 + 4 + 1 + 1 + STA_SSID_MAX + 1 + STA_PW_MAX; // 104
static_assert(STA_INFO_MAX <= MAX_PAYLOAD, "STA_INFO too long");

inline size_t putLenStr(uint8_t* out, const char* s, uint8_t n) {
    out[0] = n;
    if (n) memcpy(out + 1, s, n);
    return (size_t)1 + n;
}

// NET（§3.5）：u8 mode, u8 ip[4], u8 ssid_len, ssid[]
// ssid：mode 0 = 車子 AP 名稱，mode 1..3 = 熱點名稱（由呼叫端選）；ip 只在 mode 2 有效，其餘送 0。
inline size_t encodeNet(uint8_t* out, uint8_t mode, uint32_t ip, const char* ssid, uint8_t sl) {
    out[0] = mode;
    putU32(out + 1, mode == NET_STA ? ip : 0);
    return 5 + putLenStr(out + 5, ssid, sl);
}

// DISCOVER_ACK（§3.4）：u32 nonce, u32 chip_id, u8 mode, u8 ip[4], u8 busy, u8 ssid_len, ssid[]
// ssid = 車名（AP 的 SSID），App 用它比對記住的車。
inline size_t encodeDiscoverAck(uint8_t* out, uint32_t nonce, uint32_t chipId, uint8_t mode, uint32_t ip,
                                bool busy, const char* ssid, uint8_t sl) {
    putU32(out, nonce);
    putU32(out + 4, chipId);
    out[8] = mode;
    putU32(out + 9, ip);
    out[13] = busy ? 1 : 0;
    return 14 + putLenStr(out + 14, ssid, sl);
}

// STA_INFO（§3.4）：u8 enabled, u8 state, u8 ip[4], i8 rssi_dbm, u8 ssid_len, ssid[], u8 pw_len, pw[]
// ip / rssi 只在 state 2 有效（其餘 0）；密碼只給 session 擁有者（withPw），其他人 pw_len = 0。
inline size_t encodeStaInfo(uint8_t* out, const StaCfg& c, uint8_t state, uint32_t ip, int8_t rssi, bool withPw) {
    out[0] = c.enabled;
    out[1] = state;
    putU32(out + 2, state == NET_STA ? ip : 0);
    out[6] = state == NET_STA ? (uint8_t)rssi : 0;
    size_t k = 7;
    k += putLenStr(out + k, c.ssid, c.ssidLen);
    k += putLenStr(out + k, c.pw, withPw ? c.pwLen : (uint8_t)0);
    return k;
}

}  // namespace b570
