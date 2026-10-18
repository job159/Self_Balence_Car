/*
 * show.c
 *
 *  Created on: Jun 14, 2022
 *      Author: lf
 *
 *  B570 Pilot OLED 介面（128x64）：
 *   頁 0 儀表板：狀態、傾角條、速度、PWM、電量、事件提示
 *   頁 1 配對  ：Wi-Fi QR Code（手機 App 掃描即可連線）+ SSID / 密碼
 *   頁 2 資訊  ：PID 參數、零點、演算法、控制迴圈耗時
 *  手機 App 連上時自動切到儀表板，斷線時自動切回配對頁；雙擊按鍵可手動切換。
 */
#include "show.h"

extern u8 OLED_GRAM[128][8];

enum { PAGE_DASH = 0, PAGE_PAIR, PAGE_INFO, PAGE_COUNT };

static volatile uint8_t page = PAGE_DASH;
static volatile uint8_t toast_id, toast_frames;
static uint8_t  want_pair_prev = 0xFF;
static uint16_t frame_no;

static qr_t     qr;
static uint8_t  qr_ok, qr_cred_ver;
static char     ssid[33], pw[64];

/* ------------------------------------------------------------------ 繪圖小工具 */
static void text(u8 x, u8 y, const char *s, u8 inverse)
{
	while (*s && x <= 122) {
		u8 ch = (u8)*s++;
		if (ch < 0x20 || ch > 0x7E) ch = '?';        /* 字型只有 ASCII（中文熱點名稱等顯示為 ?） */
		OLED_ShowChar(x, y, ch, 12, inverse ? 0 : 1);
		x += 6;
	}
}

static void fill(int x0, int y0, int x1, int y1, u8 on)
{
	for (int x = x0; x <= x1; x++)
		for (int y = y0; y <= y1; y++)
			OLED_DrawPoint((u8)x, (u8)y, on);
}

static void frame_rect(int x0, int y0, int x1, int y1)
{
	for (int x = x0; x <= x1; x++) { OLED_DrawPoint((u8)x, (u8)y0, 1); OLED_DrawPoint((u8)x, (u8)y1, 1); }
	for (int y = y0; y <= y1; y++) { OLED_DrawPoint((u8)x0, (u8)y, 1); OLED_DrawPoint((u8)x1, (u8)y, 1); }
}

/* 整數 / 定點數格式化：靠右對齊，可選 '+' 號 */
static char *num(char *out, int32_t v, u8 width, u8 decimals, u8 plus)
{
	char tmp[14];
	u8 n = 0, neg = v < 0;
	uint32_t u = neg ? (uint32_t)(-v) : (uint32_t)v;
	do {
		if (decimals && n == decimals) tmp[n++] = '.';
		tmp[n++] = (char)('0' + u % 10);
		u /= 10;
	} while (u || n <= decimals);
	if (neg) tmp[n++] = '-';
	else if (plus) tmp[n++] = '+';
	u8 i = 0;
	while (n + i < width) out[i++] = ' ';
	while (n) out[i++] = tmp[--n];
	out[i] = 0;
	return out;
}

static void battery_icon(int x, int y)
{
	int pct = (Voltage - BAT_EMPTY_CV) * 100 / (BAT_FULL_CV - BAT_EMPTY_CV);
	if (pct < 0) pct = 0;
	if (pct > 100) pct = 100;
	frame_rect(x, y, x + 18, y + 9);
	fill(x + 19, y + 3, x + 20, y + 6, 1);
	if (Voltage <= 0) return;
	int w = pct * 15 / 100;
	if (pct < 15 && (frame_no & 8)) return;           /* 低電量閃爍 */
	if (w > 0) fill(x + 2, y + 2, x + 1 + w, y + 7, 1);
}

/* ------------------------------------------------------------------ 頁面：儀表板 */
static const char *stop_text(uint8_t r)
{
	switch (r) {
	case STOP_BOOT:   return "Click to balance";
	case STOP_KEY:    return "Stopped (key)";
	case STOP_APP:    return "Stopped (app)";
	case STOP_PICKUP: return "Picked up!";
	case STOP_FALL:   return "Fell - set upright";
	case STOP_LOWBAT: return "LOW BATTERY";
	case STOP_ESTOP:  return "EMERGENCY STOP";
	case STOP_IMU:    return "IMU FAULT-check";
	default:          return "";
	}
}

static void draw_status_line(void)
{
	char b[24], n1[8], n2[8];
	const char *msg = NULL;

	if (toast_frames) {
		switch (toast_id) {
		case TOAST_CANT_START: msg = "Can't start:bat/tilt"; break;
		case TOAST_ZERO_OK:    msg = "Zero calibrated OK"; break;
		case TOAST_ZERO_FAIL:  msg = "Zero: hold upright"; break;
		default: break;
		}
		if (msg) { fill(0, 52, 127, 63, 1); text(1, 52, msg, 1); return; }
	}
	if (Flag_Stop) {
		text(0, 52, stop_text(Motion_StopReason()), 0);
	} else if (Motion_SeqPlaying()) {
		strcpy(b, "SEQ ");
		strcat(b, num(n1, Motion_SeqStep() + 1, 1, 0, 0));
		strcat(b, "/");
		strcat(b, num(n2, Motion_SeqTotal(), 1, 0, 0));
		strcat(b, " loop ");
		strcat(b, num(n1, Motion_SeqLoop() + 1, 1, 0, 0));
		text(0, 52, b, 0);
	} else if (Motion_AppLinked()) {
		strcpy(b, "App drive  Q");
		strcat(b, num(n1, Motion_LinkQuality(), 1, 0, 0));
		text(0, 52, b, 0);
	} else {
		text(0, 52, Motion_Failsafe() ? "Link lost-holding" : "Balancing", 0);
	}
}

static void page_dash(void)
{
	char n[10];
	uint8_t es = Motion_EspState();

	if (Flag_Stop) text(0, 0, "STOP", 0);
	else text(0, 0, "RUN ", 1);
	text(30, 0, Motion_AppLinked() ? (RC_Link_NetMode() == 2 ? "HOT" : "APP") : (es == 1 ? "WiFi" : (es == 0xFF ? "ESP?" : "----")), 0);
	text(60, 0, num(n, Voltage / 10, 4, 1, 0), 0);
	text(84, 0, "V", 0);
	battery_icon(105, 1);

	/* 傾角：數字 + 置中刻度條（±20°） */
	text(0, 13, "A", 0);
	text(6, 13, num(n, (int32_t)(Angle_Balance * 10.0f), 5, 1, 1), 0);
	frame_rect(44, 15, 127, 23);
	OLED_DrawPoint(85, 14, 1);
	OLED_DrawPoint(85, 24, 1);
	{
		int off = (int)((Angle_Balance - Middle_angle) * 2.0f);
		if (off > 40) off = 40;
		if (off < -40) off = -40;
		fill(85 + off - 1, 17, 85 + off + 1, 21, 1);
	}

	/* 速度：目標 / 實際 */
	text(0, 26, "V", 0);
	text(6, 26, num(n, (int32_t)Motion_TargetSpeed(), 5, 0, 1), 0);
	text(36, 26, "/", 0);
	text(42, 26, num(n, (int32_t)((Velocity_Left + Velocity_Right) * 0.5f), 5, 0, 1), 0);
	text(78, 26, "mm/s", 0);

	/* PWM */
	text(0, 39, "L", 0);
	text(6, 39, num(n, Motor_Left, 5, 0, 1), 0);
	text(42, 39, "R", 0);
	text(48, 39, num(n, Motor_Right, 5, 0, 1), 0);
#if USE_ULTRASONIC
	text(84, 39, num(n, (int32_t)Distance, 4, 0, 0), 0);
	text(110, 39, "mm", 0);
#else
	text(86, 39, "T", 0);
	text(92, 39, num(n, (int32_t)Motion_TargetTurn(), 5, 0, 1), 0);
#endif
	draw_status_line();
}

/* ------------------------------------------------------------------ 頁面：配對 QR */
static void build_qr(void)
{
	char payload[96];
	char *p = payload;
	const char *src;

	RC_Link_GetCred(ssid, sizeof(ssid), pw, sizeof(pw));
	strcpy(p, "WIFI:T:WPA;S:");
	p += strlen(p);
	for (src = ssid; *src && p < payload + 60; src++) {
		if (strchr("\\;,:\"", *src)) *p++ = '\\';
		*p++ = *src;
	}
	strcpy(p, ";P:");
	p += 3;
	for (src = pw; *src && p < payload + 90; src++) {
		if (strchr("\\;,:\"", *src)) *p++ = '\\';
		*p++ = *src;
	}
	strcpy(p, ";;");
	qr_ok = (uint8_t)qr_encode(payload, &qr);
}

/* 長字串在 10 字寬的欄位中來回捲動 */
static void marquee(u8 x, u8 y, const char *s)
{
	char b[11];
	int len = (int)strlen(s);
	int off = 0;
	if (len > 10) {
		int span = len - 10, t = (frame_no / 6) % (2 * span + 8);
		off = t < 4 ? 0 : (t < span + 4 ? t - 4 : (t < span + 8 ? span : 2 * span + 8 - t));
		if (off > span) off = span;
	}
	strncpy(b, s + off, 10);
	b[10] = 0;
	text(x, y, b, 0);
}

/* 熱點模式：車子連在手機熱點上，App 會自動找到，不需要 QR */
static void page_hotspot(uint8_t mode)
{
	char hs[33], b[20], n[6];
	uint8_t ip[4];
	static const char spin[4] = { '|', '/', '-', '\\' };

	RC_Link_GetNet(ip, hs, sizeof(hs));
	text(0, 0, "HOTSPOT MODE", 1);
	if (mode == 2) {
		text(0, 13, "Phone hotspot:", 0);
		marquee(0, 26, hs);
		b[0] = 0;
		for (uint8_t i = 0; i < 4; i++) {
			strcat(b, num(n, ip[i], 1, 0, 0));
			if (i < 3) strcat(b, ".");
		}
		text(0, 39, "IP", 0);
		text(18, 39, b, 0);
		text(0, 52, Motion_AppLinked() ? "App OK" : "Open app", Motion_AppLinked() ? 0 : 1);
	} else {
		char sp[2] = { spin[(frame_no / 2) & 3], 0 };
		text(0, 13, "Joining hotspot", 0);
		text(96, 13, sp, 0);
		marquee(0, 26, hs);
		text(0, 39, "2.4GHz + WPA2", 0);
		text(0, 52, "Turn on hotspot", 0);
	}
	battery_icon(105, 1);
}

static void page_pair(void)
{
	uint8_t es = Motion_EspState();
	uint8_t nm = RC_Link_NetMode();

	if (nm == 1 || nm == 2) {
		page_hotspot(nm);
		return;
	}

	if (!RC_Link_CredVersion()) {
		static const char spin[4] = { '|', '/', '-', '\\' };
		char s[2] = { spin[(frame_no / 2) & 3], 0 };
		text(0, 0, "PAIRING", 1);
		text(0, 16, "Waiting ESP-01 ", 0);
		text(90, 16, s, 0);
		text(0, 32, "ESP TX->PA3", 0);
		text(0, 44, "ESP RX<-PA2 230400", 0);
		return;
	}
	if (qr_ok) {
		int s = 2, off = (64 - qr.size * s) / 2;
		fill(0, 0, 63, 63, 1);                        /* 亮底 = QR 淺色與留白 */
		for (int y = 0; y < qr.size; y++)
			for (int x = 0; x < qr.size; x++)
				if (qr_module(&qr, x, y))
					fill(off + x * s, off + y * s, off + x * s + s - 1, off + y * s + s - 1, 0);
	} else {
		text(0, 26, "QR too long", 0);
	}
	text(68, 0, nm == 3 ? "NO HOTSP" : "SCAN ME", 1);
	marquee(68, 14, ssid);
	marquee(68, 27, pw);
	text(68, 40, es == 2 ? "App OK" : (es == 1 ? "Phone.." : "Ready"), 0);
	battery_icon(68, 53);
	text(96, 52, Flag_Stop ? "STOP" : "RUN", 0);
}

/* ------------------------------------------------------------------ 頁面：參數資訊 */
static void page_info(void)
{
	char b[24], n[10];
	static const char *const algo[4] = { "?", "DMP", "KF", "CF" };

	text(0, 0, "PARAMS", 1);
	strcpy(b, "fw ");
	strcat(b, num(n, FW_VER_MAJOR, 1, 0, 0)); strcat(b, ".");
	strcat(b, num(n, FW_VER_MINOR, 1, 0, 0)); strcat(b, ".");
	strcat(b, num(n, FW_VER_PATCH, 1, 0, 0));
	text(56, 0, b, 0);
	if (Params_Dirty) text(110, 0, "*", 1);

	strcpy(b, "Bal P"); strcat(b, num(n, (int32_t)Balance_Kp, 1, 0, 0));
	strcat(b, " D"); strcat(b, num(n, (int32_t)Balance_Kd, 1, 0, 0));
	text(0, 13, b, 0);
	strcpy(b, "Vel P"); strcat(b, num(n, (int32_t)Velocity_Kp, 1, 0, 0));
	strcat(b, " I"); strcat(b, num(n, (int32_t)Velocity_Ki, 1, 0, 0));
	text(0, 26, b, 0);
	strcpy(b, "Trn P"); strcat(b, num(n, (int32_t)Turn_Kp, 1, 0, 0));
	strcat(b, " D"); strcat(b, num(n, (int32_t)Turn_Kd, 1, 0, 0));
	text(0, 39, b, 0);
	strcpy(b, "Z"); strcat(b, num(n, (int32_t)(Middle_angle * 100.0f), 1, 2, 1));
	strcat(b, " "); strcat(b, algo[Way_Angle & 3]);
	strcat(b, " "); strcat(b, num(n, Ctrl_LoopUs, 1, 0, 0)); strcat(b, "us");
	text(0, 52, b, 0);
}

/* ------------------------------------------------------------------ public */
void UI_NextPage(void)
{
	page = (uint8_t)((page + 1) % PAGE_COUNT);
}

void UI_Toast(uint8_t id)
{
	toast_id = id;
	toast_frames = 30;                               /* 約 1.5 s */
}

void UI_Boot(void)
{
	memset(OLED_GRAM, 0, sizeof(OLED_GRAM));
	OLED_ShowString(24, 8, "B570 PILOT");
	frame_rect(16, 4, 111, 24);
	text(34, 32, "v", 0);
	{
		char n[6];
		text(40, 32, num(n, FW_VER_MAJOR, 1, 0, 0), 0);
		text(46, 32, ".", 0);
		text(52, 32, num(n, FW_VER_MINOR, 1, 0, 0), 0);
		text(58, 32, ".", 0);
		text(64, 32, num(n, FW_VER_PATCH, 1, 0, 0), 0);
	}
	text(4, 50, "IMU init, hold still", 0);
	OLED_Refresh_Gram();
}

void UI_Task(void)
{
	uint8_t cv = RC_Link_CredVersion();
	uint8_t want_pair;

	frame_no++;
	if (toast_frames) toast_frames--;

	if (cv && cv != qr_cred_ver) {
		qr_cred_ver = cv;
		build_qr();
	}

	/* 連線狀態改變時自動切頁：App 連上 → 儀表板；尚未連線 → 配對頁 */
	want_pair = (cv != 0) && Motion_EspState() != 2;
	if (want_pair != want_pair_prev) {
		if (want_pair_prev != 0xFF || want_pair) page = want_pair ? PAGE_PAIR : PAGE_DASH;
		want_pair_prev = want_pair;
	}

	memset(OLED_GRAM, 0, sizeof(OLED_GRAM));
	switch (page) {
	case PAGE_PAIR: page_pair(); break;
	case PAGE_INFO: page_info(); break;
	default:        page_dash(); break;
	}
	OLED_Refresh_Gram();
}

#if APP_LEGACY_BT
/**************************************************************************
函式功能：向原廠 MiniBalance APP 傳送資料（USART3，阻塞式 printf）
**************************************************************************/
void APP_Show(void)
{
	static u8 flag;
	int Encoder_Left_Show, Encoder_Right_Show, Voltage_Show;
	Voltage_Show = (Voltage - 1000) * 2 / 3;
	if (Voltage_Show < 0) Voltage_Show = 0;
	if (Voltage_Show > 100) Voltage_Show = 100;
	Encoder_Right_Show = Velocity_Right * 1.1;
	if (Encoder_Right_Show < 0) Encoder_Right_Show = -Encoder_Right_Show;
	Encoder_Left_Show = Velocity_Left * 1.1;
	if (Encoder_Left_Show < 0) Encoder_Left_Show = -Encoder_Left_Show;
	flag = !flag;
	if (PID_Send == 1) {
		printf("{C%d:%d:%d:%d:%d:%d:%d:%d:%d}$", (int)Balance_Kp, (int)Balance_Kd, (int)Velocity_Kp,
		       (int)Velocity_Ki, (int)Turn_Kp, (int)Turn_Kd, 0, 0, 0);
		PID_Send = 0;
	} else if (flag == 0) {
		printf("{A%d:%d:%d:%d}$", (int)Encoder_Left_Show, (int)Encoder_Right_Show, (int)Voltage_Show, (int)Angle_Balance);
	} else {
		printf("{B%d:%d:%d}$", (int)Pitch, (int)Roll, (int)Yaw);
	}
}
#endif
