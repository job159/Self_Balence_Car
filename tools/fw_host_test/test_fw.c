/*
 * 韌體邏輯主機端測試：把真實的 proto.c / rc_link.c / motion.c / params.c 編進來，
 * 以假 UART / 假 DMA / 假 Flash 餵入 App 幀，檢查 STM32 的回應與狀態機。
 * 用法：bash tools/fw_host_test/run.sh
 */
#include "main.h"

USART_TypeDef usart2_inst, usart3_inst;
static DMA_HandleTypeDef dma_rx;
UART_HandleTypeDef huart2 = { USART2, HAL_UART_STATE_READY, HAL_UART_STATE_READY, &dma_rx };
uint8_t fake_flash[1024];
u8 Way_Angle = 2, Flag_front, Flag_back, Flag_Left, Flag_Right, Flag_velocity = 2;
volatile u8 Flag_Stop = 1;
int Motor_Left, Motor_Right, Voltage = 1180;
float Angle_Balance = 1.5f, Gyro_Balance, Gyro_Turn, Roll, Velocity_Left, Velocity_Right;
float Balance_Kp = 22500, Balance_Kd = 108, Velocity_Kp = 16000, Velocity_Ki = 80, Turn_Kp = 4200, Turn_Kd = 0;
volatile int Ctrl_BalancePwm, Ctrl_VelocityPwm, Ctrl_TurnPwm;
volatile float Ctrl_Heading;
volatile uint16_t Ctrl_LoopUs;
volatile uint8_t Ctrl_ImuResync;
static int motor_kills;
void Motor_Kill(void) { motor_kills++; }

/* ---------------------------------------------------------------- 假 HAL */
static uint8_t txcap[65536];
static int txn;
static uint8_t *rxbuf;
static uint16_t rxsize, rxw;
static int dma_busy;
static const uint8_t *dma_ptr;
static uint16_t dma_len;

HAL_StatusTypeDef HAL_UART_Transmit_DMA(UART_HandleTypeDef *h, const uint8_t *p, uint16_t n)
{
	(void)h;
	dma_busy = 1; dma_ptr = p; dma_len = n;
	huart2.gState = HAL_UART_STATE_BUSY_TX;
	return HAL_OK;
}
HAL_StatusTypeDef HAL_UARTEx_ReceiveToIdle_DMA(UART_HandleTypeDef *h, uint8_t *p, uint16_t n)
{
	(void)h;
	rxbuf = p; rxsize = n; rxw = 0; dma_rx.ndtr = n;
	huart2.RxState = HAL_UART_STATE_BUSY_RX;
	return HAL_OK;
}
HAL_StatusTypeDef HAL_FLASH_Unlock(void) { return HAL_OK; }
HAL_StatusTypeDef HAL_FLASH_Lock(void) { return HAL_OK; }
HAL_StatusTypeDef HAL_FLASHEx_Erase(FLASH_EraseInitTypeDef *e, uint32_t *err)
{
	(void)e; *err = 0xFFFFFFFF;
	memset(fake_flash, 0xFF, sizeof fake_flash);
	return HAL_OK;
}
HAL_StatusTypeDef HAL_FLASH_Program(uint32_t t, uint32_t a, uint64_t d)
{
	(void)t;
	uint16_t v = (uint16_t)d;
	uint32_t off = a - (uint32_t)(uintptr_t)fake_flash;   /* 64 位元主機上位址被截成 32 位元 */
	if (off + 2 > sizeof fake_flash) return HAL_ERROR;
	memcpy(fake_flash + off, &v, 2);
	return HAL_OK;
}
void HAL_UART_TxCpltCallback(UART_HandleTypeDef *huart);
void HAL_UARTEx_RxEventCallback(UART_HandleTypeDef *huart, uint16_t pos);
void UI_Toast(uint8_t id) { (void)id; }
void UI_NextPage(void) {}

static void pump_tx(void)
{
	while (dma_busy) {
		memcpy(&txcap[txn], dma_ptr, dma_len);
		txn += dma_len;
		dma_busy = 0;
		huart2.gState = HAL_UART_STATE_READY;
		HAL_UART_TxCpltCallback(&huart2);
	}
}

/* 模擬 DMA 環形寫入：半滿 / 全滿 / IDLE 都觸發回呼（idle=0 模擬 IDLE 中斷失效） */
static void uart_in_ex(const uint8_t *p, int n, int idle)
{
	for (int i = 0; i < n; i++) {
		rxbuf[rxw++] = p[i];
		dma_rx.ndtr = (uint32_t)(rxsize - rxw);
		if (rxw == rxsize / 2 || rxw == rxsize) {
			HAL_UARTEx_RxEventCallback(&huart2, rxw);
			if (rxw == rxsize) { rxw = 0; dma_rx.ndtr = rxsize; }
		}
	}
	if (idle) HAL_UARTEx_RxEventCallback(&huart2, rxw);
	pump_tx();
}
static void uart_in(const uint8_t *p, int n) { uart_in_ex(p, n, 1); }

static uint8_t app_seq, last_sent_seq;
static void send(uint8_t type, const void *pl, uint8_t len)
{
	uint8_t f[256];
	last_sent_seq = app_seq;
	uint16_t n = proto_build(f, type, app_seq++, pl, len);
	uart_in(f, n);
}
static void resend(uint8_t type, const void *pl, uint8_t len)   /* App 重送：SEQ 不變 */
{
	uint8_t f[256];
	uint16_t n = proto_build(f, type, last_sent_seq, pl, len);
	uart_in(f, n);
}

/* ---------------------------------------------------------------- 解析 STM32 輸出 */
typedef struct { uint8_t type, len, seq; uint8_t p[200]; } fr_t;
static fr_t out[1024];
static int nout;
static proto_parser_t outp;

static void on_out(uint8_t t, uint8_t s, const uint8_t *p, uint8_t l)
{
	if (nout < 1024) { out[nout].type = t; out[nout].len = l; out[nout].seq = s; memcpy(out[nout].p, p, l); nout++; }
}
static void collect(void) { nout = 0; proto_feed(&outp, txcap, (uint16_t)txn, on_out); txn = 0; }
static fr_t *find(uint8_t t) { for (int i = 0; i < nout; i++) if (out[i].type == t) return &out[i]; return NULL; }
static fr_t *last(uint8_t t) { fr_t *f = NULL; for (int i = 0; i < nout; i++) if (out[i].type == t) f = &out[i]; return f; }
static int count(uint8_t t) { int n = 0; for (int i = 0; i < nout; i++) n += out[i].type == t; return n; }
static int has_event(uint8_t code, int arg)
{
	for (int i = 0; i < nout; i++)
		if (out[i].type == T_EVENT && out[i].p[0] == code && (arg < 0 || rd_i16(&out[i].p[1]) == arg)) return 1;
	return 0;
}
static int ack_status(uint8_t ref)
{
	for (int i = 0; i < nout; i++) if (out[i].type == T_ACK && out[i].p[0] == ref) return out[i].p[1];
	return -1;
}
static float pv(fr_t *f, uint8_t id)
{
	for (int i = 0; f && i < f->p[0]; i++)
		if (f->p[1 + i * 5] == id) { float v; memcpy(&v, &f->p[2 + i * 5], 4); return v; }
	return NAN;
}

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("  FAIL line %d: ", __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static void tick(int n) { for (int i = 0; i < n; i++) { Motion_Tick(); RC_Link_Tick(); pump_tx(); } }
static void ctrl(int16_t s, int16_t t) { uint8_t p[5]; wr_i16(p, s); wr_i16(p + 2, t); p[4] = 0; send(T_CTRL, p, 5); }
static void put_f(uint8_t *p, float v) { memcpy(p, &v, 4); }

int main(void)
{
	memset(fake_flash, 0xFF, sizeof fake_flash);
	Params_Load();
	RC_Link_Init();
	pump_tx();
	collect();
	CHECK(find(T_CRED_REQ) != NULL, "boot CRED_REQ");
	Motion_Init();

	puts("[1] ESP CRED / LINK");
	{
		uint8_t c[] = { 9, 'B', '5', '7', '0', '-', 'A', '1', 'B', '2', 8, 'k', '7', 'm', '3', 'x', '9', 'q', 'a', 6 };
		char s[33], pw[64];
		send(T_CRED, c, sizeof c);
		RC_Link_GetCred(s, sizeof s, pw, sizeof pw);
		CHECK(strcmp(s, "B570-A1B2") == 0 && strcmp(pw, "k7m3x9qa") == 0, "cred parse '%s' '%s'", s, pw);
		CHECK(RC_Link_CredVersion() == 1, "cred version");
		uint8_t l[2] = { 2, 1 };
		send(T_LINK, l, 2);
		tick(1);
		collect();
		CHECK(Motion_EspState() == 2, "esp state");
		CHECK(has_event(EV_BOOT, -1), "BOOT event");
	}

	puts("[2] PING / INFO / PARAM_GET");
	{
		uint8_t p[4] = { 0x78, 0x56, 0x34, 0x12 };
		send(T_PING, p, 4);
		collect();
		fr_t *f = find(T_PONG);
		CHECK(f && f->len == 4 && memcmp(f->p, p, 4) == 0, "pong echo");
		send(T_INFO_GET, NULL, 0);
		collect();
		f = find(T_INFO);
		CHECK(f && f->len == 20 && f->p[0] == 1 && f->p[1] == FW_VER_MAJOR && f->p[4] == PARAM_COUNT && f->p[5] == 48 &&
		      memcmp(&f->p[8], "B570\0\0\0\0\0\0\0\0", 12) == 0, "info");
		uint8_t a = 0xFF;
		send(T_PARAM_GET, &a, 1);
		collect();
		f = find(T_PARAM_VALUES);
		CHECK(f && f->p[0] == 17 && f->len == 86, "param all: n=%d len=%d", f ? f->p[0] : -1, f ? f->len : -1);
		CHECK(pv(f, 0) == 22500 && pv(f, 7) == 500 && pv(f, 15) == 10.0f && pv(f, 13) == 2, "defaults");
		a = 7;
		send(T_PARAM_GET, &a, 1);
		collect();
		f = find(T_PARAM_VALUES);
		CHECK(f && f->p[0] == 1 && pv(f, 7) == 500, "param single");
	}

	puts("[3] PARAM_SET clamp / int rounding / invalid id");
	{
		uint8_t p[15];
		p[0] = 0;  put_f(p + 1, 99999.0f);
		p[5] = 12; put_f(p + 6, 33.4f);
		p[10] = 40; put_f(p + 11, 1.0f);
		send(T_PARAM_SET, p, 15);
		collect();
		fr_t *f = find(T_PARAM_VALUES);
		CHECK(f && f->p[0] == 2 && pv(f, 0) == 60000 && pv(f, 12) == 33, "clamp/round");
		CHECK(Balance_Kp == 60000 && Params_Dirty, "Balance_Kp applied + dirty");
		send(T_PARAM_SET, p + 10, 5);
		collect();
		CHECK(ack_status(T_PARAM_SET) == ACK_BAD_ARG, "only invalid id -> BAD_ARG");
	}

	puts("[4] PARAM_SAVE -> flash -> reload, DEFAULTS");
	{
		send(T_PARAM_SAVE, NULL, 0);
		RC_Link_Task();
		pump_tx();
		tick(1);
		collect();
		CHECK(ack_status(T_PARAM_SAVE) == ACK_OK, "save ack %d", ack_status(T_PARAM_SAVE));
		CHECK(has_event(EV_PARAMS_SAVED, 0), "PARAMS_SAVED event");
		CHECK(!Params_Dirty, "dirty cleared");
		Balance_Kp = 1;
		Params_Load();
		CHECK(Balance_Kp == 60000 && Telem_Hz == 33, "reload %f %f", Balance_Kp, Telem_Hz);
		fake_flash[10] ^= 0x55;                       /* 損毀 → 回到預設 */
		Params_Load();
		CHECK(Balance_Kp == 22500, "corrupt flash -> defaults");
		Balance_Kp = 60000;
		send(T_PARAM_DEFAULTS, NULL, 0);
		collect();
		CHECK(Balance_Kp == 22500 && Telem_Hz == 50 && Params_Dirty, "defaults");
		CHECK(find(T_PARAM_VALUES) && find(T_PARAM_VALUES)->p[0] == 17, "defaults reply all");
	}

	puts("[5] MOTOR start, locks while running");
	{
		uint8_t a = 1;
		send(T_MOTOR, &a, 1);
		collect();
		CHECK(ack_status(T_MOTOR) == ACK_OK, "motor ack");
		tick(1);
		collect();
		CHECK(!Flag_Stop && has_event(EV_MOTORS_ON, START_APP), "started + MOTORS_ON(app)");
		uint8_t p[5] = { 13 };
		put_f(p + 1, 1.0f);
		send(T_PARAM_SET, p, 5);
		collect();
		CHECK(pv(find(T_PARAM_VALUES), 13) == 1 && Way_Angle == 2, "ANGLE_ALGO stored, applied only at boot");
		p[0] = 13; put_f(p + 1, 2.0f);
		send(T_PARAM_SET, p, 5);
		collect();
		send(T_PARAM_SAVE, NULL, 0);
		collect();
		CHECK(ack_status(T_PARAM_SAVE) == ACK_BUSY, "save BUSY while running");
		send(T_ZERO_CAL, NULL, 0);
		collect();
		CHECK(ack_status(T_ZERO_CAL) == ACK_BUSY, "zero cal BUSY while running");
	}

	puts("[6] CTRL, acceleration limit, Movement, max speed, telemetry rate");
	{
		for (int i = 0; i < 50; i++) { ctrl(400, 0); tick(1); }
		CHECK(fabsf(Motion_TargetSpeed() - 400) < 0.01f, "reached 400: %f", Motion_TargetSpeed());
		ctrl(-400, 1000);
		tick(1);
		CHECK(fabsf(Motion_TargetSpeed() - 380) < 0.01f, "slew 20 mm/s per tick (ACCEL 2000): %f", Motion_TargetSpeed());
		CHECK(fabsf(Motion_TargetTurn() - 120) < 0.01f, "turn slew 120 permille per tick (TURN_ACCEL 12000): %f", Motion_TargetTurn());
		CHECK(fabsf(Motion_Movement() - 380 / 13.4927f) < 0.01f, "movement %f", Motion_Movement());
		CHECK(fabsf(Motion_TurnTarget() - 120 * 0.001f * 40) < 0.001f, "turn target %f", Motion_TurnTarget());
		collect();
		for (int i = 0; i < 200; i++) { ctrl(9999, -9999); tick(1); }
		CHECK(fabsf(Motion_TargetSpeed() - 500) < 0.01f && fabsf(Motion_TargetTurn() + 1000) < 0.01f, "clamp %f %f",
		      Motion_TargetSpeed(), Motion_TargetTurn());
		collect();
		CHECK(count(T_TELEM) == 100, "50 Hz telemetry: %d frames / 2 s", count(T_TELEM));
		fr_t *f = last(T_TELEM);
		CHECK(f && f->len == 42 && (f->p[30] & 0x03) == 0x03 && f->p[31] == 0 && f->p[32] == 0xFF && f->p[34] == SRC_APP,
		      "telem flags 0x%02X reason %d step %d src %d", f ? f->p[30] : 0, f ? f->p[31] : 0, f ? f->p[32] : 0, f ? f->p[34] : 0);
		CHECK(f && f->p[35] >= 99, "link quality %d", f ? f->p[35] : 0);
		CHECK(f && rd_i16(&f->p[24]) == 500 && rd_i16(&f->p[26]) == -1000 && rd_u16(&f->p[28]) == 1180 && rd_i16(&f->p[2]) == 150,
		      "telem fields");
	}

	puts("[7] failsafe: app stops sending");
	{
		tick(120);
		collect();
		CHECK(has_event(EV_LINK_LOST, 0), "LINK_LOST event");
		CHECK(fabsf(Motion_TargetSpeed()) < 0.01f, "decelerated to 0: %f", Motion_TargetSpeed());
		fr_t *f = last(T_TELEM);
		CHECK(f && (f->p[30] & 0x20) && !(f->p[30] & 0x02) && !Flag_Stop, "failsafe flag, still balancing (0x%02X)", f ? f->p[30] : 0);
		uint8_t l[2] = { 1, 1 };
		ctrl(0, 0);
		send(T_LINK, l, 2);                            /* ESP 說 session 已結束：立即視為斷線 */
		tick(1);
		CHECK(!Motion_AppLinked(), "ESP LINK<2 forces unlinked");
		l[0] = 2;
		send(T_LINK, l, 2);
		ctrl(0, 0);
		tick(1);
		CHECK(Motion_AppLinked() && !Motion_Failsafe(), "relinked");
	}

	puts("[8] sequence: chunked load, play, done, override");
	{
		uint8_t p[3 + 24 * 8];
		p[0] = 0; p[1] = 30; p[2] = 24;
		for (int i = 0; i < 24; i++) {
			wr_i16(p + 3 + i * 8, 100); wr_i16(p + 5 + i * 8, 0); wr_u16(p + 7 + i * 8, 50);
			p[9 + i * 8] = 0; p[10 + i * 8] = 0;
		}
		send(T_SEQ_LOAD, p, sizeof p);
		collect();
		CHECK(ack_status(T_SEQ_LOAD) == ACK_OK, "chunk 1");
		uint8_t b = 2;
		send(T_SEQ_PLAY, &b, 1);
		collect();
		CHECK(ack_status(T_SEQ_PLAY) == ACK_NOT_READY, "play before complete -> NOT_READY");
		p[0] = 5; p[2] = 6;
		send(T_SEQ_LOAD, p, 3 + 6 * 8);
		collect();
		CHECK(ack_status(T_SEQ_LOAD) == ACK_BAD_ARG, "out-of-order chunk -> BAD_ARG");
		p[0] = 24;
		send(T_SEQ_LOAD, p, 3 + 6 * 8);
		collect();
		CHECK(ack_status(T_SEQ_LOAD) == ACK_OK, "chunk 2");
		send(T_SEQ_LOAD, p, 3 + 6 * 8);               /* ACK 遺失 → App 重送同一批 */
		collect();
		CHECK(ack_status(T_SEQ_LOAD) == ACK_OK && Motion_SeqTotal() == 30, "resent chunk accepted");
		send(T_SEQ_PLAY, &b, 1);
		collect();
		CHECK(ack_status(T_SEQ_PLAY) == ACK_OK, "play ok");
		ctrl(0, 0);
		tick(1);
		CHECK(Motion_SeqPlaying() && Motion_Source() == SRC_SEQ, "playing");
		for (int i = 0; i < 12; i++) { ctrl(0, 0); tick(1); }
		uint8_t step_before = Motion_SeqStep();
		send(T_SEQ_PLAY, &b, 1);                        /* 重送 PLAY 不可從頭播 */
		ctrl(0, 0);
		tick(1);
		collect();
		CHECK(ack_status(T_SEQ_PLAY) == ACK_OK && Motion_SeqStep() >= step_before && step_before >= 2,
		      "resent PLAY does not restart (%d -> %d)", step_before, Motion_SeqStep());
		uint8_t q[11] = { 0, 1, 1 };
		send(T_SEQ_LOAD, q, 11);
		collect();
		CHECK(ack_status(T_SEQ_LOAD) == ACK_BUSY, "load BUSY while playing");
		for (int i = 0; i < 400; i++) { ctrl(10, 0); tick(1); }
		collect();
		CHECK(!Motion_SeqPlaying() && has_event(EV_SEQ_DONE, 0), "done after 2 loops");
		b = 0;
		send(T_SEQ_PLAY, &b, 1);
		ctrl(0, 0);
		tick(5);
		ctrl(200, 0);
		tick(1);
		collect();
		CHECK(!Motion_SeqPlaying() && has_event(EV_SEQ_ABORTED, SEQ_ABORT_OVERRIDE), "joystick override aborts");
	}

	puts("[9] sine / ramp shapes");
	{
		uint8_t p[3 + 2 * 8] = { 0, 2, 2 };
		wr_i16(p + 3, 0);   wr_i16(p + 5, 500); wr_u16(p + 7, 1000);  p[9] = 1;  p[10] = 50;   /* SINE_TURN 1 s */
		wr_i16(p + 11, 200); wr_i16(p + 13, 0); wr_u16(p + 15, 1000); p[17] = 3; p[18] = 0;    /* RAMP → 200 */
		Turn_Accel_Limit = 1e6f;
		Accel_Limit = 1e6f;
		for (int i = 0; i < 30; i++) { ctrl(0, 0); tick(1); }
		send(T_SEQ_LOAD, p, sizeof p);
		uint8_t b = 1;
		send(T_SEQ_PLAY, &b, 1);
		collect();
		float maxt = 0, mint = 0;
		for (int i = 0; i < 100; i++) {
			ctrl(0, 0); tick(1);
			if (Motion_TargetTurn() > maxt) maxt = Motion_TargetTurn();
			if (Motion_TargetTurn() < mint) mint = Motion_TargetTurn();
		}
		CHECK(maxt > 499 && maxt <= 501 && mint < -499 && mint >= -501, "sine peaks %f %f", maxt, mint);
		for (int i = 0; i < 50; i++) { ctrl(0, 0); tick(1); }
		CHECK(fabsf(Motion_TargetSpeed() - 98) < 1, "ramp midpoint %f", Motion_TargetSpeed());
		Params_Set(P_ACCEL, 1200);
		Params_Set(P_TURN_ACCEL, 6000);
	}

	puts("[10] ESTOP, zero cal, refusals, garbage resilience");
	{
		uint8_t a = 2;
		send(T_MOTOR, &a, 1);
		tick(1);
		collect();
		CHECK(Flag_Stop && Motion_StopReason() == STOP_ESTOP && !Motion_SeqPlaying(), "estop");
		CHECK(has_event(EV_MOTORS_OFF, STOP_ESTOP) && has_event(EV_SEQ_ABORTED, SEQ_ABORT_CMD), "estop events");
		send(T_MOTOR, &a, 1);
		tick(1);
		collect();
		CHECK(!has_event(EV_MOTORS_OFF, -1), "no duplicate MOTORS_OFF when already stopped");
		uint8_t junk[300];
		for (int i = 0; i < 300; i++) junk[i] = (uint8_t)(i * 37 + 0xA5);
		uart_in(junk, 300);
		uint8_t p[4] = { 1, 2, 3, 4 };
		send(T_PING, p, 4);
		collect();
		CHECK(find(T_PONG) != NULL, "recovers after garbage");
		send(T_ZERO_CAL, NULL, 0);
		collect();
		CHECK(ack_status(T_ZERO_CAL) == ACK_OK && fabsf(pv(find(T_PARAM_VALUES), 6) - 1.5f) < 1e-4f, "zero cal -> 1.5");
		a = 1;
		Voltage = 950;
		send(T_MOTOR, &a, 1);
		collect();
		CHECK(ack_status(T_MOTOR) == ACK_NOT_READY, "low battery start refused");
		Voltage = 1180;
		Angle_Balance = 45;
		send(T_MOTOR, &a, 1);
		collect();
		CHECK(ack_status(T_MOTOR) == ACK_NOT_READY, "tilted start refused");
		Angle_Balance = 1.5f;
		uint8_t u = 0;
		send(0x3F, &u, 1);
		collect();
		CHECK(ack_status(0x3F) == ACK_UNKNOWN_TYPE, "unknown type");
		send(T_SEQ_STOP, NULL, 0);
		collect();
		CHECK(ack_status(T_SEQ_STOP) == ACK_OK, "seq stop ack");
	}

	puts("[10b] review fixes: double ESTOP, ACK-then-command ordering, PLAY retry, TELEM_HZ, pwm saturation");
	{
		uint8_t a = 1;
		send(T_MOTOR, &a, 1); tick(1); collect();
		CHECK(!Flag_Stop, "restart");
		int kills = motor_kills;
		a = 2;
		send(T_MOTOR, &a, 1);
		send(T_MOTOR, &a, 1);                          /* App 的緊急停止會連送兩次 */
		CHECK(Flag_Stop && motor_kills > kills, "ESTOP cuts motors inside the UART IRQ");
		tick(1); collect();
		CHECK(has_event(EV_MOTORS_OFF, STOP_ESTOP), "double ESTOP still reports MOTORS_OFF(7)");
		a = 1;
		send(T_MOTOR, &a, 1); tick(1); collect();
		a = 0;
		send(T_MOTOR, &a, 1);                          /* 停止後立刻存檔：不可回 BUSY */
		send(T_PARAM_SAVE, NULL, 0);
		RC_Link_Task(); pump_tx(); tick(1); collect();
		CHECK(ack_status(T_PARAM_SAVE) == ACK_OK, "save right after stop: %d", ack_status(T_PARAM_SAVE));
		CHECK(has_event(EV_MOTORS_OFF, STOP_APP) && Motion_StopReason() == STOP_APP, "stop reason app");
		a = 1;
		send(T_MOTOR, &a, 1); tick(1);
		uint8_t q[3 + 8] = { 0, 1, 1 };
		wr_i16(q + 3, 0); wr_i16(q + 5, 0); wr_u16(q + 7, 5000); q[9] = 0; q[10] = 0;
		send(T_SEQ_LOAD, q, sizeof q);
		uint8_t b = 1;
		send(T_SEQ_PLAY, &b, 1); ctrl(0, 0); tick(2);
		CHECK(Motion_SeqPlaying(), "long seq playing");
		send(T_SEQ_STOP, NULL, 0);
		send(T_SEQ_LOAD, q, sizeof q);                 /* 停止後立刻上傳：不可回 BUSY */
		collect();
		CHECK(ack_status(T_SEQ_LOAD) == ACK_OK && !Motion_SeqPlaying(), "load right after SEQ_STOP");
		wr_u16(q + 7, 100);                            /* 100 ms 短編排 */
		send(T_SEQ_LOAD, q, sizeof q);
		send(T_SEQ_PLAY, &b, 1);
		for (int i = 0; i < 20; i++) { ctrl(0, 0); tick(1); }
		collect();
		CHECK(has_event(EV_SEQ_DONE, 0) && !Motion_SeqPlaying(), "short seq done");
		send(T_SEQ_PLAY, &b, 1);                       /* ACK 遺失後的重送：不可再播一次 */
		ctrl(0, 0); tick(1);
		CHECK(!Motion_SeqPlaying(), "retried PLAY of a finished seq ignored");
		for (int i = 0; i < 160; i++) { ctrl(0, 0); tick(1); }
		send(T_SEQ_PLAY, &b, 1);
		ctrl(0, 0); tick(1);
		CHECK(Motion_SeqPlaying(), "PLAY again after the retry window works");
		a = 0;
		send(T_MOTOR, &a, 1); tick(1); collect();
		CHECK(has_event(EV_SEQ_ABORTED, SEQ_ABORT_CMD), "MOTOR stop aborts seq with arg 4");
		uint8_t pp[5] = { 12 };
		put_f(pp + 1, 70.0f);
		send(T_PARAM_SET, pp, 5); collect();
		CHECK(pv(find(T_PARAM_VALUES), 12) == 100, "TELEM_HZ 70 -> actual 100");
		put_f(pp + 1, 50.0f);
		send(T_PARAM_SET, pp, 5); collect();
		Ctrl_BalancePwm = 36000;
		tick(2); collect();
		fr_t *f = last(T_TELEM);
		CHECK(f && rd_i16(&f->p[18]) == 32767, "pwm_bal saturates instead of wrapping");
		Ctrl_BalancePwm = 0;
		Motion_ImuFault();
		CHECK(Flag_Stop && Motion_StopReason() == STOP_IMU, "IMU fault stop");
		a = 1;
		send(T_MOTOR, &a, 1); tick(1); collect();
		CHECK(ack_status(T_MOTOR) == ACK_NOT_READY && Flag_Stop, "START refused after IMU fault");
	}

	puts("[10c] reply SEQ echo + exactly-once retries");
	{
		send(T_INFO_GET, NULL, 0); collect();
		CHECK(find(T_INFO) && find(T_INFO)->seq == last_sent_seq, "INFO echoes request seq");
		uint8_t a = 0xFF;
		send(T_PARAM_GET, &a, 1); collect();
		CHECK(find(T_PARAM_VALUES) && find(T_PARAM_VALUES)->seq == last_sent_seq, "PARAM_VALUES echoes seq");
		uint8_t pg[4] = { 9, 9, 9, 9 };
		send(T_PING, pg, 4); collect();
		CHECK(find(T_PONG) && find(T_PONG)->seq == last_sent_seq, "PONG echoes seq");
		a = 0;
		send(T_MOTOR, &a, 1); tick(1); collect();
		fr_t *k = find(T_ACK);
		CHECK(k && k->seq == last_sent_seq, "ACK echoes seq");
		/* ZERO_CAL 重送：不可重做、不可再寫 Flash */
		Angle_Balance = 2.0f;
		send(T_ZERO_CAL, NULL, 0); RC_Link_Task(); pump_tx(); collect();
		CHECK(ack_status(T_ZERO_CAL) == ACK_OK && fabsf(Middle_angle - 2.0f) < 1e-4f, "zero cal 2.0");
		uint8_t zseq = last_sent_seq;
		tick(1); collect();                            /* 先送出第一次校正的事件 */
		Angle_Balance = 3.0f;
		resend(T_ZERO_CAL, NULL, 0); RC_Link_Task(); pump_tx(); tick(1); collect();
		CHECK(ack_status(T_ZERO_CAL) == ACK_OK && fabsf(Middle_angle - 2.0f) < 1e-4f && find(T_ACK)->seq == zseq,
		      "retried ZERO_CAL answered without re-zeroing (zero=%f)", Middle_angle);
		CHECK(!has_event(EV_ZERO_CAL, -1) && !has_event(EV_PARAMS_SAVED, -1), "no second calibration / flash write");
		/* 緊急停止後，遲到的 START 重送（同 SEQ）不可重新啟動 */
		a = 1; send(T_MOTOR, &a, 1); tick(1);
		CHECK(!Flag_Stop, "START ok");
		uint8_t start_s = last_sent_seq;
		a = 2; send(T_MOTOR, &a, 1); tick(1);
		CHECK(Flag_Stop, "ESTOP");
		{ uint8_t f2[16]; uint8_t one = 1; uint16_t n2 = proto_build(f2, T_MOTOR, start_s, &one, 1); uart_in(f2, n2); }
		tick(2); collect();
		CHECK(Flag_Stop && Motion_StopReason() == STOP_ESTOP && ack_status(T_MOTOR) == ACK_OK, "late START retry after ESTOP does not restart motors");
		a = 1; send(T_MOTOR, &a, 1); tick(1);
		CHECK(!Flag_Stop, "new START (new seq) works");
		a = 0; send(T_MOTOR, &a, 1); tick(1); collect();
		send(T_ZERO_CAL, NULL, 0); RC_Link_Task(); pump_tx(); collect();
		CHECK(fabsf(Middle_angle - 3.0f) < 1e-4f, "new ZERO_CAL (new seq) works");
		/* PARAM_SAVE 延後回覆也要帶原 SEQ；重送只回覆一次 */
		send(T_PARAM_SAVE, NULL, 0);
		uint8_t sseq = last_sent_seq;
		resend(T_PARAM_SAVE, NULL, 0);                 /* 還在等 Flash：不回覆 */
		collect();
		CHECK(ack_status(T_PARAM_SAVE) == -1, "pending save: retry stays silent");
		RC_Link_Task(); pump_tx(); collect();
		CHECK(count(T_ACK) == 1 && find(T_ACK)->seq == sseq && ack_status(T_PARAM_SAVE) == ACK_OK, "deferred save ACK carries request seq");
		tick(5); collect();                            /* 事件每週期最多送 2 個 */
		resend(T_PARAM_SAVE, NULL, 0); RC_Link_Task(); pump_tx(); tick(1); collect();
		CHECK(ack_status(T_PARAM_SAVE) == ACK_OK && !has_event(EV_PARAMS_SAVED, -1), "retry after save: ACK only, no second write");
		Angle_Balance = 1.5f;
	}

	puts("[12] RX polling (IDLE interrupt missing) + control echo + NET");
	{
		uint8_t f[32];
		uint8_t pl[5];
		Motion_Start(START_KEY); tick(1); collect();
		wr_i16(pl, 250); wr_i16(pl + 2, 0); pl[4] = 0;
		uint16_t n = proto_build(f, T_CTRL, 0x77, pl, 5);
		uart_in_ex(f, n, 0);                          /* 沒有 IDLE 回呼 */
		RC_Link_PollRx();                             /* 下一個控制週期開頭 */
		Accel_Limit = 1e6f;
		Motion_Tick();
		CHECK(fabsf(Motion_TargetSpeed() - 250) < 0.01f, "command applied within one tick without IDLE: %f", Motion_TargetSpeed());
		Params_Set(P_ACCEL, 2000);
		for (int i = 0; i < 3; i++) { RC_Link_Tick(); pump_tx(); Motion_Tick(); }
		RC_Link_Tick(); pump_tx(); collect();
		fr_t *t = last(T_TELEM);
		CHECK(t && t->len == 42 && t->p[40] == 0x77 && t->p[41] <= 5, "telem ctrl_seq=0x%02X age=%d", t ? t->p[40] : 0, t ? t->p[41] : -1);
		uint8_t net[] = { 2, 172, 20, 10, 5, 7, 'M', 'y', 'P', 'h', 'o', 'n', 'e' };
		send(T_NET, net, sizeof net);
		uint8_t ip[4]; char hs[33];
		RC_Link_GetNet(ip, hs, sizeof hs);
		CHECK(RC_Link_NetMode() == 2 && ip[0] == 172 && ip[3] == 5 && strcmp(hs, "MyPhone") == 0, "NET parse mode=%d ssid=%s", RC_Link_NetMode(), hs);
		uint8_t badnet[] = { 2, 1, 2, 3, 4, 40 };     /* ssid_len 超過 32：忽略 */
		send(T_NET, badnet, sizeof badnet);
		CHECK(RC_Link_NetMode() == 2, "bad NET ignored");
		Motion_Stop(STOP_KEY); tick(1); collect();
	}

	puts("[13] v1 param blob migration + late DMA half-transfer callback");
	{
		/* fw 2.0 的 v1 存檔：ACCEL 1200 / TURN_ACCEL 6000 → 升級成新預設；使用者自訂值保留 */
		Params_Set(P_ACCEL, 1200); Params_Set(P_TURN_ACCEL, 6000); Params_Set(P_BAL_KP, 23456);
		Flag_Stop = 1;
		CHECK(Params_Save() == ACK_OK, "save");
		uint16_t *ver = (uint16_t *)(fake_flash + 4);
		CHECK(*ver == 2, "saved as blob v2");
		*ver = 1;                                       /* 模擬舊韌體寫的 v1 存檔（版本號不在 CRC 外，要重算） */
		{
			uint16_t crc = proto_crc16(fake_flash, 4 + 2 + 2 + PARAM_COUNT * 4);
			memcpy(fake_flash + 8 + PARAM_COUNT * 4, &crc, 2);
		}
		Params_Load();
		CHECK(Accel_Limit == 2000 && Turn_Accel_Limit == 12000 && Balance_Kp == 23456, "v1 migrated: %f %f %f", Accel_Limit, Turn_Accel_Limit, Balance_Kp);
		Params_Set(P_ACCEL, 1500); Params_Save(); *ver = 1;
		{
			uint16_t crc = proto_crc16(fake_flash, 4 + 2 + 2 + PARAM_COUNT * 4);
			memcpy(fake_flash + 8 + PARAM_COUNT * 4, &crc, 2);
		}
		Params_Load();
		CHECK(Accel_Limit == 1500, "custom ACCEL kept on migration: %f", Accel_Limit);
		Params_Defaults();

		/* 遲到的半滿回呼（HAL 固定給 pos=128）不可讓輪詢處理過的資料被重處理 */
		collect();
		while (rxw != 0) { uint8_t z = 0; uart_in_ex(&z, 1, 1); }   /* 對齊到緩衝區開頭 */
		collect();
		uint8_t pad[120];
		memset(pad, 0, sizeof pad);
		uart_in_ex(pad, sizeof pad, 1);
		uint8_t f[32], pl[4] = { 9, 8, 7, 6 };
		uint16_t n = proto_build(f, T_PING, 0x42, pl, 4);
		for (int i = 0; i < n; i++) { rxbuf[rxw++] = f[i]; dma_rx.ndtr = (uint32_t)(rxsize - rxw); }   /* 越過 128，但 HT 回呼還沒來 */
		RC_Link_PollRx();                              /* 控制中斷先處理到 131 */
		HAL_UARTEx_RxEventCallback(&huart2, 128);      /* 遲到的 HT 回呼 */
		pump_tx(); collect();
		CHECK(count(T_PONG) == 1, "late HT callback must not replay: %d PONG", count(T_PONG));
	}

	puts("[11] telemetry payload for cross-check with tools/b570_proto.py");
	{
		Angle_Balance = -5.23f; Roll = 1.5f; Gyro_Balance = -820; Gyro_Turn = 33;
		Velocity_Left = 120; Velocity_Right = 118; Voltage = 1156; Ctrl_Heading = 90.0f; Ctrl_LoopUs = 412;
		Ctrl_BalancePwm = 1200; Ctrl_VelocityPwm = 250; Ctrl_TurnPwm = 50;
		tick(2);
		collect();
		fr_t *f = last(T_TELEM);
		printf("TELEM ");
		for (int i = 0; f && i < f->len; i++) printf("%02X", f->p[i]);
		printf("\n");
	}

	printf(fails ? "\n%d FAILURE(S)\n" : "\nALL FIRMWARE LOGIC TESTS PASSED\n", fails);
	return fails != 0;
}
