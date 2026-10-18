// b570_session.h - App session 擁有權（docs/PROTOCOL.md §3.4 Session 規則）
// 純 C++（無 Arduino 相依），時間由呼叫端傳入（millis()），可在 PC 上測試。
//
//  1. HELLO：無 session / 來源 IP 與擁有者相同 / 擁有者已逾時 → 成為擁有者（OK），否則 BUSY。
//  2. 擁有者超過 TIMEOUT_MS 沒有任何封包 → session 結束。
//  3. 非擁有者的 0x01-0x3F 一律丟棄（由呼叫端以 isOwner() 判斷）。
//
// 擁有者 = HELLO 的來源 IP:port（回覆送往此位址）。同一 IP 換了 port 必須重送 HELLO。
#pragma once

#include <stdint.h>
#include <string.h>

namespace b570 {

class AppSession {
public:
    static constexpr uint32_t TIMEOUT_MS = 1500;

    enum HelloResult : uint8_t {
        HELLO_NEW = 0,    // 新擁有者（含同 IP 換 port、或取代已逾時的擁有者）
        HELLO_SAME = 1,   // 目前擁有者重送 HELLO（同 IP:port）
        HELLO_BUSY = 2,   // 已被其他裝置佔用
    };

    // 有效（未逾時）的 session
    bool active(uint32_t now) const { return active_ && (uint32_t)(now - lastRxMs_) <= TIMEOUT_MS; }

    // 每圈呼叫：逾時則結束 session，回傳 true 表示「剛剛結束」
    bool poll(uint32_t now) {
        if (active_ && !active(now)) { end(); return true; }
        return false;
    }

    bool isOwner(uint32_t ip, uint16_t port, uint32_t now) const {
        return active(now) && ip == ip_ && port == port_;
    }

    // 擁有者送來任何封包 → 更新活動時間；回傳是否為擁有者
    bool touch(uint32_t ip, uint16_t port, uint32_t now) {
        if (!isOwner(ip, port, now)) return false;
        lastRxMs_ = now;
        return true;
    }

    HelloResult hello(uint32_t ip, uint16_t port, uint32_t sessionId, uint32_t now) {
        const bool alive = active(now);
        if (alive && ip != ip_) return HELLO_BUSY;
        const bool same = alive && port == port_;
        if (!alive || ip != ip_) macKnown_ = false;      // 換了裝置，MAC 需重新查
        active_ = true;
        ip_ = ip;
        port_ = port;
        id_ = sessionId;
        lastRxMs_ = now;
        return same ? HELLO_SAME : HELLO_NEW;
    }

    void end() { active_ = false; macKnown_ = false; }

    // 擁有者手機的 MAC（用於「該手機斷開 Wi-Fi 時立即結束 session」）
    void setMac(const uint8_t mac[6]) { memcpy(mac_, mac, 6); macKnown_ = true; }
    bool macKnown() const { return macKnown_; }
    // 某台手機斷線：是否應結束 session（MAC 未知時保守處理：任何斷線都結束）
    bool stationLeft(const uint8_t mac[6]) const {
        if (!active_) return false;
        return !macKnown_ || memcmp(mac, mac_, 6) == 0;
    }

    uint32_t ip() const { return ip_; }
    uint16_t port() const { return port_; }
    uint32_t id() const { return id_; }

private:
    bool active_ = false;
    bool macKnown_ = false;
    uint32_t ip_ = 0;          // 網路位元組序（與 lwIP / IPAddress 相同）
    uint16_t port_ = 0;
    uint32_t id_ = 0;
    uint32_t lastRxMs_ = 0;
    uint8_t mac_[6] = {0, 0, 0, 0, 0, 0};
};

}  // namespace b570
