// b570_preset.h - 燒錄時內建的手機熱點（熱點模式的出廠預設）
//
// 你的熱點名稱 / 密碼放在 b570_preset_local.h（已列入 .gitignore，不會上傳）：
//   複製 b570_preset_local.example.h → b570_preset_local.h，填好後燒錄。沒有這個檔案時 PRESET_ID = 0（不使用）。
// 燒好之後車子一開機就直接加入這個手機熱點：不用先直連、掃 QR，手機 Wi-Fi 也不用開。
// 只套用一次：EEPROM 後段記下 PRESET_ID；之後在 App「設定 › 連線方式」修改熱點或改回直連，都以 App 的設定為準。
// 換一組內建熱點：改 b570_preset_local.h 的名稱 / 密碼，並把 PRESET_ID 加 1 再燒錄（下次開機會再套用一次）。
// 燒回舊版（PRESET_ID 較小）不會蓋掉 App 的設定。PRESET_ID = 0 → 不使用內建熱點（行為與 1.1.0 相同）。
// 手機熱點必須是 2.4 GHz、WPA2（或 WPA2/WPA3 混合）；名稱大小寫要完全相同。
// 純 C++（無 Arduino 相依），ESP8266 韌體與 PC 單元測試共用。
#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "b570_proto.h"
#include "b570_sta.h"

#if defined(__has_include)
#if __has_include("b570_preset_local.h")
#include "b570_preset_local.h"
#endif
#endif

#ifndef PRESET_ID
#define PRESET_ID           0            // 0 = 不使用；改了名稱 / 密碼要加 1（1..255）
#endif
#ifndef PRESET_STA_SSID
#define PRESET_STA_SSID     ""           // 手機個人熱點名稱（1..32 bytes）
#endif
#ifndef PRESET_STA_PW
#define PRESET_STA_PW       ""           // 手機個人熱點密碼（8..63 字元；"" = 開放熱點）
#endif

static_assert(PRESET_ID >= 0 && PRESET_ID <= 255, "PRESET_ID must be 0..255");
static_assert(PRESET_ID == 0 || (sizeof(PRESET_STA_SSID) - 1 >= 1 && sizeof(PRESET_STA_SSID) - 1 <= 32),
              "PRESET_STA_SSID must be 1..32 bytes");
static_assert(PRESET_ID == 0 || sizeof(PRESET_STA_PW) - 1 == 0 ||
                  (sizeof(PRESET_STA_PW) - 1 >= 8 && sizeof(PRESET_STA_PW) - 1 <= 63),
              "PRESET_STA_PW must be empty (open hotspot) or 8..63 characters");

namespace b570 {

// 套用紀錄：EEPROM 設定紀錄（REC_SIZE）之後的 6 bytes：u32 magic | u8 已套用的最大 id | u8 ~id
constexpr size_t   PRESET_MARK_OFFSET = 248;
constexpr size_t   PRESET_MARK_LEN = 6;
constexpr uint32_t PRESET_MAGIC = 0x54455350u;   // "PSET"

// 這個 id 還沒套用過：紀錄無效，或紀錄的 id 比較小（燒回舊版、id 較小時不再套用）。id 0 = 停用，永遠不套用。
inline bool presetPending(const uint8_t m[PRESET_MARK_LEN], uint8_t id) {
    if (id == 0) return false;
    const bool valid = getU32(m) == PRESET_MAGIC && m[5] == (uint8_t)~m[4];
    return !valid || m[4] < id;
}

inline void presetMark(uint8_t m[PRESET_MARK_LEN], uint8_t id) {
    putU32(m, PRESET_MAGIC);
    m[4] = id;
    m[5] = (uint8_t)~id;
}

// 把內建熱點寫進 s（啟用熱點模式）。名稱 / 密碼不合規則時不動 s、回傳 false。
inline bool presetApply(StaCfg& s, const char* ssid, const char* pw) {
    const size_t sl = strlen(ssid), pl = strlen(pw);
    if (!staSsidOk((const uint8_t*)ssid, sl) || !staPwOk((const uint8_t*)pw, pl)) return false;
    staSet(s, 1, (const uint8_t*)ssid, (uint8_t)sl, (const uint8_t*)pw, (uint8_t)pl);
    return true;
}

}  // namespace b570
