/*
 * motion.c
 *
 * 運動指令層（見 motion.h）。執行環境：
 *   - Motion_Tick / Motion_Start / Motion_Stop：MPU6050 外部中斷（控制中斷，10 ms）
 *   - Motion_SetAppControl / RequestMotor / Seq*：USART2 中斷（優先權較高）
 * 跨中斷的動作一律以「請求旗標」交給 Motion_Tick 執行，避免競爭。
 */
#include "main.h"

#define TICK_S              0.01f
#define OVERRIDE_SPEED      30      /* 編排播放中，超過這個搖桿量視為手動接管 */
#define OVERRIDE_TURN       50
#define ESP_TIMEOUT_TICKS   200     /* 2 s 沒收到 LINK 心跳視為 ESP 不在線 */
#define PLAY_RETRY_TICKS    150     /* App 重送 PLAY 的時間窗（400 ms × 3） */

enum { SEQ_HOLD = 0, SEQ_SINE_TURN, SEQ_SINE_SPEED, SEQ_RAMP };

typedef struct {
	int16_t  speed;
	int16_t  turn;
	uint16_t dur_ms;
	uint8_t  mode;
	uint8_t  period;    /* 20 ms 單位 */
} seq_step_t;

/* ---- 狀態 ---- */
static volatile uint32_t ticks;
static volatile int16_t  app_speed, app_turn;
static volatile uint32_t app_last_tick;
static volatile uint8_t  app_seen;
static volatile uint16_t app_frames;
static uint8_t           app_linked, failsafe, link_q, src;
static volatile uint8_t  esp_state = 0xFF;
static volatile uint32_t esp_last_tick;

static float             tgt_speed, tgt_turn;     /* 經斜率限制後的目標 */
static volatile uint8_t  stop_reason = STOP_BOOT;

static volatile uint8_t  req_start, req_stop, off_evt_owed;
static volatile uint8_t  req_play, req_play_loops, play_armed;
static volatile uint32_t last_play_tick;

static seq_step_t        seq[SEQ_MAX_STEPS];
static volatile uint8_t  seq_total, seq_recv;
static volatile uint8_t  seq_playing;
static uint8_t           seq_idx, seq_loop, seq_loops;
static uint16_t          seq_t;
static float             seq_from_s, seq_from_t;

/* ---- 事件佇列 ---- */
#define EVQ_SIZE 8
static struct { uint8_t code; int16_t arg; } evq[EVQ_SIZE];
static volatile uint8_t evq_head, evq_tail;

void Motion_PushEvent(uint8_t code, int16_t arg)
{
	uint32_t pm = __get_PRIMASK();
	__disable_irq();
	uint8_t next = (uint8_t)((evq_head + 1) % EVQ_SIZE);
	if (next != evq_tail) {                 /* 滿了就丟棄最新的 */
		evq[evq_head].code = code;
		evq[evq_head].arg = arg;
		evq_head = next;
	}
	__set_PRIMASK(pm);
}

int Motion_PopEvent(uint8_t *code, int16_t *arg)
{
	int ok = 0;
	uint32_t pm = __get_PRIMASK();
	__disable_irq();
	if (evq_tail != evq_head) {
		*code = evq[evq_tail].code;
		*arg = evq[evq_tail].arg;
		evq_tail = (uint8_t)((evq_tail + 1) % EVQ_SIZE);
		ok = 1;
	}
	__set_PRIMASK(pm);
	return ok;
}

/* ---- 小工具 ---- */
static float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

static float slew(float cur, float target, float step)
{
	if (target > cur + step) return cur + step;
	if (target < cur - step) return cur - step;
	return target;
}

/* sin(2π·u)，u 以「圈」為單位；拋物線近似，誤差 < 0.1 %，避免連結整套 libm */
static float sin_turns(float u)
{
	u -= (float)(int)u;
	if (u < 0.0f) u += 1.0f;
	if (u >= 0.5f) u -= 1.0f;                       /* -0.5 .. 0.5 */
	float a = u < 0.0f ? -u : u;
	float y = 8.0f * u - 16.0f * u * a;
	float ay = y < 0.0f ? -y : y;
	return 0.225f * (y * ay - y) + y;
}

static uint8_t can_start(void)
{
	float tilt = Angle_Balance - Middle_angle;
	if (Flag_Stop && stop_reason == STOP_IMU) return 0;      /* IMU 失效：需檢查後重新開機 */
	if (Voltage <= 0 || Voltage < (int)(LowBat_V * 100.0f)) return 0;
	if (tilt > Fall_Angle || tilt < -Fall_Angle) return 0;
	return 1;
}

/* ---- 啟停 ---- */
uint8_t Motion_Start(uint8_t source)
{
	if (!Flag_Stop) return ACK_OK;
	if (!can_start()) return ACK_NOT_READY;
	tgt_speed = tgt_turn = 0.0f;
	stop_reason = STOP_NONE;
	Flag_Stop = 0;
	Motion_PushEvent(EV_MOTORS_ON, source);
	return ACK_OK;
}

/* 可在任何中斷呼叫：立即停止播放（同步生效，之後的指令看到的就是新狀態） */
static void seq_abort(uint8_t why)
{
	uint32_t pm = __get_PRIMASK();
	__disable_irq();
	uint8_t was = seq_playing;
	seq_playing = 0;
	req_play = 0;
	__set_PRIMASK(pm);
	if (was) Motion_PushEvent(EV_SEQ_ABORTED, why);
}

/* 控制中斷 / 主迴圈使用 */
void Motion_Stop(uint8_t reason)
{
	uint32_t pm = __get_PRIMASK();
	__disable_irq();
	uint8_t was_running = !Flag_Stop;
	Flag_Stop = 1;
	__set_PRIMASK(pm);
	stop_reason = reason;                   /* 使用者意圖會覆蓋自動停機原因（避免放下後又自動啟動） */
	if (was_running) Motion_PushEvent(EV_MOTORS_OFF, reason);
	seq_abort(SEQ_ABORT_MOTORS);
	tgt_speed = tgt_turn = 0.0f;
}

/* UART 中斷使用：馬達立即斷電，原因與事件交給下一個 Motion_Tick；ESTOP 優先權最高、不會被降級 */
static void request_stop(uint8_t reason)
{
	uint32_t pm = __get_PRIMASK();
	__disable_irq();
	if (!Flag_Stop) off_evt_owed = 1;
	Flag_Stop = 1;
	if (req_stop != STOP_ESTOP) req_stop = reason;
	req_start = 0;
	__set_PRIMASK(pm);
	Motor_Kill();
	seq_abort(SEQ_ABORT_CMD);
}

/* ---- 由 UART 中斷呼叫的指令 ---- */
void Motion_OnAppFrame(void)
{
	app_last_tick = ticks;
	app_seen = 1;
	if (app_frames < 0xFFFF) app_frames++;
}

void Motion_SetAppControl(int16_t speed_mmps, int16_t turn_permille)
{
	app_speed = speed_mmps;
	app_turn = turn_permille;
	if (seq_playing && (speed_mmps > OVERRIDE_SPEED || speed_mmps < -OVERRIDE_SPEED ||
	                    turn_permille > OVERRIDE_TURN || turn_permille < -OVERRIDE_TURN))
		seq_abort(SEQ_ABORT_OVERRIDE);
}

uint8_t Motion_RequestMotor(uint8_t action)
{
	switch (action) {
	case 0:
		request_stop(STOP_APP);
		return ACK_OK;
	case 1:
		if (!Flag_Stop && !req_stop) return ACK_OK;
		if (!can_start()) return ACK_NOT_READY;
		req_start = START_APP;              /* 在同一週期的停止請求之後處理 */
		return ACK_OK;
	case 2:
		request_stop(STOP_ESTOP);
		app_speed = app_turn = 0;
		return ACK_OK;
	default:
		return ACK_BAD_ARG;
	}
}

uint8_t Motion_ZeroCal(void)
{
	if (!Flag_Stop) return ACK_BUSY;
	if (Angle_Balance > 10.0f || Angle_Balance < -10.0f) return ACK_BAD_ARG;
	float z = Params_Set(P_ZERO_ANGLE, Angle_Balance);
	Motion_PushEvent(EV_ZERO_CAL, (int16_t)(z * 100.0f));
	RC_Link_RequestSave(0);                 /* 校正結果直接存入 Flash（馬達已停止） */
	return ACK_OK;
}

uint8_t Motion_SeqLoad(const uint8_t *p, uint8_t len)
{
	if (len < 3) return ACK_BAD_ARG;
	uint8_t offset = p[0], total = p[1], count = p[2];
	if (seq_playing || req_play) return ACK_BUSY;          /* 要換編排請先 SEQ_STOP */
	if (total == 0 || total > SEQ_MAX_STEPS || (uint16_t)offset + count > total || len != 3 + count * 8)
		return ACK_BAD_ARG;
	/* ACK 遺失時 App 會重送同一批：offset + count == 已收數量 視為重送，照樣接受 */
	uint8_t resend = (offset != 0 && total == seq_total && (uint16_t)offset + count == seq_recv);
	if (offset == 0) { seq_total = total; seq_recv = 0; }
	if (!resend && (total != seq_total || offset != seq_recv)) return ACK_BAD_ARG;   /* 必須依序上傳 */

	for (uint8_t i = 0; i < count; i++) {
		const uint8_t *s = &p[3 + i * 8];
		seq_step_t *d = &seq[offset + i];
		if (s[6] > SEQ_RAMP) return ACK_BAD_ARG;
		d->speed = rd_i16(&s[0]);
		d->turn = rd_i16(&s[2]);
		d->dur_ms = rd_u16(&s[4]);
		if (d->dur_ms < 20) d->dur_ms = 20;
		if (d->dur_ms > 30000) d->dur_ms = 30000;
		d->mode = s[6];
		d->period = s[7] ? s[7] : 50;
	}
	seq_recv = (uint8_t)(offset + count);
	if (seq_recv >= seq_total) play_armed = 1;
	return ACK_OK;
}

uint8_t Motion_SeqPlay(uint8_t loops)
{
	if (seq_total == 0 || seq_recv < seq_total) return ACK_NOT_READY;
	if (Flag_Stop) return ACK_NOT_READY;
	if (seq_playing || req_play) return ACK_OK;          /* 重送的 PLAY 不要從頭重播 */
	/* ACK 遺失時 App 會在 1.2 s 內重送；短編排可能已播完，不要再播一次（重新上傳或 SEQ_STOP 會重新武裝） */
	if (!play_armed && ticks - last_play_tick < PLAY_RETRY_TICKS) return ACK_OK;
	play_armed = 0;
	last_play_tick = ticks;
	req_play_loops = loops;
	req_play = 1;
	return ACK_OK;
}

void Motion_SeqStop(void)
{
	seq_abort(SEQ_ABORT_CMD);
	play_armed = 1;
}

/* 主迴圈偵測到 IMU 中斷停止（控制迴圈不再執行）：直接切斷馬達 */
void Motion_ImuFault(void)
{
	Motor_Kill();
	Motion_Stop(STOP_IMU);
}

void Motion_SetEspLink(uint8_t state)
{
	esp_state = state;
	esp_last_tick = ticks;
}

/* ---- 編排播放 ---- */
static void seq_begin(uint8_t loops)
{
	seq_idx = 0;
	seq_loop = 0;
	seq_loops = loops;
	seq_t = 0;
	seq_from_s = tgt_speed;
	seq_from_t = tgt_turn;
	seq_playing = 1;
}

static void seq_eval(float *out_s, float *out_t)
{
	const seq_step_t *st = &seq[seq_idx];
	float s = st->speed, t = st->turn;
	float ph = (float)seq_t / (st->period * 20.0f);

	switch (st->mode) {
	case SEQ_SINE_TURN:  t = st->turn * sin_turns(ph); break;
	case SEQ_SINE_SPEED: s = st->speed * sin_turns(ph); break;
	case SEQ_RAMP: {
		float f = (float)seq_t / st->dur_ms;
		s = seq_from_s + (st->speed - seq_from_s) * f;
		t = seq_from_t + (st->turn - seq_from_t) * f;
		break;
	}
	default: break;
	}
	*out_s = s;
	*out_t = t;

	seq_t += 10;
	if (seq_t >= st->dur_ms) {
		/* 下一步 RAMP 的起點：定值/漸變用目標值，正弦用最後輸出值 */
		if (st->mode == SEQ_HOLD || st->mode == SEQ_RAMP) { seq_from_s = st->speed; seq_from_t = st->turn; }
		else { seq_from_s = s; seq_from_t = t; }
		seq_t = 0;
		if (++seq_idx >= seq_total) {
			seq_idx = 0;
			seq_loop++;
			if (seq_loops != 0 && seq_loop >= seq_loops) {
				seq_playing = 0;
				Motion_PushEvent(EV_SEQ_DONE, 0);
			}
		}
	}
}

/* ---- 每 10 ms ---- */
void Motion_Init(void)
{
	stop_reason = STOP_BOOT;
	Motion_PushEvent(EV_BOOT, (FW_VER_MAJOR << 8) | FW_VER_MINOR);
}

void Motion_Tick(void)
{
	float ds = 0.0f, dt = 0.0f;
	ticks++;

	/* 1) 套用中斷送來的請求（停止已在中斷內生效，這裡補上原因與事件） */
	if (req_stop) {
		uint32_t pm = __get_PRIMASK();
		__disable_irq();
		uint8_t r = req_stop, owed = off_evt_owed;
		req_stop = 0;
		off_evt_owed = 0;
		__set_PRIMASK(pm);
		stop_reason = r;
		tgt_speed = tgt_turn = 0.0f;
		if (owed) Motion_PushEvent(EV_MOTORS_OFF, r);
	}
	if (req_start) {
		uint8_t s = req_start;
		req_start = 0;
		Motion_Start(s);
	}
	if (req_play) {
		req_play = 0;
		if (!Flag_Stop) seq_begin(req_play_loops);
	}

	/* 2) 連線狀態 / 失聯保護 */
	if (esp_state != 0xFF && ticks - esp_last_tick > ESP_TIMEOUT_TICKS) esp_state = 0xFF;
	uint8_t fresh = app_seen && (ticks - app_last_tick) * 10u < (uint32_t)Failsafe_Ms;
	uint8_t linked = fresh && !(esp_state != 0xFF && esp_state < 2);
	if (app_linked && !linked) {
		app_speed = app_turn = 0;
		seq_abort(SEQ_ABORT_LINK);
		Motion_PushEvent(EV_LINK_LOST, 0);
		failsafe = 1;
	}
	if (linked) failsafe = 0;
	app_linked = linked;

	if (ticks % 100 == 0) {
		link_q = app_frames > 255 ? 255 : (uint8_t)app_frames;
		app_frames = 0;
	}

	/* 3) 選擇目標來源 */
	src = SRC_NONE;
	if (seq_playing) {
		seq_eval(&ds, &dt);
		src = SRC_SEQ;
	} else if (linked) {
		ds = app_speed;
		dt = app_turn;
		src = SRC_APP;
	}
#if APP_LEGACY_BT
	else if (Flag_front || Flag_back || Flag_Left || Flag_Right) {
		/* 原廠藍牙 APP：Flag_velocity 1 = 高速、2 = 一般 */
		float v = 674.0f / (Flag_velocity ? Flag_velocity : 2);
		ds = Flag_front ? v : (Flag_back ? -v : 0.0f);
		dt = Flag_Right ? 1000.0f * (54.0f / Flag_velocity) / Max_Turn : (Flag_Left ? -1000.0f * (54.0f / Flag_velocity) / Max_Turn : 0.0f);
		src = SRC_BT;
	}
#endif

	/* 4) 夾限 + 斜率限制（平順加減速） */
	if (Flag_Stop) {
		tgt_speed = tgt_turn = 0.0f;
		return;
	}
	ds = clampf(ds, -Max_Speed, Max_Speed);
	dt = clampf(dt, -1000.0f, 1000.0f);
	tgt_speed = slew(tgt_speed, ds, Accel_Limit * TICK_S);
	tgt_turn = slew(tgt_turn, dt, Turn_Accel_Limit * TICK_S);
}

/* ---- 給控制環 ---- */
float Motion_Movement(void)
{
#if MOTION_INVERT_SPEED
	return -tgt_speed / MMPS_PER_MOVEMENT;
#else
	return tgt_speed / MMPS_PER_MOVEMENT;
#endif
}

float Motion_TurnTarget(void)
{
#if MOTION_INVERT_TURN
	return -tgt_turn * 0.001f * Max_Turn;
#else
	return tgt_turn * 0.001f * Max_Turn;
#endif
}

float    Motion_TargetSpeed(void)  { return tgt_speed; }
float    Motion_TargetTurn(void)   { return tgt_turn; }
uint8_t  Motion_StopReason(void)   { return Flag_Stop ? stop_reason : STOP_NONE; }
uint8_t  Motion_AppLinked(void)    { return app_linked; }
uint8_t  Motion_Failsafe(void)     { return failsafe; }
uint8_t  Motion_SeqPlaying(void)   { return seq_playing; }
uint8_t  Motion_SeqStep(void)      { return seq_playing ? seq_idx : 0xFF; }
uint8_t  Motion_SeqLoop(void)      { return seq_loop; }
uint8_t  Motion_SeqTotal(void)     { return seq_total; }
uint8_t  Motion_Source(void)       { return src; }
uint8_t  Motion_LinkQuality(void)  { return link_q; }
uint8_t  Motion_EspState(void)     { return esp_state; }
uint32_t Motion_Ticks(void)        { return ticks; }
