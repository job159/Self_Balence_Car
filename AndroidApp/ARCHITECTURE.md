# B570 Pilot — Android 架構與 API 參考（給畫面工程師）

App `B570 Pilot`，package `com.b570.pilot`，minSdk 29 / target 35，Kotlin 2.0.21 + Compose（BOM 2024.12.01，Material3）。
通訊協定以 `docs/PROTOCOL.md` 為準（測試向量都在 `app/src/test` 重現）。

> **橫向改版（2026-10）**：App 只有橫向（sensorLandscape）、沉浸式、搖桿型遙控器介面，既有功能全部保留。
> 版面規則、導覽軌 / 內距、駕駛全螢幕 + 滑入式導覽、遙控器元件、**安全不變式**、6 位畫面工程師的分工都在 **§11**，請先讀。
> **整合完成（2026-10-17）**：六個畫面已合併、建置與單元測試（含駕駛多點觸控 UI 測試）全過；
> 最終版面、整合時的修正、驗證方式與還需要實機確認的項目見 **§11.8**。
> **第二次整合（2026-10-17）**：**360° 搖桿**（駕駛預設模式，像手把的圓形類比搖桿）+ **快速連線**（開 App 自動連線、附近的車、
> 斷線自動重連、桌面捷徑）已合併；之後的安全審查修正見 **§11.10**（182 個單元測試全過）。改了什麼、怎麼驗證、還要實機確認什麼見 **§11.9**。
> **第三次整合（2026-10-17）**：**熱點模式**（車子的 ESP 加入手機個人熱點，手機照用 4G/5G，「行動網路也能控制」）+
> **控制延遲量測**（TELEM v1.1 的 ctrl_seq / ctrl_age：HUD 的「控制 xx ms」= 手機 → 車上真正套用）+ 上行防堆積（`ControlPacer`）已合併，
> 見 **§11.11**（API、畫面、整合修正、驗證、實機待確認）。

建置（一律用序列化腳本，同一時間只能跑一個 Gradle）：
```
bash ".../scratchpad/locked_gradle.sh" assembleDebug testDebugUnitTest        # 298 個測試，含 Robolectric 的 DriveMultiTouchTest（約 3 分鐘）
bash ".../scratchpad/locked_gradle.sh" assembleRelease                         # R8 + 資源壓縮，只打包 ARM
```

---

## 0. 規則（請先讀）

**檔案所有權（橫向改版：6 位畫面工程師，橫向版面建議見 §11.7）**
| 工程師 | 你唯一可以修改的目錄 | 入口函式（簽名固定，不可改） |
|---|---|---|
| 1. 連線 + QR 掃描 | `ui/screens/connect/`、`ui/screens/scanner/` | `fun ConnectScreen(vm: AppViewModel, nav: AppNavigator, modifier: Modifier = Modifier)`、`fun QrScannerScreen(vm: AppViewModel, nav: AppNavigator, modifier: Modifier = Modifier)` |
| 2. 駕駛 | `ui/screens/drive/` | `fun DriveScreen(vm: AppViewModel, nav: AppNavigator, modifier: Modifier = Modifier)` |
| 3. 監測 | `ui/screens/monitor/` | `fun MonitorScreen(vm: AppViewModel, nav: AppNavigator, modifier: Modifier = Modifier)` |
| 4. 整定 | `ui/screens/tune/` | `fun TuneScreen(vm: AppViewModel, nav: AppNavigator, modifier: Modifier = Modifier)` |
| 5. 編排 | `ui/screens/choreo/` | `fun ChoreoScreen(vm: AppViewModel, nav: AppNavigator, modifier: Modifier = Modifier)` |
| 6. 設定 | `ui/screens/settings/` | `fun SettingsScreen(vm: AppViewModel, nav: AppNavigator, modifier: Modifier = Modifier)` |

- 所有入口都是 `@Composable`，路徑 `app/src/main/java/com/b570/pilot/ui/screens/<pkg>/<Name>Screen.kt`。
- 在自己的 package 內可以自由新增檔案、private composable、自己的小 ViewModel／state holder。
- **不要修改** package 外的任何檔案（`protocol/ link/ data/ ui/theme ui/components ui/shell`、`AppViewModel`、`AppNavigator`、Gradle、Manifest、res）。
  需要共用元件沒有的東西 → 在自己 package 內做 private 版本。不要新增 Gradle 依賴（CameraX、ML Kit 已加好）。
- 所有使用者看得到的文字直接寫在 Kotlin（繁體中文），不用 strings.xml。
- 刪掉佔位用的 `ConstructionPlaceholder(...)`。

**版面（橫向，詳見 §11）**：shell 傳進來的 `modifier` 只有 `fillMaxSize()`。App 只有橫向（sensorLandscape）、沉浸式（系統列隱藏）。
- 一般分頁（監測 / 整定 / 編排 / 設定）畫在**左側導覽軌**與**頂部狀態條**「底下」：
  可捲動內容 `LazyColumn(contentPadding = LocalScreenPadding.current + PaddingValues(horizontal = 16.dp))`；固定版面 `Modifier.screenPadding()`。
- 駕駛是**全螢幕**遙控器（沒有導覽軌與狀態條）：`LocalScreenPadding` = 安全區；用 ≡ 鈕 `nav.openNavOverlay()` 叫出滑入式導覽。
- Connect / Scanner 的 `LocalScreenPadding` = 安全區（瀏海 + 系統列）。`LocalScreenPadding` 不含鍵盤，輸入框所在的捲動容器自己加 `imePadding()`。
- 尺寸目標 640 × 320、780 × 360 ~ 915 × 412、1280 × 800 dp：一律 `BoxWithConstraints`；字級 1.3 倍也不能切字（側欄可捲動）。
- 安全不變式（§11.6）改版時不可破壞。

**效能規則**
1. `vm.telemetry`（約 50 Hz）只在**小的葉節點**收集，或在繪製階段讀取（`drawBehind{}`、`graphicsLayer{}`、`offset{}` 的 lambda 內）。一般文字、狀態用 `vm.telemetrySlow`（≤10 Hz）。
2. 曲線一律用 `LineChart`（直接讀 `vm.history`，不重組）。不要把遙測堆進 `mutableStateListOf`。
3. 繪圖迴圈零配置：Path / Brush / TextLayout 放 `drawWithCache` 或 `remember`；用 lambda 版 modifier（`offset { }`、`graphicsLayer { }`）。
4. 門檻判斷用 `remember { derivedStateOf { ... } }`（例如「電量 < 20 %」），避免高頻重組。
5. `setControl*` 是原子寫入，可在每次搖桿事件直接呼叫；不要自己開 50 Hz 迴圈送 CTRL（連線層已經固定 50 Hz 送）。
6. 所有 suspend 指令都是 main-safe，直接在 `rememberCoroutineScope().launch {}` 呼叫；不要包 `Dispatchers.IO`。
7. Lazy 清單要給 `key`。

**中文語氣**：台灣繁體、短、口語、可行動。先說結果、再說下一步：「馬達運轉中，請先停止再儲存」。
用台灣用語：連線（非連接）、設定（非設置）、訊號、螢幕、資料、預設、儲存、介面、網路、軟體。全形標點「，。：？！」，數字與單位用半形並空一格（`12 ms`、`11.6 V`）。
按鈕 2–4 字（儲存、套用、重試、中斷連線）。錯誤訊息不要出現英文代碼。

---

## 1. 套件結構

```
com.b570.pilot
├─ B570Application        Application，持有 CarRepository 單例
├─ MainActivity           橫向（sensorLandscape）、singleTop、沉浸式 + edge-to-edge（瀏海 SHORT_EDGES）、依設定（連線中 / 自動重連中）保持螢幕常亮；onStop / 失去視窗焦點自動 stopControl()；
│                         桌面捷徑（onCreate 新啟動 / onNewIntent）→ vm.handleLaunchRequest()
├─ protocol/   純 Kotlin（無 Android）：Crc16、Frame/FrameCodec/FrameParser、MsgType、訊息編解碼、Telemetry、Params、SeqStep
├─ link/       WifiCredentials(+QR)、ConnectionState、CarLink、CarConnection（UDP 實作：直連 + 熱點）、LinkPermissions、
│              LinkTypes（LinkMode / LinkRequest / DiscoveredCar / HotspotConfig）、HotspotNet（熱點判斷 + DISCOVER）、
│              ControlTiming（控制延遲 + 上行幀數 + LinkQSettleTracker）、ControlPacer（CTRL 送不送）
├─ data/       CarRepository、TelemetryHistory、CarEvent、LinkStats/EspInfo/SavedCar/AppSettings/SavedSequence、Stores、DemoTelemetrySource、SequencePresets、
│              QuickConnect（ReconnectStatus / ReconnectPolicy / NearbyCars）、Shortcuts（LaunchRequest / CarShortcuts）、
│              Hotspot（ConnectPlan / ConnectPlanner / HotspotSwitchStatus）、LinkMetrics（LatencyWindow / UplinkLossEstimator）
└─ ui/
   ├─ AppViewModel, AppNavigator(Route, MainTab)
   ├─ theme/       B570Theme（colors/type/spacing/radii）、AccentPalette、Modifier.cockpitBackground()/glass()/neonGlow()
   ├─ components/  共用元件（見 §6）
   ├─ shell/       B570Root、左側導覽軌 NavRail、頂部狀態條 StatusStrip（含訊號中斷 / 自動重連提示）、駕駛的滑入式導覽 NavOverlay、HudStatusRow、ReconnectBadge、
   │               ShellRouting（rootRouteOf + 重連文字，純函式）（畫面可呼叫公開函式，不要改檔）
   └─ screens/     各畫面（見所有權表）
```

---

## 2. AppViewModel（`com.b570.pilot.ui.AppViewModel`）

收集：`val x by vm.flow.collectAsStateWithLifecycle()`（`androidx.lifecycle.compose`）。

**資料流**
| 屬性 | 型別 | 說明 |
|---|---|---|
| `connectionState` | `StateFlow<ConnectionState>` | 見 §4 |
| `telemetry` | `StateFlow<Telemetry?>` | 每筆遙測（~50 Hz），未連線為 null |
| `telemetrySlow` | `StateFlow<Telemetry?>` | 節流版（≤10 Hz） |
| `linkStats` | `StateFlow<LinkStats>` | 控制延遲（+P95）、來回延遲、Wi-Fi 延遲、上行遺失 / 連線品質、fps、遺失率、RSSI、CRC 錯誤、同時連兩個 Wi-Fi（§5） |
| `linkMode` | `StateFlow<LinkMode?>` | 目前連線路徑：`DIRECT`「直連」/ `HOTSPOT`「手機熱點」；沒連線 = null |
| `staInfo` | `StateFlow<StaInfo?>` | 車子的熱點模式狀態（STA_INFO）：連上時讀一次，熱點模式中每 3 s 更新 |
| `hotspotSupported` | `StateFlow<Boolean?>` | ESP 支援熱點模式；false = ESP 韌體太舊（STA_GET / STA_SET 回 UNKNOWN_TYPE）；null = 還不知道 |
| `hotspotSwitch` | `StateFlow<HotspotSwitchStatus?>` | 改用熱點模式後的切換進度（重開機 → 請開啟手機熱點 → 尋找車子 / 沒找到）；null = 沒在切換 |
| `carInfo` | `StateFlow<Info?>` | 主控板韌體資訊 |
| `espInfo` | `StateFlow<EspInfo>` | `hello: HelloAck?`、`wifi: WifiInfo?`（每 3 s 更新） |
| `params` | `StateFlow<Map<Int, Float>>` | 參數 id → 車上目前值；**未知時為空 map**，顯示 `ParamDef.default` |
| `currentCar` | `StateFlow<WifiCredentials?>` | 目前／最後連線的車 |
| `currentCarName` | `StateFlow<String>` | 暱稱 > SSID；展示模式為「展示車」 |
| `isDemo` | `StateFlow<Boolean>` | 展示模式中 |
| `loadedSequence` | `StateFlow<List<SeqStep>>` | 最後成功載入車上的編排（對照 `telemetry.seqStep`） |
| `events` | `SharedFlow<CarEvent>` | 提示事件（shell 已顯示 toast，畫面可另外收集做特效） |
| `recentEvents` | `StateFlow<List<CarEvent>>` | 最近 50 則事件（最新在前，從 App 啟動起保留；另含不跳 toast 的連線變化：訊號中斷 / 連線失敗 / 已中斷連線） |
| `nav` | `AppNavigator` | 導覽狀態放在 ViewModel，Activity 重建時分頁與覆蓋層不會重置 |
| `reconnect` | `StateFlow<ReconnectStatus?>` | 斷線後自動重連的進度（第幾次 / 共幾次 / 下次嘗試時間）；null = 沒在重連。重連期間連線狀態在 Failed / Idle / 連線中之間切換 |
| `reconnectGaveUp` | `StateFlow<ReconnectStatus?>` | 自動重連放棄時最後一次的進度（連線頁失敗卡顯示「已自動重試 n 次」）；狀態離開 Failed 就清掉 |
| `nearby` | `StateFlow<Map<String, Int>>` | 附近的車：SSID → dBm（記住的車 + 尚未配對的 `B570-` 熱點）；只有連線頁顯示期間掃描 |
| `pendingConnect` | `StateFlow<WifiCredentials?>` | 桌面捷徑要連的車（連線頁收下後走權限 / Wi-Fi 檢查再連） |
| `savedCars` | `StateFlow<List<SavedCar>>` | 最近連線在前 |
| `settings` | `StateFlow<AppSettings>` | |
| `sequences` | `StateFlow<List<SavedSequence>>` | 使用者編排，最近修改在前 |
| `history` | `TelemetryHistory` | 60 s 遙測環形緩衝 |
| `repo` | `CarRepository` | 底層（通常不需要） |

**連線**
```kotlin
fun connect(credentials: WifiCredentials)        // 智慧連線（ConnectPlanner）：熱點模式的車 / 手機熱點開著 → 先在熱點上找（1.5–12 s），找不到且有密碼再直連
fun smartConnect(car: SavedCar)                  // = connect(car.credentials)；自動連線、自動重連、重試、捷徑都走這個
fun connectDirect(credentials: WifiCredentials)  // 一律直連車子的 AP（剛掃 QR：OLED 顯示 QR = 車子在直連模式）
fun connectPlanFor(credentials: WifiCredentials): ConnectPlan   // .discover = 不一定要 Wi-Fi / 附近裝置權限（連線頁據此跳過檢查）
fun connectDemo()                       // 展示模式
fun retry()                             // 用 currentCar 重試（含展示模式）
fun disconnect()                        // BYE、釋放 Wi-Fi → Idle
fun onQrScanned(credentials: WifiCredentials, connectNow: Boolean = true)  // 記住並**直連**
// 熱點模式（§11.11）
fun isPhoneHotspotLikelyOn(): Boolean                                   // 網路介面判斷，約 1–5 ms；畫面用 rememberPhoneHotspotOn 輪詢
suspend fun discoverHotspotCars(timeoutMs: Long = 2_000L): List<DiscoveredCar>   // 熱點網段 DISCOVER 廣播（不影響目前連線）
fun connectDiscovered(car: DiscoveredCar)                               // 連找到的車（沒記住會記下，沒有 AP 密碼 = 只能用熱點連）
suspend fun setHotspot(enable: Boolean, ssid: String = "", password: String = "", notify: Boolean = true): AckStatus
                                        // STA_SET → 車子 0.5 s 後重開機；enable = 開始切換流程（hotspotSwitch，最多 60 s）；關閉 = 約 4 s 後自動直連（沒有 AP 密碼 → 請掃 QR）
suspend fun requestStaInfo(notify: Boolean = false): StaInfo?
fun cancelHotspotSwitch(); fun dismissHotspotSwitch()
// 快速連線
fun autoConnectOnLaunch(): SavedCar?    // 設定「開啟 App 自動連線」：連最近的車（每個 App 行程最多一次；連線頁在權限 + Wi-Fi 就緒時呼叫）；回傳正在連的車
fun cancelReconnect()                   // 重連提示的「取消」：停止自動重連並中斷（→ Idle → 連線頁）
fun startNearbyScan(); fun stopNearbyScan(); fun refreshNearby()   // 附近的車（連線頁跟著生命週期呼叫，見 rememberNearbyUi）
fun consumePendingConnect(credentials: WifiCredentials)
fun handleLaunchRequest(request: LaunchRequest)   // MainActivity onCreate（新啟動）/ onNewIntent：桌面捷徑「連線 <車名>」/「掃描配對」
                                                  // 先 stopControl()；正連著另一台真車而且馬達運轉中 → 不切換，提示「請先按『停止』」
fun onWindowFocusLost()                           // MainActivity：自動重連某次嘗試跳出系統同意視窗卻沒連上 → 停止自動重連（不一直跳視窗）
```
`CarRepository.skipAutoConnectOnLaunch()`：這次啟動由捷徑決定，不再自動連最近的車。
**控制（不阻塞）**
```kotlin
fun setControl(speedMmps: Int, turnPermille: Int)        // 正 = 前進 / 右轉；turn -1000..1000
fun setControlNormalized(forward: Float, turn: Float, gear: Gear)  // -1..1 → forward×gear.speedFactor×MAX_SPEED、turn×gear.turnFactor×1000
fun stopControl()                                        // = setControl(0, 0)；放開搖桿、離開畫面時呼叫
fun emergencyStop()                                      // 立即歸零 + ESTOP（不需 coroutine）
```
**指令（suspend，失敗自動跳中文提示；`notify = false` 關閉）**
```kotlin
suspend fun motor(action: MotorAction, notify: Boolean = true): AckStatus          // STOP / START / ESTOP
suspend fun zeroCal(notify: Boolean = true): AckStatus                              // 馬達需停止
suspend fun setParams(values: Map<Int, Float>, notify: Boolean = true): Map<Int, Float>?   // 回傳車上實際套用（夾限後）的值；失敗 null
suspend fun setParam(id: Int, value: Float, notify: Boolean = true): Float?
suspend fun saveParams(notify: Boolean = true): AckStatus                           // 寫 Flash，馬達需停止（否則 BUSY）
suspend fun resetParams(notify: Boolean = true): Map<Int, Float>?                   // RAM 恢復預設（不存檔）
suspend fun refreshParams(notify: Boolean = true): Map<Int, Float>?
suspend fun loadAndPlaySequence(steps: List<SeqStep>, loops: Int, notify: Boolean = true): AckStatus  // loops 0 = 無限；需馬達啟動；播放中會自動先 SEQ_STOP（不跳「編排已停止」）
suspend fun stopSequence(notify: Boolean = true): AckStatus
suspend fun setWifi(ssid: String, password: String, channel: Int = 0, notify: Boolean = true): AckStatus  // 成功後自動更新記住的車並重新連線
suspend fun factoryWifi(notify: Boolean = true): AckStatus                          // 之後需重新掃 QR
fun launch(block: suspend CarRepository.() -> Unit): Job                            // 在 VM 範圍執行，例：vm.launch { saveParams() }
suspend fun exportHistoryCsv(uri: Uri): Boolean                                      // 配合 CreateDocument("text/csv")
```
`setParams`/`saveParams` 會自動更新 `params`。成功提示（例如「參數已儲存」）由車子事件產生，畫面不用自己跳。

**本地資料**
```kotlin
fun updateSettings(transform: (AppSettings) -> AppSettings)   // vm.updateSettings { it.copy(expo = 0.5f) }
fun saveCar(credentials: WifiCredentials, nickname: String? = null)
fun renameCar(ssid: String, nickname: String)
fun forgetCar(ssid: String)
fun saveSequence(seq: SavedSequence): SavedSequence           // id 空白會自動產生
fun deleteSequence(id: String)
fun newSequenceId(): String
fun clearHistory()
fun clearEventLog()                                             // 清除 recentEvents
fun notice(messageZh: String, severity: Severity = Severity.INFO)   // 自己跳 toast
```

---

## 3. AppNavigator（`com.b570.pilot.ui`）

```kotlin
enum class MainTab(val labelZh: String, val icon: ImageVector, val fullScreen: Boolean = false) { DRIVE /* fullScreen */, MONITOR, TUNE, CHOREO, SETTINGS }
sealed interface Route { data object Connect; data object QrScanner /* overlay */; data class Main(val tab: MainTab) }

class AppNavigator {
    val backStack: List<Route>; val current: Route; val base: Route; val overlay: Route?
    val currentTab: MainTab; val previousTab: MainTab; val canGoBack: Boolean
    fun navigate(route: Route)
    fun selectTab(tab: MainTab)
    fun openScanner()            // QR 覆蓋層（由下往上）
    fun closeOverlay()
    fun back(): Boolean          // 先關滑入式導覽，再關覆蓋層，再回駕駛分頁
    fun resetTo(route: Route)
    // 橫向新增：全螢幕分頁（駕駛）的滑入式導覽（§11.4）
    val isNavOverlayOpen: Boolean
    fun openNavOverlay(); fun closeNavOverlay(); fun toggleNavOverlay()
}
```
- **Shell 自動處理**（純函式 `rootRouteOf(state, reconnecting)`，`ShellRoutingTest`）：Connected → 主畫面；**自動重連中（`vm.reconnect != null`）→ 留在原頁**
  （駕駛照樣顯示、輸入被封鎖；狀態條 / 滑入式導覽車況面板 / `HudStatusRow` / 駕駛上緣顯示「車子斷線，自動重連中（第 n/6 次）」+ 倒數 + 取消）；
  沒在重連的 Idle / Failed → Connect（主畫面收到 Failed 先等 `ROUTE_FAILED_GRACE_MS` = 250 ms 再確認，因為 repository 先設 Failed 才排重連）；
  連線中 / LinkLost 留在原頁、狀態條顯示「訊號中斷」（不蓋住分頁內容）。畫面**不要**自己依連線狀態切頁。
- 返回鍵：滑入式導覽開著 → 關閉（最優先）；非駕駛分頁 → 駕駛；駕駛 → shell 問「中斷連線？」。畫面內若有自己的返回層級（例如編輯器），用 `BackHandler` 自己攔。
- 掃描成功：`vm.onQrScanned(creds); nav.closeOverlay()`。

---

## 4. 連線（`com.b570.pilot.link`）

```kotlin
sealed class ConnectionState {
    data object Idle
    data class Discovering(val ssid: String)             // 在手機熱點網段送 DISCOVER 找車（熱點模式）
    data class RequestingWifi(val ssid: String)          // 可能跳出系統「連線到裝置」對話框
    data object Handshaking
    data class Connected(val info: Info?, val sinceMs: Long)
    data class LinkLost(val sinceMs: Long)               // >1.5 s 沒收到車子資料；恢復後自動回 Connected
    data class Failed(val reasonZh: String, val retryable: Boolean = true, val wifiDisabled: Boolean = false,
                      val hotspotHint: Boolean = false)  // hotspotHint = 建議開啟手機熱點（提供「開啟熱點」）
    val isSession: Boolean     // Connected || LinkLost
    val isConnecting: Boolean  // Discovering || RequestingWifi || Handshaking
    val isConnected: Boolean
    val labelZh: String        // 未連線 / 尋找車子… / 連線 Wi-Fi… / 握手中… / 已連線 / 訊號中斷 / 連線失敗
}
```
`Failed.reasonZh` 已是給使用者看的句子（例：「連不上車子：車子未開機、密碼錯誤或已拒絕連線」「另一支手機正在控制這台車」「手機的 Wi-Fi 已關閉，請先開啟 Wi-Fi」）。`wifiDisabled = true` 時提供「開啟 Wi-Fi」按鈕，`hotspotHint = true` 時提供「開啟熱點」。網路中斷（onLost）也是 Failed。

```kotlin
data class WifiCredentials(val ssid: String, val password: String) {
    val isValid: Boolean
    fun toQrPayload(): String
    companion object {
        fun validateSsid(ssid: String): String?        // 中文錯誤或 null；1..12 字元 [A-Za-z0-9_-]
        fun validatePassword(pw: String): String?      // 8..16 可列印 ASCII，不含 ; , : \ "
        fun parseQr(raw: String): WifiCredentials?     // 任意順序、跳脫、T:/H: 可省略；非 WIFI: 回 null
        fun parseCarQr(raw: String): QrResult          // 解析 + §3.4 驗證
    }
}
sealed interface QrResult { data class Ok(val credentials: WifiCredentials); data object NotWifi; data class Invalid(val reasonZh: String) }

object LinkPermissions {
    fun requiredForConnect(sdkInt: Int = Build.VERSION.SDK_INT): Array<String>  // 33+: NEARBY_WIFI_DEVICES；29..32: FINE + COARSE LOCATION
    fun missingForConnect(context: Context): Array<String>                      // 空 = 可連線
    const val CAMERA: String
    fun hasCamera(context: Context): Boolean
    val rationaleZh: String                                                      // 權限說明文字
    fun isWifiEnabled(context: Context): Boolean
    fun wifiPanelIntent(): Intent                                                // 系統 Wi-Fi 面板
    fun appSettingsIntent(context: Context): Intent                              // 權限被永久拒絕時
    fun openHotspotSettings(context: Context): Boolean                           // 系統「個人熱點 / 網路共用」頁（各家候選，最後退回無線與網路）
    fun hotspotSettingsIntents(): List<Intent>
}
```
Connect 畫面：連線前先用 `rememberLauncherForActivityResult(RequestMultiplePermissions())` 請求 `missingForConnect()`；
**`vm.connectPlanFor(creds).discover == true`（會先在熱點上找）時不檢查 Wi-Fi / 權限**（開熱點時 Wi-Fi 常是關的）。
底層行為（不用自己做）：
- 直連：WifiNetworkSpecifier + 無 INTERNET 能力（**行動數據不中斷**）、socket 綁定該 Network、低延遲 WifiLock（API < 34 另持 HIGH_PERF）、同時連著別的 Wi-Fi → `LinkStats.dualWifi`。
- 熱點：不綁 Network 的 UDP socket，DISCOVER 送到「上次的 IP + 熱點 / 其他介面的子網路廣播」（每 300 ms），用 `DISCOVER_ACK.ssid` 比對車名；
  連上後車子沒聲音就重新廣播、IP 變了就跟過去；熱點關掉或 8 s 都沒回應 → Failed（自動重連會再找）。STA_GET 每 3 s（RSSI 用車子回報的）。
- 兩種模式共用：HELLO 握手、CTRL（`ControlPacer`：搖桿在動 / 停下 300 ms 內 50 Hz，靜止 10 Hz 心跳，量到的控制延遲 EMA > 150 ms 時最多 16 Hz，
  放開（值變 0）一定立即送；每次送的都是當下最新值）、PING 每 500 ms / ESP_PING 每 2 s / WIFI_GET 每 3 s、
  約 1 s 收不到車子資料就每 300 ms 重送 HELLO（§3.4 規則 4），1.5 s 判定 LinkLost。
  收發執行緒 `THREAD_PRIORITY_URGENT_AUDIO`、傳送緩衝 2 KB（Wi-Fi 變慢時不在手機排隊）、DSCP EF、CTRL 原地組幀零配置。

---

## 5. 資料模型

**Telemetry**（`protocol`，皆為物理單位）
```
tick:Int  pitchDeg rollDeg:Float(°)  gyroBalDps gyroTurnDps:Float(°/s)  velL velR:Int(mm/s)
pwmL pwmR pwmBal pwmVel pwmTurn:Int  targetSpeed:Int(mm/s) targetTurn:Int(‰)  batteryV:Float
flags:Int → motorsOn appLink seqPlaying lowBattery upright failsafe paramsDirty credOk:Boolean
stopReason:StopReason  seqStep:Int?(null=未播放)  seqLoop:Int  source:ControlSource  linkQ:Int
headingDeg:Float  loopUs:Int  speedMmps:Float(平均)
ctrlSeq:Int?  ctrlAge:Int?      // v1.1 主控板（TELEM 42 bytes）：最後收到的 CTRL SEQ、經過幾個 10 ms 週期；舊韌體（40 bytes）= null
```
列舉（都有 `labelZh`）：`StopReason`(RUNNING, BOOT, KEY, APP, PICKUP, FALL, LOW_BATT, ESTOP, IMU 感測器異常, UNKNOWN)、`ControlSource`(NONE, APP, SEQUENCE)、`MotorAction`(STOP, START, ESTOP)、`AngleAlgo`(DMP, KALMAN, COMPLEMENTARY)、
`AckStatus`(OK, BAD_ARG, BUSY, FAIL, NOT_READY, UNKNOWN_TYPE + 本地 TIMEOUT, OFFLINE, UNKNOWN, CANCELLED（被停止 / 緊急停止取消，不提示）；`isOk`)。
`Info`(protoVer, fwMajor/Minor/Patch, paramCount, maxSteps, angleAlgo, model, `fwVersion`)、`HelloAck`(…, `espVersion`, `chipIdHex`, `channel`)、`WifiInfo`(channel, stations, txPowerDbm, uptimeS, freeHeap, uartRxOk/Err, udpRx/Tx, ssid, password)。

**參數表**（`protocol.Params`，UI 唯一資料來源）
```kotlin
object Params {
    const val BAL_KP=0 BAL_KD=1 VEL_KP=2 VEL_KI=3 TURN_KP=4 TURN_KD=5 ZERO_ANGLE=6 MAX_SPEED=7 MAX_TURN=8
              ACCEL=9 TURN_ACCEL=10 FAILSAFE_MS=11 TELEM_HZ=12 ANGLE_ALGO=13 PICKUP_EN=14 LOWBAT_V=15 FALL_ANGLE=16
    val ALL: List<ParamDef>;  val BY_GROUP: Map<ParamGroup, List<ParamDef>>;  val COUNT: Int
    fun byId(id: Int): ParamDef?;  fun byKey(key: String): ParamDef?;  fun defaults(): Map<Int, Float>;  fun clamp(id: Int, v: Float): Float
}
data class ParamDef(id, key, labelZh, descZh, unit, min, max, default, step, isInteger, group: ParamGroup,
                    requiresMotorsOff: Boolean, options: List<Pair<Float, String>>?) {
    val decimals: Int;  fun clamp(v: Float): Float;  fun format(v: Float, withUnit: Boolean = true): String
}
enum class ParamGroup(val labelZh) { BALANCE 直立環, VELOCITY 速度環, TURN 轉向環, MOTION 運動限制, SAFETY 安全保護, SYSTEM 系統 }
```
`options != null`（ANGLE_ALGO、PICKUP_EN）請用 `SegmentedSelector`；`requiresMotorsOff`（ANGLE_ALGO）在 `telemetry.motorsOn` 時停用。

**編排**
```kotlin
enum class SeqMode(code, labelZh, descZh) { HOLD 定值, SINE_TURN 正弦轉向, SINE_SPEED 正弦速度, RAMP 漸變 }
data class SeqStep(speed: Int /*mm/s*/, turn: Int /*‰*/, durMs: Int /*20..30000*/, mode: SeqMode = HOLD, period20ms: Int = 50) {
    val periodMs: Int;  fun clamped(): SeqStep
    companion object { MAX_STEPS = 48; MIN_DUR_MS = 20; MAX_DUR_MS = 30000; fun totalDurationMs(steps): Long }
}
data class SavedSequence(id: String, name: String, steps: List<SeqStep>, loops: Int = 1, updatedMs: Long) { val totalDurationMs: Long }
object SequencePresets { val ALL: List<SavedSequence> }   // 搖擺舞、八字繞行、原地旋轉、蛇行
```
播放進度：`telemetry.seqPlaying`、`seqStep`（索引對照 `vm.loadedSequence`）、`seqLoop`。手動推搖桿超過 30 mm/s 或 50 ‰ 會被車子判定為接管並中止編排。

**其他（`data`）**
```kotlin
data class LinkStats(rttMs: Float?, espRttMs: Float?, telemetryFps: Float, lossPercent: Float, wifiRssi: Int?, crcErrors: Long, rxFrames: Long,
                     controlLatencyMs: Float?,      // 控制延遲（平滑）：手機送出 CTRL → 車上真正套用（+ 回報那筆遙測的下行，上限值）；v1.1 主控板才有
                     controlLatencyP95Ms: Float?,   // 最近 5 s 的 P95（偶發卡頓）
                     uplinkLossPercent: Float?,     // 上行遺失（最近約 5 s；對齊主控板每秒結算的 link_q，見 §11.11）
                     dualWifi: Boolean) {           // 直連時手機同時連著另一個 Wi-Fi（延遲會變高）
    val signalLevel: Int /*0..4*/
    val displayLatencyMs: Float?                    // HUD 用：控制延遲，沒有就用 rttMs
    val linkQualityPercent: Int?                    // = 100 − uplinkLossPercent（「品質 xx%」）；不要再用 link_q × 2（靜止時只送 10 Hz）
}
data class EspInfo(hello: HelloAck?, wifi: WifiInfo?)
data class SavedCar(ssid, password, nickname, lastConnected, hotspotMode = false, lastIp: String? = null, hotspotSsid = "") {
    val displayName: String; val canDirect: Boolean /* 有合法 AP 密碼 */; val credentials: WifiCredentials }
data class AppSettings(hapticsEnabled = true, joystickMode = SINGLE, expo = 0.35f, defaultGear = NORMAL,
                       tiltSensitivity = 0.5f, accent = CYAN, keepScreenOn = true, demoMode = false,
                       autoConnect = true /* 開啟 App 自動連線 */, autoReconnect = true /* 斷線自動重連 */)
enum class JoystickMode(labelZh) { SINGLE 360° 搖桿（預設；enum 名稱存檔用，不改名）, DUAL 雙搖桿, TILT 體感 }
// SettingsStore：搖桿模式存在 "joy_v2"。舊版 "joy" 不再讀取（分不出「選了雙搖桿」還是舊預設）→ 升級後每個人先落在 360°，
// 之後的選擇照常保存；下一次存檔時刪掉舊的 "joy"（JoystickModeMigrationTest）。
// 駕駛頁自己的偏好（不在 AppSettings）：DrivePrefs（SharedPreferences "drive_prefs"，key "stick_right_360"）= 360° 搖桿在左 / 在右（預設左）。
enum class Gear(labelZh, speedFactor, turnFactor) { SLOW 慢速(0.30,0.50), NORMAL 中速(0.60,0.75), FAST 高速(1,1) }   // 駕駛頁顯示 慢/中/快
enum class AccentChoice(labelZh) { CYAN 電光青, MAGENTA 霓虹粉, LIME 螢光綠, AMBER 琥珀橘 }
data class CarEvent(messageZh: String, severity: Severity, code: EventCode?, arg: Int, timeMs: Long, id: Long) { val isLocal: Boolean }
enum class Severity { INFO, SUCCESS, WARNING, DANGER }
```

**快速連線（`data`：`QuickConnect.kt`、`Shortcuts.kt`）**
```kotlin
data class ReconnectStatus(ssid: String, attempt: Int, maxAttempts: Int, nextAtMs: Long /* elapsedRealtime；0 = 正在嘗試 */)
object ReconnectPolicy { val maxAttempts: Int /* 6 */; fun delayBefore(attempt: Int): Long? }   // 1.5 / 3 / 5 / 8 / 12 / 15 s
const val CAR_SSID_PREFIX = "B570-"
class NearbyCars(app, savedSsids: () -> Set<String>) { val nearby: StateFlow<Map<String, Int>>; fun start(); fun stop(); fun refresh() }  // 讀 WifiManager.scanResults，沒權限 / 節流不丟例外
sealed interface LaunchRequest { data object ScanPair; data class ConnectCar(val ssid: String) }
data class ShortcutSpec(...)
object CarShortcuts { ACTION_CONNECT / ACTION_SCAN / EXTRA_SSID; MAX_CARS = 3
    fun plan(cars: List<SavedCar>, maxTotal = MAX_CARS + 1): List<ShortcutSpec>   // 最近 3 台「連線 <名稱>」+「掃描配對」
    fun parse(action: String?, ssid: String?): LaunchRequest?
    fun publish(context, cars)            // 動態捷徑（記住的車變了才重發；被忘記的車的釘選捷徑停用）
    fun reportUsed(context, ssid) }       // 連上真車時回報（桌面建議排序）
```
**熱點模式（`link/LinkTypes.kt`、`data/Hotspot.kt`、`protocol`）**
```kotlin
enum class LinkMode(labelZh) { DIRECT 直連, HOTSPOT 手機熱點 }
data class DiscoveredCar(ssid, ip, chipIdHex, viaHotspot: Boolean, busy: Boolean)
object HotspotConfig { SSID_MAX_BYTES = 32; PW_MIN = 8; PW_MAX = 63
    fun validateSsid(ssid): String?       // 1..32 bytes UTF-8（可中文，不能有換行 / 控制字元）；中文錯誤或 null
    fun validatePassword(pw): String? }   // 空（開放熱點）或 8..63 可列印 ASCII（0x20..0x7E）
data class StaInfo(enabled, state: StaState, ip, rssiDbm: Int?, ssid, password) { val carIp: String? }   // 密碼只回給 session 擁有者
enum class StaState(code, labelZh) { OFF 未啟用, SEARCHING 搜尋熱點中, CONNECTED 已連上熱點, FALLBACK 找不到熱點已改回直連, UNKNOWN }
data class ConnectPlan(discover, discoveryMs, direct, hotspotExpected)
object ConnectPlanner { fun plan(car: SavedCar?, credentials, phoneHotspotOn, forceDirect = false): ConnectPlan; fun requiresWifi(...) }
    // 熱點模式的車：熱點開著找 12 s（= ESP 加入熱點上限）/ 沒開 1.5 s，有密碼再直連；一般車 + 熱點開著：找 2 s；沒有 AP 密碼：只能找（熱點開著 12 s / 沒開 4 s）；其他：直連
data class HotspotSwitchStatus(hotspotSsid, phase: HotspotSwitchPhase, phoneHotspotOn, startedAtMs, deadlineMs) { val messageZh; val isActive }
enum class HotspotSwitchPhase(labelZh) { RESTARTING 車子重新開機中…, WAITING_HOTSPOT 請開啟手機熱點, SEARCHING 在熱點上尋找車子…, FAILED 沒有找到車子 }
```

- 自動重連只在「這次連線流程成功連上過」（系統已記住同意）且設定 `autoReconnect` 開著時進行；展示模式不重連。
  某次重連嘗試期間 App 失去視窗焦點（系統「連線到裝置」視窗跳出來 = 系統沒記住同意）而那次沒連上 → 停止自動重連並提示（`ReconnectPolicy.shouldRetry`），不會每次重試都再跳一次視窗。
  自動重連只會連 `currentCar`（同一台車）；新連線的 CTRL 一律從 0 開始（`CarConnection.start`）。
- 捷徑是**動態捷徑**：第一次開 App 之後才出現（安裝後還沒有「掃描配對」）。Activity 是 `singleTop`，捷徑在 `onCreate`（新啟動，不含從最近使用回來）與 `onNewIntent` 處理。
- 保持螢幕常亮（設定 `keepScreenOn`）也涵蓋自動重連期間。

**TelemetryHistory**（`vm.history`，3000 筆 = 60 s）
```kotlin
enum class Channel(labelZh, unit) { PITCH, ROLL, GYRO_BAL, GYRO_TURN, VEL_L, VEL_R, PWM_L, PWM_R, PWM_BAL, PWM_VEL, PWM_TURN, TGT_SPEED, TGT_TURN, BATTERY, HEADING }
val capacity: Int; val version: Long; val size: Int
fun copyLast(channel: Channel, n: Int, out: FloatArray, outOffset: Int = 0): Int   // 由舊到新，零配置
fun copyLastTimes(n: Int, out: LongArray, outOffset: Int = 0): Int
fun latest(channel: Channel): Float                // 無資料 NaN
fun minMax(channel: Channel, n: Int, outMinMax: FloatArray)
fun exportCsv(writer: Writer) / exportCsv(out: OutputStream)
```

---

## 6. 共用元件（`com.b570.pilot.ui.components`）

| 元件 | 簽名重點 |
|---|---|
| `GlassCard` | `(modifier, glow: Color? = null, onClick: (() -> Unit)? = null, shape, contentPadding = 16.dp, content: ColumnScope.() -> Unit)` |
| `NeonButton` | `(text, onClick, modifier, style = PRIMARY/SECONDARY/DANGER/GHOST, icon: ImageVector?, enabled, loading, compact, color: Color?)` 按下縮放 + 光暈 + 觸覺 |
| `SegmentedSelector<T>` | `(options: List<T>, selected: T, onSelect: (T) -> Unit, modifier, label: (T) -> String, icon: ((T) -> ImageVector?)?, enabled)` 滑動發光指示 |
| `StatTile` | `(label, value: String, modifier, unit: String?, icon, accent, caption, valueStyle)` |
| `StatusPill` | `(text, color, modifier, pulsing = false, icon)` |
| `BatteryIndicator` | `(volts: Float?, modifier, showVoltage = false, showText = true)`；`batteryPercent(volts): Int`（10.0 V=0 %、12.6 V=100 %）、`batteryColor(percent)` |
| `SignalBars` | `(level: Int 0..4, modifier, color)` 或 `(rssi: Int?, modifier, color)` |
| `ValueSlider` | `(label, value, onValueChange, range, modifier, step = 0f, unit = "", decimals: Int? = null, description, enabled, onValueChangeFinished, accent)`；或 `ValueSlider(def: ParamDef, value, onValueChange, modifier, enabled, showDescription, onValueChangeFinished)`。−/＋ 長按連續、點數值可輸入。**在 `onValueChangeFinished` 送 `setParam`**，拖曳中只改本地值 |
| `NeonSlider` | 只有滑軌：`(value, onValueChange, range, modifier, enabled, onValueChangeFinished, accent)` |
| `RepeatStepButton` | `(icon, enabled, onStep, modifier, onDone)` 長按連續 |
| `LineChart` | `(history: TelemetryHistory, series: List<ChartSeries>, modifier, windowSamples = 500, yRange: ClosedFloatingPointRange<Float>? = null, paused = false, showLegend = true, showGrid = true, minSpan = 1f, chartHeight = 180.dp)`；`ChartSeries(channel, color, label = channel.labelZh, scale = 1f)`。圖例可點擊隱藏；圖例列太長可橫向捲動（邊緣淡出提示）；Y 軸刻度依圖高 3–4 格，太擠的數字自動略過（矮圖表也不會疊字） |
| `ArcGauge` | `(value, min, max, modifier, label, unit, decimals, color, warnAt: Float?, dangerAt: Float?, sweep = 240f, thickness = 10.dp, valueText, valueStyle, animate)`；min < 0 為雙向 |
| `AttitudeIndicator` | `(pitchDeg: () -> Float, modifier, headingDeg: () -> Float = {0f}, rollDeg: () -> Float = {0f}, fallAngle = 40f, zeroAngle = 0f, active = true, showHeadingTape = true, showReadout = true)`（危險區 = \|pitch − zeroAngle\| ≥ fallAngle，與韌體相同） — **傳 lambda**：`val telem = vm.telemetry.collectAsStateWithLifecycle()` → `pitchDeg = { telem.value?.pitchDeg ?: 0f }`（只在繪製階段讀，父層不會 50 Hz 重組）。小尺寸時左上角讀數會撞到的 ±20/40° 刻度字自動不畫（刻度弧必要時縮小）；航向帶只畫完整落在帶內的數字 |
| `Joystick` | `(onMove: (x: Float, y: Float) -> Unit, modifier, axes = BOTH/VERTICAL/HORIZONTAL, expo = 0f, deadzone = 0.06f, enabled, accent, onRelease, style = STANDARD/THROTTLE/STEERING/ANALOG_360, showReadout, ghost, feedback: (() -> Offset)?, labels, onPress, excludeSystemGestures = true, snapDeg = ANALOG_SNAP_DEG)`；x 右正、y 上正，-1..1，已套死區與 expo；放開或觸控中斷（取消、enabled 改變、離開畫面）一定送 (0,0)；兩支可同時操作（多點觸控獨立）；`joystickCurve(v, expo)`。`ANALOG_360` = 圓形類比搖桿（真圓形行程、圓形死區、expo 只套推桿量、前後左右 ±5° 吸附），換算 `analogStickMap(...)`。詳見 §11.5 |
| `ConfirmDialog` | `(title, message, onConfirm, onDismiss, confirmText = "確定", dismissText = "取消", danger = false, icon)`（矮螢幕自動緊湊、可捲動、沉浸式） |
| `GlassDialog` | `(onDismissRequest, modifier, maxWidth = 440.dp, glow, contentPadding: PaddingValues? = null, scrollable = true, properties, horizontalAlignment, content: ColumnScope.() -> Unit)`；與螢幕邊緣固定留 `DialogEdgeH` 16 dp × `DialogEdgeV` 12 dp（見 §11.5）；自己的 `Dialog {}` 請呼叫 `DialogImmersiveEffect()`；`isShortScreen()` |
| `NumberInputDialog` | `(title, initial, range, onConfirm: (Float) -> Unit, onDismiss, decimals = 2, unit = "")` |
| `CarEventToastHost` / `ToastCard` / `severityStyle(s)` | shell 已放；`CarEventToastHost(events, modifier, maxVisible = 3, maxWidth = 460.dp, dismissOnClick = true)`；`ToastCard(event, modifier, onClick = null, maxWidth = 460.dp)` 可用於事件列表（`onClick = null` 不攔觸控） |
| `EmptyState` | `(icon, title, modifier, message, actionText, onAction)` |
| `SectionHeader` | `(title, modifier, subtitle, trailing: (@Composable () -> Unit)?)` |
| `AnimatedNumber` | `(value: Float, modifier, decimals = 0, style = readoutL, color, unit, unitColor, signed = false, animate = true)` 等寬數字 |
| 觸覺 | `val h = rememberHaptics()` → `h.tick() / click() / confirm() / heavy() / reject()`（自動遵守設定） |
| 版面 | `LocalScreenPadding`、`Modifier.screenPadding()`、`PaddingValues + PaddingValues`、`PaddingValues.verticalOnly(extra)`、`horizontalOnly(ld, extra)`、`WindowInsets.safeArea`、`safeAreaInsets()`、`LocalLandscapeClass`、`LocalWindowDpSize`、`rememberDisplayRotation()`、`Modifier.horizontalEdgeFade(scrollState)`（§11.5） |
| 遙控器（橫向新增） | `ShoulderButton`、`GamepadButton`、`RockerSwitch`、`LeverSwitch`、`EmergencyStopButton`、`HudChip` / `BatteryChip` / `RttChip` / `SignalChip` / `LinkQualityChip` / `ConnectionChip` / `MotorChip`、`HudIconButton`、`LinkLostBadge`；shell 的 `HudStatusRow(vm)`。簽名見 §11.5 |
| 延遲（第三次整合） | `ControlLatencyChip(stats: LinkStats, modifier, expanded = false, onClick = null)`：「控制 23 ms」（< 40 綠、< 100 黃、其餘紅；舊主控板韌體退回「延遲 18 ms」= PING 來回），`expanded` = 原地展開「控制 · P95 · 來回 · Wi-Fi」（**不是浮層**）；`LinkModeChip(mode, modifier, onClick)`「熱點」/「直連」；`latencyColor(level)`、`diagnosisColor(tone)`、`linkModeColor(mode)`。純函式（`LatencyRules.kt`，`LatencyRulesTest`）：`hudLatencyOf(stats)`、`latencyLevelOf(ms)`、`latencyDetailZh(stats)`、`latencyDiagnosisOf(stats, mode, connected, ctrlSeqFirmware)`（一句白話診斷：Wi-Fi 慢 / 雙 Wi-Fi / 車上處理慢 / 上行遺失 / 偶爾卡頓 / 舊韌體…）、`formatMs`、`formatPercent`、`linkModeShortZh` |
| 熱點（第三次整合） | `HotspotSwitchPanel(status, onOpenHotspot, onRetry, onDismiss, modifier, onCancel = null, phoneHotspotOn)`（切換進度卡：三步進度 + 倒數 + 開啟手機熱點 / 取消 / 重試）、`CarModeTags(hotspotMode, hasPassword, modifier, showDirect = true)` / `MiniTag(text, color)`（「熱點」「直連」「無密碼」）、`rememberPhoneHotspotOn(active = true, periodMs = 1500) { vm.isPhoneHotspotLikelyOn() }`（背景輪詢，不在前景不輪詢）、`openPhoneHotspotSettings(context): Boolean`。純函式（`HotspotRules.kt`，`HotspotRulesTest`）：`staStatusOf(sta, supported, connected, carApSsid)`、`rssiWordZh(dbm)`、`HOTSPOT_SWITCH_STEPS`、`hotspotSwitchStepOf(phase)`、`hotspotSwitchSecondsLeft(deadline, now)` |
| 其他 | `Modifier.noRippleClickable(enabled, onClick)`、`lerpColor(a, b, t)` |

Shell 常數（`ui.shell`）：`RailWidth = 72.dp`、`RailMargin = 8.dp`、`RailReserve = 80.dp`、`StatusStripHeight = 48.dp`、`ContentBottomMargin = 8.dp`（直向的 `BottomBarHeight` 已移除）。

---

## 7. 主題（`com.b570.pilot.ui.theme`）

只有深色。存取：`B570Theme.colors / .type / .spacing / .radii`。Material3 元件已對應同一組色彩。

- **colors**：`background #05070D`、`backgroundGlow`、`grid`、`surface`（不透明）、`glass`（半透明卡片）、`glassHigh`（按下/選取）、`glassBorder`、`glassBorderDim`、`divider`、
  `textPrimary #E6EDF7`、`textSecondary #9AA8BF`、`textMuted #5B6880`、`accent`（預設電光青 #22D3EE，可切換）、`accent2`（紫 #8B5CF6）、`onAccent`、
  `ok #34D399`、`warn #FBBF24`、`danger #F43F5E`、`info #60A5FA`、`series: List<Color>`（圖表配色，依序使用）。
- **type**：`readoutXL 48sp / readoutL 32 / readoutM 22 / readoutS 15`（tabular 數字）、`mono`、`titleL 26 / titleM 19 / titleS 16`、`body 15`、`bodySmall 13`、`label 12`、`caption 11`。
- **spacing**：`xxs 2, xs 4, s 8, m 12, l 16, xl 24, xxl 32, screen 16`（dp）。
- **radii**：`s 12, m 16, l 20, xl 24`（dp）。卡片用 `l`，對話框 `xl`，小元件 `s`。
- **Modifier**：`cockpitBackground()`（放射光暈 + 淡格線；shell 已畫，全螢幕覆蓋層才需要）、`glass(shape, fill, glow: Color?, glowElevation, borderWidth)`、`neonGlow(color, shape, elevation)`。
- 強調色跟隨設定 `AppSettings.accent`；請用 `B570Theme.colors.accent`，不要寫死色碼。

---

## 8. 展示模式（不需要車子）

- 進入：`vm.connectDemo()`（Connect 佔位頁有按鈕），或設定 `demoMode = true` → 每次啟動自動進入。
- 行為完全走真協定（同一條解碼路徑）：50 Hz 遙測（平衡晃動、加速前傾、對搖桿反應、電池緩慢下降）、約 1.2 s 後「放下自動啟動」馬達、閒置 25 s 後偶爾模擬「被拿起」3 秒。
- 指令都會回覆並**比照韌體規則**：參數夾限回傳、`ANGLE_ALGO` 運轉中不可改、**馬達運轉中存檔 / 校正零點回 BUSY**（先停止馬達）、編排載入／播放會推進 `seqStep`、推搖桿會觸發「手動接管」、ESTOP、WIFI_SET/FACTORY 回 OK。
- 狀態條顯示「展示」標籤；`vm.isDemo` 可判斷。展示模式不會寫入記住的車子。
  離開展示模式（中斷連線）後 `isDemo` 仍為 true（「重試」會回到展示），要判斷展示車是否還在跑請看連線狀態（repository 的 `discoverHotspotCars` 已這樣處理）。
- 第三次整合：送 v1.1 遙測（42 bytes，ctrl_seq / ctrl_age，模擬 15–30 ms 控制延遲）與上行樣本；回覆 STA_GET / STA_SET / DISCOVER，
  `setHotspot` 在展示模式只模擬切換提示（重新開機中 → 尋找中 → 連上），`linkMode` 會跟著變成「手機熱點」。

---

## 9. 各畫面提示

- **Drive**：`DisposableEffect(Unit) { onDispose { vm.stopControl() } }`；搖桿 → `DriveController`（360° 搖桿：`controller.set(forward = y, turn = x)`；雙搖桿分軸 `setForward` / `setTurn`）；緊急停止用 `vm.emergencyStop()`；設定的 `joystickMode / expo / defaultGear / tiltSensitivity`；搖桿在左 / 右存在 `DrivePrefs`。
  自動重連中（`vm.reconnect != null`）：控制區顯示 `ControlBlock.RECONNECTING`「自動重連中…」，上緣列車況膠囊那一格換成 `ReconnectBadge`。
  上緣列車況膠囊（`PriorityRow`，放不下時依重要度收起：品質 → 車名 → 馬達 → 連線方式 → 訊號 → 延遲 → 電量 → 連線）含 `LinkModeChip` 與
  `ControlLatencyChip`；點延遲膠囊原地展開細節（8 s 後收回，展開時延遲膠囊最優先），不是浮層、不會蓋到緊急停止。
  橫向：全螢幕（`LocalScreenPadding` = 安全區），自己放 ≡（`nav.openNavOverlay()`）與 `HudStatusRow(vm)`；`nav.isNavOverlayOpen` 時停用搖桿；體感用 `rememberDisplayRotation()`；安全不變式見 §11.6。
- **Monitor**：`LineChart(vm.history, …)`、`vm.linkStats`、匯出 `vm.exportHistoryCsv(uri)`；左欄「延遲診斷」卡（`MonitorLatency.kt`：控制延遲 + P95、端到端來回、Wi-Fi 來回、上行遺失、連線方式、雙 Wi-Fi 警告、一句診斷）。
- **Tune**：`Params.BY_GROUP` + `ValueSlider(def, …)`；`telemetry.paramsDirty` 顯示「未儲存」；儲存前提示先停止馬達。
- **Choreo**：`SequencePresets.ALL`、`vm.sequences`、`vm.loadAndPlaySequence(steps, loops)`、`vm.stopSequence()`。
- **Settings**：`vm.settings/updateSettings`、`vm.savedCars`、`vm.espInfo`、`vm.setWifi(...)`（先用 `WifiCredentials.validate*` 檢查）、`vm.factoryWifi()`（ConfirmDialog danger）、`BuildConfig.VERSION_NAME`。
  「連線方式」分類（`LinkModeSection.kt` / `HotspotDialogs.kt`）：`vm.staInfo` / `hotspotSupported` / `hotspotSwitch`、`vm.setHotspot(...)`（先用 `HotspotConfig.validate*` 檢查）。
- **Connect**：權限（`connectPlanFor(creds).discover` 時跳過）→ `vm.connect(creds)`（掃 QR → `connectDirect`）；`Discovering` 顯示「在熱點網路尋找車子…」步驟條；`Failed.hotspotHint` → 「開啟熱點」（熱點一開就自動重試一次）；
  熱點模式的車 Wi-Fi 掃描看不到 → 每 5 s `vm.discoverHotspotCars()`（「在熱點上」/「熱點上的車」）；顯示 `connectionState`（第一次連這台車、RequestingWifi 超過 0.7 s → 提示「請在系統視窗按「連線」，之後就會自動連線」）；`savedCars` 列表 + 附近標記；`nav.openScanner()`；展示模式入口保留。
  快速連線：權限 + Wi-Fi 就緒 → `vm.autoConnectOnLaunch()`（大卡片「自動連線 · 正在連線 <名稱>…」+ 取消）；桌面捷徑 `vm.pendingConnect`（同一張卡片，標籤「桌面捷徑」）；
  主按鈕：沒有記住的車 →「配對你的平衡車」三步驟 + 掃描 QR 配對；剛好一台記住的車在附近 →「連線 <名稱>」；其他 → 掃描 QR 配對。
  附近的車只在這頁可見、有權限、Wi-Fi 開著、沒在連線時掃描（每 30 s + 手動重新整理）；還沒配對的 `B570-` 熱點顯示「附近的新車」→ 開掃描。
- **Scanner**：CameraX `PreviewView` + ML Kit `BarcodeScanning`（`FORMAT_QR_CODE`）；`WifiCredentials.parseCarQr(raw)`：`Ok` → `vm.onQrScanned(it.credentials); nav.closeOverlay()`，`NotWifi` 忽略，`Invalid` 顯示原因；相機權限 `LinkPermissions.CAMERA`。

---

## 10. 協定注意事項（影響 UI 的部分）

- 指令請求在連線層**序列化**（ACK 不帶 SEQ），同時送多個指令會排隊；長操作（編排載入）期間其他指令會稍晚完成。
- 緊急停止不排隊：`emergencyStop()` 先中止還在重送 / 排隊的 START、SEQ_PLAY（韌體不對 MOTOR 去重，舊 START 的重送會把馬達再打開），直接連發 MOTOR ESTOP 幀（不經請求佇列），之後每 100 ms 補送到遙測確認馬達已停（訊號中斷期間持續；1.5 s 仍未確認跳危險提示）。`motor(STOP)` 也會先中止 START / SEQ_PLAY；被取消的請求回 `AckStatus.CANCELLED`（不提示）。
  **沒有連線時（自動重連中）按的、或確認前就斷線的緊急停止不會被丟掉**：記下這台車，連回同一台車（自動重連 / 訊號恢復，或 60 s 內手動重連）時在任何其他指令之前補送並提示「已連回，補送緊急停止」；按下當時提示「車子目前沒有連線，緊急停止會在連回時立刻送出」。遙測確認停止或使用者按「啟動平衡」才清掉。
- 主控板重開機（EVENT BOOT 或遙測 stop_reason 回到 BOOT）與訊號恢復時，repository 自動重讀 INFO 與全部參數（`params` 換成車上 Flash 的值），重開機時 `loadedSequence` 清空。EVENT ZERO_CAL（含車上按鍵校正）也會更新 `params[ZERO_ANGLE]`。
- 零點校正會把 RAM 裡「全部」參數一起寫入 Flash（含未儲存的即時調整）。
- 韌體行為（WHEELTEC/rc_link.c、motion.c）：TELEM `pitch` 是**未扣零點**的原始傾角；ZERO_CAL 運轉中回 BUSY、傾斜超過 ±10° 回 BAD_ARG，成功時先 ACK 再送 PARAM_VALUES(6)；PARAM_SAVE 運轉中只回 ACK BUSY（不另送 PARAMS_SAVED 事件）。
- 每個請求逾時 400 ms、最多重送 3 次 → 失敗最長約 1.6 s 才回 `TIMEOUT`，按鈕請顯示 `loading`。
- 熱點模式（PROTOCOL §3.4 / §3.6）：`STA_SET` 只接受 session 擁有者（或沒有 session），回 ESP_ACK 後 0.5 s 重開機；ESP 加入熱點最多 12 s，
  找不到（或熱點斷線 > 15 s）改開直連 AP（同一組帳密、OLED 顯示 QR），沒有手機連 AP 時每 30 s 再試熱點。熱點必須 2.4 GHz + WPA2（不可只開 WPA3）。
  `DISCOVER` 任何人、任何模式都可送（不需 session）；`DISCOVER_ACK.busy` = 已有別的手機在控制。STA_INFO 的密碼只給擁有者。
  舊 ESP 韌體回 `UNKNOWN_TYPE` → `hotspotSupported = false`（設定頁顯示「Wi-Fi 模組不支援熱點模式」）。
- `params` 在 PARAM_SET / PARAM_DEFAULTS / ZERO_CAL 後自動更新為車上實際值。

---

## 11. 橫向遙控器介面（Landscape 改版，所有畫面必讀）

使用者需求：**橫向、搖桿型、像遙控車發射器**，功能要多、**既有功能一個都不能少**：
駕駛模式（360° 搖桿 / 雙搖桿 / 體感）、檔位、啟動平衡 / 停止、緊急停止、快捷動作、HUD / 姿態儀 / 儀表；
監測圖表 / 統計 / 事件紀錄 / CSV 匯出；整定分組 / 即時套用 / 設定檔 / 零點校正 / 儲存 / 恢復預設；
編排庫 / 編輯器 / 路徑預覽 / 播放；設定 Wi-Fi / 操控 / 外觀 / 遙測 / 展示 / 記住的車 / 關於；
連線 / 掃描 / 手動輸入 / 展示模式；掃描的縮放 / 手電筒 / 對焦。

### 11.1 視窗與 Activity（foundation 已完成）
- `android:screenOrientation="sensorLandscape"`：只有橫向，左右兩個橫向跟著感測器翻轉。
- `configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboard|keyboardHidden|navigation"`：
  翻轉、分割畫面調整大小、折疊機展開、接鍵盤**不重建** Activity（Compose 直接重排，連線與正在進行的手勢不中斷）。
  其他設定變更（字體大小、語言…）仍會重建；狀態都在 ViewModel / CarRepository，重建也不斷線。
- **沉浸式**：隱藏狀態列 + 導覽列，`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`（從邊緣滑動暫時叫出，**不改變 insets**，版面不跳）；
  `onResume` 與重新取得視窗焦點時重套。
- **edge-to-edge + 瀏海**：`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`，畫面延伸到瀏海區；內容一律用 insets 避開（shell 已算進 `LocalScreenPadding`）。
- **安全**：`onStop` 與失去視窗焦點（下拉通知欄、系統對話框）時 `vm.stopControl()`。
- **左右翻轉（ROTATION_90 ⇄ 270）不會觸發 configuration change**：瀏海會換邊（insets 自動更新）；
  用感測器換算螢幕座標的程式（體感駕駛）一定要用 `rememberDisplayRotation()`，不能只在開始時讀一次。
- **對話框**：用 `GlassDialog { }`；自己寫 `Dialog { }` 時在內容第一行呼叫 `DialogImmersiveEffect()`，否則對話框取得焦點時系統列會跑出來。
  `ConfirmDialog` / `NumberInputDialog` 已內建，矮螢幕（高 < 480 dp）自動緊湊排版，內容超高可捲動。
  平台預設寬度（`usePlatformDefaultWidth = true`）在橫向手機上可以寬到整個螢幕，所以 `GlassDialog` 一律改用
  `usePlatformDefaultWidth = false` + 16 / 12 dp 外距（640 × 320 上 maxWidth 640 的對話框也不會貼邊）；
  呼叫端自己傳 `usePlatformDefaultWidth = false` 時視為自行處理外距（設定頁的 `SettingsDialog` 就是這樣）。
- **鍵盤**：Compose 不使用橫向全螢幕輸入法；鍵盤會蓋住下半部（手機橫放只剩約 150 dp），
  有輸入框的可捲動容器加 `Modifier.imePadding()`，對話框用 `GlassDialog`（可捲動）。

### 11.2 尺寸目標與級距
| 裝置 | 典型可用區（dp，橫向） | `LandscapeClass` |
|---|---|---|
| 小手機 / 分割畫面 | 640 × 320 | `COMPACT`（高 < 360 或寬 < 700） |
| 一般手機 | 780 × 360 ~ 915 × 412 | `MEDIUM` |
| 平板 | 1280 × 800 | `EXPANDED`（高 ≥ 600 且寬 ≥ 900） |

- `LocalLandscapeClass.current`、`LocalWindowDpSize.current`（整個視窗扣掉安全區）由 shell 提供；**畫面內細節一律用 `BoxWithConstraints` 依實際可用區域決定**。
- 字級 1.3 倍在 640 × 320 也不能切字：側欄 / 面板寧可 `verticalScroll` 也不要裁切；單行文字加 `maxLines = 1` + `TextOverflow.Ellipsis`。
- 高度最寶貴：不要沿用直向的「上下堆疊」，改成左右分欄（主內容 + 側欄、清單 + 詳情、圖表 + 數據）。

### 11.3 Shell 版面
```
一般分頁（監測 / 整定 / 編排 / 設定）                     駕駛（全螢幕遙控器）
┌────────┬───────────────────────────────────────┐    ┌──────────────────────────────────────────┐
│ 導覽軌  │ 狀態條：車名 · 馬達 │ 連線 │ 電量 │ 訊號/延遲 │    │ ≡ 車況膠囊 / 重連提示   [搖桿在左] [編排]   │
│ 72 dp  ├───────────────────────────────────────┤    │ [啟動平衡] [360°|雙搖桿|體感] [緊急停止] │
│ 駕駛   │                                       │    │                                          │
│ 監測   │      分頁內容（從玻璃底下捲過）          │    │ (360° 搖桿)  中控台 / 儀表  [檔] (ABXY)  │
│ 整定   │                                       │    └──────────────────────────────────────────┘
│ 編排   │                                       │     ≡ → nav.openNavOverlay()：遮罩 + 導覽軌（含緊急停止）
│ 設定   │                                       │         + 車況面板（車名、連線/失聯、馬達、電量、訊號、
│[緊急停止]│                                      │           延遲、連線品質、韌體、中斷連線）
└────────┴───────────────────────────────────────┘
```
- 一般分頁：左側玻璃**導覽軌** `NavRail`（圖示 + 短標籤、垂直滑動發光指示塊、觸覺、TalkBack 分頁角色；底部「緊急停止」觸碰即觸發），
  導覽軌右側是頂部**狀態條** `StatusStrip`（漸層底；訊號中斷時左側換成不透明失聯警告 + 秒數）。太矮時導覽軌可捲動。
- 駕駛 `MainTab.DRIVE.fullScreen = true`：**不畫**導覽軌與狀態條，整個螢幕給遙控器；駕駛畫面自己放 ≡ 鈕呼叫 `nav.openNavOverlay()`，
  並自己顯示連線 / 電量 / 失聯（`HudStatusRow(vm)` 一行搞定）。
- 分頁切換：依導覽軌順序**垂直**滑動 + 淡入（往下的分頁由下往上進場）；每個分頁的 `rememberSaveable` 狀態保留。
- Connect、QR 掃描：全螢幕路由（掃描由下往上滑入）。
- 提示（toast）：內容區上緣置中，最多 2 則、寬 ≤ 420 dp，點一下關閉；
  **駕駛畫面**：貼安全區上緣 8 dp、寬 = 視窗寬 × 0.42（240..420 dp，避開左右上角肩鍵）、**不攔截觸控**（手指落在提示上仍按得到下面的控制）。
- 返回鍵：滑入式導覽開著 → 關閉（最優先）；非駕駛分頁 → 回駕駛；駕駛 → 詢問「中斷連線？」。

**常數（`com.b570.pilot.ui.shell`）**
| 常數 | 值 | 說明 |
|---|---|---|
| `RailWidth` | 72.dp | 導覽軌寬 |
| `RailMargin` | 8.dp | 導覽軌與安全區左緣距離（上下也各 8 dp） |
| `RailReserve` | 80.dp | 安全區左緣 → 導覽軌右緣（= `RailMargin + RailWidth`） |
| `StatusStripHeight` | 48.dp | 頂部狀態條高（不含安全區上緣） |
| `ContentBottomMargin` | 8.dp | 一般分頁下緣留白 |

（直向版的 `BottomBarHeight` / `FloatingBottomBar` 已移除。）

**`LocalScreenPadding`（精確定義）**，`safe` = `WindowInsets.safeArea` = `systemBars ∪ displayCutout`（沉浸式時系統列為 0，通常只剩一側瀏海）：

| 位置 | start | top | end | bottom |
|---|---|---|---|---|
| 一般分頁 | `safe.start + RailReserve`（剛好到導覽軌右緣，**不含間距**） | `safe.top + StatusStripHeight` | `safe.end` | `safe.bottom + ContentBottomMargin` |
| 駕駛（全螢幕） | `safe.start` | `safe.top` | `safe.end` | `safe.bottom` |
| Connect / QR 掃描 | `safe.start` | `safe.top` | `safe.end` | `safe.bottom` |

- **不含鍵盤（IME）**：有輸入框的可捲動容器自己加 `Modifier.imePadding()`。
- 可捲動：`LazyColumn(contentPadding = LocalScreenPadding.current + PaddingValues(horizontal = 16.dp))`（左邊距 = 導覽軌右緣 + 16 dp，內容從狀態條玻璃下穿過）。
- 固定版面：`Modifier.screenPadding()` 再加自己的留白（`PaddingValues.horizontalOnly(ld)` / `verticalOnly()` 可拆開使用）。
- 瀏海只在一側（翻轉後換邊）：想左右對稱就取 `maxOf(start, end)`。螢幕圓角不在 insets 裡：貼邊的控制（肩鍵、搖桿）至少離安全區 12 dp。

### 11.4 導覽 API（`AppNavigator`，新增）
```kotlin
enum class MainTab(val labelZh: String, val icon: ImageVector, val fullScreen: Boolean = false)  // 只有 DRIVE.fullScreen = true

val isNavOverlayOpen: Boolean   // Compose state：全螢幕分頁的滑入式導覽是否打開
fun openNavOverlay()            // 駕駛的 ≡ 鈕；只在目前分頁 fullScreen 且位於主畫面時有效
fun closeNavOverlay()
fun toggleNavOverlay()
// 既有：backStack / current / base / overlay / currentTab / previousTab / canGoBack / navigate / selectTab / openScanner / closeOverlay / back / resetTo
```
- 關閉時機：點遮罩、面板 ✕、返回鍵（最優先）、選任何分頁（`selectTab`）、`resetTo`、`navigate` 到覆蓋層（掃描）。
- 打開時 shell 會 `vm.stopControl()`；**駕駛畫面必須**在 `nav.isNavOverlayOpen` 時把搖桿 / 體感「按住駕駛」設 `enabled = false`
  （遮罩出現前已按住的手指仍會被搖桿追蹤，停用才會結束手勢並送 0）。
- 滑入式導覽的「中斷連線」走 shell 的確認對話框；導覽軌底部的「緊急停止」= `vm.stopControl()` + `vm.emergencyStop()` + 重震動。

### 11.5 遙控器元件（`com.b570.pilot.ui.components`，新增 / 擴充；舊 API 全部相容）

**Joystick（擴充）**
```kotlin
fun Joystick(
    onMove: (x: Float, y: Float) -> Unit,      // 同步呼叫；x 右正、y 上正，-1..1（已套死區 + expo）；放開 / 取消 / 停用 / 離開一定送 (0,0)
    modifier: Modifier = Modifier,              // 用 size / fillMaxHeight 決定大小；預設長寬比 BOTH 1:1、VERTICAL 0.42、HORIZONTAL 2.4
    axes: JoystickAxes = JoystickAxes.BOTH,     // BOTH / VERTICAL / HORIZONTAL
    expo: Float = 0f, deadzone: Float = 0.06f,
    enabled: Boolean = true,                    // false → 立即結束手勢並送 (0,0)；重新啟用要重新按下
    accent: Color = B570Theme.colors.accent,
    onRelease: (() -> Unit)? = null,
    style: JoystickStyle = JoystickStyle.STANDARD,  // STANDARD（大尺寸自動加刻度圈）/ THROTTLE（直立滑槽 + LED + 前進/後退 + T 形把手）/ STEERING（弧形軌道 + LED + 左/右）/ ANALOG_360（見下）
    showReadout: Boolean = false,               // 按住時旋鈕上顯示 %（VERTICAL→y、HORIZONTAL→x、BOTH→合成量；ANALOG_360 = 角度° + %；整數字串預建，零配置）
    ghost: Boolean = false,                     // 放開後在最後位置留下淡出殘影（約 1.4 s）
    feedback: (() -> Offset)? = null,           // 回授標記 -1..1（例如車子實際 targetSpeed / targetTurn），**繪製階段呼叫**，可讀 50 Hz State
    labels: JoystickLabels? = null,             // null = 依 style（Throttle「前進/後退」、Steering「右/左」、ANALOG_360 前/後/左/右）；JoystickLabels.None = 不畫
    onPress: (() -> Unit)? = null,              // 手指按下（第一個 onMove 前）
    excludeSystemGestures: Boolean = true,      // 搖桿區排除系統返回手勢（貼邊時拇指不會被「返回」搶走）
    snapDeg: Float = ANALOG_SNAP_DEG,           // 只用於 ANALOG_360：前 / 後 / 左 / 右 ±5° 內輸出正方向（0 = 不吸附）
)
data class JoystickLabels(val positive: String, val negative: String)  // .Throttle / .Steering / .None
fun joystickCurve(v: Float, expo: Float): Float
const val ANALOG_SNAP_DEG = 5f
internal fun analogStickMap(dx: Float, dy: Float, deadzone: Float, expo: Float, snapDeg: Float, out: FloatArray /* ≥ ANALOG_OUT_SIZE = 6 */): Float
// out[0] x 右正、out[1] y 上正（x² + y² ≤ 1）、out[2..3] 旋鈕位置（跟手指、不吸附）、out[4] 推桿量 0..1、out[5] 方位角°（0 前 / 90 右；0 推桿量 = -1）；回傳夾限後半徑
```
**`JoystickStyle.ANALOG_360`（360° 類比搖桿，駕駛預設）**：一律雙軸、1:1。外觀 = 金屬刻度環（每 15° 一格、45° 斜向較長、前 / 後 / 左 / 右 字，推向哪邊哪個字亮）+
凹槽底座（50 % 行程圈、100 % 行程虛線、淡十字）+ 凹面旋鈕帽（止滑紋、陰影、高光）跟著拇指在圓內任意移動、放開彈簧回中（稍微過衝）；
外環發光弧指向推桿方向（長度 / 亮度 = 推桿量）；按住時角度° / % 讀數放在旋鈕「靠中心」那一側（拇指不會蓋住）；放開後上次指令的殘影約 1.4 s 淡出；
`feedback` = 車子實際目標速度 / 轉向的紫色標記。換算 `analogStickMap`：**徑向**夾回單位圓（斜推不會變 √2 倍）→ 圓形死區 6 % → 重新縮放 →
expo 只套推桿量（方向不變）→ 正方向吸附（純前推轉向 = 0、純橫推速度 = 0，其他角度連續、45° 不變）。
觸覺：離開死區 tick、經過前 / 後 / 左 / 右 輕 tick、推到邊緣輕撞（`click()`，共用觸覺沒有更輕的）。
駕駛頁換算（`DriveController.set(forward = y, turn = x)`）：車速 = y × MAX_SPEED × 檔位速度比、轉向 = x × 1000‰ × 檔位轉向比；全斜推 = 各 70.7 %（`Drive360MappingTest`）。
- 大尺寸：旋鈕半徑 = 短邊 × 0.22，夾在 14..36 dp → 180–240 dp 的搖桿行程更長、更細膩（ANALOG_360 另算：旋鈕 = 凹槽半徑 × 0.4、行程 = 凹槽半徑 − 0.7 × 旋鈕）。
  建議：雙搖桿 = 左 `VERTICAL + THROTTLE`、右 `HORIZONTAL + STEERING`（高 160–240 dp）；360° 搖桿 = `ANALOG_360` 180–300 dp。
- 觸覺：離開死區 tick、推到底 heavy；THROTTLE / STEERING 每 25 % 一格輕 tick。
- **多點觸控**：每個搖桿只追蹤按下它的那根手指（以 `PointerId` 過濾；Compose 只把命中該節點的指標送進它的 `pointerInput`），
  兩個搖桿可同時用兩根拇指操作、互不干擾；同一搖桿上的第二根手指被忽略直到第一根放開；一根放開只會讓**那一支**送 (0,0)
  （雙搖桿請分別寫 forward / turn 軸，例如 `controller.setForward(y)` / `controller.setTurn(x)`，不要在一支放開時把另一軸歸零）。
- 換算是純函式 `joystickMap(...)` / `analogStickMap(...)`（internal），有單元測試 `JoystickMathTest`（圓形夾限、圓形死區、expo 不改方向、角度、吸附、NaN / 無限大 / NaN 設定值、讀數字串）。
- **手勢保護 `GestureGuard`**（兩種搖桿共用）：換算用的是按下當時的大小與中心，所以按著的時候搖桿本身被移動 / 縮放 / 改變大小
  （換邊、切分頁 / 模式的動畫、視窗大小改變、整頁捲動、瀏海換邊）超過 2 dp，或一個事件內手指跳超過搖桿長邊 1.5 倍（螢幕 180° 翻轉的座標鏡射、觸控面板把手指搞混）
  → 結束手勢、送 (0,0)、要重新按下（`JoystickGestureGuardTest`）。

**ShoulderButton**（上緣左右角的寬扳機鍵）
```kotlin
enum class ShoulderSide { LEFT, RIGHT, CENTER }   // 外側上角較圓（L1 / R1）；CENTER = 膠囊
fun ShoulderButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,   // 寬度由 modifier 給（建議 ≥ 120 dp）
    icon: ImageVector? = null, side: ShoulderSide = ShoulderSide.CENTER, color: Color = accent,
    enabled: Boolean = true, active: Boolean = false /* 亮燈 */, loading: Boolean = false,
    holdToConfirmMs: Int = 0,          // > 0：按住填滿才觸發（提早放開 = 取消 + 拒絕震動）；TalkBack 雙擊直接觸發
    badge: String? = null,             // 外側上角小字，例如 "L1"
    height: Dp = 48.dp,                // 含 4 dp 立體唇邊；按下時鍵面下沉
    onPressedChange: ((Boolean) -> Unit)? = null,  // 瞬時鍵（按住才作用）
)
```

**GamepadButton**（A / B / X / Y 圓形動作鍵）
```kotlin
fun GamepadButton(
    icon: ImageVector, label: String? /* 鍵下方小字，1 行 */, onClick: () -> Unit, modifier: Modifier = Modifier,
    diameter: Dp = 60.dp,              // 建議 52–72 dp；總高 ≈ 直徑 + 18 dp（含標籤）
    color: Color = accent, enabled: Boolean = true,
    active: Boolean = false,           // 播放中：旋轉發光環 + 呼吸光暈（TalkBack 念「進行中」）
    loading: Boolean = false,
    dimmed: Boolean = false,           // 較暗但仍可點（例如「需先啟動平衡」→ 點了跳提示說明）
    glyph: String? = null,             // 右上角小字母 "A"/"B"/"X"/"Y"
    progress: (() -> Float)? = null,   // 外環進度 0..1，繪製階段呼叫（編排播放進度）
    onLongClick: (() -> Unit)? = null,
)
```

**RockerSwitch**（升檔 / 降檔翹板，中央顯示目前檔位 + 位置點；按下往該側傾斜；到頂再按 = 拒絕震動）
```kotlin
fun <T> RockerSwitch(
    options: List<T> /* 由低到高 */, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },   // 例：{ it.driveLabel }（慢 / 中 / 快）
    title: String? = "檔位", increaseText: String = "升檔", decreaseText: String = "降檔",
    vertical: Boolean = true,                   // 直立 64 × 148 dp（上 = 升檔）；false = 橫向 176 × 56 dp（右 = 升檔）
    enabled: Boolean = true, colorOf: ((T) -> Color)? = null,
)
```

**LeverSwitch**（遙控器撥桿開關，上 = 開；40 × 64 dp + 標籤；TalkBack「切換開關」）
```kotlin
fun LeverSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
                label: String? = null, enabled: Boolean = true, color: Color = accent)
```

**EmergencyStopButton**（共用緊急停止：觸碰瞬間觸發、紅色、強光暈、字級最多放大 1.15 倍保證完整顯示、無進場動畫）
```kotlin
enum class EStopStyle { BAR /* 圖示 + 字橫排 */, ROUND /* 工業蘑菇頭：紅鈕 + 黃外環 */, STACKED /* 圖示在上，窄欄用 */ }
fun EmergencyStopButton(onStop: () -> Unit, modifier: Modifier = Modifier, style: EStopStyle = EStopStyle.BAR,
                        haptic: Boolean = true /* 自己做觸覺時傳 false */, label: String = "緊急停止")
```

**HUD 膠囊（遙控器上緣狀態列）**，高 `HudChipHeight = 30.dp`
```kotlin
fun HudChip(modifier = Modifier, color = textSecondary, onClick: (() -> Unit)? = null, contentDescription: String? = null, content: RowScope.() -> Unit)
fun HudChip(text: String, modifier = Modifier, icon: ImageVector? = null, color = textSecondary, textColor = textPrimary, onClick = null)
fun BatteryChip(volts: Float?, modifier = Modifier, showVoltage: Boolean = true)   // 電池圖示 + % + 電壓，依電量變色
fun RttChip(rttMs: Float?, modifier = Modifier)                                    // 「12 ms」：< 60 綠、< 150 黃、其餘紅；rttColor(rttMs)
fun SignalChip(level: Int /* 0..4 */, modifier = Modifier, text: String? = null)
fun LinkQualityChip(percent: Int?, modifier = Modifier)                            // 「品質 98%」；傳 vm.linkStats 的 linkQualityPercent（舊的 linkQualityPercent(linkQ) 已移除）
fun ConnectionChip(state: ConnectionState, modifier = Modifier, demo: Boolean = false)  // 呼吸圓點；connectionColor(state)
fun MotorChip(motorsOn: Boolean?, modifier = Modifier, detail: String? = null)    // 運轉中 / 已停止 · 原因
fun HudIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier = Modifier,
                  size: Dp = 40.dp, color = textPrimary, highlighted: Boolean = false, badge: Boolean = false, enabled: Boolean = true)  // ≡ 選單鈕等
fun LinkLostBadge(sinceMs: Long, modifier = Modifier, compact: Boolean = false)   // 訊號中斷 + 失聯秒數（狀態條同款）
```
**HudStatusRow**（`com.b570.pilot.ui.shell`，綁 ViewModel 的現成組合：連線 / 失聯、電量、訊號、延遲、連線品質；自己收集 ≤ 10 Hz 資料，只有這一列重組）
```kotlin
fun HudStatusRow(vm: AppViewModel, modifier: Modifier = Modifier, compact: Boolean = false, showConnection: Boolean = true)
```
Shell 其他公開函式（畫面一般不用）：`NavRail(selected, onSelect, modifier, onEStop: (() -> Unit)? = null)`、`StatusStrip(vm, modifier)`、
`ReconnectBadge(status: ReconnectStatus, onCancel, modifier, compact = true)`（自動重連提示：旋轉圖示 +「車子斷線，自動重連中（第 n/6 次）」/ 短句 / 極短句（依寬度 ÷ 字級）+ 倒數 + 取消；
本體不攔觸控、倒數只在內部文字重組；狀態條、滑入式導覽的車況面板、`HudStatusRow`、駕駛上緣列都用它）。純函式：`rootRouteOf`、`reconnectTitleZh`、`reconnectTitleLengthOf`、`reconnectDetailZh`。

**對話框**
```kotlin
fun GlassDialog(onDismissRequest: () -> Unit, modifier: Modifier = Modifier, maxWidth: Dp = 440.dp,
                glow: Color? = accent.copy(alpha = 0.4f), contentPadding: PaddingValues? = null /* null = 自動 */,
                scrollable: Boolean = true, properties: DialogProperties = DialogProperties(),
                horizontalAlignment: Alignment.Horizontal = Alignment.Start, content: ColumnScope.() -> Unit)
fun DialogImmersiveEffect()      // 自己的 Dialog 內容第一行呼叫
fun isShortScreen(): Boolean     // 螢幕高 < 480 dp（手機橫放）
val DialogEdgeH = 16.dp; val DialogEdgeV = 12.dp   // GlassDialog 與螢幕邊緣的最小距離
```

**版面工具（`Layout.kt`）**
```kotlin
val LocalScreenPadding: ProvidableCompositionLocal<PaddingValues>; fun Modifier.screenPadding(): Modifier
operator fun PaddingValues.plus(other: PaddingValues): PaddingValues          // @Composable
fun PaddingValues.verticalOnly(extra: Dp = 0.dp): PaddingValues
fun PaddingValues.horizontalOnly(layoutDirection: LayoutDirection, extra: Dp = 0.dp): PaddingValues
val WindowInsets.Companion.safeArea: WindowInsets                              // systemBars ∪ displayCutout（不含 IME）
fun safeAreaInsets(): PaddingValues
enum class LandscapeClass { COMPACT, MEDIUM, EXPANDED }; fun landscapeClassOf(width: Dp, height: Dp): LandscapeClass
val LocalLandscapeClass; data class WindowDpSize(val width: Dp, val height: Dp); val LocalWindowDpSize
fun rememberDisplayRotation(): State<Int>                                      // Surface.ROTATION_*，左右翻轉也會更新
fun Modifier.horizontalEdgeFade(state: ScrollableState, width: Dp = 20.dp): Modifier  // 水平捲動列還能捲的那一側淡出（放在 horizontalScroll / LazyRow 之前）
```
**提示**：`CarEventToastHost(events, modifier, maxVisible = 3, maxWidth: Dp = 460.dp, dismissOnClick: Boolean = true)`、
`ToastCard(event, modifier, onClick = null, maxWidth = 460.dp)`（`onClick = null` 時不可點、不攔觸控）。

### 11.6 安全不變式（對抗式審查後加入，改版時**不可破壞**）
1. **即時**：搖桿 / 體感回呼**同步**呼叫 `setControl`（經 `DriveController`）——不經 coroutine、不 debounce、不自己開迴圈（連線層固定 50 Hz 送最新值）。
2. **歸零**：放開、觸控取消、離開駕駛（`DisposableEffect` onDispose）、`ON_PAUSE` / `ON_STOP`、切換駕駛模式（**含從設定頁改**：`LaunchedEffect(mode, stickRight)`）、
   **360° 搖桿換邊**、訊號中斷、**打開滑入式導覽** → 控制量立即歸零。
   **駕駛畫面被蓋住或正在離開**（`covered`）也一樣停用並歸零：QR 掃描覆蓋層（桌面捷徑「掃描配對」，下層駕駛畫面仍在組合中）、
   切到別的分頁（編排鈕 / Y 鍵：退場動畫約 260 ms 期間駕駛畫面還在、而且往上滑）、離開主畫面；**螢幕左右翻轉**時短暫停用（同緊急停止 350 ms）要重新按下。
   shell 另外在 `onStop`、失去視窗焦點、按導覽軌緊急停止時 `stopControl()`；`handleLaunchRequest` 一開始也 `stopControl()`。
3. **封鎖**：`ControlBlock.blocksInput`（LINK_LOST / OFFLINE / CONNECTING / **RECONNECTING**）時搖桿與「按住駕駛」`enabled = false`；恢復後**必須重新按下**才會送出
   （`Joystick(enabled = false)` 會結束手勢並送 (0,0)，重新啟用不會接續舊手指）。滑入式導覽打開時同樣停用。
   自動重連中 shell 留在駕駛畫面（不跳連線頁），`controlBlockOf(state, telemetry, reconnecting = vm.reconnect != null)` 在 Failed / Idle / 連線中都回 RECONNECTING「自動重連中…」。
   搖桿本身在按著的手指下被移動 / 改變大小、或手指座標跳動 → `GestureGuard` 結束手勢（§11.5），同樣要重新按下。
4. **MotorToggle**：依「按下當時顯示的標籤」決定 START / STOP；標籤翻轉後 700 ms 內忽略 START；
   IMU 異常（`startBlockedOf` → SENSOR）時 START 停用；低電量顯示 LOW_BATT 說明。`startBlockedOf` / `controlBlockOf` / `noticeOf` 規則與測試保持不變。
5. **緊急停止**：`controller.stop()` + `vm.emergencyStop()` + 重震動；**一直看得到、按得到**——不做進場動畫、不被提示 / 選單 / 自己的面板蓋住、
   大、紅、觸碰瞬間觸發（用 `EmergencyStopButton` 即符合）。滑入式導覽打開時遮罩會蓋住駕駛畫面，所以導覽軌底部也有緊急停止。
   **整合時補上**：駕駛畫面按下緊急停止後，搖桿 / 按住駕駛停用 350 ms（`ESTOP_INPUT_LOCK_MS`，與紅色閃光同時）→
   還按著的拇指一律結束手勢，要**重新按下**才會再送。（沒有這段時，另一根拇指仍按著油門會在緊急停止後立刻把 300 mm/s 送回去，
   之後若在拇指沒放開的情況下按「啟動平衡」，車子會帶著油門啟動。）
   **極小視窗**（寬 < 520 或高 < 260 dp，整頁可捲動）：肩鍵列的緊急停止可能在畫面外 / 被捲走 → 另外固定一顆在視窗上（沒捲動 / 捲到底時剛好疊在原位）。
   **自動重連中按下**：送不到 → 提示並在連回同一台車時立刻補送（§10）。
6. **快捷動作 / 編排播放**：需「已連線 + 馬達運轉中」，否則拒絕震動 + 提示（「尚未連線」/「請先按『啟動平衡』」）。
7. **體感**：螢幕座標用 `rememberDisplayRotation()`；左右翻轉（90 ⇄ 270）時**結束目前的按住駕駛並要求重新按下**（零點與軸向都失效）。
8. 駕駛畫面的提示不攔截觸控；不要在駕駛畫面放會擋住搖桿 / 緊急停止的常駐浮層（延遲膠囊的細節是在上緣列**原地展開**、8 s 自動收回，不是浮層）。
9. 多點觸控：兩支搖桿同時操作互不干擾；一支放開只歸零自己的軸。360° 搖桿按著時，另一根拇指按 A/B/X/Y 或檔位翹板不會打斷搖桿；放開搖桿立即歸零。

**自動測試**：`app/src/test/.../ui/screens/drive/DriveMultiTouchTest.kt`（Robolectric + Compose UI test，真 `B570Root` + 真 `DriveScreen` + 展示車，
直接讀連線層最後送出的 CTRL 值）涵蓋 1、2、3、5、9：兩根拇指同時推 → 兩軸都有、放開一根只歸零自己的軸、同一支搖桿的第二根手指被忽略、
放開 / 觸控取消歸零、打開滑入式導覽 / 訊號中斷 / 切換模式 / `ON_PAUSE` / 緊急停止立即歸零、恢復後舊手指繼續推不送（要重新按下）；
360° 搖桿：純前推轉向 0、純橫推速度 0、按住搖桿時另一根拇指升檔（用新檔位重送）/ 按住 A 鍵（搖桿照送、放開搖桿歸零、放開 A 鍵播放快捷動作）、
換邊（記住）；自動重連中留在駕駛畫面、封鎖並歸零、重連提示在緊急停止那一列之上、連回後要重新按下、「取消」停止重連並中斷。
搖桿容器有 testTag（`DriveTestTags.THROTTLE / STEERING / STICK`，只加在 semantics）。

### 11.7 檔案所有權（橫向改版，6 位畫面工程師）
| 工程師 | 只能修改 | 入口（簽名固定） | 橫向版面建議 |
|---|---|---|---|
| 1. 連線 + 掃描 | `ui/screens/connect/`、`ui/screens/scanner/` | `ConnectScreen`、`QrScannerScreen` | 連線：左 = 品牌 / 陀螺儀主視覺 + 連線進度，右 = 記住的車清單 + 掃描 QR / 手動輸入 / 展示模式。掃描：相機全螢幕、取景框置中，左側說明 / 返回，右側縮放（直立滑桿）+ 手電筒 + 對焦提示。 |
| 2. 駕駛 | `ui/screens/drive/` | `DriveScreen` | 全螢幕遙控器：左上 ≡（`HudIconButton` → `nav.openNavOverlay()`）、上緣中央 `HudStatusRow`、左右上角 `ShoulderButton`；下方左右兩支大搖桿（依模式），中控台 = 姿態儀 + 車速 / 儀表 + 啟動平衡 + **緊急停止**；快捷動作 = `GamepadButton` 菱形 A/B/X/Y（+ 更多 → 編排）；模式選擇、`RockerSwitch` 檔位；情境提示 / ControlBlock 保留。（最終版面見 §11.8） |
| 3. 監測 | `ui/screens/monitor/` | `MonitorScreen` | 左 = 圖表（`LineChart` 依可用高度算 `chartHeight`），右 = 統計 / 連線數據 / 事件紀錄（可捲動側欄）；工具列（暫停、時間窗、匯出 CSV、清除）。 |
| 4. 整定 | `ui/screens/tune/` | `TuneScreen` | 左 = 參數群組直列（直立環…系統）+ 即時套用 / 設定檔，右 = 參數列（參數區寬 ≥ 600 dp 才兩欄，實際上只有平板；915 × 412 手機的參數區約 490 dp，兩欄會只剩 220 dp 寬的滑桿）；儲存 / 零點 / 恢復預設放頂部動作列；未儲存標記。 |
| 5. 編排 | `ui/screens/choreo/` | `ChoreoScreen` | 左 = 編排庫（預設 + 自己的），右 = 路徑預覽 + 步驟清單；編輯器左右分欄（步驟清單 ⇄ 步驟編輯 / 路徑），播放列固定在下緣。 |
| 6. 設定 | `ui/screens/settings/` | `SettingsScreen` | 主從式：左 = 分類清單（車輛 Wi-Fi、連線方式、操控、外觀、遙測、展示、記住的車、關於；第三次整合加入「連線方式」= 8 類），右 = 該分類內容（可捲動）。 |

- 每位只改自己的目錄；需要共用元件沒有的東西 → 在自己 package 做 private 版本。共用層（`ui/components`、`ui/theme`、`ui/shell`、`AppNavigator`、`AppViewModel`、Manifest、`MainActivity`、Gradle）屬 foundation，不要改。
  （分工階段已結束；整合時對共用層與各畫面的修改列在 §11.8。之後的修改照一般程式碼審查即可，但 §11.6 不變式與 `DriveMultiTouchTest` 必須維持通過。）
- 自己 package 的單元測試放 `app/src/test/java/com/b570/pilot/ui/screens/<pkg>/`；既有測試（`DriveModelsTest`、`ReadinessTest`…）必須繼續通過。
- 建置：`bash ".../scratchpad/locked_gradle.sh" assembleDebug testDebugUnitTest`（全機序列化）；同時有人在改別的 package，別人檔案的編譯錯誤先忽略。

### 11.8 整合結果（2026-10-17，最終狀態）

**最終版面（各畫面摘要，細節看各 package 的 `*Layout.kt` 純函式與其單元測試）**
| 畫面 | 版面 | 尺寸決策 |
|---|---|---|
| 連線 | 左 = 連線狀態 + 陀螺儀主視覺 + 「B570 PILOT」（標題依欄寬縮放），右（可捲動）= 主要動作（沒有記住的車：「配對你的平衡車」三步驟 + 掃描 QR 配對；一台記住的車在附近：「連線 <名稱>」；其他：掃描 QR 配對 / 手動輸入）→ 自動連線 / 桌面捷徑大卡片或連線中的 4 步驟 / 失敗卡（自動重連放棄時加「已自動重試 n 次」）→ Wi-Fi 關閉卡 → 附近的新車 → 記住的車子（在附近 / 不在範圍內） | `ConnectLayout.kt`；寬 < 560 dp 單欄。開 App 自動連線由這頁在 Wi-Fi 與權限就緒時呼叫 `vm.autoConnectOnLaunch()`（每個 App 行程最多一次）。系統同意提示：分欄時放在左側主視覺上緣、單欄時放下緣（不蓋住連線卡的「取消」） |
| 掃描 | 相機全螢幕、取景框在安全區置中；左 = 關閉 + 說明 + 改用手動輸入；右 = 手電筒 + 直立縮放滑桿 + 對焦 | `ScanLayout.kt`；影像分析跟著 90 ⇄ 270 翻轉更新旋轉 |
| 駕駛 | 上緣列（≡、車況膠囊依寬度整顆隱藏 / 自動重連中換成重連提示、360° 模式的「搖桿在左 / 在右」、編排、檔位翹板（360° 模式放得下時搬到按鍵區））→ 肩鍵列（啟動平衡 / 停止、模式選擇「360° 搖桿 \| 雙搖桿 \| 體感」、紅色緊急停止）→ 左手區 · 中控台 · 右手區。**360° 搖桿（預設）** = 一手圓形類比搖桿、另一手 A/B/X/Y 菱形（原地轉圈 / 搖擺 / 點頭 / 更多 → 編排）+ 直立檔位翹板（靠中控台那側），預設搖桿在左（像手把），可對調並記住；中控台 = 姿態儀 + 車速 + 輪速 + 向量讀數（跟搖桿同步的方向 + 推桿量 + 送出的速度 / 轉向，窄時收起部分）。雙搖桿 = 直立油門 + 橫向方向；體感 = 左右「按住駕駛」+ 水平儀 | `DriveLayout.kt`（`rcMetricsOf`、`leftZoneOf` / `rightZoneOf`）；360° 搖桿 640 × 320 約 185 dp、780 × 360 約 226 dp、915 × 412 約 240 dp（手機上限）、平板 300 dp；按鍵區至少留給中控台 200 dp，菱形鍵 ≥ 44 dp 才把檔位翹板搬進按鍵區（否則留在上緣列，極窄分割畫面 < 520 × 260）；高 < 260 或寬 < 520 dp 整頁可捲動。中控台快捷列（雙搖桿 / 體感）依量字結果用完整 / 短標籤 / 緊湊 / 只圖示 |
| 監測 | 左（可捲動）= 狀態卡 + 12 個數據磚 + 事件紀錄；右 = 工具列（5/10/30/60 秒、暫停、匯出 CSV）+ 五張圖表卡（逐張吸附捲動）；點圖表 → 放大檢視（右側統計面板） | `MonitorLayout.kt`；915 寬以上兩欄圖表；時間範圍每段太窄（字級 1.3）時只顯示數字 |
| 整定 | 左 = 固定即時曲線 + 可捲動（即時套用撥桿、儲存到車上、讀取 / 零點校正 / 恢復預設、設定檔）；右 = 群組快速跳轉列 + 六個參數群組 + 浮動「套用 N 項變更」 | `TuneLayout.kt`；零點校正對話框在矮螢幕左右分欄、按鈕固定在下方，「馬達運轉中 · 停止馬達」移到說明最前面（整合時修正：原本在可捲動區最後面，手機橫放看不到） |
| 編排 | 首頁 = 標題 + 狀態橫幅 + 動作庫格線（3–6 欄）+ 我的編排（平板在右側獨立欄）；編輯器左右分欄（步驟時間軸 ⇄ 路徑預覽 + 步驟條 + 循環 + 上傳並播放）；播放中下緣固定播放列 | `ChoreoLayout.kt` |
| 設定 | 主從式：左 = 分類清單（7 類 + 中斷連線 / 離開展示模式），右 = 該分類內容（「記住的車子」最上面是「開啟 App 自動連線 / 斷線自動重連」開關）；對話框在矮螢幕左右兩欄 | `SettingsLayout.kt`；內容寬 < 480 dp 單欄 |

**整合時的修正（共用層 + 各畫面）**
- 駕駛：緊急停止後強制重新按下（§11.6 第 5 點）；快捷動作列不再被切掉半顆（640 × 320 原本「點頭」被裁）；搖桿容器加 testTag。
- `AttitudeIndicator`：左上角傾角讀數不再壓到 20° / 40° 刻度字；航向帶兩端不畫被切一半的數字。
- `LineChart`：矮圖表（整定頁 56–91 dp）Y 軸數字不再疊在一起；圖例列邊緣淡出。
- `GlassDialog`：固定 16 / 12 dp 外距（見 §11.1）。滑入式導覽的車況面板：膠囊改成同一個流式排列，640 × 320 不用捲就看得到「中斷連線」。
- 新增 `Modifier.horizontalEdgeFade`（圖表圖例、整定群組列）。監測時間範圍大字級不再擠字。
- Gradle：加入 Robolectric 4.14.1 / Compose ui-test 測試依賴（只在 test / debug），`DriveMultiTouchTest` 進入正式測試。

**驗證**
- `assembleDebug testDebugUnitTest assembleRelease`：0 錯誤、0 警告；129 個單元測試全過。
- 截圖：另有一份 Robolectric 截圖用的專案副本（scratchpad 的 `integ_iso`，不在 repo 內，`LandscapeShotsTest`），
  以展示模式跑完連線 → 掃描 → 駕駛三種模式 / 滑入式導覽 / 緊急停止 / 訊號中斷 → 監測（+ 放大）→ 整定（+ 零點校正）→ 編排（+ 編輯器）→ 設定（+ 修改 Wi-Fi），
  尺寸 640 × 320（字級 1.0 與 1.3）、780 × 360，另有 915 × 412 與 1280 × 800 的主要分頁。
  主視窗用 `HardwareRenderer` + `ImageReader` 擷取（RecordingCanvas → Compose 圖層走 RenderNode，離屏合成 / 光暈與實機一致；
  軟體 `View.draw` 會讓 `horizontalEdgeFade` 這類 `BlendMode.DstIn` 穿透成透明，是截圖假象）。對話框是另一個視窗，截圖時另外疊上去。
  已知截圖假象：Robolectric 的系統時鐘不隨真實時間前進，監測頁「遙測幀率」會顯示不合理的值（例如 5200 Hz），實機不會。

**還需要實機確認**（Robolectric 不能驗證的部分）
1. 沉浸式 + 瀏海（SHORT_EDGES）在真機上的安全區；左右翻轉時瀏海換邊、提示 / 肩鍵 / 導覽軌跟著避開。
2. 真實多點觸控（兩根拇指 + 系統邊緣返回手勢排除）與觸覺強度；體感模式的感測器方向與 90 ⇄ 270 翻轉。
3. 鍵盤彈出時的對話框（手動輸入、修改 Wi-Fi、重新命名）：輸入框是否自動捲進可見範圍。
4. 掃描：兩個橫向方向都能讀到 QR、成功動畫落在 QR 上；縮放 / 手電筒 / 對焦。
5. 開啟 App 自動連線（新接上的呼叫端）：權限已給、Wi-Fi 已開、有記住的車時，啟動 App 會自動跳出系統「連線到裝置」對話框。
6. 平板（1280 × 800）駕駛頁的搖桿上限 300 dp、中控台置中：實際握持時拇指是否舒適。

### 11.9 第二次整合：360° 搖桿 + 快速連線（2026-10-17，最終狀態）

使用者需求：「我要的是像 joystick 喔，360 度」→ 駕駛預設改成一支圓形類比搖桿（往任何方向推）；同時合併快速連線（配對一次之後打開 App 就自動連線）。

**360° 搖桿（駕駛工程師）**
- `Joystick(style = JoystickStyle.ANALOG_360, snapDeg = ANALOG_SNAP_DEG)` + 純函式 `analogStickMap`（§11.5）；舊的 STANDARD / THROTTLE / STEERING 行為完全不變。
- `JoystickMode.SINGLE` 標籤改「360° 搖桿」並成為預設；`SettingsStore` 改用 `joy_v2`（升級後每個人先落在 360°，之後的選擇照常保存；`JoystickModeMigrationTest`）。
- 駕駛版面：`ZoneKind.STICK_360` / `CLUSTER`（A/B/X/Y 菱形 + 直立檔位翹板，翹板在按鍵區時上緣列不重複）；上緣列「搖桿在左 / 在右」膠囊（換邊會歸零、記在 `DrivePrefs`）；
  中控台下方是跟搖桿同步的向量讀數（方向儀 + 角度 / 推桿 % + 送出的速度 / 轉向）。雙搖桿、體感不變。
- 測試：`JoystickMathTest`（+7）、`Drive360MappingTest`（新）、`DriveLayoutTest`（+2）、`DriveMultiTouchTest`、`JoystickModeMigrationTest`（新）。

**快速連線（連線工程師）**：§2 的 `reconnect / reconnectGaveUp / nearby / pendingConnect` 與函式、§3 的路由規則、§5 的 `ReconnectStatus / NearbyCars / CarShortcuts`；
`MainActivity` 變成 `singleTop` 並處理桌面捷徑（`onCreate` 新啟動 + `onNewIntent`）；新增 `res/drawable/ic_shortcut_car.xml`、`ic_shortcut_scan.xml`；
設定「記住的車子」有「開啟 App 自動連線 / 斷線自動重連」兩個開關，分類摘要顯示連線模式，使用說明改成「開機 → 掃描 OLED QR（只要第一次）→ 之後打開 App 會自動連線」。
測試：`ShortcutsTest`、`ShellRoutingTest`、`ConnectQuickTest`（新）。

**整合時的修正**
- **駕駛的自動重連提示改由駕駛上緣列自己畫**：原本 shell 用 `driveBannerInsetsOf()` 抄駕駛上緣列的寬度把提示疊上去，360° 模式多了換邊膠囊、
  檔位翹板又會搬到按鍵區，寬度猜不準（640 × 320 字級 1.3 時提示只剩「重連 …」）。現在 `DriveTopBar` 的車況膠囊那一格在 `vm.reconnect != null` 時換成 `ReconnectBadge`，
  寬度永遠是那一列實際剩下的空間、不可能蓋到右側按鈕或下一列的緊急停止；shell 的 `DriveReconnectBanner` / `driveBannerInsetsOf` 移除（對應的單元測試一併移除，改由 `DriveMultiTouchTest` 驗證）。
- **駕駛控制區在自動重連中顯示「自動重連中…」**：新增 `ControlBlock.RECONNECTING`（`blocksInput = true`，`controlBlockOf(..., reconnecting)`）；原本重連等待期間顯示「尚未連線」。快捷動作被拒時提示「自動重連中，請稍候」。
- 滑入式導覽車況面板的重連提示：窄面板（< 300 dp ÷ 字級）把「取消」改成 ✕ 圖示鈕，主文字不再被擠成兩行。
- 連線頁「請在系統視窗按「連線」」提示：原本貼上緣置中，在 640 × 320 會蓋住自動連線卡的「取消」→ 分欄時放在左側主視覺上緣（只佔那一欄），單欄時放下緣；說明縮短成一行。
- 連線頁「附近的新車：B570-7F3A」在窄欄被切成「B570-7F…」→ 車名整段換行（`FlowRow`）；配對說明兩句分行、「QR Code」不再被拆開（不換行空白）。
- 設定頁開關（`SettingSwitchRow`）右側光暈被這一列的圓角裁掉一塊 → 右邊留 4 dp；「斷線自動重連」說明縮短（780 寬一行放得下，「45 秒」不拆開）。
- 文案：使用說明 / 常見問題 / 操控說明改成「360° 搖桿」（原本「單搖桿」「左手油門、右手方向」）；設定頁 360° 模式圖示改成和駕駛頁一致（`ControlCamera`）。
- `DriveMultiTouchTest` 新增：按住 360° 搖桿時另一根拇指按住 A 鍵（搖桿照送、放開搖桿歸零、放開 A 鍵播放快捷動作）；自動重連中留在駕駛畫面、封鎖並歸零、
  重連提示在緊急停止那一列之上、連回後要重新按下、「取消」停止重連並中斷。`DriveModelsTest` 新增 RECONNECTING 規則。

**驗證**
- `assembleDebug testDebugUnitTest assembleRelease`：0 錯誤、0 警告（Kotlin 全部重新編譯確認）；**170 個單元測試全過**（含 8 個駕駛多點觸控 UI 測試）。
- 截圖（scratchpad 的 `integ_iso` 副本，`LandscapeShotsTest`，輸出 `landscape_shots_v2/`）：780 × 360 與 640 × 320（字級 1.3）完整流程 ——
  連線頁（自動連線卡、系統同意提示、記住的車 + 附近標記、清單捲下去的「在附近 / 不在範圍內」、沒有記住的車）→ 駕駛 360°（靜止、拇指斜推、搖桿在右）、雙搖桿、體感、
  滑入式導覽、緊急停止、自動重連中（+ 車況面板）、訊號中斷 → 監測 → 整定 → 編排（+ 編輯器）→ 設定「記住的車子」（連線開關）；
  另有 1280 × 800、915 × 412、640 × 320（字級 1.0）的 360° 搖桿斜推。觸控用真的指標事件（按下 → 斜推 → 按住時截圖）。
  360° 搖桿實際直徑：640 × 320 = 185 dp、780 × 360 = 226 dp、915 × 412 = 240 dp、1280 × 800 = 300 dp，都完整落在左（或右）手區、底部對齊拇指區。

**還需要實機確認**（在 §11.8 清單之外）
1. 360° 搖桿的觸覺（離開死區 / 經過前後左右 / 推到邊緣）強度與頻率；兩根拇指同時操作（搖桿 + A/B/X/Y / 檔位翹板）。
2. 桌面捷徑：長按圖示的「連線 <車名>」/「掃描配對」、App 開著時點捷徑（singleTop → onNewIntent）、釘選捷徑在車子被忘記後停用。
3. 系統「連線到裝置」視窗跳出的時機與「請在系統視窗按『連線』」提示（0.7 s）是否同時出現、會不會被系統視窗蓋住。
4. 附近的車掃描：系統節流（前景 2 分鐘 4 次）下的更新頻率；Android 10–12 關閉定位服務時掃描結果是否為空（此時不顯示「不在範圍內」）。
5. 自動重連：真車關機 / 走遠再回來，6 次重試的節奏、駕駛畫面停留、連回後要重新按下搖桿；重連期間螢幕保持常亮。

### 11.10 安全審查：360° 搖桿 + 快速連線（2026-10-17）

對抗式審查第二次整合的新路徑（360° 搖桿、按鍵區、搖桿換邊、自動重連 / 自動連線 / 桌面捷徑）後修正：
| 問題（修正前） | 修正 |
|---|---|
| 搖桿換算用「按下當時」的中心：按著時搖桿被版面移走（換邊後新搖桿區的寬度動畫、切分頁 / 模式的進退場動畫、視窗大小改變、整頁捲動）→ 手指沒動卻送出假指令（實測：移 40 dp → 左轉 60 %） | `GestureGuard`：搖桿在手指下移動 > 2 dp 或改變大小 → 結束手勢送 0，要重新按下 |
| 螢幕 180° 翻轉時按著的手指座標整個鏡射 → 一瞬間推到底（實測：右轉 100 %） | `GestureGuard` 一個事件跳超過搖桿長邊 1.5 倍 → 結束手勢；駕駛畫面另外監看螢幕旋轉，翻轉時停用 350 ms 要重新按下 |
| 按著搖桿時點「編排」（上緣列 / Y 鍵）：駕駛畫面退場約 260 ms 期間搖桿照送，而且畫面往上滑會把前進變成倒退 | `onMore` 先歸零；`covered` 含「目前分頁不是駕駛 / 不在主畫面」 → 搖桿停用 |
| 桌面捷徑「掃描配對」（或找不到的車）在駕駛中打開 QR 掃描覆蓋層：下層搖桿照送，緊急停止被蓋住 | `covered` 含 `nav.overlay != null`；`handleLaunchRequest` 先 `stopControl()` |
| 桌面捷徑「連線 B」在駕駛 A（馬達運轉中）時直接中斷 A → A 一直原地平衡，手機再也停不了它 | 連著真車且馬達運轉中 → 不切換，提示「請先按『停止』」 |
| 自動重連中按緊急停止：沒有 session，ESTOP 送不出去、確認迴圈立刻結束，連回後馬達仍在轉，使用者卻看到閃紅以為停了 | 記下待補送，連回同一台車時立刻補送 + 提示（§10） |
| 分割畫面寬 < 520 dp（整頁左右捲動）時肩鍵列右端的緊急停止一開始就在畫面外；高 < 260 dp 時會被捲走 | 可捲動時另外固定一顆緊急停止在視窗上 |
| Android 系統沒記住同意時，每次自動重連都會再跳「連線到裝置」視窗（取消也會一直跳，最多 6 次） | 某次重連期間跳出系統視窗（App 失去焦點）而那次沒連上 → 停止自動重連並提示 |
| NaN 的 expo / 死區 / 吸附角 / MAX_SPEED → 搖桿輸出 NaN → `roundToInt` 在觸控事件裡丟例外（App 當掉） | 換算與 `DriveController` 把 NaN 當 0 / 預設值 |
| 新連線沿用上一段連線留下的 CTRL 值（`CarConnection.stop()` 沒有 session 時不清） | `CarConnection.start()` 一律先歸零 |

審查後確認沒問題的：搖桿 / 體感回呼同步呼叫 `setControl`；兩根手指交錯（同一支搖桿第二根手指忽略、放開只歸零自己）；換邊時舊搖桿立即停用；
重連開始 / 結束時搖桿停用並要重新按下；Activity 重建（舊手勢在 onDispose 送 0、捷徑不重複處理）；自動重連只連同一台車；
開 App 自動連線每個行程最多一次且只在 Idle；緊急停止在所有模式 / 左右 / 尺寸都看得到（重連提示在它上面那一列）；360° 換算輸出永遠在單位圓內。

測試（+12）：`JoystickGestureGuardTest`（新，真手勢）、`QuickConnectSafetyTest`（新：捷徑不丟下運轉中的車、斷線時的緊急停止連回補送）、
`DriveMultiTouchTest` +3（按著搖桿切到編排分頁、掃描覆蓋層蓋住駕駛、400 dp 分割畫面的緊急停止）、`JoystickMathTest` +3、`DriveModelsTest` +1、`ReconnectPolicyTest` +1。
每個新測試都在「拿掉修正」的版本上確認會失敗。
還需要實機確認：Android 10–14 實際翻轉 180° 時進行中的觸控是被取消還是座標鏡射（兩種都已處理）；系統同意視窗出現時 App 是否失去視窗焦點（停止重連迴圈的依據）。

### 11.11 第三次整合：熱點模式 + 控制延遲（2026-10-17，最終狀態）

使用者需求：「我想要行動網路也能控制，不僅限 Wi-Fi」→ **熱點模式**（車子的 ESP-01 以 STA 加入手機個人熱點，手機照用 4G/5G，不經雲端）；
「延遲超重…App 收數據收的很順，只是控制訊號發過去很延遲」→ 量得到**真正的控制延遲**（手機 → 車上套用），並讓上行不在手機排隊。
三端分工：STM32（每 10 ms 控制週期輪詢 UART RX DMA、TELEM v1.1 ctrl_seq / ctrl_age、NET 幀 + OLED 熱點頁、預設 ACCEL 2000 / TURN_ACCEL 12000）、
ESP-01（韌體 1.1.0：STA_SET / STA_GET / DISCOVER、熱點 ⇄ AP 備援狀態機、關閉 modem-sleep、UDP 先於 UART 處理）、App（本節）。協定見 `docs/PROTOCOL.md` §1、§2、§3.4–3.6、§4.1。

**連線層 / 資料層（連線工程師）**
- `CarConnection` 重寫成兩種路徑：直連（WifiNetworkSpecifier）與熱點（不綁 Network 的 socket + DISCOVER，見 §4）；`ConnectPlanner` 決定先找還是直連（§5），
  `connect()` / 自動連線 / 自動重連 / 重試 / 捷徑都是智慧連線；掃 QR 一律直連。
- 控制延遲：`ControlTiming` 記每個 SEQ 的 CTRL 送出時間，收到 TELEM(ctrl_seq, ctrl_age) → `延遲 = 收到 − 送出 − max(ctrl_age − 1, 0) × 10 ms`（§11.12：主控板在收到後的下一個週期才套用）→ `LinkStats.controlLatencyMs`（EMA）/ `controlLatencyP95Ms`（5 s）。
  HUD 的數字（`displayLatencyMs`）就是這個；舊主控板韌體沒有 ctrl_seq 時退回 PING 來回。
- 上行遺失：主控板 TELEM.link_q（每 100 個控制週期結算一次的「前 1 秒收到幀數」）對照手機**同一段時間**送出的主控板幀數（`LinkQSettleTracker` + `carFramesForSettle`）→ `uplinkLossPercent`、`linkQualityPercent`。
- `ControlPacer`（上行防堆積，§4）：靜止 10 Hz 心跳、操作中 50 Hz、壅塞（控制延遲 EMA > 150 ms）最多 16 Hz、放開立即送（PROTOCOL §3.1 已同步；主控板只靠它做失聯保護（400 ms），10 Hz 足夠）。
- 收發執行緒音訊優先權、CTRL 原地組幀、API < 34 加持 HIGH_PERF WifiLock、DSCP EF、傳送緩衝縮到最小。

**畫面（UI 工程師）**
- 駕駛上緣列：「熱點」/「直連」+「控制 23 ms」膠囊（點一下原地展開「控制 · P95 · 來回 · Wi-Fi」，8 s 收回）；其他分頁狀態條同款（點了開監測頁）；
  滑入式導覽車況面板：連線方式 + 延遲膠囊、「中斷連線」下方一行細節 + 一句診斷。
- 監測頁左欄「延遲診斷」卡（`MonitorLatency.kt`）。設定第 8 類「連線方式」（`LinkModeSection.kt`）：直連 / 熱點模式、車子目前狀態（已連上 + IP + 訊號 / 搜尋中 / 找不到已改回直連）、
  2.4 GHz / WPA2 說明 + 開啟手機熱點設定頁；改用熱點：表單（`HotspotConfig` 即時檢查、顯示 / 隱藏密碼）→ 確認 → 進度（重開機 → 開啟熱點 → 尋找車子、倒數、取消、在背景繼續）→ 完成；改回直連先確認。
- 連線頁：`Discovering` 三步驟條「在熱點網路尋找車子…」、記住的車標「熱點 / 直連 / 無密碼」、熱點模式的車在熱點沒開時「請開啟手機熱點「名稱」」+ 開啟熱點、
  每 5 s 在熱點上找車（「在熱點上」/ 沒記住的「熱點上的車」一點就連，不用 QR）、建議開熱點的失敗卡（熱點一開自動重試）、切換進度卡；關於頁新增熱點模式步驟、延遲小技巧、常見問題。

**整合時的修正**
| 問題（修正前） | 修正 |
|---|---|
| `ControlPacer`（靜止只送 10 Hz）之後，「品質 xx%」仍用 `link_q × 2`（假設固定 50 Hz）→ **車子靜止時連線品質顯示約 24 %（紅）** | 品質改成 `LinkStats.linkQualityPercent` = 100 − 上行遺失；移除 `linkQualityPercent(linkQ)` |
| 上行遺失拿「收到遙測前 1 秒送出的幀」比對「最多 1 秒前才結算的 link_q」→ 送出速率一變（開始推搖桿）就算出 10 % 以上的假遺失，診斷跳「指令遺失」 | `LinkQSettleTracker` 從 link_q 改變的時刻學主控板的結算相位、之後每 100 tick 推算（u16 溢位照樣準、重開機重學），手機端的 1 秒窗往前推「結算到現在 + 單程延遲」；每秒一個樣本、`UplinkLossEstimator` 改成 5 個樣本（約 5 s）。模擬測試：對齊後最大假遺失 < 3 %，舊算法 > 8 % |
| 離開展示模式後 `isDemo` 仍為 true，`discoverHotspotCars()` 一直去問已停止的展示車 → 連線頁的「熱點上的車」直到下次真連線前都找不到 | 只有展示車還在跑（`activeLink === demoLink` 且不是 Idle）才問展示車（`HotspotRepositoryTest` 用 127.0.0.1:8888 的假車驗證） |
| 「手機熱點開著」判斷：Wi-Fi Direct 群組擁有者介面叫 `wlan1` 的機型會被當成熱點（藏掉「請開啟手機熱點」提示） | 只有 192.168.49.0/24（AOSP 保留給 P2P）位址的 `wlan*` 介面不算熱點；名稱像 AP（`ap0`、`swlan0`…）的照算（`HotspotNetTest`） |
| 640 × 320、字級 1.3：滑入式導覽車況面板多了兩顆膠囊，「中斷連線」被擠到要捲動 | 面板寬 = 視窗寬 × 0.42（236..300 dp），膠囊兩兩並排（`overlayPanelWidthOf`，`OverlayPanelTest`） |
| 記住的車「請開啟手機熱點「我的iPhone」」在窄卡片被拆成「「我 / 的iPhone」」 | 熱點名稱整段換行（`FlowRow`） |
| 「新車請掃描 OLED 上的 QR Code」「不用掃 QR」「（2.4 GHz、WPA2）」換行時「Code」/「QR」/「GHz」落單；尋找車子步驟條的提示在 780 寬只剩「上」一個字換行 | 不換行空白（所有 UI 字串的「2.4 GHz」）；提示縮短成「手機熱點沒開？開啟後車子會自動連上」 |
| 640 × 320、字級 1.3：改用熱點模式的表單 / 確認對話框要捲動才看得到「下一步」/「套用」 | 確認頁按鈕移到左欄摘要下方；表單拿掉和標題重複的「手機照常使用行動網路」，兩欄時展示模式提示只放確認頁 |

**驗證**
- `assembleDebug testDebugUnitTest assembleRelease`：0 錯誤、0 警告（debug / release / 測試的 Kotlin 全部 `--rerun` 重新編譯確認）；**298 個單元測試全過**
  （第三次整合 +116：連線層 66、畫面 41、整合 9 —— `ControlTimingTest` +4（結算相位、u16 溢位、時間窗、變速模擬）、`LinkMetricsTest` +2、`HotspotNetTest` +1、
  `HotspotRepositoryTest` +1、`OverlayPanelTest`（新）+1）。APK：debug 約 87 MB、release（R8 + 資源壓縮、只打包 ARM）約 13.6 MB。
- 截圖（scratchpad 的 `integ_iso` 副本，`LandscapeShotsTest` v3，輸出 `landscape_shots_v3/`；同步用 `integ_v3/sync_v3.sh`，它在**副本**的 `HotspotNet` 加一個
  `HotspotProbeOverride` 讓截圖能指定「手機熱點開 / 關」，正式程式碼沒有這個開關）：780 × 360 與 640 × 320（字級 1.3）——
  連線頁（熱點模式的車 + 請開啟手機熱點、尋找車子步驟條、建議開熱點的失敗卡、本機假車回 DISCOVER_ACK 的「熱點上的車」/「在熱點上」）→
  駕駛（直連 / 熱點膠囊、延遲展開、慢 + 雙 Wi-Fi）→ 滑入式導覽 → 監測延遲診斷（一般 / 慢 + 雙 Wi-Fi + 上行遺失）→ 設定「連線方式」（直連、熱點表單、錯誤、確認、進度、完成、熱點狀態）→ 記住的車子。
  「慢 + 雙 Wi-Fi」是在暫停展示車延遲回報後直接餵 repository 高延遲樣本（`端到端來回` 那格因此仍是展示車的 9 ms，實機不會這樣）。

**還需要實機確認**
1. 刷新 STM32 韌體後「控制 xx ms」是否降到 20–40 ms（直連）；ESP_PING 小而控制延遲大 = 慢在主控板（監測頁診斷會說「車上處理慢」）。
2. 熱點模式整套：iPhone（最大化相容性）/ Android（2.4 GHz、WPA2）熱點、ESP 1.1.0 加入熱點、App 找車（子網路廣播 + 上次 IP）、熱點關掉 → 8 s Failed → 自動重連再找；
   車子 12 s 找不到熱點改回直連 AP、每 30 s 再試。
3. 系統熱點設定頁（`TETHER_SETTINGS` 與各家候選）在目標手機上打得開；「手機熱點開著」判斷在 Pixel / Samsung / 小米上的實際介面名稱。
4. 靜止 ⇄ 操作切換時「品質 xx%」與上行遺失維持在 0–3 %（對齊算法依賴主控板 `ticks % 100 == 0` 結算 link_q、TELEM.tick = `(uint16_t)ticks`）。

### 11.12 跨端協定複查：熱點模式 + 控制延遲（2026-10-17）

對照 `docs/PROTOCOL.md`、`tools/b570_proto.py`、ESP-01（`ESP01/B570_Bridge`）、App（protocol / link / data）與 STM32（`WHEELTEC/rc_link.c`、`motion.c`、`control.c`）逐位元組、逐時序檢查新項目。

**位元組一致（已驗證）**：STA_SET / STA_GET / STA_INFO / DISCOVER / DISCOVER_ACK / NET / TELEM v1.0（40）與 v1.1（42）的測試向量 Python ⇄ App 測試 ⇄ ESP PC 測試三方逐位元組相同；
新增到 `vectors`：STA_INFO、STA_GET、STA_SET 關閉（保留帳密）、兩種 TELEM。SEQ：ESP 的 STA_INFO / DISCOVER_ACK 回覆帶請求 SEQ、NET 用自己的滾動序號（規格 §2 已補上）。

**修正**
| 問題（修正前） | 修正 |
|---|---|
| 控制延遲公式 `收到 − 送出 − ctrl_age × 10 ms` 每個樣本**少算 10 ms**：主控板記的是「收到時的 tick」，`Motion_Tick` 先 tick+1 才套用，套用那一週期送出的遙測 ctrl_age 已經是 1（用真的 `rc_link.c` / `motion.c` 在 PC 上跑過：中斷間收到、控制週期開頭輪詢 DMA 收到都是 1） | `ControlTiming.appliedTicksAgo(a) = max(a − 1, 0)`；規格 §4.1 寫明 ctrl_age 的時間點與公式，Python 加 `control_latency_ms()` 參考實作；展示車的 ctrl_age 改成 1..2（與韌體相同） |
| 熱點模式的車 + 手機熱點開著只找 4 s 就改直連：車子剛開機還在加入熱點（ESP 最多 12 s）時，WifiNetworkSpecifier 會去等一個不會出現的 AP，卡到 30 s 逾時才失敗 | 找 12 s（`ConnectPlanner.HOTSPOT_JOIN_DISCOVERY_MS` = `b570_netsm.h` JOIN_BUDGET_MS）；車子在熱點上時通常 0.1 s 內就找到，不會多等。沒有 AP 密碼的車熱點開著時也找 12 s |
| 內建設定檔「運動」TURN_ACCEL 10000 比主控板 v2 的新預設 12000 還慢 | 「運動」改成 ACCEL 3000、TURN_ACCEL 16000（`TunePresetsTest`：運動 > 原廠 > 穩定、都在韌體範圍內） |
| `PROTOCOL.md`：§3.1 寫固定 50 Hz、§4.1 標題 40 bytes、§1 遙測 47 bytes、SEQ 規則缺 STA_INFO / DISCOVER_ACK / NET、§3.6 找車時間只寫 4 s | 全部同步（CTRL 10–50 Hz 規則、42 bytes、49 bytes 幀、找車 4 / 12 / 1.5 / 60 s、連線中重新找車與 8 s 斷線） |
| `b570_proto.py monitor` 每圈只讀一個 datagram（50 Hz 遙測會越積越多） | 每圈讀完已到的 datagram，並顯示控制延遲（v1.1 韌體） |

**上行（手機 → 車）逐段檢查**：App 搖桿回呼直接寫 atomic（無 coroutine / debounce）→ 傳送執行緒每 20 ms 送最新值（第一個指令等 0–20 ms）、socket 傳送緩衝最小、不排隊 →
ESP 每圈先收 UDP、整幀直接寫進 UART 硬體 FIFO（PC 模擬平均 0.6 ms；SEQ_LOAD / PARAM_SET 大幀同時傳時最多約 23 ms）→ 230400 bps 0.5 ms →
STM32 IDLE 中斷 + 每 10 ms 控制週期開頭輪詢 DMA → 下一個 `Motion_Tick` 套用 → ACCEL 2000 mm/s²（0 → 500 mm/s 約 250 ms）/ TURN_ACCEL 12000 ‰/s 斜率限制 → 速度環（設定值經由積分項進入，平衡車本來就要先前傾才會加速）。
通訊部分 < 20 ms；如果「控制 xx ms」已經只有 20–40 ms、車子仍覺得慢，就是斜率限制 / 速度環的反應（調參數），不是通訊延遲。

**交給主控板（STM32）負責人**：見本次複查報告（舊存檔參數會蓋掉新的 ACCEL / TURN_ACCEL 預設值、DMA 半滿 / 全滿回呼可能重播舊資料、OLED 熱點名稱的 UTF-8、韌體版本號）。
