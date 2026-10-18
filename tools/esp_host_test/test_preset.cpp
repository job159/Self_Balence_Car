// 內建手機熱點（b570_preset.h）的 PC 單元測試：套用紀錄、帳密檢查、模擬開機流程（EEPROM 256 bytes）。
#include <cstdio>
#include <cstring>
#include <vector>

#include "b570_proto.h"
#include "b570_wificfg.h"
#include "b570_sta.h"
#include "b570_preset.h"

using namespace b570;

static int g_fail = 0, g_pass = 0;
#define CHECK(cond)                                                              \
    do {                                                                         \
        if (cond) ++g_pass;                                                      \
        else { ++g_fail; std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); } \
    } while (0)

// 測試用的內建熱點（與本機 b570_preset_local.h 無關）
static const uint8_t kId = 1;
static const char* const kSsid = "TestPhone";
static const char* const kPw = "testpass1";

static_assert(PRESET_MARK_OFFSET >= REC_SIZE, "preset mark overlaps the config record");
static_assert(PRESET_MARK_OFFSET + PRESET_MARK_LEN <= 256, "preset mark beyond EEPROM_SIZE");

// 模擬 B570_Bridge.ino setup() 的設定載入 + 內建熱點套用（與韌體同一段邏輯）
struct Eeprom {
    uint8_t b[256];
    Eeprom() { memset(b, 0xFF, sizeof(b)); }
};

static uint32_t g_rng = 12345;
static uint32_t rng() { g_rng = g_rng * 1103515245u + 12345u; return g_rng; }

static void boot(Eeprom& e, WifiCfg& cfg, StaCfg& sta, uint8_t presetId, const char* ssid, const char* pw) {
    if (!decodeRecord(e.b, cfg, sta)) {
        factoryCfg(cfg, 0x00A1B2u, rng);
        staClear(sta);
        encodeRecord(e.b, cfg, sta);
    }
    uint8_t* m = e.b + PRESET_MARK_OFFSET;
    if (presetPending(m, presetId)) {
        StaCfg s = sta;
        if (!presetApply(s, ssid, pw)) return;   // 不合規則：不記錄
        sta = s;
        presetMark(m, presetId);
        encodeRecord(e.b, cfg, sta);
    }
}

int main() {
    // ---- 套用紀錄
    {
        uint8_t m[PRESET_MARK_LEN];
        memset(m, 0xFF, sizeof(m));
        CHECK(presetPending(m, 1));
        CHECK(!presetPending(m, 0));            // 0 = 停用
        presetMark(m, 1);
        CHECK(!presetPending(m, 1));
        CHECK(presetPending(m, 2));             // 換一組（id + 1）會再套用
        presetMark(m, 2);
        CHECK(!presetPending(m, 1));            // 燒回舊版（id 較小）不再套用
        CHECK(!presetPending(m, 2));
        CHECK(presetPending(m, 255));
        m[5] ^= 0x01;                           // 損毀
        CHECK(presetPending(m, 1));
        memset(m, 0x00, sizeof(m));
        CHECK(presetPending(m, 1));
    }
    // ---- 帳密
    {
        StaCfg s;
        staClear(s);
        CHECK(presetApply(s, kSsid, kPw));
        CHECK(s.enabled == 1);
        CHECK(s.ssidLen == strlen(kSsid) && memcmp(s.ssid, kSsid, s.ssidLen) == 0);
        CHECK(s.pwLen == strlen(kPw) && memcmp(s.pw, kPw, s.pwLen) == 0);
        CHECK(staCfgOk(s));

        StaCfg t = s;
        CHECK(!presetApply(t, "x", "short7!"));            // 密碼太短
        CHECK(memcmp(&t, &s, sizeof(s)) == 0);              // 失敗不動原設定
        CHECK(!presetApply(t, "", "abcd1234"));             // 沒有名稱
        CHECK(!presetApply(t, "123456789012345678901234567890123", "abcd1234"));   // 33 bytes
        CHECK(presetApply(t, "Open", ""));                  // 開放熱點
        CHECK(t.enabled == 1 && t.pwLen == 0 && staCfgOk(t));
        CHECK(presetApply(t, "\xE6\x88\x91\xE7\x9A\x84\xE6\x89\x8B\xE6\xA9\x9F", "abcd1234"));   // UTF-8 名稱「我的手機」
        CHECK(staCfgOk(t));
    }
    // ---- 開機流程：全新 ESP（EEPROM 全 0xFF）→ 直接是熱點模式
    {
        Eeprom e;
        WifiCfg cfg;
        StaCfg sta;
        boot(e, cfg, sta, kId, kSsid, kPw);
        CHECK(sta.enabled == 1 && std::strcmp(sta.ssid, kSsid) == 0 && std::strcmp(sta.pw, kPw) == 0);
        CHECK(cfgOk(cfg));                                   // AP 帳密仍是出廠隨機（熱點找不到時改開 AP 用）

        // 重開機：設定從 EEPROM 讀回、不再重新套用
        WifiCfg cfg2;
        StaCfg sta2;
        CHECK(decodeRecord(e.b, cfg2, sta2) != 0);
        CHECK(memcmp(&sta2, &sta, sizeof(sta)) == 0);
        CHECK(!presetPending(e.b + PRESET_MARK_OFFSET, kId));

        // 使用者在 App 改回直連（STA_SET enable=0、保留帳密）→ 存檔 → 重開機仍是直連
        const uint8_t off[3] = {0, 0, 0};
        StaCfg next;
        CHECK(decodeStaSet(off, sizeof(off), sta2, next) == ST_OK);
        CHECK(next.enabled == 0);
        encodeRecord(e.b, cfg2, next);
        WifiCfg cfg3;
        StaCfg sta3;
        boot(e, cfg3, sta3, kId, kSsid, kPw);
        CHECK(sta3.enabled == 0);
        CHECK(std::strcmp(cfg3.ssid, cfg.ssid) == 0 && std::strcmp(cfg3.pw, cfg.pw) == 0);

        // 使用者在 App 改成別的熱點 → 重開機保留使用者的
        const char* ns = "OtherPhone";
        const char* np = "abcdefgh";
        std::vector<uint8_t> p;
        p.push_back(1);
        p.push_back((uint8_t)strlen(ns));
        p.insert(p.end(), ns, ns + strlen(ns));
        p.push_back((uint8_t)strlen(np));
        p.insert(p.end(), np, np + strlen(np));
        StaCfg other;
        CHECK(decodeStaSet(p.data(), p.size(), sta3, other) == ST_OK);
        encodeRecord(e.b, cfg3, other);
        WifiCfg cfg4;
        StaCfg sta4;
        boot(e, cfg4, sta4, kId, kSsid, kPw);
        CHECK(sta4.enabled == 1 && std::strcmp(sta4.ssid, "OtherPhone") == 0);

        // 燒錄新的一組（id + 1）→ 套用一次
        WifiCfg cfg5;
        StaCfg sta5;
        boot(e, cfg5, sta5, (uint8_t)(kId + 1), "NewPhone", "12345678");
        CHECK(sta5.enabled == 1 && std::strcmp(sta5.ssid, "NewPhone") == 0);
        CHECK(std::strcmp(cfg5.ssid, cfg.ssid) == 0);       // AP 帳密不變

        // 燒回舊版（kId 較小）：不蓋掉
        WifiCfg cfg6;
        StaCfg sta6;
        boot(e, cfg6, sta6, kId, kSsid, kPw);
        CHECK(std::strcmp(sta6.ssid, "NewPhone") == 0);
    }
    // ---- 內建熱點填錯（密碼 7 碼）：不套用、不記錄；改好用同一個 id 重燒就會套用
    {
        Eeprom e;
        WifiCfg cfg;
        StaCfg sta;
        boot(e, cfg, sta, 3, "Phone", "1234567");
        CHECK(sta.enabled == 0);
        CHECK(presetPending(e.b + PRESET_MARK_OFFSET, 3));
        WifiCfg c2;
        StaCfg s2;
        boot(e, c2, s2, 3, "Phone", "12345678");
        CHECK(s2.enabled == 1 && std::strcmp(s2.ssid, "Phone") == 0);
        CHECK(!presetPending(e.b + PRESET_MARK_OFFSET, 3));
    }
    // ---- 從 1.1.0 升級（EEPROM 已有 v2 紀錄、熱點模式關閉、沒有套用紀錄）→ 套用一次，AP 帳密保留
    {
        Eeprom e;
        WifiCfg cfg;
        StaCfg sta;
        factoryCfg(cfg, 0x00C3D4u, rng);
        staClear(sta);
        encodeRecord(e.b, cfg, sta);
        WifiCfg c2;
        StaCfg s2;
        boot(e, c2, s2, kId, kSsid, kPw);
        CHECK(s2.enabled == 1 && std::strcmp(s2.ssid, kSsid) == 0);
        CHECK(std::strcmp(c2.ssid, cfg.ssid) == 0 && std::strcmp(c2.pw, cfg.pw) == 0);
    }
    // ---- kId = 0：完全不動
    {
        Eeprom e;
        WifiCfg cfg;
        StaCfg sta;
        boot(e, cfg, sta, 0, kSsid, kPw);
        CHECK(sta.enabled == 0 && sta.ssidLen == 0);
        CHECK(e.b[PRESET_MARK_OFFSET] == 0xFF);
    }

    // 這台電腦的 b570_preset_local.h（有的話）也要合規則
    if (PRESET_ID != 0) {
        StaCfg real;
        staClear(real);
        CHECK(presetApply(real, PRESET_STA_SSID, PRESET_STA_PW));
    }

    std::printf("%s: %d passed, %d failed\n", g_fail ? "FAILED" : "ALL PRESET TESTS PASSED", g_pass, g_fail);
    return g_fail ? 1 : 0;
}
