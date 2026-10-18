/*
 * motion.h
 *
 * 運動指令層：把 App 搖桿 / 動作編排 轉成平滑的速度與轉向目標，
 * 並負責馬達啟停狀態機、失聯保護與事件佇列。原廠的直立/速度/轉向三環控制完全保留，
 * 這裡只提供它們的設定值（Movement、Turn_Target）。
 */
#ifndef INC_MOTION_H_
#define INC_MOTION_H_

#include <stdint.h>

/* 停機原因（遙測 stop_reason） */
enum { STOP_NONE = 0, STOP_BOOT, STOP_KEY, STOP_APP, STOP_PICKUP, STOP_FALL, STOP_LOWBAT, STOP_ESTOP, STOP_IMU };
/* 事件碼（EVENT code） */
enum {
	EV_MOTORS_ON = 1, EV_MOTORS_OFF, EV_PICKED_UP, EV_FELL, EV_LOW_BATTERY, EV_SEQ_DONE,
	EV_SEQ_ABORTED, EV_ZERO_CAL, EV_PARAMS_SAVED, EV_BOOT, EV_LINK_LOST
};
/* 控制來源（遙測 src） */
enum { SRC_NONE = 0, SRC_APP, SRC_SEQ, SRC_BT };
/* 啟動來源（EV_MOTORS_ON arg） */
enum { START_KEY = 1, START_APP, START_AUTO };
/* 編排中止原因（EV_SEQ_ABORTED arg） */
enum { SEQ_ABORT_OVERRIDE = 1, SEQ_ABORT_LINK, SEQ_ABORT_MOTORS, SEQ_ABORT_CMD };

void     Motion_Init(void);
void     Motion_Tick(void);                 /* 控制中斷內，每 10 ms 呼叫一次 */

/* 給原廠控制環的設定值 */
float    Motion_Movement(void);             /* 速度環：兩輪 5 ms 編碼器計數和 */
float    Motion_TurnTarget(void);           /* 轉向環：原廠 Turn_Target 單位 */
float    Motion_TargetSpeed(void);          /* mm/s（經加速度限制） */
float    Motion_TargetTurn(void);           /* 千分比（經變化率限制） */

/* 指令（可在 UART 中斷呼叫），回傳 ACK status */
void     Motion_OnAppFrame(void);
void     Motion_SetAppControl(int16_t speed_mmps, int16_t turn_permille);
uint8_t  Motion_RequestMotor(uint8_t action);   /* 0 停止 1 啟動 2 緊急停止 */
uint8_t  Motion_ZeroCal(void);
uint8_t  Motion_SeqLoad(const uint8_t *p, uint8_t len);
uint8_t  Motion_SeqPlay(uint8_t loops);
void     Motion_SeqStop(void);
void     Motion_SetEspLink(uint8_t state);

/* 控制中斷內使用 */
uint8_t  Motion_Start(uint8_t source);
void     Motion_Stop(uint8_t reason);
void     Motion_ImuFault(void);         /* 主迴圈：IMU 中斷停止時呼叫 */

/* 狀態查詢 */
uint8_t  Motion_StopReason(void);
uint8_t  Motion_AppLinked(void);
uint8_t  Motion_Failsafe(void);
uint8_t  Motion_SeqPlaying(void);
uint8_t  Motion_SeqStep(void);
uint8_t  Motion_SeqLoop(void);
uint8_t  Motion_SeqTotal(void);
uint8_t  Motion_Source(void);
uint8_t  Motion_LinkQuality(void);
uint8_t  Motion_EspState(void);             /* 0/1/2；0xFF = 沒收到 ESP 心跳 */
uint32_t Motion_Ticks(void);

void     Motion_PushEvent(uint8_t code, int16_t arg);
int      Motion_PopEvent(uint8_t *code, int16_t *arg);

#endif /* INC_MOTION_H_ */
