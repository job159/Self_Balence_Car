/*
 * params.h
 *
 * 可由 App 整定、並存入 Flash 的參數表（id 定義見 docs/PROTOCOL.md §4.2）。
 * PID 仍使用原廠全域變數（Balance_Kp...），這裡只是統一管理、夾限與存檔。
 */
#ifndef INC_PARAMS_H_
#define INC_PARAMS_H_

#include <stdint.h>

enum {
	P_BAL_KP = 0, P_BAL_KD, P_VEL_KP, P_VEL_KI, P_TURN_KP, P_TURN_KD,
	P_ZERO_ANGLE, P_MAX_SPEED, P_MAX_TURN, P_ACCEL, P_TURN_ACCEL,
	P_FAILSAFE_MS, P_TELEM_HZ, P_ANGLE_ALGO, P_PICKUP_EN, P_LOWBAT_V, P_FALL_ANGLE,
	PARAM_COUNT
};

/* Flash 最後一頁（1 KB）保留給參數；連結腳本已把程式區縮為 63 KB */
#ifndef PARAMS_FLASH_ADDR
#define PARAMS_FLASH_ADDR   0x0800FC00UL
#endif

extern float Middle_angle;      /* 機械零點（原本是巨集，現在可調） */
extern float Max_Speed;         /* mm/s */
extern float Max_Turn;          /* 原廠轉向單位 */
extern float Accel_Limit;       /* mm/s^2 */
extern float Turn_Accel_Limit;  /* 千分比/s */
extern float Failsafe_Ms;
extern float Telem_Hz;
extern float Angle_Algo;
extern float Pickup_En;
extern float LowBat_V;
extern float Fall_Angle;

extern volatile uint8_t Params_Dirty;   /* RAM 與 Flash 不同 */

float   Params_Get(uint8_t id);
float   Params_Set(uint8_t id, float value);   /* 夾限後套用，回傳實際值 */
void    Params_Defaults(void);
void    Params_Load(void);                     /* 開機時呼叫 */
uint8_t Params_Save(void);                     /* 回傳 ACK status；只能在馬達停止時呼叫 */

#endif /* INC_PARAMS_H_ */
