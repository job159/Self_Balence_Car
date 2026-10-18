/*
 * rc_link.c
 *
 * 規格：docs/PROTOCOL.md。
 * RX：HAL ReceiveToIdle + DMA 環形模式，IDLE/半滿/全滿時在中斷內解析。
 * TX：512 bytes 環形佇列，DMA 背景送出；任何優先權的中斷都可安全呼叫 tx_frame()。
 * 回覆幀（ACK / PARAM_VALUES / PONG / INFO）的 SEQ = 請求幀的 SEQ；App 重送時 SEQ 不變，
 * 因此 ZERO_CAL / PARAM_SAVE / SEQ_PLAY 的重送可以辨識出來，只回覆、不重做（exactly-once）。
 */
#include "main.h"
#include "usart.h"

extern UART_HandleTypeDef huart2;

#define RX_DMA_SIZE  256
#define TX_RING_SIZE 512                 /* 必須是 2 的次方 */
#define TX_MASK      (TX_RING_SIZE - 1)

static uint8_t           rx_dma[RX_DMA_SIZE];
static uint16_t          rx_pos;
static proto_parser_t    parser;

static uint8_t           tx_ring[TX_RING_SIZE];
static volatile uint16_t tx_head, tx_tail, tx_inflight;
static uint8_t           tx_seq;

static volatile uint8_t  save_pending;
static volatile uint8_t  save_seq;          /* PARAM_SAVE 請求的 SEQ（延後回覆用） */
static uint8_t           rx_seq;            /* 目前處理中請求的 SEQ（回覆沿用） */
static uint8_t           dup_type, dup_seq, dup_status, dup_valid;
static uint32_t          dup_tick;
static uint8_t           start_seq, start_status, start_valid;   /* MOTOR 啟動的重送去重（獨立欄位，不被其他指令覆蓋） */
static uint32_t          start_tick;
static uint8_t           telem_div;

static char              cred_ssid[33], cred_pw[64];
static volatile uint8_t  cred_ver;

static char              net_ssid[33];
static uint8_t           net_ip[4];
static volatile uint8_t  net_mode = 0xFF, net_ver;

static volatile uint8_t  ctrl_seq;          /* 最後收到的 CTRL 的 SEQ（遙測回報，App 據此量測控制延遲） */
static volatile uint32_t ctrl_tick;
static volatile uint8_t  ctrl_seen;

/* ------------------------------------------------------------------ TX */
static void tx_kick_locked(void)
{
	if (tx_inflight || tx_head == tx_tail || huart2.gState != HAL_UART_STATE_READY) return;
	uint16_t len = tx_head > tx_tail ? (uint16_t)(tx_head - tx_tail) : (uint16_t)(TX_RING_SIZE - tx_tail);
	tx_inflight = len;
	if (HAL_UART_Transmit_DMA(&huart2, &tx_ring[tx_tail], len) != HAL_OK) tx_inflight = 0;
}

static uint8_t tx_frame_seq(uint8_t type, uint8_t seq, const void *payload, uint8_t len)
{
	uint8_t frame[PROTO_MAX_FRAME];
	uint8_t ok = 0;
	uint32_t pm;
	uint16_t n = proto_build(frame, type, seq, payload, len);   /* CRC 計算不關中斷 */

	pm = __get_PRIMASK();
	__disable_irq();
	uint16_t space = (uint16_t)((tx_tail - tx_head - 1) & TX_MASK);
	if (n && n <= space) {
		for (uint16_t i = 0; i < n; i++) tx_ring[(tx_head + i) & TX_MASK] = frame[i];
		tx_head = (uint16_t)((tx_head + n) & TX_MASK);
		tx_kick_locked();
		ok = 1;
	}
	__set_PRIMASK(pm);
	return ok;
}

/* 主動發送的幀（遙測、事件…）使用自己的滾動序號 */
static uint8_t tx_frame(uint8_t type, const void *payload, uint8_t len)
{
	uint32_t pm = __get_PRIMASK();
	__disable_irq();
	uint8_t seq = tx_seq++;
	__set_PRIMASK(pm);
	return tx_frame_seq(type, seq, payload, len);
}

void HAL_UART_TxCpltCallback(UART_HandleTypeDef *huart)
{
	if (huart->Instance != USART2) return;
	uint32_t pm = __get_PRIMASK();
	__disable_irq();
	tx_tail = (uint16_t)((tx_tail + tx_inflight) & TX_MASK);
	tx_inflight = 0;
	tx_kick_locked();
	__set_PRIMASK(pm);
}

static void send_ack_seq(uint8_t ref, uint8_t status, uint8_t seq)
{
	uint8_t p[2] = { ref, status };
	tx_frame_seq(T_ACK, seq, p, 2);
}

/* 以下回覆函式只在 on_frame（UART 中斷）內使用，SEQ 沿用請求 */
static void send_ack(uint8_t ref, uint8_t status)
{
	send_ack_seq(ref, status, rx_seq);
}

/* id = 0xFF 送全部參數 */
static void send_params(const uint8_t *ids, uint8_t count)
{
	uint8_t p[1 + PARAM_COUNT * 5];
	uint8_t n = 0;
	for (uint8_t i = 0; i < count && n < PARAM_COUNT; i++) {
		uint8_t id = ids ? ids[i] : i;
		if (id >= PARAM_COUNT) continue;
		float v = Params_Get(id);
		p[1 + n * 5] = id;
		memcpy(&p[2 + n * 5], &v, 4);
		n++;
	}
	p[0] = n;
	tx_frame_seq(T_PARAM_VALUES, rx_seq, p, (uint8_t)(1 + n * 5));
}

static void send_info(void)
{
	uint8_t p[20];
	memset(p, 0, sizeof(p));
	p[0] = PROTO_VERSION;
	p[1] = FW_VER_MAJOR;
	p[2] = FW_VER_MINOR;
	p[3] = FW_VER_PATCH;
	p[4] = PARAM_COUNT;
	p[5] = SEQ_MAX_STEPS;
	p[6] = Way_Angle;
	memcpy(&p[8], MODEL_NAME, sizeof(MODEL_NAME) - 1);
	tx_frame_seq(T_INFO, rx_seq, p, sizeof(p));
}

static int16_t sat16(float v)
{
	if (v > 32767.0f) return 32767;
	if (v < -32768.0f) return -32768;
	return (int16_t)v;
}

static void send_telem(void)
{
	uint8_t p[42];
	float tilt = Angle_Balance - Middle_angle;
	uint8_t flags = 0;

	if (!Flag_Stop)                                         flags |= 0x01;
	if (Motion_AppLinked())                                 flags |= 0x02;
	if (Motion_SeqPlaying())                                flags |= 0x04;
	if (Voltage > 0 && Voltage < (int)(LowBat_V * 100.0f))  flags |= 0x08;
	if (tilt < Fall_Angle && tilt > -Fall_Angle)            flags |= 0x10;
	if (Motion_Failsafe())                                  flags |= 0x20;
	if (Params_Dirty)                                       flags |= 0x40;
	if (cred_ver)                                           flags |= 0x80;

	wr_u16(&p[0], (uint16_t)Motion_Ticks());
	wr_i16(&p[2], sat16(Angle_Balance * 100.0f));
	wr_i16(&p[4], sat16(Roll * 100.0f));
	wr_i16(&p[6], sat16(Gyro_Balance));
	wr_i16(&p[8], sat16(Gyro_Turn));
	wr_i16(&p[10], sat16(Velocity_Left));
	wr_i16(&p[12], sat16(Velocity_Right));
	wr_i16(&p[14], (int16_t)Motor_Left);
	wr_i16(&p[16], (int16_t)Motor_Right);
	wr_i16(&p[18], sat16((float)Ctrl_BalancePwm));
	wr_i16(&p[20], sat16((float)Ctrl_VelocityPwm));
	wr_i16(&p[22], sat16((float)Ctrl_TurnPwm));
	wr_i16(&p[24], sat16(Motion_TargetSpeed()));
	wr_i16(&p[26], sat16(Motion_TargetTurn()));
	wr_u16(&p[28], (uint16_t)(Voltage > 0 ? Voltage : 0));
	p[30] = flags;
	p[31] = Motion_StopReason();
	p[32] = Motion_SeqStep();
	p[33] = Motion_SeqLoop();
	p[34] = Motion_Source();
	p[35] = Motion_LinkQuality();
	wr_i16(&p[36], sat16(Ctrl_Heading * 100.0f));
	wr_u16(&p[38], Ctrl_LoopUs);
	p[40] = ctrl_seq;
	{
		uint32_t age = ctrl_seen ? Motion_Ticks() - ctrl_tick : 255u;
		p[41] = (uint8_t)(age > 255u ? 255u : age);
	}
	tx_frame(T_TELEM, p, sizeof(p));
}

/* ------------------------------------------------------------------ RX dispatch（UART 中斷內） */
static void on_cred(const uint8_t *p, uint8_t len)
{
	if (len < 3) return;
	uint8_t sl = p[0];
	if (sl == 0 || sl > 32 || len < 1 + sl + 1) return;
	uint8_t pl = p[1 + sl];
	if (pl > 63 || len < 2 + sl + pl) return;
	memcpy(cred_ssid, &p[1], sl);
	cred_ssid[sl] = 0;
	memcpy(cred_pw, &p[2 + sl], pl);
	cred_pw[pl] = 0;
	cred_ver = (uint8_t)(cred_ver == 0xFF ? 1 : cred_ver + 1);
}

/* NET：u8 mode, u8 ip[4], u8 ssid_len, ssid[] */
static void on_net(const uint8_t *p, uint8_t len)
{
	if (len < 6) return;
	uint8_t sl = p[5];
	if (sl > 32 || len < 6 + sl) return;
	memcpy(net_ip, &p[1], 4);
	memcpy(net_ssid, &p[6], sl);
	net_ssid[sl] = 0;
	net_mode = p[0];
	net_ver++;
}

/* 有副作用、不可重做的指令：同 TYPE + 同 SEQ 在 1.5 s 內再來 = App 重送（ACK 遺失），只重送回覆 */
static int is_retry(uint8_t type, uint8_t seq)
{
	if (!dup_valid || dup_type != type || dup_seq != seq || Motion_Ticks() - dup_tick > 150) return 0;
	if (type == T_PARAM_SAVE && save_pending) return 1;          /* 還在寫 Flash：存完會回覆 */
	send_ack(type, dup_status);
	if (type == T_ZERO_CAL && dup_status == ACK_OK) { uint8_t id = P_ZERO_ANGLE; send_params(&id, 1); }
	return 1;
}

static void remember(uint8_t type, uint8_t seq, uint8_t status)
{
	dup_type = type;
	dup_seq = seq;
	dup_status = status;
	dup_tick = Motion_Ticks();
	dup_valid = 1;
}

static void on_frame(uint8_t type, uint8_t seq, const uint8_t *p, uint8_t len)
{
	rx_seq = seq;
	if (type < 0x40) Motion_OnAppFrame();
	if ((type == T_ZERO_CAL || type == T_PARAM_SAVE || type == T_SEQ_PLAY) && is_retry(type, seq)) return;

	switch (type) {
	case T_CTRL:
		if (len >= 4) Motion_SetAppControl(rd_i16(&p[0]), rd_i16(&p[2]));
		ctrl_seq = seq;
		ctrl_tick = Motion_Ticks();
		ctrl_seen = 1;
		break;
	case T_PING:
		if (len >= 4) tx_frame_seq(T_PONG, seq, p, 4);
		break;
	case T_MOTOR:
		if (len >= 1 && p[0] == 1) {
			/* 啟動的重送（同 SEQ、1.5 s 內）只回覆不重做：避免緊急停止之後被遲到的重送重新啟動 */
			if (start_valid && start_seq == seq && Motion_Ticks() - start_tick <= 150) {
				send_ack(type, start_status);
				break;
			}
			start_status = Motion_RequestMotor(1);
			start_seq = seq;
			start_tick = Motion_Ticks();
			start_valid = 1;
			send_ack(type, start_status);
		} else {
			send_ack(type, len >= 1 ? Motion_RequestMotor(p[0]) : ACK_BAD_ARG);
		}
		break;
	case T_ZERO_CAL: {
		uint8_t st = Motion_ZeroCal();
		remember(type, seq, st);
		send_ack(type, st);
		if (st == ACK_OK) { uint8_t id = P_ZERO_ANGLE; send_params(&id, 1); }
		break;
	}
	case T_PARAM_GET:
		if (len >= 1 && p[0] != 0xFF) send_params(&p[0], 1);
		else send_params(NULL, PARAM_COUNT);
		break;
	case T_PARAM_SET: {
		uint8_t ids[20], n = 0;
		for (uint8_t i = 0; i + 5 <= len && n < 20; i += 5) {
			float v;
			if (p[i] >= PARAM_COUNT) continue;
			memcpy(&v, &p[i + 1], 4);
			Params_Set(p[i], v);
			ids[n++] = p[i];
		}
		if (n) send_params(ids, n);
		else send_ack(type, ACK_BAD_ARG);
		break;
	}
	case T_PARAM_SAVE:
		if (!Flag_Stop) { remember(type, seq, ACK_BUSY); send_ack(type, ACK_BUSY); }
		else { remember(type, seq, ACK_OK); save_seq = seq; RC_Link_RequestSave(1); }   /* 主迴圈寫 Flash 後回 ACK */
		break;
	case T_PARAM_DEFAULTS:
		Params_Defaults();
		send_params(NULL, PARAM_COUNT);
		break;
	case T_SEQ_LOAD:
		send_ack(type, Motion_SeqLoad(p, len));
		break;
	case T_SEQ_PLAY:
	{
		uint8_t st = Motion_SeqPlay(len >= 1 ? p[0] : 1);
		remember(type, seq, st);
		send_ack(type, st);
	}
		break;
	case T_SEQ_STOP:
		Motion_SeqStop();
		send_ack(type, ACK_OK);
		break;
	case T_INFO_GET:
		send_info();
		break;
	case T_LINK:
		if (len >= 1) Motion_SetEspLink(p[0]);
		break;
	case T_CRED:
		on_cred(p, len);
		break;
	case T_NET:
		on_net(p, len);
		break;
	default:
		if (type < 0x40) send_ack(type, ACK_UNKNOWN_TYPE);
		break;
	}
}

static void rx_start(void)
{
	rx_pos = 0;
	if (HAL_UARTEx_ReceiveToIdle_DMA(&huart2, rx_dma, RX_DMA_SIZE) != HAL_OK) return;
}

/* 處理 DMA 環形緩衝中 rx_pos..pos 的新資料（呼叫端保證不會同時進入） */
static void rx_process(uint16_t pos)
{
	if (pos > RX_DMA_SIZE) return;
	if (pos != rx_pos) {
		if (pos > rx_pos) {
			proto_feed(&parser, &rx_dma[rx_pos], (uint16_t)(pos - rx_pos), on_frame);
		} else {
			proto_feed(&parser, &rx_dma[rx_pos], (uint16_t)(RX_DMA_SIZE - rx_pos), on_frame);
			if (pos) proto_feed(&parser, rx_dma, pos, on_frame);
		}
		rx_pos = pos;
	}
	if (rx_pos == RX_DMA_SIZE) rx_pos = 0;
}

/* DMA 環形模式：pos = 目前寫入位置（IDLE、半滿、全滿都會呼叫） */
void HAL_UARTEx_RxEventCallback(UART_HandleTypeDef *huart, uint16_t pos)
{
	(void)pos;
	if (huart->Instance != USART2) return;
	/* 不用 HAL 給的 pos（半滿/全滿時固定 128/256）：若控制中斷的輪詢剛處理過更新的位置，
	   遲到的回呼會被誤判成繞回而重處理約 250 bytes 舊資料。一律以 DMA 計數器為準。 */
	rx_process((uint16_t)(RX_DMA_SIZE - __HAL_DMA_GET_COUNTER(huart->hdmarx)));
}

/*
 * 控制中斷（優先權較低）每 10 ms 主動讀 DMA 計數器處理新資料：
 * 就算 IDLE 中斷沒有觸發（只剩半滿/全滿才回呼），控制指令最多也只延遲 10 ms。
 * 關中斷執行，與 UART/DMA 中斷內的 rx_process 不會交錯。
 */
void RC_Link_PollRx(void)
{
	if (huart2.hdmarx == NULL || huart2.RxState != HAL_UART_STATE_BUSY_RX) return;
	uint32_t pm = __get_PRIMASK();
	__disable_irq();
	uint16_t pos = (uint16_t)(RX_DMA_SIZE - __HAL_DMA_GET_COUNTER(huart2.hdmarx));
	rx_process(pos);
	__set_PRIMASK(pm);
}

/* 雜訊、溢位等錯誤會讓 HAL 中止 DMA 接收：自動重啟 */
void HAL_UART_ErrorCallback(UART_HandleTypeDef *huart)
{
	if (huart->Instance == USART2) {
		if (huart->RxState == HAL_UART_STATE_READY) rx_start();
	}
#if APP_LEGACY_BT
	else if (huart->Instance == USART3) {
		if (huart->RxState == HAL_UART_STATE_READY)
			HAL_UART_Receive_IT(&huart3, Usart3_Receive_buf, sizeof(Usart3_Receive_buf));
	}
#endif
}

/* ------------------------------------------------------------------ public */
void RC_Link_Init(void)
{
	rx_start();
	tx_frame(T_CRED_REQ, NULL, 0);
}

void RC_Link_Tick(void)
{
	uint8_t code;
	int16_t arg;
	uint32_t t = Motion_Ticks();

	for (uint8_t i = 0; i < 2 && Motion_PopEvent(&code, &arg); i++) {
		uint8_t p[3] = { code, (uint8_t)arg, (uint8_t)((uint16_t)arg >> 8) };
		tx_frame(T_EVENT, p, 3);
	}

	uint8_t div = (uint8_t)(100.0f / Telem_Hz + 0.5f);
	if (div == 0) div = 1;
	if (++telem_div >= div) {
		telem_div = 0;
		send_telem();
	}

	if (!cred_ver && t % 100 == 37) tx_frame(T_CRED_REQ, NULL, 0);
}

void RC_Link_RequestSave(uint8_t reply_ack)
{
	save_pending |= reply_ack ? 2 : 1;
}

void RC_Link_Task(void)
{
	uint32_t pm0 = __get_PRIMASK();
	__disable_irq();
	uint8_t sp = save_pending;
	save_pending = 0;
	__set_PRIMASK(pm0);
	if (sp) {
		uint8_t want_ack = sp & 2;
		uint8_t st = Params_Save();
		Ctrl_ImuResync = 1;
		if (want_ack) {
			__disable_irq();                      /* dup_* 也會被 UART 中斷改寫 */
			if (dup_valid && dup_type == T_PARAM_SAVE && dup_seq == save_seq) dup_status = st;
			__set_PRIMASK(pm0);
			send_ack_seq(T_PARAM_SAVE, st, save_seq);
		}
		Motion_PushEvent(EV_PARAMS_SAVED, st);
	}
#if APP_LEGACY_BT
	if (huart3.RxState == HAL_UART_STATE_READY) {           /* 原廠藍牙接收重啟失敗時補救 */
		uint32_t pm3 = __get_PRIMASK();
		__disable_irq();
		if (huart3.RxState == HAL_UART_STATE_READY)
			HAL_UART_Receive_IT(&huart3, Usart3_Receive_buf, sizeof(Usart3_Receive_buf));
		__set_PRIMASK(pm3);
	}
#endif
	/* 接收看門狗：任何原因停止接收就重新啟動 */
	if (huart2.RxState == HAL_UART_STATE_READY) {
		uint32_t pm = __get_PRIMASK();
		__disable_irq();
		if (huart2.RxState == HAL_UART_STATE_READY) rx_start();
		__set_PRIMASK(pm);
	}
}

uint8_t RC_Link_CredVersion(void)
{
	return cred_ver;
}

void RC_Link_GetCred(char *ssid, uint8_t ssid_sz, char *pw, uint8_t pw_sz)
{
	uint8_t v;
	do {                                         /* 讀取中被中斷更新就重讀 */
		v = cred_ver;
		strncpy(ssid, cred_ssid, ssid_sz - 1);
		ssid[ssid_sz - 1] = 0;
		strncpy(pw, cred_pw, pw_sz - 1);
		pw[pw_sz - 1] = 0;
	} while (v != cred_ver);
}

uint32_t RC_Link_CrcErrors(void)
{
	return parser.crc_errors;
}

uint8_t RC_Link_NetMode(void)
{
	return net_mode;
}

void RC_Link_GetNet(uint8_t ip[4], char *ssid, uint8_t ssid_sz)
{
	uint8_t v;
	do {                                         /* 讀取中被中斷更新就重讀 */
		v = net_ver;
		memcpy(ip, net_ip, 4);
		strncpy(ssid, net_ssid, ssid_sz - 1);
		ssid[ssid_sz - 1] = 0;
	} while (v != net_ver);
}
