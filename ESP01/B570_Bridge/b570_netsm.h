// b570_netsm.h - 熱點模式連線策略（docs/PROTOCOL.md §3.6）
// 純狀態機：時間（millis()）與 Wi-Fi 狀態由呼叫端餵入，回傳要執行的動作；無 Arduino 相依，可在 PC 上測試。
//
//            開機（STA_SET 已啟用）
//                  │ A_JOIN（WIFI_STA + WiFi.begin，AP 不開）
//                  ▼
//   ┌──────── JOIN（NET 1）── 12 s 內連上 ──▶ STA（NET 2）◀──── 15 s 內恢復 ──┐
//   │ 12 s 逾時                     A_STA_UP（關 AP）│ 斷線                       │
//   │ A_START_AP                                     ▼                            │
//   │                                          LOST（NET 1）────────────────────┘
//   │                                                │ 斷線 > 15 s：A_START_AP
//   ▼                                                ▼
//  FALLBACK（NET 3，AP 運作中）◀─────────────────────┘
//   │ AP 連續閒置（沒有手機連在 AP、沒有 session）：A_RETRY（WIFI_AP_STA + WiFi.begin，AP 照常）
//   │   閒置多久才試（retryIdleMs()）：快速重試期 6 s（改開 AP 後的第一次 12 s），之後 30 s
//   ▼
//  RETRY（NET 仍為 3）── 連上 ──▶ STA：A_STA_UP（關 AP）
//   │ SDK 回報找不到熱點 / 連線失敗 / 密碼錯誤（重試 0.5 s 後才採信），或 8 s 逾時：
//   │   A_ABORT_RETRY（停 STA、切回純 AP）→ FALLBACK
//   │ 有手機連上 AP / 有 session：A_YIELD_RETRY（只停 STA 連線，不切模式、不阻塞）→ FALLBACK，
//   │   AP 再度閒置時 A_RESTORE_AP（把殘留的 AP_STA 切回純 AP）
//
// 快速重試期（fastRetry()）：戶外常見「先開車子、才開手機熱點」，或開車途中熱點斷線（走太遠）又回到範圍內。
// - 每次進入 FALLBACK（開機加入失敗、熱點斷線超過 15 s）開始 3 分鐘（FAST_WINDOW_MS）：每輪 = 閒置 6 s + 找熱點。
//   熱點不在時 SDK 掃完一輪（約 2 s）就回報 STATION_NO_AP_FOUND → 立即放棄這次重試（不必等滿 8 s），
//   每輪約 8–9 s、射頻離開 AP 頻道約 25–30%（1.1.0 是每 38 s 一輪、約 6 s）。
// - 改開 AP 後的第一次重試多等一點（FIRST_IDLE_MS 12 s，從 AP 真正開起來算：apStarted()）：
//   OLED 剛顯示 QR，使用者可能正要掃描直連，這段時間射頻一直留在 AP 頻道。
// - 直連的手機離開 AP（例如打開手機熱點時 Wi-Fi 被系統關掉）→ 重新開始 60 s 快速期（REARM_WINDOW_MS；
//   不會縮短還沒結束的 3 分鐘）。
// - 連續 2 次重試都是密碼錯誤（STATION_WRONG_PASSWORD）：再快也沒用 → 回到 30 s，直到下次連上熱點或重開機
//   （只看一次會誤判：訊號很弱時 4-way handshake 逾時 SDK 也回報密碼錯誤）。
// 熱點出現後通常 15 秒內、最久約 20 秒連上；快速期過後放慢成 30 s，減少 AP 離開頻道的時間（直連配對較順）。
//
// 低延遲保證：會阻塞（WiFi.mode() 切換約 100 ms）或讓射頻離開 AP 頻道（STA 掃描）的動作，
// 只會在「沒有 App session 且沒有手機連在 AP 上」時發出；唯一例外 A_YIELD_RETRY 本身不阻塞。
// 快速重試期只改變「多久試一次」，提早放棄只是提早發出同一個 A_ABORT_RETRY；每個動作本身（與阻塞時間）完全相同。
#pragma once

#include <stdint.h>

#include "b570_sta.h"

namespace b570 {

class NetSm {
public:
    static constexpr uint32_t JOIN_BUDGET_MS = 12000;   // 開機加入熱點的時間上限
    static constexpr uint32_t STA_LOST_MS = 15000;      // 熱點斷線多久改開 AP
    static constexpr uint32_t FAST_WINDOW_MS = 180000;  // 快速重試期：每次改開 AP 起算 3 分鐘
    static constexpr uint32_t REARM_WINDOW_MS = 60000;  // 手機離開 AP 後重新開始的快速重試期
    static constexpr uint32_t FIRST_IDLE_MS = 12000;    // 改開 AP 後第一次重試前，AP 要連續閒置多久（QR 剛出現）
    static constexpr uint32_t FAST_IDLE_MS = 6000;      // 快速重試期內：AP 連續閒置多久再試一次熱點
    static constexpr uint32_t RETRY_IDLE_MS = 30000;    // 快速重試期過後：AP 連續閒置多久再試一次熱點
    static constexpr uint32_t RETRY_BUDGET_MS = 8000;   // 每次重試的時間上限
    static constexpr uint32_t RETRY_MIN_MS = 500;       // 重試開始後這麼久內不採信 SDK 的失敗狀態（可能還是上一次的）
    static constexpr uint8_t WRONG_PW_STRIKES = 2;      // 連續幾次重試密碼錯誤就放棄快速重試
    static_assert(FAST_IDLE_MS <= FIRST_IDLE_MS && FIRST_IDLE_MS <= RETRY_IDLE_MS, "idle intervals out of order");
    static_assert(FAST_WINDOW_MS > FIRST_IDLE_MS + RETRY_BUDGET_MS && REARM_WINDOW_MS > FAST_IDLE_MS + RETRY_BUDGET_MS &&
                      REARM_WINDOW_MS <= FAST_WINDOW_MS,
                  "fast retry windows must fit at least one retry");
    static_assert(RETRY_MIN_MS < RETRY_BUDGET_MS, "early abort must come before the retry budget");

    enum Action : uint8_t {
        A_NONE = 0,
        A_JOIN,          // 開機：WIFI_STA + WiFi.begin（只由 begin() 回傳）
        A_STA_UP,        // 熱點已連上：WIFI_STA（關閉 AP）
        A_START_AP,      // 加入失敗 / 熱點斷線太久：停止 STA、開 AP（同一組 B570-XXXX 帳密）
        A_RETRY,         // AP 閒置：WIFI_AP_STA + WiFi.begin
        A_ABORT_RETRY,   // 重試失敗 / 逾時（AP 仍閒置）：停止 STA、切回純 AP
        A_YIELD_RETRY,   // 重試中 AP 有人：立即停止 STA 連線（不切模式）
        A_RESTORE_AP,    // 讓出後 AP 再度閒置：AP_STA → 純 AP
    };

    // STA 介面這次加入熱點的失敗狀態（wifi_station_get_connect_status()；STA 介面沒開 = SE_NONE）
    enum StaErr : uint8_t {
        SE_NONE = 0,     // 閒置 / 連線中 / 已連上
        SE_NOT_FOUND,    // 找不到熱點（STATION_NO_AP_FOUND）或連線失敗（STATION_CONNECT_FAIL）
        SE_WRONG_PW,     // 密碼錯誤（STATION_WRONG_PASSWORD）
    };

    // staEnabled = EEPROM 中熱點模式已啟用
    Action begin(bool staEnabled, uint32_t now) {
        t0_ = now;
        fbSince_ = now;
        fastLen_ = FAST_WINDOW_MS;
        linger_ = false;
        fast_ = false;
        first_ = false;
        wasBusy_ = false;
        wrongPw_ = false;
        strikes_ = 0;
        if (!staEnabled) {
            phase_ = P_AP;
            mode_ = NET_AP;
            return A_NONE;
        }
        phase_ = P_JOIN;
        mode_ = NET_JOINING;
        return A_JOIN;
    }

    // 改開 AP 要先掃描選頻（最多 SCAN_BUDGET_MS）時，AP 真正開起來後呼叫：第一次重試的閒置時間從這裡算
    void apStarted(uint32_t now) {
        if (phase_ == P_FALLBACK) t0_ = now;
    }

    // staUp：STA 已連上熱點且拿到 IP；apStations：連在 AP 上的手機數（AP 沒開 = 0）；
    // session：目前有 App session；staErr：SDK 回報的加入失敗（只在 RETRY 使用）。
    // 每圈（或每 20 ms）呼叫一次，回傳要執行的動作（一次最多一個）。
    Action step(uint32_t now, bool staUp, uint8_t apStations, bool session, StaErr staErr = SE_NONE) {
        const bool apBusy = apStations > 0 || session;
        const uint32_t dt = now - t0_;
        // 快速重試期只會結束，只有 enterFallback() / 手機離開 AP 會重新開始（millis() 49.7 天溢位時也一樣）
        if (fast_ && (uint32_t)(now - fbSince_) >= fastLen_) fast_ = false;
        switch (phase_) {
        case P_AP:
            return A_NONE;
        case P_JOIN:
            if (staUp) return enterSta(now);
            if (dt >= JOIN_BUDGET_MS) return enterFallback(now);
            return A_NONE;
        case P_STA:
            if (!staUp) {
                phase_ = P_LOST;
                mode_ = NET_JOINING;
                t0_ = now;
            }
            return A_NONE;
        case P_LOST:
            if (staUp) {                       // 自動重連成功：模式不變（仍是 WIFI_STA）
                phase_ = P_STA;
                mode_ = NET_STA;
                t0_ = now;
                return A_NONE;
            }
            if (dt >= STA_LOST_MS) return enterFallback(now);
            return A_NONE;
        case P_FALLBACK:
            if (apBusy) {                      // 有人在用 AP：重新計算閒置時間
                t0_ = now;
                wasBusy_ = true;
                first_ = false;                // 已經有手機用過 AP（QR 掃過了）
                return A_NONE;
            }
            if (wasBusy_) {                    // 手機剛離開 AP（例如打開熱點時 Wi-Fi 被關掉）
                wasBusy_ = false;
                rearm(now);
            }
            if (linger_) {
                linger_ = false;
                t0_ = now;
                return A_RESTORE_AP;
            }
            if (dt >= retryIdleMs()) {
                phase_ = P_RETRY;
                first_ = false;
                t0_ = now;
                return A_RETRY;
            }
            return A_NONE;
        case P_RETRY:
            if (apBusy) {                      // 優先保護正在連 AP 的手機
                phase_ = P_FALLBACK;
                linger_ = true;
                wasBusy_ = true;
                t0_ = now;
                return A_YIELD_RETRY;
            }
            if (staUp) return enterSta(now);
            if (staErr != SE_NONE && dt >= RETRY_MIN_MS) {   // SDK 已經說這次連不上：不必等滿 8 s
                if (staErr == SE_WRONG_PW) {
                    if (strikes_ < WRONG_PW_STRIKES) ++strikes_;
                    if (strikes_ >= WRONG_PW_STRIKES) {
                        wrongPw_ = true;
                        fast_ = false;
                    }
                } else {
                    strikes_ = 0;
                }
                return abortRetry(now);
            }
            if (dt >= RETRY_BUDGET_MS) {
                strikes_ = 0;
                return abortRetry(now);
            }
            return A_NONE;
        }
        return A_NONE;
    }

    NetMode mode() const { return mode_; }
    bool retrying() const { return phase_ == P_RETRY; }
    bool lingering() const { return linger_; }
    bool fastRetry() const { return fast_; }        // 快速重試期中（截至上一次 step()）
    bool wrongPassword() const { return wrongPw_; } // 連續密碼錯誤 → 不再快速重試（直到連上熱點或重開機）
    // FALLBACK 時 AP 要連續閒置多久才再試熱點
    uint32_t retryIdleMs() const { return !fast_ ? RETRY_IDLE_MS : first_ ? FIRST_IDLE_MS : FAST_IDLE_MS; }

private:
    enum Phase : uint8_t { P_AP, P_JOIN, P_STA, P_LOST, P_FALLBACK, P_RETRY };

    Action enterSta(uint32_t now) {
        phase_ = P_STA;
        mode_ = NET_STA;
        linger_ = false;
        fast_ = false;
        first_ = false;
        wasBusy_ = false;
        wrongPw_ = false;
        strikes_ = 0;
        t0_ = now;
        return A_STA_UP;
    }
    Action enterFallback(uint32_t now) {
        phase_ = P_FALLBACK;
        mode_ = NET_FALLBACK;
        linger_ = false;
        wasBusy_ = false;
        fast_ = true;                          // 每次改開 AP 都重新開始快速重試期（從 JOIN / LOST 進來，wrongPw_ 一定已清除）
        first_ = true;
        fbSince_ = now;
        fastLen_ = FAST_WINDOW_MS;
        t0_ = now;                             // 從 AP 開起來算閒置時間（要先選頻時由 apStarted() 重設）
        return A_START_AP;
    }
    Action abortRetry(uint32_t now) {
        phase_ = P_FALLBACK;
        t0_ = now;
        return A_ABORT_RETRY;
    }
    // 手機離開 AP：重新開始 REARM_WINDOW_MS 的快速期（剩下更長的快速期不縮短；密碼錯誤時不開始）
    void rearm(uint32_t now) {
        if (wrongPw_) return;
        if (fast_ && fastLen_ - (uint32_t)(now - fbSince_) >= REARM_WINDOW_MS) return;
        fast_ = true;
        fbSince_ = now;
        fastLen_ = REARM_WINDOW_MS;
    }

    Phase phase_ = P_AP;
    NetMode mode_ = NET_AP;
    bool linger_ = false;    // 讓出重試後，STA 介面還開著（AP_STA），等 AP 閒置再切回純 AP
    bool fast_ = false;      // 快速重試期中（FAST_IDLE_MS）
    bool first_ = false;     // 改開 AP 後還沒重試過、也還沒有手機用過 AP（FIRST_IDLE_MS）
    bool wasBusy_ = false;   // FALLBACK：上一次看到 AP 有人用（離開時重新開始快速期）
    bool wrongPw_ = false;   // 連續 WRONG_PW_STRIKES 次密碼錯誤：不再快速重試
    uint8_t strikes_ = 0;    // 連續幾次重試是密碼錯誤
    uint32_t t0_ = 0;        // JOIN / LOST / RETRY：開始時間；FALLBACK：AP 開始閒置的時間
    uint32_t fbSince_ = 0;   // 快速重試期起點（進入 FALLBACK / 手機離開 AP）
    uint32_t fastLen_ = FAST_WINDOW_MS;   // 這次快速重試期的長度
};

}  // namespace b570
