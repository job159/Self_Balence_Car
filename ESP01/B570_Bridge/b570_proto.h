// b570_proto.h - B570 Pilot 通訊協定 v1：CRC、建幀、串流解析器、TYPE 路由
// 規格：docs/PROTOCOL.md §2/§3；參考實作：tools/b570_proto.py（語意逐位元組一致）
// 純 C++（無 Arduino 相依），ESP8266 韌體與 PC 單元測試共用。
#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

namespace b570 {

// ---------------------------------------------------------------- 常數
constexpr uint8_t  PROTO_VERSION = 1;
constexpr uint8_t  SOF0 = 0xA5;
constexpr uint8_t  SOF1 = 0x5A;
constexpr uint8_t  MAX_PAYLOAD = 200;
constexpr size_t   HEADER_LEN = 5;                         // SOF0 SOF1 LEN TYPE SEQ
constexpr size_t   OVERHEAD = HEADER_LEN + 2;              // + CRC16
constexpr size_t   MAX_FRAME = OVERHEAD + MAX_PAYLOAD;     // 207

// App -> STM32（ESP 透傳）
constexpr uint8_t T_CTRL = 0x01, T_PING = 0x02, T_MOTOR = 0x03, T_ZERO_CAL = 0x04;
constexpr uint8_t T_PARAM_GET = 0x10, T_PARAM_SET = 0x11, T_PARAM_SAVE = 0x12, T_PARAM_DEFAULTS = 0x13;
constexpr uint8_t T_SEQ_LOAD = 0x20, T_SEQ_PLAY = 0x21, T_SEQ_STOP = 0x22, T_INFO_GET = 0x30;
// App -> ESP（本地處理，不轉送）
constexpr uint8_t T_HELLO = 0x40, T_WIFI_GET = 0x41, T_WIFI_SET = 0x42, T_ESP_PING = 0x43;
constexpr uint8_t T_BYE = 0x44, T_WIFI_FACTORY = 0x45;
constexpr uint8_t T_STA_SET = 0x46, T_STA_GET = 0x47, T_DISCOVER = 0x48;   // 熱點模式（§3.4、§3.6）
// ESP <-> STM32（只在 UART）
constexpr uint8_t T_LINK = 0x50, T_CRED = 0x51, T_CRED_REQ = 0x52, T_NET = 0x53;
// STM32 -> App
constexpr uint8_t T_TELEM = 0x81, T_PONG = 0x82, T_PARAM_VALUES = 0x90, T_ACK = 0x91;
constexpr uint8_t T_INFO = 0xA0, T_EVENT = 0xA1;
// ESP -> App
constexpr uint8_t T_HELLO_ACK = 0xC0, T_WIFI_INFO = 0xC1, T_ESP_PONG = 0xC2, T_ESP_ACK = 0xC3;
constexpr uint8_t T_STA_INFO = 0xC4, T_DISCOVER_ACK = 0xC5;

// ACK / ESP_ACK status
constexpr uint8_t ST_OK = 0, ST_BAD_ARG = 1, ST_BUSY = 2, ST_FAIL = 3, ST_NOT_READY = 4, ST_UNKNOWN_TYPE = 5;

// ---------------------------------------------------------------- TYPE 分區（§3）
inline bool isAppToStm(uint8_t t)  { return t >= 0x01 && t <= 0x3F; }
inline bool isAppToEsp(uint8_t t)  { return t >= 0x40 && t <= 0x4F; }
inline bool isEspStm(uint8_t t)    { return t >= 0x50 && t <= 0x5F; }   // 絕不上 UDP
inline bool isStmToApp(uint8_t t)  { return t >= 0x80 && t <= 0xBF; }
inline bool isEspToApp(uint8_t t)  { return t >= 0xC0 && t <= 0xCF; }

// ---------------------------------------------------------------- CRC-16/CCITT-FALSE
// poly 0x1021, init 0xFFFF, 不反射, xorout 0；免查表的逐位元組版本（與逐位元運算結果相同）
inline uint16_t crc16Update(uint16_t crc, uint8_t b) {
    crc = (uint16_t)((crc >> 8) | (crc << 8));
    crc ^= b;
    crc ^= (uint16_t)((crc & 0xFFu) >> 4);
    crc ^= (uint16_t)(crc << 12);
    crc ^= (uint16_t)((crc & 0xFFu) << 5);
    return crc;
}

inline uint16_t crc16(const uint8_t* data, size_t len, uint16_t crc = 0xFFFF) {
    while (len--) crc = crc16Update(crc, *data++);
    return crc;
}

// ---------------------------------------------------------------- 建幀
// 寫入 out（容量 cap），回傳幀長；payload 太長或空間不足回傳 0。
inline size_t buildFrame(uint8_t* out, size_t cap, uint8_t type, uint8_t seq,
                         const uint8_t* payload, size_t len) {
    if (len > MAX_PAYLOAD || cap < OVERHEAD + len) return 0;
    out[0] = SOF0;
    out[1] = SOF1;
    out[2] = (uint8_t)len;
    out[3] = type;
    out[4] = seq;
    if (len) memmove(out + HEADER_LEN, payload, len);
    const uint16_t c = crc16(out + 2, 3 + len);
    out[HEADER_LEN + len] = (uint8_t)(c & 0xFF);      // little-endian
    out[HEADER_LEN + len + 1] = (uint8_t)(c >> 8);
    return OVERHEAD + len;
}

// ---------------------------------------------------------------- little-endian 小工具
inline void putU16(uint8_t* p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
inline void putU32(uint8_t* p, uint32_t v) {
    p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24);
}
inline uint32_t getU32(const uint8_t* p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

// ---------------------------------------------------------------- 串流解析器
// 解出的幀；所有指標只在 callback 執行期間有效。
struct Frame {
    const uint8_t* raw;      // 完整幀（含 SOF 與 CRC），可原封轉送
    uint16_t rawLen;
    uint8_t type;
    uint8_t seq;
    const uint8_t* payload;
    uint8_t len;
};

// 與 tools/b570_proto.py Parser 完全相同的語意：
//  - 尋找 A5 5A；找不到時只保留最後一個 A5（可能是被切開的 SOF0）
//  - LEN > 200：丟 1 byte 後重新同步（不計錯）
//  - CRC 錯誤：crcErrors++，丟 1 byte 後在既有資料中重新尋找 SOF
//  - 處理前置雜訊、黏包（一次多幀）、拆包（一幀分多次 feed）
// 固定緩衝、無動態配置。callback 內不可再呼叫同一個 parser 的 feed()。
class Parser {
public:
    static constexpr size_t CAP = 512;
    static_assert(CAP >= 2 * MAX_FRAME, "parser buffer too small");

    uint32_t crcErrors = 0;   // CRC 錯誤次數（同 Python crc_errors）
    uint32_t frames = 0;      // 成功解出的幀數

    void reset() { n_ = 0; }
    size_t pending() const { return n_; }

    template <class F>
    void feed(const uint8_t* data, size_t len, F&& onFrame) {
        while (len) {
            size_t k = CAP - n_;
            if (k > len) k = len;
            memcpy(buf_ + n_, data, k);
            n_ += k;
            data += k;
            len -= k;
            process(onFrame);   // 處理後剩餘 <= MAX_FRAME-1，下一輪必有空間
        }
    }

private:
    uint8_t buf_[CAP];
    size_t n_ = 0;

    template <class F>
    void process(F& onFrame) {
        size_t i = 0;   // 目前讀取位置（i 之前的位元組視為已刪除）
        for (;;) {
            size_t j = i;
            while (j + 1 < n_ && !(buf_[j] == SOF0 && buf_[j + 1] == SOF1)) ++j;
            if (j + 1 >= n_) {                           // 找不到 SOF
                i = (n_ > i && buf_[n_ - 1] == SOF0) ? n_ - 1 : n_;
                break;
            }
            i = j;
            if (n_ - i < 3) break;                       // 等 LEN
            const uint8_t ln = buf_[i + 2];
            if (ln > MAX_PAYLOAD) { ++i; continue; }     // LEN 不合法：丟 1 byte
            const size_t total = OVERHEAD + ln;
            if (n_ - i < total) break;                   // 等整幀
            const uint8_t* f = buf_ + i;
            const uint16_t got = (uint16_t)(f[HEADER_LEN + ln] | (f[HEADER_LEN + ln + 1] << 8));
            if (crc16(f + 2, 3 + (size_t)ln) == got) {
                ++frames;
                Frame fr{f, (uint16_t)total, f[3], f[4], f + HEADER_LEN, ln};
                onFrame(fr);
                i += total;
            } else {
                ++crcErrors;
                ++i;                                     // 丟 1 byte 重新同步
            }
        }
        if (i) {
            n_ -= i;
            if (n_) memmove(buf_, buf_ + i, n_);
        }
    }
};

}  // namespace b570
