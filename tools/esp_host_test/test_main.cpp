// ESP-01 橋接韌體的 PC 單元測試：CRC、建幀（docs/PROTOCOL.md §2 全部測試向量）、
// 串流解析器（含與 tools/b570_proto.py 的差分測試）、session 規則、Wi-Fi 設定、
// 熱點模式（STA_SET / STA_INFO / DISCOVER_ACK / NET、EEPROM v1→v2、連線狀態機）、UART 傳送佇列與延遲模擬。
#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <random>
#include <sstream>
#include <string>
#include <vector>

#include "b570_proto.h"
#include "b570_session.h"
#include "b570_wificfg.h"
#include "b570_sta.h"
#include "b570_netsm.h"
#include "b570_uarttx.h"
#ifdef _WIN32
#include <windows.h>
#endif

using namespace b570;
using Bytes = std::vector<uint8_t>;

static int g_fail = 0, g_pass = 0;
#define CHECK(cond)                                                              \
    do {                                                                         \
        if (cond) ++g_pass;                                                      \
        else { ++g_fail; std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); } \
    } while (0)

static Bytes hex(const std::string& s) {
    Bytes b;
    std::string d;
    for (char c : s) if (std::isxdigit((unsigned char)c)) d += c;
    for (size_t i = 0; i + 1 < d.size(); i += 2) b.push_back((uint8_t)std::stoul(d.substr(i, 2), nullptr, 16));
    return b;
}
static std::string tohex(const Bytes& b) {
    std::string s;
    char t[4];
    for (size_t i = 0; i < b.size(); ++i) { std::snprintf(t, sizeof t, i ? " %02X" : "%02X", b[i]); s += t; }
    return s;
}
static Bytes frame(uint8_t type, uint8_t seq, const Bytes& p) {
    Bytes out(MAX_FRAME);
    size_t n = buildFrame(out.data(), out.size(), type, seq, p.data(), p.size());
    out.resize(n);
    return out;
}
static void le16(Bytes& b, int v) { b.push_back((uint8_t)v); b.push_back((uint8_t)((unsigned)v >> 8)); }
static void le32(Bytes& b, uint32_t v) { for (int i = 0; i < 4; ++i) b.push_back((uint8_t)(v >> (8 * i))); }
static void lef(Bytes& b, float f) { uint32_t u; std::memcpy(&u, &f, 4); le32(b, u); }

struct Got { uint8_t type, seq; Bytes payload; Bytes raw; };

struct Harness {
    Parser p;
    std::vector<Got> got;
    void feed(const Bytes& d) { feed(d.data(), d.size()); }
    void feed(const uint8_t* d, size_t n) {
        p.feed(d, n, [this](const Frame& f) {
            got.push_back({f.type, f.seq, Bytes(f.payload, f.payload + f.len), Bytes(f.raw, f.raw + f.rawLen)});
        });
    }
};

// bit-by-bit 參考 CRC（與 Python 相同寫法）
static uint16_t crcRef(const uint8_t* d, size_t n) {
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < n; ++i) {
        crc ^= (uint16_t)(d[i] << 8);
        for (int k = 0; k < 8; ++k) crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021) : (uint16_t)(crc << 1);
    }
    return crc;
}

// ---------------------------------------------------------------- §2 測試向量
static void testVectors() {
    const char* s = "123456789";
    CHECK(crc16((const uint8_t*)s, 9) == 0x29B1);
    std::mt19937 rng(1);
    for (int i = 0; i < 2000; ++i) {
        Bytes b(rng() % 210);
        for (auto& x : b) x = (uint8_t)rng();
        CHECK(crc16(b.data(), b.size()) == crcRef(b.data(), b.size()));
    }

    struct V { const char* name; Bytes f; const char* want; };
    Bytes ctrl; le16(ctrl, 300); le16(ctrl, -250); ctrl.push_back(0);
    Bytes ping; le32(ping, 0x12345678);
    Bytes pset; pset.push_back(0); lef(pset, 23000.0f);
    Bytes seql = {0, 1, 1}; le16(seql, 200); le16(seql, 0); le16(seql, 1000); seql.push_back(0); seql.push_back(0);
    Bytes hello; le32(hello, 0xCAFEBABE);
    // TELEM 依 §4.1 欄位逐一組出（tools/b570_proto.py decode_telem 的範例值）
    Bytes tel;
    le16(tel, 1234); le16(tel, -523); le16(tel, 150); le16(tel, -820); le16(tel, 33);
    le16(tel, 120); le16(tel, 118); le16(tel, 1500); le16(tel, 1480); le16(tel, 1200);
    le16(tel, 250); le16(tel, 50); le16(tel, 120); le16(tel, 0); le16(tel, 1156);
    for (int v : {3, 0, 255, 0, 1, 50}) tel.push_back((uint8_t)v);
    le16(tel, 9000); le16(tel, 412);
    CHECK(tel.size() == 40);

    const V vs[] = {
        {"CTRL", frame(T_CTRL, 7, ctrl), "A5 5A 05 01 07 2C 01 06 FF 00 D4 4D"},
        {"PING", frame(T_PING, 1, ping), "A5 5A 04 02 01 78 56 34 12 E3 C1"},
        {"MOTOR", frame(T_MOTOR, 2, {1}), "A5 5A 01 03 02 01 67 DD"},
        {"PARAM_GET", frame(T_PARAM_GET, 3, {0xFF}), "A5 5A 01 10 03 FF B4 FA"},
        {"PARAM_SET", frame(T_PARAM_SET, 4, pset), "A5 5A 05 11 04 00 00 B0 B3 46 98 6D"},
        {"SEQ_LOAD", frame(T_SEQ_LOAD, 5, seql), "A5 5A 0B 20 05 00 01 01 C8 00 00 00 E8 03 00 00 FF 25"},
        {"HELLO", frame(T_HELLO, 0, hello), "A5 5A 04 40 00 BE BA FE CA 65 78"},
        {"CRED_REQ", frame(T_CRED_REQ, 9, {}), "A5 5A 00 52 09 68 35"},
        {"ACK", frame(T_ACK, 10, {0x12, 2}), "A5 5A 02 91 0A 12 02 36 67"},
        {"TELEM", frame(T_TELEM, 0x2A, tel),
         "A5 5A 28 81 2A D2 04 F5 FD 96 00 CC FC 21 00 78 00 76 00 DC 05 C8 05 B0 04 FA 00 32 00 78 00 00 00 84 04 "
         "03 00 FF 00 01 32 28 23 9C 01 4C 8F"},
    };
    int ok = 0;
    for (const V& v : vs) {
        const bool eq = v.f == hex(v.want);
        CHECK(eq);
        if (!eq) std::printf("  %s got  %s\n  want %s\n", v.name, tohex(v.f).c_str(), v.want);
        // 每個向量都要能被解析回來
        Harness h;
        h.feed(hex(v.want));
        CHECK(h.got.size() == 1 && h.got[0].raw == hex(v.want) && h.p.crcErrors == 0);
        if (h.got.size() == 1) CHECK(h.got[0].type == v.f[3] && h.got[0].seq == v.f[4]);
        ok += eq;
    }
    std::printf("vectors: %d/%d byte-exact (CRC16('123456789')=0x%04X)\n", ok, (int)(sizeof vs / sizeof vs[0]),
                crc16((const uint8_t*)s, 9));

    // buildFrame 邊界
    uint8_t buf[MAX_FRAME + 4];
    uint8_t big[201] = {0};
    CHECK(buildFrame(buf, sizeof buf, 1, 0, big, 200) == 207);
    CHECK(buildFrame(buf, sizeof buf, 1, 0, big, 201) == 0);
    CHECK(buildFrame(buf, 10, 1, 0, big, 4) == 0);   // 容量不足
    CHECK(buildFrame(buf, 11, 1, 0, big, 4) == 11);
}

// ---------------------------------------------------------------- 解析器情境
static void testParser() {
    const Bytes fCtrl = hex("A5 5A 05 01 07 2C 01 06 FF 00 D4 4D");
    const Bytes fPing = hex("A5 5A 04 02 01 78 56 34 12 E3 C1");

    {   // tools/b570_proto.py vectors() 內建自我測試（原樣重現）
        Harness h;
        Bytes s = {0x00, 0xA5, 0xFF};
        s.insert(s.end(), fCtrl.begin(), fCtrl.end());
        s.insert(s.end(), fPing.begin(), fPing.begin() + 5);
        h.feed(s);
        Bytes s2(fPing.begin() + 5, fPing.end());
        s2.push_back(0xA5);
        h.feed(s2);
        h.feed(hex("5A 00"));
        CHECK(h.got.size() == 2 && h.got[0].type == T_CTRL && h.got[1].type == T_PING);
        CHECK(h.p.pending() == 3);   // A5 5A 00 留著等後續
    }
    {   // 前置雜訊（模擬 74880 開機亂碼，含 A5 / 5A 碎片）
        Harness h;
        Bytes s = hex("00 FF A5 A5 5A C9 12 5A 5A A5 00 A5 5A FF 72 6E 0D 0A 5A A5");
        s.insert(s.end(), fCtrl.begin(), fCtrl.end());
        h.feed(s);
        CHECK(h.got.size() == 1 && h.got[0].raw == fCtrl);
    }
    {   // 兩幀黏在一起（同一次 feed / 同一個 datagram）
        Harness h;
        Bytes s = fCtrl;
        s.insert(s.end(), fPing.begin(), fPing.end());
        h.feed(s);
        CHECK(h.got.size() == 2 && h.got[0].raw == fCtrl && h.got[1].raw == fPing && h.p.pending() == 0);
    }
    {   // 一幀被拆在兩次 feed：每個切點都測
        int good = 0;
        for (size_t cut = 0; cut <= fPing.size(); ++cut) {
            Harness h;
            h.feed(fPing.data(), cut);
            h.feed(fPing.data() + cut, fPing.size() - cut);
            good += (h.got.size() == 1 && h.got[0].raw == fPing && h.p.crcErrors == 0);
        }
        CHECK(good == (int)fPing.size() + 1);
        Harness h;   // 逐 byte 餵
        for (uint8_t b : fCtrl) h.feed(&b, 1);
        CHECK(h.got.size() == 1 && h.got[0].raw == fCtrl);
    }
    {   // CRC 錯誤 → 計數、丟 1 byte 重新同步，後面的好幀照收
        Harness h;
        Bytes bad = fCtrl;
        bad[7] ^= 0x01;
        Bytes s = bad;
        s.insert(s.end(), fPing.begin(), fPing.end());
        h.feed(s);
        CHECK(h.got.size() == 1 && h.got[0].raw == fPing && h.p.crcErrors == 1);
    }
    {   // 假 header 的 LEN 把真幀吞進去：CRC 錯 → 丟 1 byte → 在已收資料中找回真幀
        Harness h;
        Bytes s = hex("A5 5A 0A 01 00");
        s.insert(s.end(), fPing.begin(), fPing.end());
        s.insert(s.end(), fCtrl.begin(), fCtrl.end());
        h.feed(s);
        CHECK(h.got.size() == 2 && h.got[0].raw == fPing && h.got[1].raw == fCtrl && h.p.crcErrors == 1);
    }
    {   // LEN > 200 → 丟 1 byte，不計 CRC 錯
        Harness h;
        Bytes s = hex("A5 5A C9 01 00 01 02");
        s.insert(s.end(), fCtrl.begin(), fCtrl.end());
        s.insert(s.end(), {0xA5, 0x5A, 0xFF});
        s.insert(s.end(), fPing.begin(), fPing.end());
        h.feed(s);
        CHECK(h.got.size() == 2 && h.got[0].raw == fCtrl && h.got[1].raw == fPing && h.p.crcErrors == 0);
    }
    {   // LEN = 200（最大幀 207 bytes）分三段餵
        Bytes pl(200);
        for (size_t i = 0; i < pl.size(); ++i) pl[i] = (uint8_t)(i * 7 + 0xA5);
        Bytes f = frame(0x90, 77, pl);
        CHECK(f.size() == 207);
        Harness h;
        h.feed(f.data(), 3);
        h.feed(f.data() + 3, 100);
        h.feed(f.data() + 103, 104);
        CHECK(h.got.size() == 1 && h.got[0].payload == pl && h.got[0].seq == 77);
    }
    {   // 結尾是 A5、下一次從 5A 開始
        Harness h;
        h.feed(hex("11 22 A5"));
        CHECK(h.p.pending() == 1);
        Bytes rest(fPing.begin() + 1, fPing.end());
        h.feed(rest);
        CHECK(h.got.size() == 1 && h.got[0].raw == fPing);
    }
    {   // 一次 feed 超過內部緩衝（60 幀黏在一起 + 雜訊）
        Harness h;
        Bytes s;
        for (int i = 0; i < 60; ++i) {
            Bytes p = {(uint8_t)i, (uint8_t)(i * 3)};
            Bytes f = frame(0x81, (uint8_t)i, p);
            s.insert(s.end(), f.begin(), f.end());
            if (i % 7 == 0) s.insert(s.end(), {0x00, 0xA5, 0x13});
        }
        h.feed(s);
        bool ok = h.got.size() == 60;
        for (size_t i = 0; ok && i < 60; ++i) ok = h.got[i].seq == i && h.got[i].payload[0] == i;
        CHECK(ok && h.p.crcErrors == 0 && h.p.frames == 60);
    }
    {   // 純雜訊不會讓緩衝累積
        Harness h;
        Bytes n(5000, 0x5A);
        h.feed(n);
        CHECK(h.got.empty() && h.p.pending() == 0);
    }
    {   // reset() 之後不會拼接上一個 datagram 的殘幀
        Harness h;
        h.feed(fPing.data(), 6);
        h.p.reset();
        h.feed(fPing.data() + 6, fPing.size() - 6);
        CHECK(h.got.empty());
    }
}

// ---------------------------------------------------------------- 與 Python Parser 的差分測試
static int testDifferential(const char* path) {
    std::ifstream in(path);
    if (!in) { std::printf("FAIL cannot open %s\n", path); ++g_fail; return 0; }
    struct Case { Bytes stream; std::vector<Got> want; uint32_t errs = 0; };
    std::vector<Case> cs;
    std::string line;
    while (std::getline(in, line)) {
        std::istringstream ss(line);
        std::string tag;
        ss >> tag;
        if (tag == "C") { std::string h; ss >> h; cs.push_back({}); if (h != "-") cs.back().stream = hex(h); }
        else if (tag == "F") {
            int t, q; std::string h; ss >> t >> q >> h;
            cs.back().want.push_back({(uint8_t)t, (uint8_t)q, h == "-" ? Bytes() : hex(h), {}});
        } else if (tag == "E") ss >> cs.back().errs;
    }
    std::mt19937 rng(7);
    int okCases = 0, totalFrames = 0;
    for (const Case& c : cs) {
        bool caseOk = true;
        for (int mode = 0; mode < 4; ++mode) {   // 一次全部 / 逐 byte / 隨機切塊 x2
            Harness h;
            size_t i = 0;
            while (i < c.stream.size()) {
                size_t k = mode == 0 ? c.stream.size() : mode == 1 ? 1 : 1 + rng() % 64;
                if (k > c.stream.size() - i) k = c.stream.size() - i;
                h.feed(c.stream.data() + i, k);
                i += k;
            }
            bool ok = h.got.size() == c.want.size() && h.p.crcErrors == c.errs;
            for (size_t j = 0; ok && j < c.want.size(); ++j)
                ok = h.got[j].type == c.want[j].type && h.got[j].seq == c.want[j].seq && h.got[j].payload == c.want[j].payload;
            caseOk = caseOk && ok;
        }
        CHECK(caseOk);
        okCases += caseOk;
        totalFrames += (int)c.want.size();
    }
    std::printf("differential vs tools/b570_proto.py Parser: %d/%d cases identical (%d frames, 4 chunkings each)\n",
                okCases, (int)cs.size(), totalFrames);
    return (int)cs.size();
}

// ---------------------------------------------------------------- 路由分區
static void testRouting() {
    int a = 0, e = 0, l = 0, s = 0, x = 0;
    for (int t = 0; t < 256; ++t) {
        a += isAppToStm((uint8_t)t); e += isAppToEsp((uint8_t)t); l += isEspStm((uint8_t)t);
        s += isStmToApp((uint8_t)t); x += isEspToApp((uint8_t)t);
    }
    CHECK(a == 63 && e == 16 && l == 16 && s == 64 && x == 16);
    CHECK(!isAppToStm(0x00) && isAppToStm(0x01) && isAppToStm(0x3F) && !isAppToStm(0x40));
    CHECK(isStmToApp(0x80) && isStmToApp(0xBF) && !isStmToApp(0xC0) && !isStmToApp(0x52));
}

// ---------------------------------------------------------------- Session 規則（§3.4）
static void testSession() {
    const uint32_t A = 0x6404A8C0, B = 0x6504A8C0;   // 192.168.4.100 / .101（網路位元組序）
    AppSession s;
    CHECK(!s.active(0) && !s.isOwner(A, 5000, 0));
    CHECK(s.hello(A, 5000, 0x11, 100) == AppSession::HELLO_NEW);       // 規則 1：無 session
    CHECK(s.active(100) && s.isOwner(A, 5000, 100) && s.id() == 0x11);
    CHECK(!s.isOwner(B, 5000, 100) && !s.isOwner(A, 5001, 100));
    CHECK(s.hello(B, 6000, 0x22, 200) == AppSession::HELLO_BUSY);      // 其他裝置 → BUSY
    CHECK(s.isOwner(A, 5000, 200) && s.id() == 0x11);
    CHECK(s.hello(A, 5000, 0x33, 300) == AppSession::HELLO_SAME);      // 擁有者重送
    CHECK(s.hello(A, 5100, 0x44, 400) == AppSession::HELLO_NEW);       // 同 IP 換 port → 接手
    CHECK(s.isOwner(A, 5100, 400) && !s.isOwner(A, 5000, 400) && s.port() == 5100);
    CHECK(!s.touch(B, 6000, 1000));                                     // 非擁有者不續命
    CHECK(s.touch(A, 5100, 1000));
    CHECK(s.active(2500) && !s.poll(2500));                             // 1500 ms 整：仍有效
    CHECK(!s.active(2501) && !s.isOwner(A, 5100, 2501));                // 規則 2：超過 1500 ms
    CHECK(s.hello(B, 6000, 0x55, 2600) == AppSession::HELLO_NEW);      // 擁有者已逾時 → 新擁有者
    CHECK(s.isOwner(B, 6000, 2600));
    CHECK(s.poll(4200) && !s.active(4200) && !s.poll(4300));            // poll 只回報一次
    // millis() 溢位
    AppSession w;
    CHECK(w.hello(A, 1, 1, 0xFFFFFF00u) == AppSession::HELLO_NEW);
    CHECK(w.active(0x00000100u) && !w.active(0xFFFFFF00u + 1501));
    // 手機斷線
    AppSession m;
    const uint8_t macA[6] = {1, 2, 3, 4, 5, 6}, macB[6] = {9, 9, 9, 9, 9, 9};
    CHECK(!m.stationLeft(macA));                                        // 無 session
    m.hello(A, 1, 1, 0);
    CHECK(m.stationLeft(macB));                                         // MAC 未知：保守結束
    m.setMac(macA);
    CHECK(!m.stationLeft(macB) && m.stationLeft(macA));
    m.hello(A, 2, 1, 10);                                               // 同 IP 換 port：MAC 保留
    CHECK(m.macKnown());
    m.end();
    CHECK(!m.active(10) && !m.macKnown() && !m.stationLeft(macA));
}

// ---------------------------------------------------------------- Wi-Fi 設定
static bool ssidS(const char* s) { return ssidOk((const uint8_t*)s, std::strlen(s)); }
static bool pwS(const char* s) { return pwOk((const uint8_t*)s, std::strlen(s)); }
static Bytes wifiSet(const std::string& ssid, const std::string& pw, int ch) {
    Bytes p;
    p.push_back((uint8_t)ssid.size()); p.insert(p.end(), ssid.begin(), ssid.end());
    p.push_back((uint8_t)pw.size()); p.insert(p.end(), pw.begin(), pw.end());
    p.push_back((uint8_t)ch);
    return p;
}

static void testWifiCfg() {
    CHECK(ssidS("B570-1A2B") && ssidS("a") && ssidS("Abc_def-0129") && !ssidS("") && !ssidS("ABCDEFGHIJKLM"));
    CHECK(!ssidS("a b") && !ssidS("a.b") && !ssidS("車") && !ssidS("a;b"));
    CHECK(pwS("abcdefgh") && pwS("0123456789abcdef") && pwS("!#$%&'()*+-./<=") && pwS("~~~~~~~~"));
    CHECK(!pwS("abcdefg") && !pwS("0123456789abcdefg"));
    for (const char* bad : {"abcdefg;", "abcdefg,", "abcdefg:", "abcdefg\\", "abcdefg\"", "abcd efg", "abcdefg\x7f"})
        CHECK(!pwS(bad));

    WifiCfg c;
    Bytes p = wifiSet("MyCar_01", "pass-word!", 6);
    CHECK(decodeWifiSet(p.data(), p.size(), c) && std::strcmp(c.ssid, "MyCar_01") == 0 &&
          std::strcmp(c.pw, "pass-word!") == 0 && c.channel == 6 && cfgOk(c));
    p = wifiSet("MyCar", "abcdefgh", 0);  CHECK(decodeWifiSet(p.data(), p.size(), c) && c.channel == 0);
    p = wifiSet("MyCar", "abcdefgh", 13); CHECK(decodeWifiSet(p.data(), p.size(), c));
    p = wifiSet("MyCar", "abcdefgh", 14); CHECK(!decodeWifiSet(p.data(), p.size(), c));
    p = wifiSet("", "abcdefgh", 1);       CHECK(!decodeWifiSet(p.data(), p.size(), c));
    p = wifiSet("MyCar", "short", 1);     CHECK(!decodeWifiSet(p.data(), p.size(), c));
    p = wifiSet("My Car", "abcdefgh", 1); CHECK(!decodeWifiSet(p.data(), p.size(), c));
    p = wifiSet("MyCar", "abc;defgh", 1); CHECK(!decodeWifiSet(p.data(), p.size(), c));
    p = wifiSet("MyCar", "abcdefgh", 1);  p.push_back(0);   // 多 1 byte
    CHECK(!decodeWifiSet(p.data(), p.size(), c));
    p = wifiSet("MyCar", "abcdefgh", 1);  p.pop_back();     // 少 channel
    CHECK(!decodeWifiSet(p.data(), p.size(), c));
    CHECK(!decodeWifiSet(p.data(), 0, c));
    Bytes trunc = {12, 'a', 'b'};                           // ssid_len 超出 payload
    CHECK(!decodeWifiSet(trunc.data(), trunc.size(), c));

    // 預設 SSID
    char ssid[10];
    defaultSsid(ssid, 0x12345678); CHECK(std::strcmp(ssid, "B570-5678") == 0);
    defaultSsid(ssid, 0x00ABCDEF); CHECK(std::strcmp(ssid, "B570-CDEF") == 0);
    defaultSsid(ssid, 0x00000A0B); CHECK(std::strcmp(ssid, "B570-0A0B") == 0);

    // 隨機密碼：長度、字元集、拒絕取樣、分布
    std::mt19937 rng(42);
    std::vector<int> hist(256, 0);
    bool allOk = true;
    for (int i = 0; i < 4000; ++i) {
        char pw[9];
        genPassword(pw, [&] { return (uint32_t)rng(); });
        allOk = allOk && std::strlen(pw) == 8 && pwS(pw);
        for (int k = 0; k < 8; ++k) {
            allOk = allOk && std::strchr(PW_ALPHABET, pw[k]) != nullptr;
            hist[(uint8_t)pw[k]]++;
        }
    }
    CHECK(allOk);
    int mn = 1 << 30, mx = 0;
    for (const char* a = PW_ALPHABET; *a; ++a) { mn = std::min(mn, hist[(uint8_t)*a]); mx = std::max(mx, hist[(uint8_t)*a]); }
    CHECK(mn > 850 && mx < 1250);   // 期望 32000/31 ≈ 1032
    {
        int calls = 0;   // 前兩次全部 >= 248 被拒絕，之後 0x00
        char pw[9];
        genPassword(pw, [&] { return ++calls <= 2 ? 0xFFFEFDF8u : 0u; });
        CHECK(std::strcmp(pw, "aaaaaaaa") == 0 && calls == 4);
    }
    WifiCfg f;
    factoryCfg(f, 0xBEEF1234, [&] { return (uint32_t)rng(); });
    CHECK(cfgOk(f) && std::strcmp(f.ssid, "B570-1234") == 0 && f.pwLen == 8 && f.channel == 0);

    // EEPROM 紀錄
    uint8_t rec[REC_SIZE];
    p = wifiSet("ABCDEFGHIJKL", "0123456789ABCDEF", 11);   // 最長 SSID / 密碼
    CHECK(decodeWifiSet(p.data(), p.size(), c));
    encodeRecord(rec, c);
    WifiCfg r;
    CHECK(decodeRecord(rec, r) && std::memcmp(&r, &c, sizeof r) == 0);
    int corrupt = 0;
    for (size_t i = 0; i < REC_SIZE; ++i)
        for (int bit = 0; bit < 8; ++bit) {
            uint8_t t[REC_SIZE];
            std::memcpy(t, rec, REC_SIZE);
            t[i] ^= (uint8_t)(1 << bit);
            corrupt += !decodeRecord(t, r);
        }
    CHECK(corrupt == (int)REC_SIZE * 8);   // 任何 1 bit 損壞都偵測得到
    uint8_t blank[REC_SIZE];
    std::memset(blank, 0xFF, REC_SIZE);
    CHECK(!decodeRecord(blank, r));        // 全新 flash
    std::memset(blank, 0x00, REC_SIZE);
    CHECK(!decodeRecord(blank, r));
    uint8_t v3[REC_SIZE];                  // 未知版本（CRC 正確）也拒絕
    std::memcpy(v3, rec, REC_SIZE);
    v3[4] = 3;
    putU16(v3 + REC_CRC, crc16(v3, REC_CRC));
    CHECK(!decodeRecord(v3, r));

    // CRED payload
    uint8_t cred[64];
    p = wifiSet("B570-1A2B", "abcd2345", 0);
    decodeWifiSet(p.data(), p.size(), c);
    size_t n = encodeCred(cred, c, 6);
    Bytes want = wifiSet("B570-1A2B", "abcd2345", 6);
    CHECK(n == want.size() && std::memcmp(cred, want.data(), n) == 0);
}

// ---------------------------------------------------------------- 自動選頻
static void testChannel() {
    ChannelScore e;
    CHECK(e.pick(0) == 1 && e.pick(1) == 6 && e.pick(2) == 11);   // 空：隨機
    ChannelScore a;
    a.add(1, -40);
    CHECK(a.pick(0) == 6 || a.pick(0) == 11);
    CHECK(a.score[1] == 0 && a.score[2] == 0 && a.pick(0) == 6 && a.pick(1) == 11);
    ChannelScore b;
    b.add(6, -60); b.add(11, -60);
    CHECK(b.pick(7) == 1);
    ChannelScore c;
    c.add(3, -50);                       // 3 干擾 1 與 6
    CHECK(c.pick(0) == 11);
    ChannelScore d;                      // 1 個強 AP vs 3 個很弱的 AP
    d.add(1, -30);
    d.add(6, -92); d.add(6, -92); d.add(6, -92);
    d.add(11, -45); d.add(11, -50);
    CHECK(d.pick(0) == 6);
    ChannelScore z;
    z.add(0, -40); z.add(15, -40);       // 不合法頻道忽略
    CHECK(z.apCount == 0);
}

// ================================================================ 熱點模式（§3.4 / §3.5 / §3.6）
static uint32_t ipv4(int a, int b, int c, int d) {   // lwIP u32（記憶體順序 a.b.c.d）
    return (uint32_t)a | ((uint32_t)b << 8) | ((uint32_t)c << 16) | ((uint32_t)d << 24);
}
static uint32_t ipStr(const std::string& s) {
    int a, b, c, d;
    std::sscanf(s.c_str(), "%d.%d.%d.%d", &a, &b, &c, &d);
    return ipv4(a, b, c, d);
}
static Bytes str(const std::string& s) { return Bytes(s.begin(), s.end()); }
static Bytes staSetPayload(int en, const Bytes& ssid, const Bytes& pw) {
    Bytes p;
    p.push_back((uint8_t)en);
    p.push_back((uint8_t)ssid.size()); p.insert(p.end(), ssid.begin(), ssid.end());
    p.push_back((uint8_t)pw.size()); p.insert(p.end(), pw.begin(), pw.end());
    return p;
}
static Bytes span(const uint8_t* p, size_t n) { return Bytes(p, p + n); }
static bool parsesBack(const Bytes& f) {
    Harness h;
    h.feed(f);
    return h.got.size() == 1 && h.got[0].raw == f && h.p.crcErrors == 0;
}

// §2 新增的測試向量（逐位元組）
static void testStaVectors() {
    int ok = 0, total = 0;
    auto vec = [&](const char* name, const Bytes& got, const char* want) {
        const bool eq = got == hex(want);
        CHECK(eq);
        CHECK(parsesBack(hex(want)));
        if (!eq) std::printf("  %s got  %s\n  want %s\n", name, tohex(got).c_str(), want);
        ok += eq;
        ++total;
    };
    const Bytes ss = staSetPayload(1, str("MyPhone"), str("pass1234"));
    vec("STA_SET", frame(T_STA_SET, 11, ss),
        "A5 5A 12 46 0B 01 07 4D 79 50 68 6F 6E 65 08 70 61 73 73 31 32 33 34 F8 9F");
    StaCfg cur, s;
    staClear(cur);
    CHECK(decodeStaSet(ss.data(), ss.size(), cur, s) == ST_OK && s.enabled == 1 &&
          std::strcmp(s.ssid, "MyPhone") == 0 && std::strcmp(s.pw, "pass1234") == 0 && staCfgOk(s));

    Bytes d; le32(d, 0x01020304);
    vec("DISCOVER", frame(T_DISCOVER, 12, d), "A5 5A 04 48 0C 04 03 02 01 93 39");

    uint8_t p[STA_INFO_MAX + 8];
    size_t n = encodeDiscoverAck(p, 0x01020304, 0x00A1B2C3, discoverMode(NET_STA), ipv4(172, 20, 10, 5), false,
                                 "B570-A1B2", 9);
    vec("DISCOVER_ACK", frame(T_DISCOVER_ACK, 12, span(p, n)),
        "A5 5A 18 C5 0C 04 03 02 01 C3 B2 A1 00 01 AC 14 0A 05 00 09 42 35 37 30 2D 41 31 42 32 59 56");

    n = encodeNet(p, NET_STA, ipv4(172, 20, 10, 5), "MyPhone", 7);
    vec("NET", frame(T_NET, 13, span(p, n)), "A5 5A 0D 53 0D 02 AC 14 0A 05 07 4D 79 50 68 6F 6E 65 63 91");
    std::printf("hotspot vectors: %d/%d byte-exact\n", ok, total);

    // 型別與路由
    CHECK(T_STA_SET == 0x46 && T_STA_GET == 0x47 && T_DISCOVER == 0x48 && T_NET == 0x53);
    CHECK(T_STA_INFO == 0xC4 && T_DISCOVER_ACK == 0xC5);
    CHECK(isAppToEsp(T_STA_SET) && isAppToEsp(T_STA_GET) && isAppToEsp(T_DISCOVER));
    CHECK(isEspStm(T_NET) && !isStmToApp(T_NET) && !isAppToStm(T_NET));
    CHECK(isEspToApp(T_STA_INFO) && isEspToApp(T_DISCOVER_ACK) && !isStmToApp(T_DISCOVER_ACK));

    // DISCOVER_ACK：直連模式、忙碌、AP IP
    n = encodeDiscoverAck(p, 0xDEADBEEF, 0x00123456, discoverMode(NET_FALLBACK), ipv4(192, 168, 4, 1), true,
                          "B570-3456", 9);
    CHECK(span(p, n) == hex("EF BE AD DE 56 34 12 00 00 C0 A8 04 01 01 09 42 35 37 30 2D 33 34 35 36"));
    CHECK(discoverMode(NET_AP) == 0 && discoverMode(NET_JOINING) == 0 && discoverMode(NET_FALLBACK) == 0 &&
          discoverMode(NET_STA) == 1);

    // NET：mode != 2 時 ip 一律 0（就算呼叫端傳了）；mode 0 帶 AP 名稱
    n = encodeNet(p, NET_FALLBACK, ipv4(10, 0, 0, 9), "MyPhone", 7);
    CHECK(span(p, n) == hex("03 00 00 00 00 07 4D 79 50 68 6F 6E 65"));
    n = encodeNet(p, NET_AP, 0, "B570-1A2B", 9);
    CHECK(span(p, n) == hex("00 00 00 00 00 09 42 35 37 30 2D 31 41 32 42"));
    CHECK(n <= NET_MAX);
    const std::string s32(32, 'x');
    n = encodeNet(p, NET_STA, ipv4(1, 2, 3, 4), s32.c_str(), 32);
    CHECK(n == NET_MAX && p[5] == 32);

    // STA_INFO（§3.4）
    StaCfg c;
    const Bytes ms = str("MyPhone"), mp = str("pass1234");
    staSet(c, 1, ms.data(), 7, mp.data(), 8);
    n = encodeStaInfo(p, c, NET_STA, ipv4(172, 20, 10, 5), -55, true);          // 擁有者：含密碼
    CHECK(span(p, n) == hex("01 02 AC 14 0A 05 C9 07 4D 79 50 68 6F 6E 65 08 70 61 73 73 31 32 33 34"));
    n = encodeStaInfo(p, c, NET_STA, ipv4(172, 20, 10, 5), -55, false);         // 其他人：pw_len 0
    CHECK(span(p, n) == hex("01 02 AC 14 0A 05 C9 07 4D 79 50 68 6F 6E 65 00"));
    n = encodeStaInfo(p, c, NET_FALLBACK, ipv4(172, 20, 10, 5), -55, false);    // state != 2：ip / rssi = 0
    CHECK(span(p, n) == hex("01 03 00 00 00 00 00 07 4D 79 50 68 6F 6E 65 00"));
    n = encodeStaInfo(p, c, NET_JOINING, 0, 0, true);
    CHECK(n == 24 && p[1] == 1 && p[6] == 0);
    StaCfg e;
    staClear(e);
    n = encodeStaInfo(p, e, NET_AP, 0, 0, true);                                 // 未啟用、沒存帳密
    CHECK(span(p, n) == hex("00 00 00 00 00 00 00 00 00"));
    const std::string p63(63, '~');
    staSet(c, 0, (const uint8_t*)s32.data(), 32, (const uint8_t*)p63.data(), 63);
    n = encodeStaInfo(p, c, NET_AP, 0, 0, true);
    CHECK(n == STA_INFO_MAX && p[0] == 0 && p[7] == 32 && p[40] == 63 && staCfgOk(c));
    CHECK(frame(T_STA_INFO, 1, span(p, n)).size() == OVERHEAD + STA_INFO_MAX);

    // TELEM 42 bytes（新增 ctrl_seq / ctrl_age）：ESP 原封轉送，不看長度
    Bytes tel(40, 0x11);
    tel.push_back(7);    // ctrl_seq
    tel.push_back(3);    // ctrl_age
    const Bytes tf = frame(T_TELEM, 5, tel);
    Harness h;
    h.feed(tf);
    CHECK(h.got.size() == 1 && h.got[0].payload.size() == 42 && h.got[0].raw == tf && isStmToApp(h.got[0].type));
}

// 回傳一個「後面緊接著不可存取頁」的緩衝區尾端（n bytes 可用）：越界讀取會立即 access violation
static uint8_t* guardTail(size_t n) {
#ifdef _WIN32
    static uint8_t* base = nullptr;
    if (!base) {
        base = (uint8_t*)VirtualAlloc(nullptr, 8192, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
        DWORD old;
        VirtualProtect(base + 4096, 4096, PAGE_NOACCESS, &old);
    }
    return base + 4096 - n;
#else
    static uint8_t buf[4096];
    return buf + sizeof buf - n;
#endif
}

// STA_SET 驗證（0x46）
static void testStaSet() {
    StaCfg cur, out;
    const Bytes ms = str("Saved"), mp = str("savedpw1");
    staSet(cur, 1, ms.data(), 5, mp.data(), 8);
    auto dec = [&](const Bytes& p) { return decodeStaSet(p.data(), p.size(), cur, out); };

    CHECK(dec(staSetPayload(1, str("A"), {})) == ST_OK && out.enabled == 1 && out.ssidLen == 1 && out.pwLen == 0);
    CHECK(dec(staSetPayload(1, str(std::string(32, 'a')), str(std::string(63, 'b')))) == ST_OK &&
          out.ssidLen == 32 && out.pwLen == 63 && out.ssid[32] == 0 && out.pw[63] == 0 && staCfgOk(out));
    CHECK(dec(staSetPayload(1, str(std::string(33, 'a')), str("12345678"))) == ST_BAD_ARG);   // SSID 33 bytes
    CHECK(dec(staSetPayload(1, {}, str("12345678"))) == ST_BAD_ARG);                          // 啟用但沒 SSID
    CHECK(dec(staSetPayload(1, {}, {})) == ST_BAD_ARG);
    CHECK(dec(staSetPayload(2, str("A"), {})) == ST_BAD_ARG);                                 // enable 只能 0/1
    CHECK(dec(staSetPayload(0xFF, str("A"), {})) == ST_BAD_ARG);
    // enable=0 + ssid_len 0：保留原本帳密、只停用
    CHECK(dec(staSetPayload(0, {}, {})) == ST_OK && out.enabled == 0 && std::strcmp(out.ssid, "Saved") == 0 &&
          std::strcmp(out.pw, "savedpw1") == 0 && staCfgOk(out));
    CHECK(dec(staSetPayload(0, {}, str("12345678"))) == ST_BAD_ARG);                          // 沒 SSID 卻帶密碼
    // enable=0 + 新帳密：存起來但不啟用
    CHECK(dec(staSetPayload(0, str("NewHot"), str("newpass9"))) == ST_OK && out.enabled == 0 &&
          std::strcmp(out.ssid, "NewHot") == 0 && std::strcmp(out.pw, "newpass9") == 0);
    // 密碼長度與字元
    for (int len = 1; len <= 70; ++len) {
        const bool want = len >= 8 && len <= 63;
        CHECK((dec(staSetPayload(1, str("Hot"), str(std::string((size_t)len, 'p')))) == ST_OK) == want);
    }
    for (int ch = 0; ch < 256; ++ch) {
        Bytes pw = str("abcdefg");
        pw.push_back((uint8_t)ch);
        CHECK((dec(staSetPayload(1, str("Hot"), pw)) == ST_OK) == (ch >= 0x20 && ch <= 0x7E));
    }
    CHECK(dec(staSetPayload(1, str("Hot"), str("pass word with ; , : \\ \" ok"))) == ST_OK);   // 熱點密碼可含空白與符號
    // SSID：UTF-8（中文 / emoji / 控制字元可、NUL 與非法序列不可）
    CHECK(dec(staSetPayload(1, str("我的手機熱點"), str("12345678"))) == ST_OK && out.ssidLen == 18);
    CHECK(dec(staSetPayload(1, str("Ming\xE2\x80\x99s iPhone"), {})) == ST_OK);
    CHECK(dec(staSetPayload(1, str("car \xF0\x9F\x9A\x97"), {})) == ST_OK);
    CHECK(dec(staSetPayload(1, Bytes{'a', 0x01, 'b'}, {})) == ST_OK);
    CHECK(dec(staSetPayload(1, Bytes{'a', 0x00, 'b'}, {})) == ST_BAD_ARG);
    CHECK(dec(staSetPayload(1, Bytes{0xC0, 0x80}, {})) == ST_BAD_ARG);           // 過長編碼的 NUL
    CHECK(dec(staSetPayload(1, Bytes{0xED, 0xA0, 0x80}, {})) == ST_BAD_ARG);     // 代理區
    CHECK(dec(staSetPayload(1, Bytes{'a', 0xE4, 0xB8}, {})) == ST_BAD_ARG);      // 截斷
    CHECK(dec(staSetPayload(1, Bytes{0xFF}, {})) == ST_BAD_ARG);
    // 32 bytes 的中文 SSID（10 字 = 30 bytes + "ab"）
    CHECK(dec(staSetPayload(1, str("一二三四五六七八九十ab"), {})) == ST_OK && out.ssidLen == 32);
    CHECK(dec(staSetPayload(1, str("一二三四五六七八九十abc"), {})) == ST_BAD_ARG);
    // 長度必須剛好
    Bytes p = staSetPayload(1, str("Hot"), str("12345678"));
    p.push_back(0);
    CHECK(dec(p) == ST_BAD_ARG);
    p = staSetPayload(1, str("Hot"), str("12345678"));
    for (size_t k = 0; k < p.size(); ++k) {   // 每個截斷長度都拒絕，且不讀超過 k
        uint8_t* g = guardTail(k);
        if (k) std::memcpy(g, p.data(), k);
        CHECK(decodeStaSet(g, k, cur, out) == ST_BAD_ARG);
    }
    CHECK(dec(Bytes{1, 5, 'a', 'b'}) == ST_BAD_ARG);                                 // ssid_len 超出 payload
    CHECK(dec(Bytes{1, 1, 'a', 9, '1', '2'}) == ST_BAD_ARG);                         // pw_len 超出 payload
    // 隨機 payload：不越界、OK 時結果一定自洽
    std::mt19937 rng(99);
    int okCount = 0;
    bool allSane = true;
    for (int i = 0; i < 200000; ++i) {
        Bytes r(rng() % 100);
        for (auto& x : r) x = (uint8_t)(rng() % 10 == 0 ? rng() : 0x20 + rng() % 95);   // 大多是可列印 ASCII
        if (r.size() >= 2 && rng() % 4) r[1] = (uint8_t)(rng() % 34);
        if (r.size() >= 3 && rng() % 4 && r[1] + 2u < r.size()) r[2u + r[1]] = (uint8_t)(r.size() - 3u - r[1]);
        if (!r.empty() && rng() % 4) r[0] = (uint8_t)(rng() % 2);
        StaCfg o;
        std::memset(&o, 0xAB, sizeof o);
        // payload 放在可讀頁的最尾端，下一頁不可存取：解碼器多讀 1 byte 就會當掉
        uint8_t* g = guardTail(r.size());
        if (!r.empty()) std::memcpy(g, r.data(), r.size());
        if (decodeStaSet(g, r.size(), cur, o) == ST_OK) {
            ++okCount;
            allSane = allSane && staCfgOk(o);
        }
    }
    std::printf("STA_SET random payloads: %d of 200000 accepted, all self-consistent: %s\n", okCount,
                allSane ? "yes" : "NO");
    CHECK(allSane && okCount > 1000);
}

// 與 Python（tools/b570_proto.py、嚴格 UTF-8 解碼器）的差分測試
static void testStaDifferential(const char* path) {
    std::ifstream in(path);
    if (!in) { std::printf("FAIL cannot open %s\n", path); ++g_fail; return; }
    int nU = 0, okU = 0, nN = 0, okN = 0, nD = 0, okD = 0, nS = 0, okS = 0, nValidUtf8 = 0;
    std::string line;
    StaCfg cur;
    const Bytes cs = str("OldHot"), cp = str("oldpass1");
    staSet(cur, 1, cs.data(), 6, cp.data(), 8);
    uint8_t p[STA_INFO_MAX];
    while (std::getline(in, line)) {
        std::istringstream ss(line);
        std::string tag;
        ss >> tag;
        if (tag == "U") {
            std::string h; int want;
            ss >> h >> want;
            const Bytes b = h == "-" ? Bytes() : hex(h);
            const bool got = utf8Ok(b.data(), b.size());
            const bool ssidWant = want && !b.empty() && b.size() <= 32;
            ++nU;
            nValidUtf8 += want;
            okU += (got == (want != 0)) && (staSsidOk(b.data(), b.size()) == ssidWant);
        } else if (tag == "N") {
            int mode; std::string ip, sh, fh;
            ss >> mode >> ip >> sh >> fh;
            const Bytes sb = sh == "-" ? Bytes() : hex(sh);
            const size_t n = encodeNet(p, (uint8_t)mode, ipStr(ip), (const char*)sb.data(), (uint8_t)sb.size());
            ++nN;
            okN += frame(T_NET, (uint8_t)(nN - 1), span(p, n)) == hex(fh);
        } else if (tag == "D") {
            unsigned long long nonce, chip; int mode, busy, seq; std::string ip, sh, fh;
            ss >> nonce >> chip >> mode >> ip >> busy >> sh >> seq >> fh;
            const Bytes sb = sh == "-" ? Bytes() : hex(sh);
            const size_t n = encodeDiscoverAck(p, (uint32_t)nonce, (uint32_t)chip, (uint8_t)mode, ipStr(ip), busy != 0,
                                               (const char*)sb.data(), (uint8_t)sb.size());
            ++nD;
            okD += frame(T_DISCOVER_ACK, (uint8_t)seq, span(p, n)) == hex(fh);
        } else if (tag == "S") {
            int en, seq; std::string sh, ph, fh;
            ss >> en >> sh >> ph >> seq >> fh;
            const Bytes sb = sh == "-" ? Bytes() : hex(sh), pb = ph == "-" ? Bytes() : hex(ph);
            Harness h;
            h.feed(hex(fh));
            bool ok = h.got.size() == 1 && h.got[0].type == T_STA_SET && h.got[0].seq == seq &&
                      h.got[0].payload == staSetPayload(en, sb, pb);
            StaCfg o;
            if (ok) ok = decodeStaSet(h.got[0].payload.data(), h.got[0].payload.size(), cur, o) == ST_OK && staCfgOk(o);
            if (ok && sb.empty()) ok = o.enabled == 0 && std::strcmp(o.ssid, "OldHot") == 0 && std::strcmp(o.pw, "oldpass1") == 0;
            else if (ok) ok = o.enabled == en && span((const uint8_t*)o.ssid, o.ssidLen) == sb &&
                              span((const uint8_t*)o.pw, o.pwLen) == pb;
            ++nS;
            okS += ok;
        }
    }
    CHECK(nU > 0 && okU == nU);
    CHECK(nN > 0 && okN == nN);
    CHECK(nD > 0 && okD == nD);
    CHECK(nS > 0 && okS == nS);
    std::printf("hotspot differential vs Python: UTF-8 %d/%d (%d valid), NET %d/%d, DISCOVER_ACK %d/%d, STA_SET %d/%d\n",
                okU, nU, nValidUtf8, okN, nN, okD, nD, okS, nS);
}

// 韌體 1.0 寫入 EEPROM 的方式（照抄舊版 encodeRecord；EEPROM 64 bytes，其餘位置是抹除值 0xFF）
static void encodeV1(uint8_t rec[REC_SIZE], const WifiCfg& c) {
    std::memset(rec, 0xFF, REC_SIZE);
    std::memset(rec, 0, REC_V1_SIZE);
    putU32(rec, REC_MAGIC);
    rec[4] = 1;
    rec[5] = c.ssidLen;
    std::memcpy(rec + 6, c.ssid, c.ssidLen);
    rec[18] = c.pwLen;
    std::memcpy(rec + 19, c.pw, c.pwLen);
    rec[35] = c.channel;
    putU16(rec + 36, crc16(rec, 36));
}

// EEPROM 紀錄 v2 與 v1 → v2 轉換
static void testRecordV2() {
    WifiCfg ap, r;
    StaCfg s, rs;
    Bytes p = wifiSet("B570-1A2B", "abcd2345", 6);
    CHECK(decodeWifiSet(p.data(), p.size(), ap));
    uint8_t rec[REC_SIZE];

    // v1 → 轉換：AP 帳密照用、熱點關閉
    encodeV1(rec, ap);
    std::memset(&rs, 0x5A, sizeof rs);
    CHECK(decodeRecord(rec, r, rs) == 1);
    CHECK(std::memcmp(&r, &ap, sizeof r) == 0 && rs.enabled == 0 && rs.ssidLen == 0 && rs.pwLen == 0 && staCfgOk(rs));
    CHECK(decodeRecord(rec, r));
    {   // v1 只看前 38 bytes：後面是什麼都沒關係；前 38 bytes 任何 1 bit 損壞都偵測得到
        uint8_t t[REC_SIZE];
        int ok = 0, bad = 0;
        for (size_t i = 0; i < REC_SIZE; ++i)
            for (int bit = 0; bit < 8; ++bit) {
                std::memcpy(t, rec, REC_SIZE);
                t[i] ^= (uint8_t)(1 << bit);
                const uint8_t v = decodeRecord(t, r, rs);
                if (i < REC_V1_SIZE) bad += v == 0;
                else ok += v == 1;
            }
        CHECK(bad == (int)REC_V1_SIZE * 8 && ok == (int)(REC_SIZE - REC_V1_SIZE) * 8);
    }
    {   // 轉換後存檔 → v2，AP 不變
        decodeRecord(rec, r, rs);
        uint8_t v2[REC_SIZE];
        encodeRecord(v2, r, rs);
        WifiCfg r2;
        StaCfg s2;
        CHECK(decodeRecord(v2, r2, s2) == 2 && std::memcmp(&r2, &ap, sizeof r2) == 0 && s2.enabled == 0);
        CHECK(std::memcmp(v2, rec, 36) != 0 && v2[4] == 2 && std::memcmp(v2 + 5, rec + 5, 31) == 0);
    }

    // v2 往返 + 版面
    const Bytes hs = str("我的手機熱點"), hp = str("pass word~ 123");
    staSet(s, 1, hs.data(), (uint8_t)hs.size(), hp.data(), (uint8_t)hp.size());
    CHECK(staCfgOk(s));
    encodeRecord(rec, ap, s);
    CHECK(getU32(rec) == REC_MAGIC && rec[4] == 2 && rec[5] == 9 && rec[35] == 6);
    CHECK(rec[36] == 1 && rec[37] == 18 && std::memcmp(rec + 38, hs.data(), 18) == 0 && rec[38 + 18] == 0);
    CHECK(rec[70] == 14 && std::memcmp(rec + 71, hp.data(), 14) == 0 && rec[71 + 14] == 0);
    CHECK((uint16_t)(rec[134] | (rec[135] << 8)) == crc16(rec, 134));
    CHECK(decodeRecord(rec, r, rs) == 2 && std::memcmp(&r, &ap, sizeof r) == 0 && std::memcmp(&rs, &s, sizeof rs) == 0);
    {   // v2 任何 1 bit 損壞都偵測得到
        uint8_t t[REC_SIZE];
        int bad = 0;
        for (size_t i = 0; i < REC_SIZE; ++i)
            for (int bit = 0; bit < 8; ++bit) {
                std::memcpy(t, rec, REC_SIZE);
                t[i] ^= (uint8_t)(1 << bit);
                bad += decodeRecord(t, r, rs) == 0;
            }
        CHECK(bad == (int)REC_SIZE * 8);
    }
    // 最長 SSID / 密碼、開放網路、停用但保留帳密
    const std::string s32(32, 'S'), p63(63, 'P');
    staSet(s, 1, (const uint8_t*)s32.data(), 32, (const uint8_t*)p63.data(), 63);
    encodeRecord(rec, ap, s);
    CHECK(decodeRecord(rec, r, rs) == 2 && std::memcmp(&rs, &s, sizeof rs) == 0);
    staSet(s, 1, (const uint8_t*)"OpenNet", 7, nullptr, 0);
    encodeRecord(rec, ap, s);
    CHECK(decodeRecord(rec, r, rs) == 2 && rs.enabled == 1 && rs.pwLen == 0 && std::strcmp(rs.ssid, "OpenNet") == 0);
    staSet(s, 0, (const uint8_t*)"KeepMe", 6, (const uint8_t*)"keeppass", 8);
    encodeRecord(rec, ap, s);
    CHECK(decodeRecord(rec, r, rs) == 2 && rs.enabled == 0 && std::strcmp(rs.ssid, "KeepMe") == 0 &&
          std::strcmp(rs.pw, "keeppass") == 0);
    // CRC 正確但熱點欄位不合法（不應發生）→ AP 照用、熱點清掉
    struct Bad { size_t off; uint8_t val; };
    for (const Bad& b : {Bad{36, 2}, Bad{37, 33}, Bad{37, 0}, Bad{70, 64}, Bad{70, 5}, Bad{38, 0}, Bad{71, 0x7F}}) {
        staSet(s, 1, (const uint8_t*)"GoodHot", 7, (const uint8_t*)"goodpass", 8);
        encodeRecord(rec, ap, s);
        rec[b.off] = b.val;
        putU16(rec + REC_CRC, crc16(rec, REC_CRC));
        std::memset(&rs, 0x5A, sizeof rs);
        CHECK(decodeRecord(rec, r, rs) == 2 && std::memcmp(&r, &ap, sizeof r) == 0 && rs.enabled == 0 &&
              rs.ssidLen == 0 && staCfgOk(rs));
    }
    // 舊呼叫（只有 AP）＝ 熱點關閉
    encodeRecord(rec, ap);
    CHECK(decodeRecord(rec, r, rs) == 2 && rs.enabled == 0 && rs.ssidLen == 0);
    // 版本 0 / 3 / v1 版面但版本寫 2 → 無效
    encodeRecord(rec, ap, s);
    rec[4] = 0; putU16(rec + REC_CRC, crc16(rec, REC_CRC));
    CHECK(decodeRecord(rec, r, rs) == 0);
    encodeV1(rec, ap);
    rec[4] = 2; putU16(rec + 36, crc16(rec, 36));
    CHECK(decodeRecord(rec, r, rs) == 0);
    CHECK(REC_SIZE == 136 && REC_V1_SIZE == 38 && REC_CRC == 134);
}

// 連線狀態機（§3.6）
static void testNetSm() {
    using A = NetSm::Action;
    const NetSm::StaErr kOk = NetSm::SE_NONE, kNf = NetSm::SE_NOT_FOUND, kPw = NetSm::SE_WRONG_PW;
    {   // 熱點模式未啟用：永遠直連、沒有任何動作
        NetSm sm;
        CHECK(sm.begin(false, 0) == NetSm::A_NONE && sm.mode() == NET_AP);
        bool none = true;
        for (uint32_t t = 0; t < 200000; t += 20)
            none = none && sm.step(t, (t / 1000) % 2, (uint8_t)(t % 3), t % 5 == 0, (NetSm::StaErr)(t % 3)) == NetSm::A_NONE;
        sm.apStarted(200000);
        CHECK(none && sm.mode() == NET_AP && !sm.fastRetry());
    }
    {   // 開機加入成功
        NetSm sm;
        CHECK(sm.begin(true, 1000) == NetSm::A_JOIN && sm.mode() == NET_JOINING);
        CHECK(sm.step(5000, false, 0, false, kNf) == NetSm::A_NONE && sm.mode() == NET_JOINING);   // JOIN 中 SDK 自己重試
        CHECK(sm.step(5500, false, 0, false, kPw) == NetSm::A_NONE && !sm.wrongPassword());
        CHECK(sm.step(6000, true, 0, false) == NetSm::A_STA_UP && sm.mode() == NET_STA);
        CHECK(sm.step(6020, true, 0, true) == NetSm::A_NONE && sm.mode() == NET_STA);
        bool none = true;   // 連線中不管 AP（已關閉）與 session 都不動作
        for (uint32_t t = 6040; t < 300000; t += 20) none = none && sm.step(t, true, 0, t % 3 == 0) == NetSm::A_NONE;
        CHECK(none && sm.mode() == NET_STA);
    }
    {   // 開機 12 s 加入失敗 → AP（NET 3）；第一次重試等 AP 閒置 12 s（QR 剛出現）；重試成功 → STA
        NetSm sm;
        sm.begin(true, 0);
        CHECK(!sm.fastRetry());
        CHECK(sm.step(11999, false, 0, false, kNf) == NetSm::A_NONE && sm.mode() == NET_JOINING);
        CHECK(sm.step(12000, false, 0, false) == NetSm::A_START_AP && sm.mode() == NET_FALLBACK);
        CHECK(sm.fastRetry() && sm.retryIdleMs() == NetSm::FIRST_IDLE_MS);
        CHECK(sm.step(12000 + 11999, false, 0, false) == NetSm::A_NONE && !sm.retrying());
        CHECK(sm.step(24000, false, 0, false) == NetSm::A_RETRY && sm.retrying() && sm.mode() == NET_FALLBACK);
        CHECK(sm.retryIdleMs() == NetSm::FAST_IDLE_MS);                // 之後每次 6 s
        CHECK(sm.step(27000, false, 0, false) == NetSm::A_NONE && sm.mode() == NET_FALLBACK);   // 重試中 OLED 仍顯示 QR
        CHECK(sm.step(28000, true, 0, false) == NetSm::A_STA_UP && sm.mode() == NET_STA && !sm.retrying());
        CHECK(!sm.fastRetry());
    }
    {   // 改開 AP 要先選頻（AP 晚 1.5 s 才開）：第一次重試從 AP 真正開起來算（apStarted）
        NetSm sm;
        sm.begin(true, 0);
        sm.apStarted(5000);                                              // JOIN 中呼叫：沒有作用
        CHECK(sm.step(11999, false, 0, false) == NetSm::A_NONE);
        CHECK(sm.step(12000, false, 0, false) == NetSm::A_START_AP);
        CHECK(sm.step(12020, false, 0, false) == NetSm::A_NONE);
        sm.apStarted(13500);
        CHECK(sm.step(25499, false, 0, false) == NetSm::A_NONE);
        CHECK(sm.step(25500, false, 0, false) == NetSm::A_RETRY);
        sm.apStarted(30000);                                             // 重試中呼叫：不影響重試的時間上限
        CHECK(sm.step(33499, false, 0, false) == NetSm::A_NONE && sm.retrying());
        CHECK(sm.step(33500, false, 0, false) == NetSm::A_ABORT_RETRY);
    }
    {   // 重試 8 s 逾時（SDK 一直在連線中）→ 回純 AP；快速重試期內下一次在 6 s 後
        NetSm sm;
        sm.begin(true, 0);
        sm.step(12000, false, 0, false);
        CHECK(sm.step(24000, false, 0, false) == NetSm::A_RETRY);
        CHECK(sm.step(31999, false, 0, false, kOk) == NetSm::A_NONE && sm.retrying());
        CHECK(sm.step(32000, false, 0, false) == NetSm::A_ABORT_RETRY && !sm.retrying() && sm.mode() == NET_FALLBACK);
        CHECK(sm.step(37999, false, 0, false) == NetSm::A_NONE);
        CHECK(sm.step(38000, false, 0, false) == NetSm::A_RETRY);
    }
    {   // SDK 回報找不到熱點：提早放棄（不等滿 8 s）；剛 WiFi.begin 的 0.5 s 內不採信（可能是上一次留下的狀態）
        NetSm sm;
        sm.begin(true, 0);
        sm.step(12000, false, 0, false);
        CHECK(sm.step(24000, false, 0, false) == NetSm::A_RETRY);
        CHECK(sm.step(24020, false, 0, false, kNf) == NetSm::A_NONE && sm.retrying());
        CHECK(sm.step(24499, false, 0, false, kNf) == NetSm::A_NONE && sm.retrying());
        CHECK(sm.step(24500, false, 0, false, kNf) == NetSm::A_ABORT_RETRY && !sm.retrying() && sm.fastRetry());
        CHECK(sm.step(30499, false, 0, false) == NetSm::A_NONE);
        CHECK(sm.step(30500, false, 0, false) == NetSm::A_RETRY);
        // 找到熱點、SDK 連線中（SE_NONE）：照樣給滿 8 s
        CHECK(sm.step(33000, false, 0, false, kOk) == NetSm::A_NONE && sm.retrying());
        CHECK(sm.step(38499, false, 0, false, kOk) == NetSm::A_NONE);
        CHECK(sm.step(38500, false, 0, false, kOk) == NetSm::A_ABORT_RETRY);
        // 連線失敗（STATION_CONNECT_FAIL）也一樣
        CHECK(sm.step(44500, false, 0, false) == NetSm::A_RETRY);
        CHECK(sm.step(46000, false, 0, false, kNf) == NetSm::A_ABORT_RETRY && !sm.wrongPassword());
        // 同一步連上熱點又帶著舊的失敗狀態：連上優先
        CHECK(sm.step(52000, false, 0, false) == NetSm::A_RETRY);
        CHECK(sm.step(53000, true, 0, false, kNf) == NetSm::A_STA_UP && sm.mode() == NET_STA);
    }
    // 先開車子、後開熱點（戶外）：模擬 SDK——熱點不在時每次 WiFi.begin 後 2 s 回報找不到；
    // 熱點出現後，之後開始的重試 3 s 內連上（開始時熱點還不在的那次照樣找不到）
    auto outdoor = [](uint32_t hotspotAt, std::vector<uint32_t>* tries, uint32_t* retryMs, uint32_t apUpDelay) -> uint32_t {
        NetSm sm;
        sm.begin(true, 0);
        uint32_t retryStart = 0, offMs = 0;
        bool pending = false;
        for (uint32_t t = 20; t <= 400000; t += 20) {
            if (pending && t >= 12000 + apUpDelay) {
                pending = false;
                sm.apStarted(t);
            }
            const bool r = sm.retrying();
            const uint32_t in = t - retryStart;
            const bool up = r && retryStart >= hotspotAt && in >= 3000;
            const NetSm::StaErr err = r && retryStart < hotspotAt && in >= 2000 ? NetSm::SE_NOT_FOUND : NetSm::SE_NONE;
            if (r && t >= 12000 && t < 12000 + NetSm::FAST_WINDOW_MS) offMs += 20;
            const A a = sm.step(t, up, 0, false, err);
            if (a == NetSm::A_START_AP) pending = apUpDelay > 0;
            if (a == NetSm::A_RETRY) {
                retryStart = t;
                if (tries) tries->push_back(t);
            }
            if (a == NetSm::A_STA_UP) {
                if (retryMs) *retryMs = offMs;
                return t;
            }
        }
        if (retryMs) *retryMs = offMs;
        return 0;
    };
    {   // 熱點一直沒開：快速期（12–192 s）每 8 s 一輪（12 s 後第一次，之後閒置 6 s + 找 2 s），之後 30 s + 2 s
        std::vector<uint32_t> tries, want;
        uint32_t offMs = 0;
        CHECK(outdoor(0xFFFFFFFFu, &tries, &offMs, 0) == 0);
        for (uint32_t t = 24000; t < 192000; t += 8000) want.push_back(t);   // 21 次
        for (uint32_t t = 216000; t <= 400000; t += 32000) want.push_back(t);
        CHECK(want.size() == 27 && tries == want);
        // 快速期內射頻離開 AP 頻道的比例：21 × 2 s / 180 s ≈ 23%（1.1.1 第一版每 14 s 試滿 8 s ≈ 57%）
        CHECK(offMs == 21u * 2000u && offMs * 100u / NetSm::FAST_WINDOW_MS <= 25u);
        std::printf("NetSm outdoor: %zu retries in 400 s, %u%% off-channel in the fast window\n", tries.size(),
                    (unsigned)(offMs * 100u / NetSm::FAST_WINDOW_MS));
    }
    {   // 熱點在快速期內任何時候打開：最久 15 s 內連上（模擬的加入時間 3 s；第一次重試前等最久）
        uint32_t worst = 0, worstAfterFirst = 0;
        for (uint32_t at = 12020; at < 180000; at += 260) {
            const uint32_t up = outdoor(at, nullptr, nullptr, 0);
            CHECK(up > at);
            const uint32_t lat = up - at;
            worst = std::max(worst, lat);
            if (at > 24000) worstAfterFirst = std::max(worstAfterFirst, lat);
        }
        CHECK(worst <= NetSm::FIRST_IDLE_MS + 3000);
        CHECK(worstAfterFirst <= 2000 + NetSm::FAST_IDLE_MS + 3000);
        // AP 晚 1.5 s 才開（選頻掃描）：第一次重試跟著延後 1.5 s
        CHECK(outdoor(12020, nullptr, nullptr, 1500) == 12000 + 1500 + NetSm::FIRST_IDLE_MS + 3000);
        std::printf("NetSm outdoor: worst join %u ms after the hotspot appears (%u ms after the first retry)\n",
                    (unsigned)worst, (unsigned)worstAfterFirst);
    }
    {   // 密碼錯誤：連續 2 次才放棄快速重試（訊號弱時 handshake 逾時也會回報密碼錯誤）
        NetSm sm;
        sm.begin(true, 0);
        sm.step(12000, false, 0, false);
        CHECK(sm.step(24000, false, 0, false) == NetSm::A_RETRY);
        CHECK(sm.step(26500, false, 0, false, kPw) == NetSm::A_ABORT_RETRY && sm.fastRetry() && !sm.wrongPassword());
        CHECK(sm.step(32500, false, 0, false) == NetSm::A_RETRY);
        CHECK(sm.step(34500, false, 0, false, kNf) == NetSm::A_ABORT_RETRY);    // 找不到 → 重新計算
        CHECK(sm.step(40500, false, 0, false) == NetSm::A_RETRY);
        CHECK(sm.step(43000, false, 0, false, kPw) == NetSm::A_ABORT_RETRY && sm.fastRetry());
        CHECK(sm.step(49000, false, 0, false) == NetSm::A_RETRY);
        CHECK(sm.step(51500, false, 0, false, kPw) == NetSm::A_ABORT_RETRY);    // 連續第 2 次
        CHECK(!sm.fastRetry() && sm.wrongPassword() && sm.retryIdleMs() == NetSm::RETRY_IDLE_MS);
        CHECK(sm.step(81499, false, 0, false) == NetSm::A_NONE);
        CHECK(sm.step(81500, false, 0, false) == NetSm::A_RETRY);
        CHECK(sm.step(84000, false, 0, false, kPw) == NetSm::A_ABORT_RETRY);
        // 手機連上又離開：密碼錯誤時不重新開始快速期
        CHECK(sm.step(85000, false, 1, false) == NetSm::A_NONE);
        CHECK(sm.step(90000, false, 0, false) == NetSm::A_NONE && !sm.fastRetry());
        CHECK(sm.step(114999, false, 0, false) == NetSm::A_NONE);         // 閒置從手機離開前最後一次看到（85 s）起算
        CHECK(sm.step(115000, false, 0, false) == NetSm::A_RETRY);
        // 熱點密碼改對了：連上 → 清除；之後熱點斷線 → 又有快速期
        CHECK(sm.step(118000, true, 0, false) == NetSm::A_STA_UP && !sm.wrongPassword());
        CHECK(sm.step(200000, false, 0, false) == NetSm::A_NONE);
        CHECK(sm.step(215000, false, 0, false) == NetSm::A_START_AP && sm.fastRetry());
    }
    {   // 一直有手機連在 AP 上直到快速期結束前：手機離開 → 重新開始 60 s 快速期（不是等 30 s）
        NetSm sm;
        sm.begin(true, 0);
        sm.step(12000, false, 0, false);                                 // 快速期到 192 s
        bool none = true;
        for (uint32_t t = 12020; t <= 189000; t += 20) none = none && sm.step(t, false, 1, false) == NetSm::A_NONE;
        CHECK(none && sm.fastRetry());                                   // 手機連在 AP 上：完全不重試
        CHECK(sm.step(191980, false, 0, false) == NetSm::A_NONE && sm.fastRetry());
        CHECK(sm.retryIdleMs() == NetSm::FAST_IDLE_MS);                 // 有手機用過 AP：不再等 12 s
        CHECK(sm.step(193000, false, 0, false) == NetSm::A_NONE && sm.fastRetry());   // 原本的 3 分鐘已過，新的 60 s 還在
        std::vector<uint32_t> tries;
        for (uint32_t t = 193020; t <= 300000; t += 20) {
            if (sm.step(t, false, 0, false) == NetSm::A_RETRY) tries.push_back(t);
        }
        // 189 s + 6 s，之後每 14 s（重試滿 8 s）到 191.98 s + 60 s，再來 30 s
        CHECK((tries == std::vector<uint32_t>{195000, 209000, 223000, 237000, 251000, 289000}));
        CHECK(!sm.fastRetry());
    }
    {   // 手機在快速期內離開 AP：仍是 6 s
        NetSm sm;
        sm.begin(true, 0);
        sm.step(12000, false, 0, false);
        bool none = true;
        for (uint32_t t = 12020; t <= 180000; t += 20) none = none && sm.step(t, false, 1, t > 60000) == NetSm::A_NONE;
        CHECK(none);
        CHECK(sm.step(185999, false, 0, false) == NetSm::A_NONE);
        CHECK(sm.step(186000, false, 0, false) == NetSm::A_RETRY && sm.fastRetry());
    }
    {   // 手機離開時快速期還剩很多：不縮短（仍到 192 s 結束）
        NetSm sm;
        sm.begin(true, 0);
        sm.step(12000, false, 0, false);
        for (uint32_t t = 12020; t <= 40000; t += 20) sm.step(t, false, 1, false);
        bool fastOk = true;
        for (uint32_t t = 40020; t <= 260000; t += 20) {
            sm.step(t, false, 0, false);
            fastOk = fastOk && sm.fastRetry() == (t < 12000 + NetSm::FAST_WINDOW_MS);
        }
        CHECK(fastOk);
    }
    {   // AP 有手機 / 有 session → 不重試；快速期過了才離開 → 重新開始 60 s 快速期，6 s 後重試
        NetSm sm;
        sm.begin(true, 0);
        sm.step(12000, false, 0, false);
        bool none = true;
        for (uint32_t t = 12020; t <= 312000; t += 20) none = none && sm.step(t, false, 1, t > 100000) == NetSm::A_NONE;
        CHECK(none && !sm.fastRetry());
        none = true;
        for (uint32_t t = 312020; t <= 400000; t += 20) none = none && sm.step(t, false, 0, true) == NetSm::A_NONE;
        CHECK(none && !sm.fastRetry());   // 只有 session（例如手機剛斷 Wi-Fi、session 還沒逾時）也不重試
        CHECK(sm.step(400020, false, 0, false) == NetSm::A_NONE && sm.fastRetry());
        CHECK(sm.step(405999, false, 0, false) == NetSm::A_NONE);   // 閒置從最後一次看到有人（400000）起算
        CHECK(sm.step(406000, false, 0, false) == NetSm::A_RETRY);
    }
    {   // 重試中有手機連上 AP → 立即讓出（不阻塞）；AP 閒置後恢復純 AP，快速期內再 6 s 重試
        NetSm sm;
        sm.begin(true, 0);
        sm.step(12000, false, 0, false);
        CHECK(sm.step(24000, false, 0, false) == NetSm::A_RETRY);
        CHECK(sm.step(25000, true, 1, false, kNf) == NetSm::A_YIELD_RETRY && sm.mode() == NET_FALLBACK && sm.lingering());
        CHECK(sm.step(25020, false, 1, true) == NetSm::A_NONE && sm.lingering());
        CHECK(sm.step(90000, false, 0, true) == NetSm::A_NONE);
        CHECK(sm.step(90020, false, 0, false) == NetSm::A_RESTORE_AP && !sm.lingering());
        CHECK(sm.step(90040, false, 0, false) == NetSm::A_NONE);
        CHECK(sm.step(96019, false, 0, false) == NetSm::A_NONE);
        CHECK(sm.step(96020, false, 0, false) == NetSm::A_RETRY);
        CHECK(sm.step(96040, false, 0, true) == NetSm::A_YIELD_RETRY);   // 只有 session 也讓出
    }
    {   // 同上，快速期已過：手機離開 → 恢復純 AP，並重新開始 60 s 快速期
        NetSm sm;
        sm.begin(true, 0);
        sm.step(12000, false, 0, false);
        CHECK(sm.step(24000, false, 0, false) == NetSm::A_RETRY);
        CHECK(sm.step(25000, false, 2, false) == NetSm::A_YIELD_RETRY);
        CHECK(sm.step(250000, false, 1, true) == NetSm::A_NONE && sm.lingering() && !sm.fastRetry());
        CHECK(sm.step(250020, false, 0, false) == NetSm::A_RESTORE_AP && sm.fastRetry());
        CHECK(sm.step(256019, false, 0, false) == NetSm::A_NONE);
        CHECK(sm.step(256020, false, 0, false) == NetSm::A_RETRY);
    }
    {   // 熱點斷線：15 s 內恢復 → 繼續 STA；超過 15 s → AP，並開始快速重試期（LOST 中的密碼錯誤不採信）
        NetSm sm;
        sm.begin(true, 0);
        sm.step(3000, true, 0, false);
        CHECK(sm.step(10000, false, 0, false, kPw) == NetSm::A_NONE && sm.mode() == NET_JOINING);
        CHECK(sm.step(24999, false, 0, false, kPw) == NetSm::A_NONE);
        CHECK(sm.step(25000, true, 0, false) == NetSm::A_NONE && sm.mode() == NET_STA);   // 自動重連成功
        CHECK(sm.step(30000, false, 0, false, kNf) == NetSm::A_NONE && sm.mode() == NET_JOINING);  // 計時重新開始
        CHECK(sm.step(44999, false, 0, false, kPw) == NetSm::A_NONE);
        CHECK(sm.step(45000, false, 0, false) == NetSm::A_START_AP && sm.mode() == NET_FALLBACK && sm.fastRetry());
        CHECK(!sm.wrongPassword() && sm.retryIdleMs() == NetSm::FIRST_IDLE_MS);
        CHECK(sm.step(56999, false, 0, false) == NetSm::A_NONE);
        CHECK(sm.step(57000, false, 0, false) == NetSm::A_RETRY);
    }
    {   // 開車途中走出熱點範圍（再次進入 FALLBACK）：快速重試期從這次改開 AP 重新算
        NetSm sm;
        sm.begin(true, 0);
        sm.step(12000, false, 0, false);                                 // 第一次 FALLBACK（快速期到 192 s）
        bool none = true;
        for (uint32_t t = 12020; t <= 500000; t += 20) none = none && sm.step(t, false, 1, true) == NetSm::A_NONE;
        CHECK(none && !sm.fastRetry());                                  // 用直連開了一陣子
        CHECK(sm.step(530000, false, 0, false) == NetSm::A_RETRY && sm.fastRetry());   // 手機離開：60 s 快速期，已閒置 30 s
        CHECK(sm.step(533000, true, 0, false) == NetSm::A_STA_UP && sm.mode() == NET_STA);
        CHECK(sm.step(900000, false, 0, false) == NetSm::A_NONE && sm.mode() == NET_JOINING);   // 熱點斷線
        CHECK(sm.step(915000, false, 0, false) == NetSm::A_START_AP && sm.fastRetry());
        CHECK(sm.step(926999, false, 0, false) == NetSm::A_NONE);
        CHECK(sm.step(927000, false, 0, false) == NetSm::A_RETRY);       // 第一次 12 s
        CHECK(sm.step(929000, false, 0, false, kNf) == NetSm::A_ABORT_RETRY);
        CHECK(sm.step(935000, false, 0, false) == NetSm::A_RETRY);       // 之後 6 s
        CHECK(sm.step(937500, true, 0, false) == NetSm::A_STA_UP && sm.mode() == NET_STA);   // 走回範圍內
    }
    {   // millis() 溢位：加入跨過 0
        NetSm sm;
        sm.begin(true, 0xFFFFF000u);
        CHECK(sm.step(0xFFFFF000u + 11999u, false, 0, false) == NetSm::A_NONE);
        CHECK(sm.step(0xFFFFF000u + 12000u, false, 0, false) == NetSm::A_START_AP);
        const uint32_t fb = 0xFFFFF000u + 12000u;
        CHECK(sm.step(fb + 11999u, false, 0, false) == NetSm::A_NONE);
        CHECK(sm.step(fb + 12000u, false, 0, false) == NetSm::A_RETRY && sm.fastRetry());
        CHECK(sm.step(fb + 20000u, false, 0, false) == NetSm::A_ABORT_RETRY);
    }
    {   // millis() 溢位：快速重試期、提早放棄、重新開始的 60 s 都跨過 0
        NetSm sm;
        const uint32_t b = 0xFFFE0000u, fb = b + 12000u;                 // 快速期在 fb + 180 s（已溢位）結束
        sm.begin(true, b);
        CHECK(sm.step(fb, false, 0, false) == NetSm::A_START_AP);
        sm.apStarted(fb + 1000u);
        CHECK(sm.step(fb + 12999u, false, 0, false) == NetSm::A_NONE);
        CHECK(sm.step(fb + 13000u, false, 0, false) == NetSm::A_RETRY && sm.fastRetry());
        uint32_t t = fb + 13000u, r = t;
        int tries = 1;
        while ((uint32_t)(t - fb) < 170000u) {                           // 熱點不在：每 8 s 一輪，跨過 0 也一樣
            t += 20;
            const A a = sm.step(t, false, 0, false, (uint32_t)(t - r) >= 2000u ? kNf : kOk);
            if (a == NetSm::A_RETRY) {
                CHECK((uint32_t)(t - r) == 8000u);
                r = t;
                ++tries;
            }
        }
        CHECK(tries == 20 && (uint32_t)(r - fb) > (uint32_t)(0u - fb));   // 有一次在溢位後
        CHECK(sm.step(fb + NetSm::FAST_WINDOW_MS - 1u, false, 1, false) == NetSm::A_NONE && sm.fastRetry());
        CHECK(sm.step(fb + NetSm::FAST_WINDOW_MS, false, 1, false) == NetSm::A_NONE && !sm.fastRetry());
        const uint32_t leave = fb + NetSm::FAST_WINDOW_MS + 20u;
        CHECK(sm.step(leave, false, 0, false) == NetSm::A_NONE && sm.fastRetry());
        CHECK(sm.step(leave + NetSm::REARM_WINDOW_MS - 1u, false, 1, false) == NetSm::A_NONE && sm.fastRetry());
        CHECK(sm.step(leave + NetSm::REARM_WINDOW_MS, false, 1, false) == NetSm::A_NONE && !sm.fastRetry());
    }
    {   // millis() 繞一圈（49.7 天）回到「看起來剛改開 AP」的值：3 分鐘快速期不會自己重新開始；
        // 手機離開才重新開始，而且只有 60 s
        NetSm sm;
        sm.begin(true, 0);
        sm.step(12000, false, 0, false);
        bool none = true;
        uint32_t t = 12000;
        for (uint64_t k = 0; k < (1ull << 32) / 1000 + 10; ++k) {
            t += 1000;
            none = none && sm.step(t, false, 1, false) == NetSm::A_NONE;
        }
        CHECK(none && (uint32_t)(t - 12000) < NetSm::FAST_WINDOW_MS && !sm.fastRetry());
        const uint32_t leave = t + 20;
        CHECK(sm.step(leave, false, 0, false) == NetSm::A_NONE && sm.fastRetry());
        CHECK(sm.step(t + 5999, false, 0, false) == NetSm::A_NONE);
        CHECK(sm.step(t + 6000, false, 0, false) == NetSm::A_RETRY);
        CHECK(sm.step(t + 14000, false, 0, false) == NetSm::A_ABORT_RETRY);
        CHECK(sm.step(leave + NetSm::REARM_WINDOW_MS - 1, false, 1, false) == NetSm::A_NONE && sm.fastRetry());
        CHECK(sm.step(leave + NetSm::REARM_WINDOW_MS, false, 1, false) == NetSm::A_NONE && !sm.fastRetry());
    }
    {   // 隨機事件：動作序列必須和射頻狀態一致，且會阻塞的動作只在 AP 閒置時發出；
        // FALLBACK / RETRY 時的動作與參考模型完全相同（快速期、第一次 12 s、手機離開重新開始、提早放棄、密碼錯誤）
        std::mt19937 rng(2026);
        int violations = 0, actions = 0, retries = 0, fastRetries = 0, firstRetries = 0, staUps = 0;
        int early = 0, rearms = 0, bans = 0, pendingAps = 0;
        for (int run = 0; run < 300; ++run) {
            NetSm sm;
            uint32_t t = rng();
            bool apOn = false, staOn = false, staUp = false, apPending = false;
            uint32_t apUpAt = 0;
            uint8_t stations = 0;
            bool session = false;
            NetSm::StaErr err = NetSm::SE_NONE;
            uint32_t idleSince = t, lastActMs = t, retryStart = t;
            // 參考模型
            bool mFast = false, mFirst = false, mWasBusy = false, mBan = false;
            uint32_t mSince = t, mLen = NetSm::FAST_WINDOW_MS;
            uint8_t mStrikes = 0;
            const bool en = run % 10 != 0;
            const bool phones = run % 3 != 0;          // 1/3 的情況沒有任何手機連 AP（快速期會自己結束）
            const bool pwRun = run % 5 == 1;           // 熱點密碼打錯
            if (sm.begin(en, t) == NetSm::A_JOIN) staOn = true;
            else apOn = true;
            for (int i = 0; i < 20000; ++i) {
                t += 10 + rng() % 200;
                if (apPending && (int32_t)(t - apUpAt) >= 0) {   // 選頻掃描結束，AP 開起來
                    apPending = false;
                    apOn = true;
                    sm.apStarted(t);
                    lastActMs = t;
                }
                // 環境：只有開著的介面才可能有連線
                if (rng() % 50 == 0) staUp = (staOn && !staUp) ? (rng() % 2 == 0) : false;
                if (!staOn) staUp = false;
                if (rng() % 40 == 0) err = pwRun ? NetSm::SE_WRONG_PW : (NetSm::StaErr)(rng() % 3);
                if (!staOn || staUp) err = NetSm::SE_NONE;
                if (rng() % 80 == 0) stations = (uint8_t)(apOn && phones ? rng() % 3 : 0);
                if (!apOn) stations = 0;
                // session 只可能來自 AP 上的手機，或已連上的熱點（韌體在離開 NET_STA 時立即結束 session）
                const bool canSession = (apOn && stations > 0) || (staUp && sm.mode() == NET_STA);
                if (rng() % 60 == 0) session = canSession && rng() % 2 == 0;
                if (!canSession) session = false;
                const bool busy = stations > 0 || session;
                if (busy) idleSince = t;
                const uint8_t before = sm.mode();
                const bool wasFb = before == NET_FALLBACK && !sm.retrying(), wasRetry = sm.retrying();
                const bool wasLinger = sm.lingering();
                const uint32_t sinceBusy = t - idleSince, sinceAct = t - lastActMs;
                const uint32_t quiet = sinceBusy < sinceAct ? sinceBusy : sinceAct;   // AP 連續閒置多久
                // 參考模型：這一步應該發出什麼動作
                if (mFast && (uint32_t)(t - mSince) >= mLen) mFast = false;
                A expect = NetSm::A_NONE;
                uint32_t need = 0;
                if (wasFb) {
                    if (busy) {
                        mWasBusy = true;
                        mFirst = false;
                    } else {
                        if (mWasBusy) {
                            mWasBusy = false;
                            if (!mBan && !(mFast && mLen - (uint32_t)(t - mSince) >= NetSm::REARM_WINDOW_MS)) {
                                mFast = true;
                                mSince = t;
                                mLen = NetSm::REARM_WINDOW_MS;
                                ++rearms;
                            }
                        }
                        need = !mFast ? NetSm::RETRY_IDLE_MS : mFirst ? NetSm::FIRST_IDLE_MS : NetSm::FAST_IDLE_MS;
                        if (wasLinger) expect = NetSm::A_RESTORE_AP;
                        else if (quiet >= need) {
                            expect = NetSm::A_RETRY;
                            mFirst = false;
                        }
                    }
                } else if (wasRetry) {
                    const uint32_t in = t - retryStart;
                    if (busy) {
                        expect = NetSm::A_YIELD_RETRY;
                        mWasBusy = true;
                    } else if (staUp) {
                        expect = NetSm::A_STA_UP;
                    } else if (err != NetSm::SE_NONE && in >= NetSm::RETRY_MIN_MS) {
                        expect = NetSm::A_ABORT_RETRY;
                        ++early;
                        if (err == NetSm::SE_WRONG_PW) {
                            if (mStrikes < NetSm::WRONG_PW_STRIKES) ++mStrikes;
                            if (mStrikes >= NetSm::WRONG_PW_STRIKES) {
                                bans += !mBan;
                                mBan = true;
                                mFast = false;
                            }
                        } else {
                            mStrikes = 0;
                        }
                    } else if (in >= NetSm::RETRY_BUDGET_MS) {
                        expect = NetSm::A_ABORT_RETRY;
                        mStrikes = 0;
                    }
                }
                const A a = sm.step(t, staUp, stations, session, err);
                if (a != NetSm::A_NONE) ++actions;
                if (wasFb || wasRetry) violations += a != expect;
                switch (a) {
                case NetSm::A_STA_UP:
                    ++staUps;
                    violations += !staOn || !staUp;
                    if (apOn) violations += busy;                 // 從重試切過來：AP 必須閒置
                    apOn = false;
                    apPending = false;
                    mFast = mFirst = mWasBusy = mBan = false;
                    mStrikes = 0;
                    break;
                case NetSm::A_START_AP:
                    violations += apOn || apPending || session || stations;
                    violations += before == NET_STA;              // 先經過 LOST
                    staOn = false; staUp = false; err = NetSm::SE_NONE;
                    if (rng() % 2) {
                        apOn = true;                              // 頻道已決定：同一圈開 AP
                    } else {
                        apPending = true;                         // 要先選頻：最多 1.5 s 後才開
                        apUpAt = t + 20 + rng() % 1500;
                        ++pendingAps;
                    }
                    idleSince = t;
                    mFast = true;
                    mFirst = true;
                    mWasBusy = false;
                    mSince = t;
                    mLen = NetSm::FAST_WINDOW_MS;
                    break;
                case NetSm::A_RETRY:
                    ++retries;
                    fastRetries += need == NetSm::FAST_IDLE_MS;
                    firstRetries += need == NetSm::FIRST_IDLE_MS;
                    violations += !apOn || staOn || busy || quiet < need || quiet < NetSm::FAST_IDLE_MS;
                    staOn = true;
                    retryStart = t;
                    break;
                case NetSm::A_ABORT_RETRY:
                case NetSm::A_RESTORE_AP:
                    violations += !apOn || !staOn || busy;
                    staOn = false; staUp = false; err = NetSm::SE_NONE;
                    break;
                case NetSm::A_YIELD_RETRY:
                    violations += !apOn || !staOn || !busy;
                    staUp = false;                                // STA 已斷開（介面仍開著）
                    err = NetSm::SE_NONE;
                    break;
                case NetSm::A_JOIN:
                    ++violations;                                 // 只會由 begin() 回傳
                    break;
                default:
                    break;
                }
                if (a != NetSm::A_NONE) lastActMs = t;
                violations += sm.mode() > NET_FALLBACK;
                violations += (sm.mode() == NET_STA) != (staOn && !apOn && staUp);
                if (!en) violations += a != NetSm::A_NONE || sm.mode() != NET_AP;
                // 快速重試期只存在於 FALLBACK（含 RETRY），且和參考模型一致
                if (sm.mode() == NET_FALLBACK) {
                    violations += sm.fastRetry() != mFast;
                    violations += sm.wrongPassword() != mBan;
                } else {
                    violations += sm.fastRetry();
                }
            }
        }
        const int slow = retries - fastRetries - firstRetries;
        CHECK(violations == 0 && actions > 1000 && retries > 100 && staUps > 100);
        CHECK(fastRetries > 100 && firstRetries > 100 && slow > 100);   // 三種間隔都有足夠樣本
        CHECK(early > 100 && rearms > 100 && bans > 10 && pendingAps > 100);
        std::printf("NetSm random walk: %d actions (%d retries: %d first / %d fast / %d slow, %d early aborts, "
                    "%d re-arms, %d wrong-password bans, %d STA_UP), %d violations\n",
                    actions, retries, firstRetries, fastRetries, slow, early, rearms, bans, staUps, violations);
    }
}

// ---------------------------------------------------------------- UART 傳送佇列（b570_uarttx.h）
// 模擬 ESP8266 UART0：128 bytes TX FIFO，availableForWrite() = 128 - count，room() 再保留 1 格
struct FakeUart {
    std::vector<uint8_t> fifo, wire;
    size_t maxWrite = 0;
    bool overflow = false;
    size_t room() const {
        const int r = 128 - (int)fifo.size() - 1;
        return r > 0 ? (size_t)r : 0;
    }
    void write(const uint8_t* d, size_t n) {
        if (n > room()) overflow = true;   // 真機上會忙等
        maxWrite = std::max(maxWrite, n);
        fifo.insert(fifo.end(), d, d + n);
    }
    void shift(size_t k) {
        k = std::min(k, fifo.size());
        wire.insert(wire.end(), fifo.begin(), fifo.begin() + (std::ptrdiff_t)k);
        fifo.erase(fifo.begin(), fifo.begin() + (std::ptrdiff_t)k);
    }
};

static void testTxRing() {
    {   // 平常：整幀直接進 FIFO，不排隊
        FakeUart u;
        TxRing<1024> tx;
        const Bytes ctrl = hex("A5 5A 05 01 07 2C 01 06 FF 00 D4 4D");
        CHECK(tx.send(u, ctrl.data(), ctrl.size()) && tx.used() == 0 && u.fifo == ctrl);
    }
    {   // FIFO 快滿：能放的直接放，其餘排隊，順序不變
        FakeUart u;
        TxRing<1024> tx;
        u.fifo.assign(120, 0xEE);
        Bytes f(12);
        for (size_t i = 0; i < f.size(); ++i) f[i] = (uint8_t)i;
        CHECK(tx.send(u, f.data(), f.size()) && tx.used() == 5 && u.fifo.size() == 127 && !u.overflow);
        u.shift(10);
        tx.pump(u);
        CHECK(tx.used() == 0 && u.fifo.size() == 122 && !u.overflow);
        u.shift(200);
        CHECK(u.wire.size() == 132 && Bytes(u.wire.end() - 12, u.wire.end()) == f);
    }
    {   // 佇列滿：整幀丟棄（不會送出半幀）
        FakeUart u;
        TxRing<64> tx;
        u.fifo.assign(127, 0xEE);
        Bytes f(40, 0x11), g(30, 0x22);
        CHECK(tx.send(u, f.data(), f.size()) && tx.used() == 40);
        CHECK(!tx.send(u, g.data(), g.size()) && tx.drops == 1 && tx.used() == 40);
        u.shift(127);
        tx.pump(u);
        u.shift(127);
        CHECK(u.wire.size() == 167 && std::count(u.wire.begin(), u.wire.end(), 0x22) == 0 && !u.overflow);
    }
    {   // 隨機：輸出 = 被接受的幀依序串接；寫入永遠不超過 FIFO 空間（絕不忙等）
        std::mt19937 rng(5);
        FakeUart u;
        TxRing<256> tx;
        Bytes expect;
        int accepted = 0, dropped = 0;
        for (int i = 0; i < 100000; ++i) {
            if (rng() % 3) {
                Bytes f(1 + rng() % 207);
                for (auto& x : f) x = (uint8_t)rng();
                if (tx.send(u, f.data(), f.size())) { expect.insert(expect.end(), f.begin(), f.end()); ++accepted; }
                else ++dropped;
            } else {
                u.shift(rng() % 160);
                tx.pump(u);
            }
        }
        for (int i = 0; i < 100; ++i) { u.shift(1000); tx.pump(u); }
        CHECK(u.wire == expect && !u.overflow && tx.used() == 0 && accepted > 1000 && dropped > 100 &&
              (int)tx.drops == dropped);
    }
}

// ---------------------------------------------------------------- 上行延遲：主機量測 + UART 模擬
// (1) 主機 CPU 上量測「收到一個 CTRL datagram → 解析 → 擁有者判斷 → 寫進 UART FIFO」的純邏輯耗時
// (2) 以 230400 8N1（43.4 µs/byte）、128 bytes FIFO 模擬 ESP → STM32 的實際流量，量 CTRL 從 UDP 收到到最後一個 bit 送出的時間
struct NullUart {
    size_t room() const { return 127; }
    void write(const uint8_t*, size_t n) { bytes += n; }
    size_t bytes = 0;
};

static void testUplinkLatency() {
    {
        const Bytes ctrl = hex("A5 5A 05 01 07 2C 01 06 FF 00 D4 4D");
        AppSession s;
        const uint32_t ip = ipv4(192, 168, 4, 100);
        s.hello(ip, 40000, 1, 0);
        Parser par;
        TxRing<1024> tx;
        NullUart u;
        const int N = 2000000;
        const auto t0 = std::chrono::steady_clock::now();
        for (int i = 0; i < N; ++i) {
            const uint32_t now = (uint32_t)(i / 100);
            s.touch(ip, 40000, now);
            par.reset();
            par.feed(ctrl.data(), ctrl.size(), [&](const Frame& f) {
                if (isAppToStm(f.type) && s.isOwner(ip, 40000, now)) tx.send(u, f.raw, f.rawLen);
            });
        }
        const double ns = (double)std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - t0).count() / N;
        CHECK(u.bytes == (size_t)N * ctrl.size() && tx.used() == 0);
        std::printf("uplink host benchmark: %.0f ns per CTRL datagram (parse + CRC + owner check + FIFO write)\n", ns);
    }
    // UART 模擬：1 µs 解析度，loop() 每 100 µs 一圈（保守；實機空轉一圈 < 20 µs）
    struct Src { uint32_t periodUs, offsetUs; size_t len; bool ctrl; };
    auto run = [](const std::vector<Src>& srcs, uint32_t durUs, double& avgUs, uint32_t& maxUs) {
        const double byteUs = 1e6 * 10.0 / 230400.0;
        FakeUart u;
        TxRing<1024> tx;
        struct Pending { uint32_t arrive; size_t endByte; };
        std::vector<Pending> ctrlPend;
        size_t sentBytes = 0;     // 已完整送上線的 bytes
        size_t queuedBytes = 0;   // 已交給 TxRing 的 bytes（被接受者）
        double shiftAcc = 0;
        uint64_t sum = 0;
        uint32_t n = 0;
        maxUs = 0;
        std::vector<uint32_t> next(srcs.size());
        for (size_t k = 0; k < srcs.size(); ++k) next[k] = srcs[k].offsetUs;
        std::vector<std::pair<uint32_t, size_t>> inbox;
        for (uint32_t t = 0; t < durUs; ++t) {
            for (size_t k = 0; k < srcs.size(); ++k)
                if (t == next[k]) { inbox.push_back({t, k}); next[k] += srcs[k].periodUs; }
            if (t % 100 == 0) {   // loop()：收件匣全部立即轉送
                for (auto& m : inbox) {
                    Bytes f(srcs[m.second].len, 0x33);
                    if (tx.send(u, f.data(), f.size())) {
                        queuedBytes += f.size();
                        if (srcs[m.second].ctrl) ctrlPend.push_back({m.first, queuedBytes});
                    }
                }
                inbox.clear();
                tx.pump(u);
            }
            shiftAcc += 1.0;
            while (shiftAcc >= byteUs && !u.fifo.empty()) {   // 移位暫存器每 43.4 µs 送完 1 byte
                shiftAcc -= byteUs;
                u.shift(1);
                ++sentBytes;
            }
            if (u.fifo.empty()) shiftAcc = 0;   // 線路閒置：下一個 byte 寫入後要完整 43.4 µs
            while (!ctrlPend.empty() && sentBytes >= ctrlPend.front().endByte) {
                const uint32_t lat = t - ctrlPend.front().arrive;
                sum += lat; ++n; maxUs = std::max(maxUs, lat);
                ctrlPend.erase(ctrlPend.begin());
            }
        }
        avgUs = n ? (double)sum / n : 0;
        return n;
    };
    // 駕駛中：CTRL 50 Hz（12 B）、PING 2 Hz（11 B）、LINK 2 Hz（9 B）、NET 0.5 Hz（最長 45 B）。
    // 相位刻意讓 PING / NET 剛好排在 CTRL 前面（最壞碰撞）
    std::vector<Src> drive = {{20000, 137, 12, true}, {500000, 120, 11, false}, {500000, 250013, 9, false},
                              {2000000, 1000100, 45, false}};
    double avg; uint32_t mx;
    uint32_t n = run(drive, 20000000, avg, mx);
    CHECK(n > 900 && mx < 3000);
    std::printf("UART sim, driving (20 s): CTRL UDP-in -> last bit on wire avg %.0f us, max %u us (%u frames)\n", avg, mx, n);
    // 最壞情況：同時在上傳編排（SEQ_LOAD 207 B x2 連發）與改參數（PARAM_SET 107 B）
    std::vector<Src> burst = drive;
    burst.push_back({300000, 40, 207, false});
    burst.push_back({300000, 41, 207, false});
    burst.push_back({700000, 5, 107, false});
    n = run(burst, 20000000, avg, mx);
    CHECK(n > 900 && mx < 25000);
    std::printf("UART sim, with SEQ_LOAD/PARAM_SET bursts: CTRL avg %.0f us, max %u us\n", avg, mx);
}

int main(int argc, char** argv) {
    testVectors();
    testParser();
    if (argc > 1) testDifferential(argv[1]);
    testRouting();
    testSession();
    testWifiCfg();
    testChannel();
    testStaVectors();
    testStaSet();
    if (argc > 2) testStaDifferential(argv[2]);
    testRecordV2();
    testNetSm();
    testTxRing();
    testUplinkLatency();
    std::printf("%s: %d checks passed, %d failed\n", g_fail ? "FAILED" : "ALL PASS", g_pass, g_fail);
    return g_fail ? 1 : 0;
}
