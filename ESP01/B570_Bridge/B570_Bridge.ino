/*
 * B570 Pilot - ESP-01 Wi-Fi 橋接韌體（ESP8266, 1 MB flash）
 * ============================================================================
 * 直連模式：Android App ──Wi-Fi(UDP 8888)── ESP-01 SoftAP 192.168.4.1 ──UART 230400 8N1── STM32F103
 * 熱點模式：手機個人熱點（手機照用 4G/5G）◀── ESP-01 以 STA 加入 ──UART 230400── STM32F103
 * 協定：docs/PROTOCOL.md（§1、§3 路由、§3.4、§3.5、§3.6、§5）。測試向量：tools/b570_proto.py
 *
 * 接線
 *   ESP TX  (GPIO1) → STM32 PA3 (USART2_RX)
 *   ESP RX  (GPIO3) ← STM32 PA2 (USART2_TX)
 *   VCC 3.3 V：穩壓器 >= 300 mA（Wi-Fi 發射峰值 ~250 mA），模組旁加 100~470 uF 電解 + 0.1 uF 陶瓷
 *   EN / CH_PD → 3.3 V（10 kΩ 上拉）；RST 上拉；GPIO0、GPIO2 開機時須為高電位（ESP-01S 板上已有上拉）
 *   GND 與 STM32 共地。兩邊都是 3.3 V 邏輯，可直接對接。
 *
 * 燒錄（Arduino IDE / arduino-cli，esp8266 core 3.1.2）
 *   開發板        Generic ESP8266 Module            (esp8266:esp8266:generic)
 *   Flash Size    1MB (FS:none OTA:~502KB)          eesz=1M
 *   CPU Frequency 160 MHz（建議）                    xtal=160
 *   Flash Mode    DOUT (compatible)                 FlashMode=dout
 *   Upload Speed  115200                            baud=115200
 *   Erase Flash   Only Sketch（保留 Wi-Fi 帳密）；第一次燒錄可選 All Flash Contents
 *                 （清掉舊 AT 韌體設定，會重新產生密碼）
 *   Reset Method  沒有 DTR/RTS 自動重置線路的轉接板選 no dtr (aka ck)，手動進燒錄模式
 *   arduino-cli   -b esp8266:esp8266:generic:xtal=160,eesz=1M,FlashMode=dout,baud=115200
 *   USB-TTL 轉接板必須是 3.3 V；燒錄時 GPIO0 接地再上電，並先拔開 STM32 的 PA2/PA3。
 *   序列燒錄完請「斷電重開」一次（ESP8266 已知問題：燒錄後第一次軟體重開可能停在燒錄模式）。
 *   從 1.0 升級：EEPROM 舊紀錄（v1）自動沿用 AP 名稱/密碼；熱點模式預設關閉，
 *   但 b570_preset.h 有內建手機熱點時，燒錄後第一次開機會直接啟用（只套用一次）。
 *   OTA：電腦連上車子的 Wi-Fi，IDE 選網路連接埠「b570-xxxx at 192.168.4.1」，密碼 = 車子 Wi-Fi 密碼。
 *        熱點模式時電腦改連同一個手機熱點，連接埠為「b570-xxxx at <車子在熱點的 IP>」（OLED 有顯示）。
 *
 * 熱點模式設定（讓手機的行動網路照常可用，車子改連手機熱點）
 *   1. 手機開「個人熱點」，必須是 2.4 GHz、WPA2（ESP-01 不支援 5 GHz 與「只有 WPA3」）：
 *        iPhone ：設定 → 個人熱點 → 開「允許其他人加入」＋「最大化相容性」
 *        Android：設定 → 網路 → 熱點 → 頻段選 2.4 GHz（或「延伸相容性」），
 *                 安全性 WPA2-Personal 或 WPA2/WPA3 混合
 *      熱點名稱 1..32 bytes（可中文）、密碼 8..63 個可列印 ASCII 字元（或不設密碼）。
 *   2. 先用直連模式連上車子 → App「設定 → 熱點模式」輸入熱點名稱/密碼並啟用（STA_SET）→ 車子自動重開機。
 *      或燒錄前在 b570_preset.h 填好手機熱點：第一次開機就直接是熱點模式（免直連、免掃 QR），
 *      每個 PRESET_ID 只套用一次，之後在 App 修改 / 停用都會保留。
 *   3. 開機後最多 12 s 加入熱點：成功就關閉車子的 AP，OLED 顯示熱點名稱與 IP（NET mode 2）；
 *      App 在熱點網段廣播 DISCOVER 找到車子，之後 HELLO / 控制與直連完全相同。
 *   4. 12 s 找不到熱點、或熱點斷線超過 15 s → 改開原本的 B570-XXXX（OLED 顯示 QR，NET mode 3），
 *      沒有手機連在 AP 上時再試熱點（每次最多 8 s；SDK 回報找不到熱點 / 密碼錯誤就提早放棄），找到就切回熱點模式：
 *      改開 AP 後的前 3 分鐘每閒置 6 s 就試（第一次等 12 s，QR 剛出現時不打擾直連配對；
 *      先開車子、後開熱點通常 15 s、最久約 20 s 內連上），之後每 30 s；直連的手機離開 AP 後再快速試 1 分鐘。
 *      戶外使用：先開手機熱點、再開車子電源最快（開機 12 s 內直接加入）。
 *   5. 停用：App 設定頁關閉熱點模式（STA_SET enable=0，帳密保留）→ 重開機回到直連模式。
 *
 * 行為摘要
 *   - session 擁有者 = HELLO 的來源 IP:port；同 IP 重送 HELLO 可接手（App 換 socket 後要重送 HELLO）
 *   - 擁有者 1500 ms 無封包、送 BYE、其手機斷開 AP、或熱點斷線 → session 立即結束並送 LINK
 *   - WIFI_SET / WIFI_FACTORY / STA_SET 只接受擁有者（或目前無 session）；否則 ESP_ACK BUSY
 *   - DISCOVER：任何人、任何模式都回 DISCOVER_ACK（單播、SEQ 與請求相同）；STA_INFO 的密碼只給擁有者
 *   - 0x49-0x4F 未定義 → ESP_ACK UNKNOWN_TYPE；每個 UDP datagram 獨立解析（不跨 datagram 拼幀）
 *
 * 低延遲（App → STM32 控制指令）
 *   - UDP 收到的幀在同一圈原封寫進 UART 硬體 FIFO：不批次、不等計時器；loop 先收 UDP 再收 UART
 *   - 所有模式都 WIFI_NONE_SLEEP（STA 預設 modem-sleep 會讓「手機→車」等 DTIM 喚醒，多 100~300 ms）
 *   - 會阻塞的 WiFi.mode() 切換（core 3.1.2 至少 100 ms）與 STA 掃描（射頻離開 AP 頻道），
 *     只在「沒有 session 且 AP 上沒有手機」時進行（b570_netsm.h）
 *
 * 注意：Serial 就是 STM32 連線，任何除錯文字都會破壞資料流 → 本韌體絕不 print。
 * ============================================================================
 */
#include <ESP8266WiFi.h>
#include <WiFiUdp.h>
#include <EEPROM.h>
extern "C" {
#include <user_interface.h>
}

#include "b570_proto.h"
#include "b570_session.h"
#include "b570_sta.h"
#include "b570_wificfg.h"
#include "b570_preset.h"
#include "b570_netsm.h"
#include "b570_uarttx.h"

// ---------------------------------------------------------------- 設定
#ifndef ENABLE_OTA
#define ENABLE_OTA          1        // ArduinoOTA（主機名 = SSID 小寫，密碼 = Wi-Fi 密碼）
#endif
#define LINK_BAUD           230400   // STM32 USART2
#define TX_POWER_DBM        17       // Wi-Fi 發射功率 dBm
#define UDP_PORT            8888
#define MAX_STATIONS        2
#define ESP_FW_MAJOR        1
#define ESP_FW_MINOR        1
#define ESP_FW_PATCH        1

#define LINK_PERIOD_MS      500      // LINK 心跳
#define NET_PERIOD_MS       2000     // NET 週期重送
#define NET_POLL_MS         20       // 熱點模式狀態機輪詢
#define CRED_BOOT_DELAY_MS  1000     // 開機後主動送 CRED
#define RESTART_DELAY_MS    500      // WIFI_SET / WIFI_FACTORY / STA_SET 後重開機
#define SCAN_BUDGET_MS      1500     // 自動選頻總時間上限
#define SCAN_DWELL_MIN_MS   20       // 每頻道主動掃描時間
#define SCAN_DWELL_MAX_MS   60
#define OTA_POLL_MS         20
#define UART_RX_BUFFER      1024
#define UART_TX_RING        1024     // 2 的次方
#define EEPROM_SIZE         256
#define UDP_MAX_PKTS_LOOP   32       // 每圈最多處理的 datagram（防洪水；平常一定清空）

#if ENABLE_OTA
#include <ArduinoOTA.h>
#endif

using namespace b570;

static_assert(REC_SIZE <= EEPROM_SIZE, "EEPROM_SIZE too small");
static_assert(PRESET_MARK_OFFSET >= REC_SIZE && PRESET_MARK_OFFSET + PRESET_MARK_LEN <= EEPROM_SIZE,
              "preset mark must sit after the config record");
// 改開 AP 時的選頻掃描（最多 SCAN_BUDGET_MS）一定在第一次重試（FIRST_IDLE_MS）之前結束；
// 第一次重試的閒置時間另外從 AP 真正開起來算（netPoll → g_net.apStarted()）
static_assert(NetSm::FIRST_IDLE_MS > SCAN_BUDGET_MS + 2 * NET_POLL_MS, "retry could start before fallback AP is up");

// ---------------------------------------------------------------- 狀態
static WiFiUDP    g_udp;
static Parser     g_uartParser;        // STM32 → ESP 串流
static Parser     g_udpParser;         // 每個 datagram 重新開始
static AppSession g_session;
static WifiCfg    g_cfg;               // AP 帳密（實際使用中）
static StaCfg     g_sta;               // 熱點模式設定（EEPROM）
static NetSm      g_net;               // 熱點模式連線策略
static uint8_t    g_channel = 0;       // AP 頻道（0 = 還沒決定：熱點模式開機時要開 AP 才選）
static uint32_t   g_chipId = 0;
static char       g_host[SSID_MAX + 1];  // OTA / DHCP 主機名（AP SSID 小寫）
static bool       g_apUp = false;      // SoftAP 運作中
static bool       g_apPending = false; // 改開 AP：等選頻掃描結束
static uint32_t   g_apPendingMs = 0;

static uint32_t g_udpRx = 0;           // 收到的 datagram 數
static uint32_t g_udpTx = 0;           // 成功送出的 datagram 數
static uint8_t  g_uartSeq = 0;         // ESP → STM32 幀序號

static uint8_t  g_linkState = 0xFF;    // 上次送出的 LINK（0xFF = 尚未送）
static uint8_t  g_linkSta = 0xFF;
static uint32_t g_lastLinkMs = 0;
static uint8_t  g_netMode = 0xFF;      // 上次送出的 NET（0xFF = 尚未送）
static uint32_t g_netIp = 0;
static uint32_t g_lastNetMs = 0;
static uint32_t g_lastNetPollMs = 0;
static bool     g_linkDirty = false;   // Wi-Fi 事件：需要重新評估 LINK
static bool     g_ownerLeft = false;   // Wi-Fi 事件：擁有者手機斷線
static bool     g_credSent = false;
static bool     g_restartPending = false;
static uint32_t g_restartAtMs = 0;
static bool     g_otaActive = false;
#if ENABLE_OTA
static uint32_t g_lastOtaMs = 0;
#endif

// 事件處理器物件必須一直存在，否則會被自動取消註冊
static WiFiEventHandler g_staConnHandler;
static WiFiEventHandler g_staDiscHandler;

// ---------------------------------------------------------------- UART 傳送（不阻塞，b570_uarttx.h）
struct UartPort {
    // 保留 1 格：硬體 FIFO 達 127 bytes 時 uart_write 會忙等
    size_t room() const {
        const int r = Serial.availableForWrite() - 1;
        return r > 0 ? (size_t)r : 0;
    }
    void write(const uint8_t* d, size_t n) { Serial.write(d, n); }
};
static UartPort              g_port;
static TxRing<UART_TX_RING>  g_tx;

static inline void uartPump() { g_tx.pump(g_port); }
static inline bool uartSend(const uint8_t* d, size_t n) { return g_tx.send(g_port, d, n); }

// 只在 OTA 開始 / 重開機前使用（允許阻塞）
static void uartFlushAll() {
    while (g_tx.used()) {
        uartPump();
        yield();
    }
    Serial.flush();
}

static void uartSendFrame(uint8_t type, const uint8_t* payload, size_t len) {
    uint8_t f[MAX_FRAME];
    const size_t n = buildFrame(f, sizeof(f), type, g_uartSeq++, payload, len);
    if (n) uartSend(f, n);
}

// ---------------------------------------------------------------- UDP 傳送
static void udpSendRaw(uint32_t ip, uint16_t port, const uint8_t* d, size_t n) {
    if (!g_udp.beginPacket(IPAddress(ip), port)) return;
    const size_t w = g_udp.write(d, n);
    if (g_udp.endPacket() && w == n) ++g_udpTx;
}

// 回覆幀的 SEQ = 請求幀的 SEQ（App 重送時 SEQ 不變，可精準配對回覆）
static void udpReply(uint32_t ip, uint16_t port, uint8_t type, uint8_t seq, const uint8_t* payload, size_t len) {
    uint8_t f[MAX_FRAME];
    const size_t n = buildFrame(f, sizeof(f), type, seq, payload, len);
    if (n) udpSendRaw(ip, port, f, n);
}

// ---------------------------------------------------------------- Wi-Fi 狀態小工具（SDK getter，皆為 µs 等級）
static inline uint8_t apStations() { return g_apUp ? wifi_softap_get_station_num() : 0; }
// LINK / WIFI_INFO 的 stations：AP 上的手機數；熱點模式（AP 關閉）時 = 有 session ? 1 : 0
static inline uint8_t linkStations(bool session) { return g_apUp ? apStations() : (session ? 1 : 0); }
static inline bool staUp() {
    return (wifi_get_opmode() & STATION_MODE) && wifi_station_get_connect_status() == STATION_GOT_IP;
}
// SDK 回報這次加入熱點失敗（STA 介面沒開 = 沒有失敗）：重試時熱點不在就不必等滿 8 s（b570_netsm.h）
static inline NetSm::StaErr staErr() {
    if (!(wifi_get_opmode() & STATION_MODE)) return NetSm::SE_NONE;
    switch (wifi_station_get_connect_status()) {
    case STATION_NO_AP_FOUND:
    case STATION_CONNECT_FAIL:
        return NetSm::SE_NOT_FOUND;
    case STATION_WRONG_PASSWORD:
        return NetSm::SE_WRONG_PW;
    default:
        return NetSm::SE_NONE;
    }
}
static inline uint32_t staIp() { return (uint32_t)WiFi.localIP(); }
static inline uint32_t apIp() { return (uint32_t)IPAddress(192, 168, 4, 1); }

// ---------------------------------------------------------------- LINK / CRED / NET（→ STM32）
static void sendLink(uint8_t state, uint8_t stations, uint32_t now) {
    const uint8_t p[2] = {state, stations};
    uartSendFrame(T_LINK, p, sizeof(p));
    g_linkState = state;
    g_linkSta = stations;
    g_lastLinkMs = now;
}

// force = 週期心跳；否則只在狀態或連線數改變時送
static void linkService(uint32_t now, bool force) {
    const bool sess = g_session.active(now);
    const uint8_t sta = linkStations(sess);
    uint8_t state = sta ? 1 : 0;
    if (g_otaActive) state = 0;
    else if (sess) state = 2;
    if (force || state != g_linkState || sta != g_linkSta) sendLink(state, sta, now);
}

static void sendCred() {
    uint8_t p[1 + SSID_MAX + 1 + PW_MAX + 1];
    uartSendFrame(T_CRED, p, encodeCred(p, g_cfg, g_channel));
}

// NET（§3.5）：mode 0 帶車子 AP 名稱；mode 1..3 帶熱點名稱；ip 只在 mode 2 有效
static void sendNet(uint32_t now) {
    const uint8_t mode = g_net.mode();
    const uint32_t ip = mode == NET_STA ? staIp() : 0;
    uint8_t p[NET_MAX];
    const size_t n = mode == NET_AP ? encodeNet(p, mode, ip, g_cfg.ssid, g_cfg.ssidLen)
                                    : encodeNet(p, mode, ip, g_sta.ssid, g_sta.ssidLen);
    uartSendFrame(T_NET, p, n);
    g_netMode = mode;
    g_netIp = ip;
    g_lastNetMs = now;
}

// 狀態或 IP 改變時立即送
static void netService(uint32_t now) {
    const uint8_t mode = g_net.mode();
    const uint32_t ip = mode == NET_STA ? staIp() : 0;
    if (mode != g_netMode || ip != g_netIp) sendNet(now);
}

static void endSession(uint32_t now) {
    g_session.end();
    linkService(now, false);
}

// ---------------------------------------------------------------- 設定儲存（EEPROM，magic + version + CRC）
static uint8_t loadCfg(WifiCfg& c, StaCfg& s) {
    uint8_t rec[REC_SIZE];
    for (size_t i = 0; i < REC_SIZE; ++i) rec[i] = EEPROM.read(i);
    return decodeRecord(rec, c, s);
}

static bool saveCfg(const WifiCfg& c, const StaCfg& s) {
    uint8_t rec[REC_SIZE];
    encodeRecord(rec, c, s);
    for (size_t i = 0; i < REC_SIZE; ++i) EEPROM.write(i, rec[i]);
    return EEPROM.commit();   // 內容沒變就不寫 flash
}

// 燒錄時內建的手機熱點（b570_preset.h）：每個 PRESET_ID 只套用一次，之後以 App 的設定（STA_SET）為準
static void applyPreset() {
    uint8_t m[PRESET_MARK_LEN];
    for (size_t i = 0; i < PRESET_MARK_LEN; ++i) m[i] = EEPROM.read(PRESET_MARK_OFFSET + i);
    if (!presetPending(m, PRESET_ID)) return;
    StaCfg s = g_sta;
    if (!presetApply(s, PRESET_STA_SSID, PRESET_STA_PW)) return;   // 名稱 / 密碼不合規則：不記錄，改好重燒就會套用
    g_sta = s;
    presetMark(m, PRESET_ID);
    uint8_t rec[REC_SIZE];
    encodeRecord(rec, g_cfg, g_sta);
    for (size_t i = 0; i < REC_SIZE; ++i) EEPROM.write(i, rec[i]);
    for (size_t i = 0; i < PRESET_MARK_LEN; ++i) EEPROM.write(PRESET_MARK_OFFSET + i, m[i]);
    EEPROM.commit();   // 設定與套用紀錄一起寫入
}

static void scheduleRestart(uint32_t now) {
    g_restartPending = true;
    g_restartAtMs = now + RESTART_DELAY_MS;
}

static void doRestart() {
    g_session.end();
    const uint8_t p[2] = {0, 0};
    uartSendFrame(T_LINK, p, sizeof(p));
    uartFlushAll();
    ESP.restart();
}

// ---------------------------------------------------------------- 射頻 / AP / STA
// 每次切換模式後都重新套用：STA 預設 modem-sleep 會讓下行（手機→車 = 控制指令）等 DTIM 喚醒
static void radioTune() {
    WiFi.setSleepMode(WIFI_NONE_SLEEP);
    WiFi.setOutputPower(TX_POWER_DBM);
}

static void startAp(uint8_t ch) {
    if (ch < 1 || ch > CHANNEL_MAX) ch = 1;
    WiFi.mode(WIFI_AP);   // 從 STA / AP_STA 切過來會一併停止 STA（含自動重連與掃描）
    radioTune();
    const IPAddress ip(192, 168, 4, 1);
    WiFi.softAPConfig(ip, ip, IPAddress(255, 255, 255, 0));
    if (!WiFi.softAP(g_cfg.ssid, g_cfg.pw, ch, 0, MAX_STATIONS) &&
        !WiFi.softAP(g_cfg.ssid, g_cfg.pw, ch, 0, MAX_STATIONS)) {
        // 理論上不會發生（設定已驗證）：本次改用出廠帳密（不寫入 EEPROM，下次開機仍先用使用者設定）。
        // CRED 會把實際帳密送給 STM32 顯示在 OLED。
        factoryCfg(g_cfg, g_chipId, [] { return ESP.random(); });
        WiFi.softAP(g_cfg.ssid, g_cfg.pw, ch, 0, MAX_STATIONS);
    }
    g_channel = ch;
    g_apUp = true;
    g_linkDirty = true;
    g_credSent = false;   // 1 s 開機延遲過後立即（重）送 CRED：實際帳密與頻道
}

static void staBegin() {
    WiFi.setAutoReconnect(true);  // 熱點短暫中斷時 SDK 自動重連（超過 15 s 才改開 AP）
    WiFi.begin(g_sta.ssid, g_sta.pwLen ? g_sta.pw : nullptr);
}

static void makeHost() {
    uint8_t i = 0;
    for (; i < g_cfg.ssidLen; ++i) {
        char ch = g_cfg.ssid[i];
        if (ch >= 'A' && ch <= 'Z') ch = (char)(ch - 'A' + 'a');
        if (ch == '_') ch = '-';   // 主機名不允許底線
        g_host[i] = ch;
    }
    g_host[i] = 0;
}

// ---------------------------------------------------------------- 自動選頻（1 / 6 / 11）
static ChannelScore  g_scan;
static volatile bool g_scanDone = false;
static bool          g_scanAccept = false;   // 逾時後才回來的結果一律忽略

static void scanDoneCb(void* arg, STATUS status) {
    if (!g_scanAccept) return;
    if (status == OK) {
        for (bss_info* b = (bss_info*)arg; b; b = STAILQ_NEXT(b, next)) g_scan.add(b->channel, b->rssi);
    }
    g_scanDone = true;
}

// STA 介面必須開著且沒有在連線；開始成功回傳 true（結果由 scanDoneCb 收集）
static bool scanStart() {
    g_scan = ChannelScore();
    g_scanDone = false;
    g_scanAccept = true;
    struct scan_config sc;
    memset(&sc, 0, sizeof(sc));
    sc.channel = 0;                 // 全頻道（相鄰頻道也會干擾 1/6/11）
    sc.show_hidden = 1;
    sc.scan_type = WIFI_SCAN_TYPE_ACTIVE;
    sc.scan_time.active.min = SCAN_DWELL_MIN_MS;
    sc.scan_time.active.max = SCAN_DWELL_MAX_MS;
    if (wifi_station_scan(&sc, scanDoneCb)) return true;
    g_scanAccept = false;
    return false;
}

// 掃描失敗 / 逾時：在 1/6/11 隨機挑
static uint8_t scanFinish() {
    g_scanAccept = false;
    return g_scan.pick(ESP.random());
}

// 只在 setup() 使用（此時沒有任何連線，允許阻塞，最多 SCAN_BUDGET_MS）
static uint8_t pickChannelBlocking(uint32_t t0) {
    if (scanStart()) {
        while (!g_scanDone && (uint32_t)(millis() - t0) < SCAN_BUDGET_MS) delay(5);
    }
    return scanFinish();
}

// 熱點模式找不到 / 斷線太久：改開 AP。頻道未定時先非阻塞掃描（此時 AP 還沒開，不可能有 session）
static void beginApFallback(uint32_t now) {
    wifi_station_disconnect();      // 停止加入 / 自動重連
    if (g_channel) {
        startAp(g_channel);
    } else if (scanStart()) {
        g_apPending = true;
        g_apPendingMs = now;
    } else {
        startAp(scanFinish());
    }
}

// ---------------------------------------------------------------- 熱點模式狀態機（b570_netsm.h）
static void netPoll(uint32_t now) {
    if (g_apPending && (g_scanDone || (uint32_t)(now - g_apPendingMs) >= SCAN_BUDGET_MS)) {
        g_apPending = false;
        startAp(scanFinish());
        g_net.apStarted(now);   // 第一次重試的閒置時間從 AP（OLED 的 QR）真正出現起算
    }
    const bool wasSta = g_net.mode() == NET_STA;
    switch (g_net.step(now, staUp(), apStations(), g_session.active(now), staErr())) {
    case NetSm::A_STA_UP:        // 熱點已連上：關閉 AP（開機加入時本來就沒開 → 不切換）
        g_apPending = false;
        g_apUp = false;
        WiFi.mode(WIFI_STA);
        radioTune();
        g_linkDirty = true;
        break;
    case NetSm::A_START_AP:      // 12 s 找不到熱點 / 斷線超過 15 s
        beginApFallback(now);
        break;
    case NetSm::A_RETRY:         // AP 已閒置 6 s（快速期；改開 AP 後第一次 12 s）/ 30 s：AP 照開，同時試著加入熱點（最多 8 s）
        WiFi.mode(WIFI_AP_STA);
        radioTune();
        staBegin();
        break;
    case NetSm::A_YIELD_RETRY:   // 重試中有手機連上 AP：只停 STA（不阻塞），射頻立即回到 AP 頻道
        wifi_station_disconnect();
        break;
    case NetSm::A_ABORT_RETRY:   // 重試失敗（找不到熱點 / 密碼錯誤）/ 逾時、讓出後 AP 又閒置：切回純 AP，確認 AP 在原頻道
    case NetSm::A_RESTORE_AP:
        wifi_station_disconnect();
        startAp(g_channel);
        break;
    default:
        break;
    }
    // 熱點斷線 = 擁有者（熱點那支手機）已離線：立即結束 session（同 AP 模式手機斷線的規則）
    if (wasSta && g_net.mode() != NET_STA && g_session.active(now)) endSession(now);
    netService(now);
}

// ---------------------------------------------------------------- App → ESP 指令（0x40-0x4F）
static void lookupOwnerMac(uint32_t ip) {
    struct station_info* st = wifi_softap_get_station_info();
    if (!st) return;
    for (struct station_info* s = st; s; s = STAILQ_NEXT(s, next)) {
        if (s->ip.addr == ip) {
            g_session.setMac(s->bssid);
            break;
        }
    }
    wifi_softap_free_station_info();
}

static void sendEspAck(uint32_t ip, uint16_t port, uint8_t seq, uint8_t ref, uint8_t status) {
    const uint8_t p[2] = {ref, status};
    udpReply(ip, port, T_ESP_ACK, seq, p, sizeof(p));
}

static void sendHelloAck(uint32_t ip, uint16_t port, uint8_t seq, bool busy) {
    uint8_t p[12];
    p[0] = busy ? 1 : 0;   // HELLO_ACK status：0 OK、1 BUSY
    p[1] = PROTO_VERSION;
    p[2] = ESP_FW_MAJOR;
    p[3] = ESP_FW_MINOR;
    p[4] = ESP_FW_PATCH;
    p[5] = wifi_get_channel();   // 實際頻道（熱點模式 = 熱點的頻道）
    putU16(p + 6, UDP_PORT);
    putU32(p + 8, g_chipId);
    udpReply(ip, port, T_HELLO_ACK, seq, p, sizeof(p));
}

static void sendWifiInfo(uint32_t ip, uint16_t port, uint8_t seq, uint32_t now) {
    uint8_t p[28 + 1 + SSID_MAX + 1 + PW_MAX];
    p[0] = wifi_get_channel();
    p[1] = linkStations(g_session.active(now));
    p[2] = TX_POWER_DBM;
    p[3] = g_cfg.channel;   // 設定的頻道：0 = 自動（實際頻道在 p[0]）
    putU32(p + 4, (uint32_t)(micros64() / 1000000ULL));
    putU32(p + 8, ESP.getFreeHeap());
    putU32(p + 12, g_uartParser.frames);
    putU32(p + 16, g_uartParser.crcErrors);
    putU32(p + 20, g_udpRx);
    putU32(p + 24, g_udpTx);
    const size_t n = 28 + putSsidPw(p + 28, g_cfg);
    udpReply(ip, port, T_WIFI_INFO, seq, p, n);
}

static void sendStaInfo(uint32_t ip, uint16_t port, uint8_t seq, uint32_t now) {
    const uint8_t state = g_net.mode();   // 熱點模式未啟用 = 0
    int8_t rssi = 0;
    if (state == NET_STA) {
        const sint8 r = wifi_station_get_rssi();   // 錯誤時回傳 31
        if (r < 0) rssi = r;
    }
    uint8_t p[STA_INFO_MAX];
    const size_t n = encodeStaInfo(p, g_sta, state, state == NET_STA ? staIp() : 0, rssi,
                                   g_session.isOwner(ip, port, now));   // 密碼只給擁有者
    udpReply(ip, port, T_STA_INFO, seq, p, n);
}

static void sendDiscoverAck(uint32_t ip, uint16_t port, uint8_t seq, uint32_t nonce, uint32_t now) {
    const uint8_t mode = discoverMode(g_net.mode());
    const bool busy = g_session.active(now) && g_session.ip() != ip;   // 其他手機正在控制
    uint8_t p[DISCOVER_ACK_MAX];
    const size_t n = encodeDiscoverAck(p, nonce, g_chipId, mode, mode ? staIp() : apIp(), busy,
                                       g_cfg.ssid, g_cfg.ssidLen);
    udpReply(ip, port, T_DISCOVER_ACK, seq, p, n);   // 單播回給發送者（請求可能是廣播）
}

// 改 Wi-Fi 設定：只允許 session 擁有者，或目前沒有 session 時
static bool mayConfigure(uint32_t ip, uint16_t port, uint32_t now) {
    return !g_session.active(now) || g_session.isOwner(ip, port, now);
}

static void applyNewCfg(uint32_t ip, uint16_t port, uint8_t seq, uint8_t ref, const WifiCfg& c, const StaCfg& s,
                        uint32_t now) {
    const bool ok = saveCfg(c, s);
    sendEspAck(ip, port, seq, ref, ok ? ST_OK : ST_FAIL);
    if (ok) scheduleRestart(now);
}

static void handleEspCmd(const Frame& f, uint32_t ip, uint16_t port, uint32_t now) {
    switch (f.type) {
    case T_HELLO: {
        if (f.len != 4) return;
        const AppSession::HelloResult r = g_session.hello(ip, port, getU32(f.payload), now);
        sendHelloAck(ip, port, f.seq, r == AppSession::HELLO_BUSY);
        if (r != AppSession::HELLO_BUSY && !g_session.macKnown() && g_apUp) lookupOwnerMac(ip);
        if (r == AppSession::HELLO_NEW) linkService(now, false);   // LINK → 2
        break;
    }
    case T_WIFI_GET:
        sendWifiInfo(ip, port, f.seq, now);
        break;
    case T_WIFI_SET: {
        WifiCfg c;
        if (!mayConfigure(ip, port, now)) sendEspAck(ip, port, f.seq, T_WIFI_SET, ST_BUSY);
        else if (!decodeWifiSet(f.payload, f.len, c)) sendEspAck(ip, port, f.seq, T_WIFI_SET, ST_BAD_ARG);
        else applyNewCfg(ip, port, f.seq, T_WIFI_SET, c, g_sta, now);   // 熱點設定不變
        break;
    }
    case T_ESP_PING:
        if (f.len == 4) udpReply(ip, port, T_ESP_PONG, f.seq, f.payload, 4);
        break;
    case T_BYE:
        if (g_session.isOwner(ip, port, now)) endSession(now);
        break;
    case T_WIFI_FACTORY: {
        if (!mayConfigure(ip, port, now)) {
            sendEspAck(ip, port, f.seq, T_WIFI_FACTORY, ST_BUSY);
            break;
        }
        WifiCfg c;
        factoryCfg(c, g_chipId, [] { return ESP.random(); });
        applyNewCfg(ip, port, f.seq, T_WIFI_FACTORY, c, g_sta, now);   // 只重設 AP 帳密
        break;
    }
    case T_STA_SET: {
        if (!mayConfigure(ip, port, now)) {
            sendEspAck(ip, port, f.seq, T_STA_SET, ST_BUSY);
            break;
        }
        StaCfg s;
        const uint8_t st = decodeStaSet(f.payload, f.len, g_sta, s);
        if (st != ST_OK) {
            sendEspAck(ip, port, f.seq, T_STA_SET, st);
            break;
        }
        // AP 帳密以 EEPROM 為準（g_cfg 可能是本次開機的臨時出廠帳密）
        WifiCfg ap;
        StaCfg old;
        if (!loadCfg(ap, old)) ap = g_cfg;
        applyNewCfg(ip, port, f.seq, T_STA_SET, ap, s, now);
        break;
    }
    case T_STA_GET:
        sendStaInfo(ip, port, f.seq, now);
        break;
    case T_DISCOVER:   // 不需要 session、任何模式都回
        if (f.len == 4) sendDiscoverAck(ip, port, f.seq, getU32(f.payload), now);
        break;
    default:
        sendEspAck(ip, port, f.seq, f.type, ST_UNKNOWN_TYPE);   // 0x49-0x4F 保留
        break;
    }
}

// ---------------------------------------------------------------- 路由（§3）
static void onUdpFrame(const Frame& f, uint32_t ip, uint16_t port, uint32_t now) {
    if (isAppToStm(f.type)) {
        // 規則 3：只轉送擁有者的幀，原封不動立即寫入 UART（FIFO 有空間就直接進硬體，不排隊）
        if (!g_otaActive && g_session.isOwner(ip, port, now)) uartSend(f.raw, f.rawLen);
    } else if (isAppToEsp(f.type)) {
        handleEspCmd(f, ip, port, now);
    }
    // 0x50-0x5F、0x80 以上、0x00：不該從 UDP 來，丟棄
}

static void onUartFrame(const Frame& f, uint32_t now) {
    if (isStmToApp(f.type)) {
        // 每幀單獨一個 datagram 立即送給擁有者；無 session 則丟棄（TELEM 40 或 42 bytes 都原樣轉送）
        if (!g_otaActive && g_session.active(now)) udpSendRaw(g_session.ip(), g_session.port(), f.raw, f.rawLen);
    } else if (f.type == T_CRED_REQ) {
        sendCred();
        sendNet(now);
    }
    // 其餘（含 0x50-0x5F 其他類型）不轉送
}

static void drainUart(uint32_t now) {
    uint8_t buf[256];
    for (int guard = 0; guard < 8; ++guard) {   // 8 x 256 > RX 緩衝 + FIFO
        int n = Serial.available();
        if (n <= 0) break;
        if (n > (int)sizeof(buf)) n = sizeof(buf);
        n = Serial.read(buf, (size_t)n);
        if (n <= 0) break;
        g_uartParser.feed(buf, (size_t)n, [now](const Frame& f) { onUartFrame(f, now); });
    }
}

static void drainUdp(uint32_t now) {
    uint8_t buf[256];
    for (int guard = 0; guard < UDP_MAX_PKTS_LOOP; ++guard) {
        if (g_udp.parsePacket() <= 0) break;
        ++g_udpRx;
        const uint32_t ip = (uint32_t)g_udp.remoteIP();
        const uint16_t port = g_udp.remotePort();
        if (g_session.poll(now)) linkService(now, false);   // 規則 2：先判斷逾時
        g_session.touch(ip, port, now);                     // 擁有者的任何封包都算活動
        g_udpParser.reset();                                // datagram 之間不拼接
        int r;
        while ((r = g_udp.read(buf, sizeof(buf))) > 0) {
            g_udpParser.feed(buf, (size_t)r, [ip, port, now](const Frame& f) { onUdpFrame(f, ip, port, now); });
        }
    }
}

// ---------------------------------------------------------------- OTA
#if ENABLE_OTA
static void otaSetup() {
    ArduinoOTA.setHostname(g_host);
    ArduinoOTA.setPassword(g_cfg.pw);
    ArduinoOTA.onStart([] {
        // 更新期間 handle() 會阻塞：先告訴 STM32 斷線並停止轉送
        g_otaActive = true;
        g_session.end();
        sendLink(0, linkStations(false), millis());
        uartFlushAll();
    });
    ArduinoOTA.onError([](ota_error_t) {
        g_otaActive = false;
        g_linkDirty = true;
    });
    ArduinoOTA.begin();   // 綁 IP_ADDR_ANY：AP 與熱點（STA）介面都能 OTA；mDNS 在介面變動時自動重新宣告
}
#endif

// ---------------------------------------------------------------- setup / loop
void setup() {
    const uint32_t t0 = millis();
    Serial.setRxBufferSize(UART_RX_BUFFER);
    Serial.begin(LINK_BAUD);
    Serial.setDebugOutput(false);   // 關閉 SDK / core 的除錯輸出（os_printf）

    g_chipId = ESP.getChipId();
    EEPROM.begin(EEPROM_SIZE);
    const bool haveCfg = loadCfg(g_cfg, g_sta) != 0;   // v1 紀錄自動轉換（熱點模式關閉）

    WiFi.persistent(false);   // 不寫 SDK 的 flash 設定區
    if (!haveCfg) {
        WiFi.mode(WIFI_STA);  // 射頻開啟：硬體 RNG 才是真亂數
        wifi_station_disconnect();
        factoryCfg(g_cfg, g_chipId, [] { return ESP.random(); });
        staClear(g_sta);
        saveCfg(g_cfg, g_sta);
    }
    applyPreset();            // 內建手機熱點 → 這次開機就直接加入熱點
    makeHost();
    WiFi.hostname(g_host);    // DHCP 主機名：手機熱點的「已連線裝置」會顯示 b570-xxxx
    g_staConnHandler = WiFi.onSoftAPModeStationConnected(
        [](const WiFiEventSoftAPModeStationConnected&) { g_linkDirty = true; });
    g_staDiscHandler = WiFi.onSoftAPModeStationDisconnected(
        [](const WiFiEventSoftAPModeStationDisconnected& e) {
            if (g_session.stationLeft(e.mac)) g_ownerLeft = true;
            g_linkDirty = true;
        });

    if (g_net.begin(g_sta.enabled != 0, t0) == NetSm::A_JOIN) {
        // 熱點模式：只開 STA 加入手機熱點（不阻塞，最多 12 s，由 netPoll() 追蹤）
        g_channel = g_cfg.channel;   // 0 = 之後真的要開 AP 時才掃描選頻
        WiFi.mode(WIFI_STA);
        radioTune();
        staBegin();
    } else {
        // 直連模式：開機選頻（自動頻道時）後開 AP
        if (g_cfg.channel == 0) {
            WiFi.mode(WIFI_STA);   // 掃描需要 STA 介面
            wifi_station_disconnect();
        }
        startAp(g_cfg.channel ? g_cfg.channel : pickChannelBlocking(t0));
    }

    g_udp.begin(UDP_PORT);   // IP_ADDR_ANY：AP / STA 介面、單播與廣播（DISCOVER）都收
#if ENABLE_OTA
    otaSetup();
#endif
    g_linkDirty = true;   // 第一圈立即送出 LINK（NET 也會在第一次 netPoll 送出）
}

void loop() {
    const uint32_t now = millis();

    // 熱路徑：先 App → STM32（控制指令，延遲最敏感），再 STM32 → App；幀到即轉，不批次
    drainUdp(now);
    drainUart(now);
    uartPump();

    // 計時與事件（皆為 millis() 比較，不阻塞）
    if (g_session.poll(now)) linkService(now, false);
    if (g_ownerLeft) {
        g_ownerLeft = false;
        g_linkDirty = false;
        endSession(now);
    }
    if (g_linkDirty) {
        g_linkDirty = false;
        linkService(now, false);
    }
    if ((uint32_t)(now - g_lastLinkMs) >= LINK_PERIOD_MS) linkService(now, true);
    if (!g_restartPending && (uint32_t)(now - g_lastNetPollMs) >= NET_POLL_MS) {
        g_lastNetPollMs = now;
        netPoll(now);   // 會阻塞的切換只在沒有 session、AP 上沒有手機時發生
    }
    if ((uint32_t)(now - g_lastNetMs) >= NET_PERIOD_MS) sendNet(now);
    if (!g_credSent && now >= CRED_BOOT_DELAY_MS) {
        g_credSent = true;
        sendCred();
    }
    if (g_restartPending && (int32_t)(now - g_restartAtMs) >= 0) doRestart();
#if ENABLE_OTA
    if ((uint32_t)(now - g_lastOtaMs) >= OTA_POLL_MS) {
        g_lastOtaMs = now;
        ArduinoOTA.handle();   // 平常只有 mDNS 計時檢查（µs）；真的在更新時才阻塞（已先結束 session）
    }
#endif
}
