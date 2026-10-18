# B570 Pilot 通訊協定 v1

三端共用的唯一規格：**STM32（平衡車）⇄ UART ⇄ ESP-01（Wi-Fi 橋接）⇄ UDP ⇄ Android App**。
可執行的參考實作在 `tools/b570_proto.py`（含測試向量與 PC 測試工具），規格與程式有出入時以本文件為準並同步修正。

---

## 1. 系統拓撲與連線方式

```
直連模式： Android App ──Wi-Fi(UDP)── ESP-01 SoftAP ──UART 230400── STM32F103 (USART2: PA2=TX, PA3=RX)
             192.168.4.x                192.168.4.1:8888
熱點模式： 手機開個人熱點（照用 4G/5G 上網）◀── ESP-01 以 STA 加入熱點 ──UART── STM32
             App 在熱點網段用 DISCOVER 廣播找到車子 IP，之後 UDP 8888 單播
```

| 項目 | 規格 | 理由 |
|---|---|---|
| Wi-Fi 模式 | ESP-01 開 SoftAP（WPA2），SSID 預設 `B570-XXXX`（晶片 ID 後 4 碼 hex），密碼 8 碼隨機 | 不需路由器，點對點延遲最低 |
| 手機連線 | Android 10+ `WifiNetworkSpecifier`（本地網路，無 INTERNET 能力） | **系統不會把它當預設網路，行動數據照常可用** |
| 配對 | 掃描 OLED 上顯示的 QR：`WIFI:T:WPA;S:<ssid>;P:<pw>;;` | 標準格式，相機 App 也能辨識 |
| 傳輸 | UDP，ESP 監聽 `8888`，回覆到最後一個合法 session 的來源位址 | 無連線建立、無重傳等待、無 Nagle |
| UART | 230400 bps 8N1，STM32 端 DMA 環形接收 + DMA 傳送 | 遙測幀 49 bytes（payload 42）≈ 2.1 ms |
| 低延遲 | App 持有 `WIFI_MODE_FULL_LOW_LATENCY` WifiLock，關閉手機 Wi-Fi 省電 | 避免 100~300 ms 的省電喚醒抖動 |
| 熱點模式 | ESP-01 改以 STA 加入**手機的個人熱點**（設定見 §3.6）；手機是基地台不會進入省電、ESP 關閉休眠 | 手機同時用行動網路；不必切換 Wi-Fi；Wi-Fi 延遲最低 |

---

## 2. 幀格式（UART 與 UDP 完全相同）

| 位移 | 長度 | 欄位 | 說明 |
|---|---|---|---|
| 0 | 1 | SOF0 | 固定 `0xA5` |
| 1 | 1 | SOF1 | 固定 `0x5A` |
| 2 | 1 | LEN | payload 長度 0..200（>200 視為錯誤，丟棄 1 byte 後重新同步） |
| 3 | 1 | TYPE | 訊息種類（見 §3） |
| 4 | 1 | SEQ | 發送端的滾動計數 0..255 |
| 5 | LEN | PAYLOAD | |
| 5+LEN | 2 | CRC16 | **little-endian**，計算範圍 = `LEN, TYPE, SEQ, PAYLOAD` |

- CRC：**CRC-16/CCITT-FALSE**（poly `0x1021`，init `0xFFFF`，不反射，xorout `0`）。`"123456789"` → `0x29B1`。
- 所有多位元組數值 **little-endian**；`f32` = IEEE-754 單精度。
- 最大幀長 207 bytes。
- 解析器必須能處理：前置雜訊（ESP 開機 74880 baud 亂碼）、一個 datagram 內多幀、幀被拆在兩次讀取中。CRC 錯誤時丟掉第 1 個 byte 並重新尋找 SOF。
- UDP：發送端**每個 datagram 放一個幀**；接收端仍需用串流解析器處理。

### SEQ 規則（請求 / 回覆配對與重送）
- **回覆幀的 SEQ = 請求幀的 SEQ**：STM32 的 `ACK`、`PARAM_VALUES`、`PONG`、`INFO`（`PARAM_SAVE` 的延後 ACK 也一樣），ESP 的 `HELLO_ACK`、`ESP_ACK`、`WIFI_INFO`、`ESP_PONG`、`STA_INFO`、`DISCOVER_ACK`。主動發送的 `TELEM`、`EVENT`、`LINK`、`CRED`、`CRED_REQ`、`NET` 使用發送端自己的滾動序號。
- App 的 SEQ 是**所有幀共用**的一個滾動計數（CTRL、PING、請求、HELLO…）；TELEM 的 `ctrl_seq` 只會是 CTRL 用過的 SEQ（§4.1）。
- App 的可靠請求**逾時重送時沿用同一個 SEQ 與同一份位元組**，並只接受 SEQ 相同的回覆（遲到的舊回覆不會被誤認）。
- STM32 對有副作用的 `ZERO_CAL`、`PARAM_SAVE`、`SEQ_PLAY`、`MOTOR 啟動`：同 TYPE + 同 SEQ 在 1.5 s 內再收到 = 重送，**只重發上次的回覆、不重做**（exactly-once；緊急停止之後遲到的啟動重送不會讓馬達重新啟動）；`PARAM_SAVE` 還在寫 Flash 時收到重送則不回覆，寫完的 ACK 會帶原 SEQ。
- `MOTOR` 停止 / 緊急停止、`PARAM_SET`、`PARAM_GET`、`PARAM_DEFAULTS`、`SEQ_LOAD`（見 §3.2）、`SEQ_STOP` 本身即可安全重送。
- 停機原因為 8（IMU）時 `MOTOR 啟動` 一律回 `NOT_READY`，需檢查 MPU6050 後重新開機。

### 測試向量（`python tools/b570_proto.py vectors`）
```
CRC16('123456789') = 0x29B1
CTRL speed=300 turn=-250 flags=0 seq=7     A5 5A 05 01 07 2C 01 06 FF 00 D4 4D
PING t=0x12345678 seq=1                    A5 5A 04 02 01 78 56 34 12 E3 C1
MOTOR start seq=2                          A5 5A 01 03 02 01 67 DD
PARAM_GET all seq=3                        A5 5A 01 10 03 FF B4 FA
PARAM_SET BAL_KP=23000.0 seq=4             A5 5A 05 11 04 00 00 B0 B3 46 98 6D
SEQ_LOAD 1 step (200,0,1000,0,0) seq=5     A5 5A 0B 20 05 00 01 01 C8 00 00 00 E8 03 00 00 FF 25
HELLO session=0xCAFEBABE seq=0             A5 5A 04 40 00 BE BA FE CA 65 78
CRED_REQ seq=9                             A5 5A 00 52 09 68 35
ACK ref=0x12 status=2 seq=10               A5 5A 02 91 0A 12 02 36 67
STA_SET on 'MyPhone'/'pass1234' seq=11     A5 5A 12 46 0B 01 07 4D 79 50 68 6F 6E 65 08 70 61 73 73 31 32 33 34 F8 9F
DISCOVER nonce=0x01020304 seq=12           A5 5A 04 48 0C 04 03 02 01 93 39
DISCOVER_ACK sta 172.20.10.5 seq=12        A5 5A 18 C5 0C 04 03 02 01 C3 B2 A1 00 01 AC 14 0A 05 00 09 42 35 37 30 2D 41 31 42 32 59 56
NET mode=2 172.20.10.5 'MyPhone' seq=13    A5 5A 0D 53 0D 02 AC 14 0A 05 07 4D 79 50 68 6F 6E 65 63 91
STA_INFO on/2 172.20.10.5 -52dBm seq=14    A5 5A 18 C4 0E 01 02 AC 14 0A 05 CC 07 4D 79 50 68 6F 6E 65 08 70 61 73 73 31 32 33 34 A2 EC
STA_GET seq=15                             A5 5A 00 47 0F 28 A9
STA_SET off (keep creds) seq=16            A5 5A 03 46 10 00 00 00 BA 07
TELEM v1.0 (40 B) seq=42                   A5 5A 28 81 2A D2 04 F5 FD 96 00 CC FC 21 00 78 00 76 00 DC 05 C8 05 B0 04 FA 00 32 00 78 00 00 00 84 04 03 00 FF 00 01 32 28 23 9C 01 4C 8F
TELEM v1.1 (42 B) ctrl 0x2A/3 seq=42       A5 5A 2A 81 2A D2 04 F5 FD 96 00 CC FC 21 00 78 00 76 00 DC 05 C8 05 B0 04 FA 00 32 00 78 00 00 00 84 04 03 00 FF 00 01 32 28 23 9C 01 2A 03 C7 E3
```
TELEM 範例的欄位（`tools/b570_proto.py` 的 `TELEM_EXAMPLE`）：tick 1234、pitch −5.23°、roll 1.50°、gyro_bal −820、gyro_turn 33、vel 120/118、pwm 1500/1480、pwm_bal/vel/turn 1200/250/50、tgt 120/0、11.56 V、flags 0x03、stop 0、seq_step 0xFF、seq_loop 0、src 1、link_q 50、heading 90.00°、loop 412 µs；v1.1 另加 ctrl_seq 0x2A、ctrl_age 3。

---

## 3. TYPE 分區與路由

| 範圍 | 方向 | ESP 行為 |
|---|---|---|
| `0x01–0x3F` | App → STM32 | 只轉送「目前 session 擁有者」送來的幀到 UART |
| `0x40–0x4F` | App → ESP | ESP 自己處理，**不轉送** |
| `0x50–0x5F` | ESP ⇄ STM32 | 只在 UART 上，**不會出現在 UDP** |
| `0x80–0xBF` | STM32 → App | ESP 從 UART 收到後，以單獨 datagram 送給 session 擁有者（無 session 則丟棄） |
| `0xC0–0xCF` | ESP → App | ESP 產生 |

### 3.1 App → STM32

| TYPE | 名稱 | Payload | 回覆 |
|---|---|---|---|
| `0x01` | CTRL | `i16 speed_mmps, i16 turn_permille, u8 flags(=0 保留)` (5B) | 無（以遙測呈現）。**同時是心跳** |
| `0x02` | PING | `u32 t`（App 自訂時間戳 ms） | `0x82 PONG` 原樣回傳 `u32 t` |
| `0x03` | MOTOR | `u8 action`：0=停止 1=啟動 2=緊急停止（停馬達+中止編排+目標歸零）。停止/緊急停止在收到時**立即**斷電；重送安全 | `0x91 ACK` |
| `0x04` | ZERO_CAL | 無。以當前傾角作為機械零點（**馬達停止時才允許**），並**自動把 RAM 中全部參數存入 Flash**（含尚未儲存的整定修改） | `ACK`，成功時另送 `PARAM_VALUES(id 6)`、`EVENT ZERO_CAL`，存檔完成後 `EVENT PARAMS_SAVED` |
| `0x10` | PARAM_GET | `u8 id`（`0xFF` = 全部） | `0x90 PARAM_VALUES` |
| `0x11` | PARAM_SET | 重複 `{u8 id, f32 value}` × 1..20 | `0x90 PARAM_VALUES`：回傳這些 id **實際套用後**（已夾限）的值 |
| `0x12` | PARAM_SAVE | 無。寫入 Flash（**馬達停止時才允許**） | `ACK`（0 OK / 2 BUSY 馬達運轉中 / 3 FAIL）+ `EVENT PARAMS_SAVED` |
| `0x13` | PARAM_DEFAULTS | 無。RAM 中參數恢復預設（不自動存檔） | `PARAM_VALUES`（全部） |
| `0x20` | SEQ_LOAD | `u8 offset, u8 total, u8 count, Step[count]`（count ≤ 24） | `ACK`（1 BAD_ARG / 2 BUSY 播放中） |
| `0x21` | SEQ_PLAY | `u8 loops`（0 = 無限循環） | `ACK`（4 NOT_READY = 馬達未啟動或未載入） |
| `0x22` | SEQ_STOP | 無 | `ACK` |
| `0x30` | INFO_GET | 無 | `0xA0 INFO` |

**CTRL 語意**
- `speed_mmps`：目標車速 mm/s，正 = 前進。韌體夾限於 ±`MAX_SPEED`。
- `turn_permille`：轉向 -1000..1000（‰ of `MAX_TURN`），正 = 右轉（順時針，從上往下看）。
- App 連線期間持續送 CTRL（沒有操作就送 0,0）作為心跳，頻率由 App 調節、**每次都送當下最新的值，舊值不排隊**：搖桿在動或停下後 300 ms 內 50 Hz；靜止 10 Hz；量到的控制延遲偏高（壅塞，EMA > 150 ms，< 80 ms 才恢復）時最多約 16 Hz；放開搖桿（值變成 0）那一刻立即送。最低 10 Hz，`FAILSAFE_MS`（預設 400 ms）內至少 4 個 CTRL；ESP session 逾時 1500 ms 也足夠。
- 失聯保護：STM32 超過 `FAILSAFE_MS` 沒收到任何 App 幀 → 目標歸零（車子原地保持平衡）、中止編排、送 `EVENT LINK_LOST`。
- 編排播放中：`|speed| ≤ 30` 且 `|turn| ≤ 50` 的 CTRL 只當心跳；超過門檻 → **手動接管**，中止編排（`EVENT SEQ_ABORTED arg=1`）。

### 3.2 Step（編排步驟，8 bytes）

| 位移 | 型別 | 欄位 | 說明 |
|---|---|---|---|
| 0 | i16 | speed | mm/s |
| 2 | i16 | turn | ‰ |
| 4 | u16 | dur_ms | 20..30000 |
| 6 | u8 | mode | 0 HOLD：定值；1 SINE_TURN：speed 定值，turn = turn·sin(2πt/P)；2 SINE_SPEED：speed = speed·sin(2πt/P)，turn 定值；3 RAMP：從上一步結束值線性變化到本步數值 |
| 7 | u8 | period | 正弦週期，單位 20 ms（1..255 → 20 ms..5.1 s），0 視為 50（1 s） |

- 最多 48 步。`SEQ_LOAD` 可分批（offset 遞增，total 為總步數），最後一批收到後才可 PLAY。
- **重送安全（ACK 遺失時）**：重送「剛剛那一批」（offset + count == 已收步數）會回 OK；播放中再收到 `SEQ_PLAY` 回 OK 但**不會從頭播**。
- 播放中不能 `SEQ_LOAD`（回 BUSY）：要換編排，App 先送 `SEQ_STOP` 再上傳。
- 播放中仍經過加速度限制（`ACCEL`, `TURN_ACCEL`），保證平順。
- 播放需馬達已啟動；失聯、馬達停止、MOTOR 指令都會中止播放。

### 3.3 STM32 → App

| TYPE | 名稱 | Payload |
|---|---|---|
| `0x81` | TELEM | 42 bytes，見 §4.1，頻率 = `TELEM_HZ`（解碼端只要求 ≥ 40 bytes，後面的欄位可選） |
| `0x82` | PONG | `u32 t`（PING 原值） |
| `0x90` | PARAM_VALUES | `u8 count, {u8 id, f32 value} × count` |
| `0x91` | ACK | `u8 ref_type, u8 status` |
| `0xA0` | INFO | `u8 proto_ver(=1), u8 fw_major, u8 fw_minor, u8 fw_patch, u8 param_count, u8 max_steps, u8 angle_algo, u8 reserved, char model[12]`（"B570"，補 0）= 20B |
| `0xA1` | EVENT | `u8 code, i16 arg`（見 §4.3） |

ACK status：`0 OK`、`1 BAD_ARG`、`2 BUSY`、`3 FAIL`、`4 NOT_READY`、`5 UNKNOWN_TYPE`。

### 3.4 App ⇄ ESP

| TYPE | 名稱 | Payload | 說明 |
|---|---|---|---|
| `0x40` | HELLO | `u32 session_id`（App 隨機） | App 連上 Wi-Fi 後每 100 ms 送一次直到收到 HELLO_ACK（加快連線）；連線中約 1 s 收不到資料時每 300 ms 重送 |
| `0xC0` | HELLO_ACK | `u8 status(0 OK,1 BUSY), u8 proto_ver, u8 esp_major, u8 esp_minor, u8 esp_patch, u8 channel, u16 udp_port, u32 chip_id` (12B) | |
| `0x41` | WIFI_GET | 無 | |
| `0xC1` | WIFI_INFO | `u8 channel（實際）, u8 stations, u8 tx_power_dbm, u8 cfg_channel（設定值，0 = 自動）, u32 uptime_s, u32 free_heap, u32 uart_rx_ok, u32 uart_rx_err, u32 udp_rx, u32 udp_tx, u8 ssid_len, ssid[], u8 pw_len, pw[]` | |
| `0x42` | WIFI_SET | `u8 ssid_len, ssid[], u8 pw_len, pw[], u8 channel(0=自動,1..13)` | 回 `ESP_ACK` 後 500 ms 重新開機 |
| `0x43` | ESP_PING | `u32 t` | 回 `0xC2 ESP_PONG u32 t`（量測純 Wi-Fi 延遲） |
| `0x44` | BYE | 無 | 立即結束 session（只接受擁有者；**沒有回覆**） |
| `0x45` | WIFI_FACTORY | 無 | 重新產生 SSID/密碼，回 `ESP_ACK` 後重開機 |
| `0xC3` | ESP_ACK | `u8 ref_type, u8 status` | |
| `0x46` | STA_SET | `u8 enable(0/1), u8 ssid_len(0..32), ssid[]（UTF-8）, u8 pw_len(0 或 8..63), pw[]（ASCII 0x20..0x7E）` | 設定熱點模式。enable=1 時 ssid_len 須 ≥ 1；enable=0 時可傳 ssid_len=0 表示保留原本存的熱點帳密。回 `ESP_ACK` 後 500 ms 重新開機（只接受擁有者或無 session） |
| `0x47` | STA_GET | 無 | 回 `0xC4 STA_INFO` |
| `0xC4` | STA_INFO | `u8 enabled, u8 state(0 未啟用, 1 搜尋熱點中, 2 已連上熱點, 3 找不到熱點→已改回直連), u8 ip[4], i8 rssi_dbm(0 = 無), u8 ssid_len, ssid[], u8 pw_len, pw[]` | ip = 車子在熱點網段的位址（state 2 才有效，其餘 0）；密碼只回給擁有者，其他人 pw_len = 0 |
| `0x48` | DISCOVER | `u32 nonce` | **任何人、任何時候**都可送（不需 session），可用廣播；直連與熱點模式都會回 |
| `0xC5` | DISCOVER_ACK | `u32 nonce, u32 chip_id, u8 mode(0 直連 AP, 1 熱點 STA), u8 ip[4], u8 busy(1 = 已有其他手機在控制), u8 ssid_len, ssid[]` | **單播**回給發送者；ssid = 車名（AP 的 SSID，例如 `B570-A1B2`，App 用它比對記住的車） |

**SSID / 密碼限制**（App 與 ESP 都要檢查，確保 QR 不需跳脫字元、且能塞進 OLED 的 QR v3）：
- SSID：1..12 字元，`[A-Za-z0-9_-]`
- 密碼：8..16 字元，可列印 ASCII（0x21..0x7E），**不得含** `; , : \ "`

**Session 規則（ESP）**
1. 收到 HELLO：若無 session、或來源 IP 與目前擁有者相同、或擁有者已逾時 → 成為擁有者，回 OK；否則回 BUSY。擁有者以 **IP:port** 記錄（同一支手機換了 socket port，重送 HELLO 即接手）。
2. 擁有者超過 **1500 ms** 沒有任何封包 → session 結束；擁有者手機的 Wi-Fi 斷線（以 DHCP 表比對 MAC）→ 立即結束。
3. 非擁有者送來的 `0x01–0x3F` 一律丟棄。
4. **App 必須**在重建 socket 後、或約 1 秒收不到任何回應時重送 HELLO（ESP 可能已重開機或 session 已逾時）。
5. `WIFI_SET` / `WIFI_FACTORY` 只接受擁有者（或沒有 session 時）；否則 `ESP_ACK status 2 BUSY`。`BYE` 非擁有者送來則忽略（BYE 永遠不回覆）。`ESP_ACK` status：0 OK、1 BAD_ARG、2 BUSY、3 FAIL（寫入失敗）、5 UNKNOWN_TYPE（0x49–0x4F）。`STA_SET` 與 `WIFI_SET` 規則相同（只接受擁有者或無 session）。
6. 每個 UDP datagram 獨立解析，不跨 datagram 拼接幀。

### 3.5 ESP ⇄ STM32（僅 UART）

| TYPE | 方向 | 名稱 | Payload |
|---|---|---|---|
| `0x50` | ESP→STM32 | LINK | `u8 state(0 無裝置, 1 有手機連上 Wi-Fi, 2 App session 進行中), u8 stations`。狀態改變時立即送，平時每 500 ms 送一次（兼 ESP 存活心跳） |
| `0x51` | ESP→STM32 | CRED | `u8 ssid_len, ssid[], u8 pw_len, pw[], u8 channel`。ESP 開機 1 秒後主動送一次，收到 CRED_REQ 時再送 |
| `0x52` | STM32→ESP | CRED_REQ | 無。STM32 尚未拿到 CRED 前每 1 秒送一次（ESP 收到時回 CRED 與 NET） |
| `0x53` | ESP→STM32 | NET | `u8 mode(0 直連, 1 搜尋熱點中, 2 熱點已連上, 3 找不到熱點已改回直連), u8 ip[4], u8 ssid_len, ssid[]`。mode 1–3 的 ssid = 熱點名稱、mode 0 = 車子 AP 名稱；ip = mode 2 時車子在熱點網段的位址。狀態改變時立即送、收到 CRED_REQ 時送、平時每 2 秒送一次。OLED 依此顯示配對 QR（直連）或熱點資訊 |

### 3.6 熱點模式（ESP 行為）

1. 用 `STA_SET` 啟用後重開機：ESP 以 STA 加入手機熱點（`WIFI_NONE_SLEEP`，最多 12 s）。連上後**關閉自己的 AP**、送 `NET mode 2`。熱點斷線時 ESP 立即結束 session（同 AP 模式擁有者手機斷線）。
2. App 在手機熱點網段送 `DISCOVER`（子網路廣播 + 上次的 IP 單播，每 300 ms），用 `DISCOVER_ACK.ssid` 比對記住的車，**之後一律送往 DISCOVER_ACK 的來源位址**（payload 的 ip 只供顯示），HELLO / Session 規則與直連完全相同（UDP 8888 單播）。
   找多久：一般 4 s；這台車設定為熱點模式**且手機熱點開著**時 **20 s**（涵蓋 ESP 加入熱點的時間：剛開機最多 12 s〔第 1 點〕；已改開直連 AP 的車再試熱點，要等 AP 閒置 12 s〔改開 AP 後第一次〕或 6 s，再加上找熱點與加入約 2–5 s〔第 3 點〕。太早放棄會改去等一個隨後就關掉的 AP，而且直連需要手機 Wi-Fi）；沒開熱點 1.5 s（也許在同一個 Wi-Fi）；剛用 `STA_SET` 切換時最多 60 s。找不到且有 AP 密碼才改直連。
   改開 AP 超過 3 分鐘、一直沒有手機連上的車改為每 30 s 才試一次（第 3 點），可能在 20 s 之後才出現在熱點上：App 應繼續在熱點上找（例如連線頁每 5 s 一次，找到就標示「在熱點上」），不要只叫使用者打開 Wi-Fi。App 的 `ConnectPlanner.HOTSPOT_JOIN_DISCOVERY_MS` 依 `b570_netsm.h` 的 `JOIN_BUDGET_MS` / `FIRST_IDLE_MS` / `FAST_IDLE_MS` 訂定，改其中一邊要一起檢查。
   連線中：約 1 s 收不到車子 → 每 300 ms 重送 HELLO、每 1 s 重新 DISCOVER（車子重開機後 IP 可能變了）；8 s 完全沒有回應、或熱點介面消失 2.5 s → 斷線，交給自動重連（重新找車 20 s → 直連）。
3. 12 s 內找不到熱點，或熱點斷線超過 15 s：改開直連 AP（同一組 `B570-XXXX` 帳密，OLED 顯示 QR），`NET mode 3`；在**沒有手機連上 AP、也沒有 session** 時再試熱點（`WIFI_AP_STA`，AP 照常運作，每次最多 8 s），找到就切回熱點模式。
   重試間隔（AP 要連續閒置多久才試）：每次改開 AP 起的**前 3 分鐘 6 s**，其中**第一次 12 s**（從 AP 真正開起來、OLED 顯示 QR 起算：使用者可能正要掃描直連，這段時間射頻一直留在 AP 頻道），之後 **30 s**。直連的手機離開 AP（例如打開手機熱點時系統關掉 Wi-Fi、或 App 中斷連線）後重新開始 **1 分鐘**的 6 s 間隔（不縮短還沒結束的 3 分鐘）。
   提早放棄：重試開始 0.5 s 後，SDK 回報找不到熱點（`STATION_NO_AP_FOUND`）、連線失敗（`STATION_CONNECT_FAIL`）或密碼錯誤（`STATION_WRONG_PASSWORD`）就立即結束這次重試、切回純 AP，不等滿 8 s；找到熱點、還在連線中則照樣給滿 8 s。熱點不在時快速期每輪約 8 s（閒置 6 s + 掃描約 2 s），射頻離開 AP 頻道約 25% 的時間。**連續 2 次**密碼錯誤 → 不再用快速間隔（改 30 s），直到下次連上熱點或重開機（只看 1 次會誤判：訊號很弱時 4-way handshake 逾時 SDK 也回報密碼錯誤）。
   戶外常見「先開車子、後開手機熱點」或開車途中走出熱點範圍又回來：快速期內熱點可用後通常 15 s 內、最久約 20 s 連回（最慢的是剛改開 AP、還在等第一次重試時）；快速期過後最久約 40 s。
   重試中有手機連上 AP 或有 session → 立即停止 STA 連線（不切模式、不阻塞），AP 再度閒置時切回純 AP 並重新計時。會阻塞的模式切換只在 AP 閒置時進行，每次切換後都重新設定 `WIFI_NONE_SLEEP`。
4. 手機熱點限制：ESP-01 只支援 **2.4 GHz** 與 **WPA2-Personal / 開放**。熱點需設為 2.4 GHz（iPhone：「最大化相容性」；Android：頻段 2.4 GHz 或「延伸相容性」），安全性 WPA2 或 WPA2/WPA3 混合（不可只開 WPA3）。

STM32 判斷「App 在線」= 最近一次 LINK.state == 2 **且** 距上一個 App 幀 < `FAILSAFE_MS`。

---

## 4. 資料定義

### 4.1 TELEM（0x81，42 bytes；v1.0 韌體 40 bytes）

| 位移 | 型別 | 欄位 | 單位 |
|---|---|---|---|
| 0 | u16 | tick | 控制週期計數（10 ms），會溢位 |
| 2 | i16 | pitch | 0.01°（平衡傾角，前傾為正） |
| 4 | i16 | roll | 0.01° |
| 6 | i16 | gyro_bal | MPU6050 原始值，16.4 LSB = 1 °/s |
| 8 | i16 | gyro_turn | 同上（Z 軸） |
| 10 | i16 | vel_l | 左輪 mm/s |
| 12 | i16 | vel_r | 右輪 mm/s |
| 14 | i16 | pwm_l | 左馬達 PWM（±6900） |
| 16 | i16 | pwm_r | 右馬達 PWM |
| 18 | i16 | pwm_bal | 直立環輸出 |
| 20 | i16 | pwm_vel | 速度環輸出 |
| 22 | i16 | pwm_turn | 轉向環輸出 |
| 24 | i16 | tgt_speed | 經加速度限制後的目標速度 mm/s |
| 26 | i16 | tgt_turn | 經限制後的目標轉向 ‰ |
| 28 | u16 | battery | 0.01 V |
| 30 | u8 | flags | bit0 MOTORS_ON、bit1 APP_LINK、bit2 SEQ_PLAYING、bit3 LOW_BATT、bit4 UPRIGHT（\|pitch\|<FALL_ANGLE）、bit5 FAILSAFE、bit6 PARAMS_DIRTY（有未存檔修改）、bit7 CRED_OK |
| 31 | u8 | stop_reason | 0 RUNNING、1 BOOT、2 KEY、3 APP、4 PICKUP、5 FALL、6 LOW_BATT、7 ESTOP、8 IMU（姿態感測器中斷停止，需檢查 MPU6050 後重開機） |
| 32 | u8 | seq_step | 目前步驟索引；未播放 = `0xFF` |
| 33 | u8 | seq_loop | 目前循環次數 |
| 34 | u8 | src | 控制來源：0 無、1 App、2 編排 |
| 35 | u8 | link_q | 最近 1 秒收到的 App 幀數（飽和 255） |
| 36 | i16 | heading | 0.01°，由 Z 軸陀螺積分（-180..180，會漂移，僅供顯示） |
| 38 | u16 | loop_us | 控制中斷執行時間 µs |
| 40 | u8 | ctrl_seq | 最後收到的 `CTRL` 幀 SEQ（還沒收過 = 0） |
| 41 | u8 | ctrl_age | 收到該 `CTRL` 之後經過的控制週期數 = 這筆遙測的 tick − 收到時的 tick（還沒收過或 ≥ 255 = 255） |

**ctrl_age 與控制延遲**（v1.1）：STM32 記下收到 CTRL 時的 tick（UART 中斷、或控制週期開頭輪詢 DMA），**下一個**控制週期（`Motion_Tick` 先 tick+1 再套用）才套用，遙測在同一個控制中斷的最後送出。所以套用那一週期送出的遙測 `ctrl_age = 1`，已套用 `max(ctrl_age − 1, 0)` 個週期（`ctrl_age = 0` 只在控制中斷進行中、tick+1 之後才收到時出現：下一週期才套用）。App：

`控制延遲 = (收到這筆遙測的時間 − 送出 SEQ = ctrl_seq 那個 CTRL 的時間) − max(ctrl_age − 1, 0) × 10 ms`（< 0 夾成 0；ctrl_age = 255、SEQ 不是 CTRL、超過 2 s 的樣本不採用）

= 手機 → Wi-Fi → ESP → UART → 主控板套用（含等下一個控制週期 0–10 ms），**再加上這筆遙測的下行時間**（UART 2.1 ms + ESP + Wi-Fi，約 3–8 ms），是控制延遲的上限。正常比 `ESP_PING` 來回多 4–15 ms；多 30 ms 以上 = 慢在 UART / 主控板。參考實作：`tools/b570_proto.py` 的 `control_latency_ms()`。

### 4.2 參數表（PARAM id）

| id | 鍵 | 預設 | 最小 | 最大 | 說明 |
|---|---|---|---|---|---|
| 0 | BAL_KP | 22500 | 0 | 60000 | 直立環 Kp（原廠 ×100 尺度） |
| 1 | BAL_KD | 108 | 0 | 600 | 直立環 Kd |
| 2 | VEL_KP | 16000 | 0 | 40000 | 速度環 Kp |
| 3 | VEL_KI | 80 | 0 | 400 | 速度環 Ki |
| 4 | TURN_KP | 4200 | 0 | 12000 | 轉向環 Kp |
| 5 | TURN_KD | 0 | 0 | 300 | 轉向環 Kd（陀螺阻尼，只在前進/後退時作用） |
| 6 | ZERO_ANGLE | 0 | -10 | 10 | 機械零點 ° |
| 7 | MAX_SPEED | 500 | 100 | 1000 | 最高車速 mm/s |
| 8 | MAX_TURN | 40 | 5 | 80 | 最大轉向量（原廠單位：一般 27、高速 54） |
| 9 | ACCEL | 2000 | 100 | 6000 | 速度變化率上限 mm/s²（越大起步越快） |
| 10 | TURN_ACCEL | 12000 | 500 | 20000 | 轉向變化率上限 ‰/s |
| 11 | FAILSAFE_MS | 400 | 150 | 2000 | 失聯保護時間 ms |
| 12 | TELEM_HZ | 50 | 10 | 100 | 遙測頻率 Hz（實際 = 100 / round(100/值)，回傳的是實際頻率，例如設 70 → 100） |
| 13 | ANGLE_ALGO | 2 | 1 | 3 | 姿態演算法：1 DMP、2 卡爾曼、3 互補（**存檔後重新開機才生效**；目前運作中的演算法看 `INFO.angle_algo`） |
| 14 | PICKUP_EN | 1 | 0 | 1 | 拿起偵測自動停機 |
| 15 | LOWBAT_V | 10.0 | 9.0 | 12.0 | 低電壓停機門檻 V |
| 16 | FALL_ANGLE | 40 | 20 | 60 | 傾倒停機角度 ° |

整數型參數（ANGLE_ALGO、PICKUP_EN、FAILSAFE_MS、TELEM_HZ）由韌體四捨五入；超出範圍一律夾限，回傳實際值。

### 4.3 EVENT code

| code | 名稱 | arg |
|---|---|---|
| 1 | MOTORS_ON | 來源：1 按鍵、2 App、3 放下自動啟動 |
| 2 | MOTORS_OFF | stop_reason（含 8 IMU） |
| 3 | PICKED_UP | 0 |
| 4 | FELL | 傾角 ×100 |
| 5 | LOW_BATTERY | 電壓 ×100 |
| 6 | SEQ_DONE | 0 |
| 7 | SEQ_ABORTED | 1 手動接管、2 失聯、3 馬達因保護/按鍵停止、4 收到 SEQ_STOP 或 MOTOR 停止/緊急停止 |
| 8 | ZERO_CAL | 新零點 ×100 |
| 9 | PARAMS_SAVED | ACK status |
| 10 | BOOT | 韌體版本 (major<<8 \| minor) |
| 11 | LINK_LOST | 0 |

---

## 5. 時序建議

| 端 | 行為 |
|---|---|
| App | 連上後：HELLO（每 100 ms 直到 ACK）→ INFO_GET → PARAM_GET(0xFF) → 開始 CTRL（10–50 Hz，§3.1）、每 500 ms 一次 PING、每 2 s 一次 ESP_PING、每 3 s 一次 WIFI_GET（熱點模式另外每 3 s 一次 STA_GET）。收到 ACK/PARAM_VALUES 逾時 400 ms 重送最多 3 次 |
| ESP | UART→UDP 每幀立即送出；UDP→UART 立即寫入（每圈先收 UDP 再收 UART）；LINK 每 500 ms；NET 狀態改變時 + 每 2 s；session 逾時 1500 ms |
| STM32 | 控制 100 Hz（MPU6050 INT 驅動）；遙測依 TELEM_HZ；事件排隊在下一個遙測空檔送出 |

延遲預算（典型）：手機等下一個 20 ms 傳送刻度 0~20 ms（推動搖桿的第一個指令）、手機 → ESP 2~6 ms、ESP 轉 UART < 1 ms、UART 0.5 ms、等待下一個控制週期 0~10 ms，**手機送出 → 車上套用 < 20 ms**；App 顯示的「控制 xx ms」另含遙測下行約 3–8 ms（§4.1）。之後還有 `ACCEL` / `TURN_ACCEL` 斜率限制與平衡車本身的動態（速度環），那是「反應」而不是通訊延遲。
