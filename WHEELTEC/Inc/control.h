/*
 * control.h
 *
 *  Created on: Jun 14, 2022
 *      Author: lf
 *  B570 Pilot：保留原廠直立環 / 速度環 / 轉向環，設定值改由 motion.c 提供。
 */

#ifndef INC_CONTROL_H_
#define INC_CONTROL_H_
#include "main.h"

//馬達驅動腳位
#define AIN1_SET    HAL_GPIO_WritePin(AIN1_GPIO_Port, AIN1_Pin, GPIO_PIN_SET)
#define AIN1_RESET  HAL_GPIO_WritePin(AIN1_GPIO_Port, AIN1_Pin, GPIO_PIN_RESET)
#define AIN2_SET    HAL_GPIO_WritePin(AIN2_GPIO_Port, AIN2_Pin, GPIO_PIN_SET)
#define AIN2_RESET  HAL_GPIO_WritePin(AIN2_GPIO_Port, AIN2_Pin, GPIO_PIN_RESET)
#define BIN1_SET    HAL_GPIO_WritePin(BIN1_GPIO_Port, BIN1_Pin, GPIO_PIN_SET)
#define BIN1_RESET  HAL_GPIO_WritePin(BIN1_GPIO_Port, BIN1_Pin, GPIO_PIN_RESET)
#define BIN2_SET    HAL_GPIO_WritePin(BIN2_GPIO_Port, BIN2_Pin, GPIO_PIN_SET)
#define BIN2_RESET  HAL_GPIO_WritePin(BIN2_GPIO_Port, BIN2_Pin, GPIO_PIN_RESET)
#define PWMA TIM1->CCR1
#define PWMB TIM1->CCR4

#define PI 3.14159265							//圓周率
#define Control_Frequency  200.0	//編碼器讀取頻率（5 ms）
#define Diameter_67  67.0 				//輪徑 67 mm
#define EncoderMultiples   4.0 		//編碼器倍頻
#define Encoder_precision  13.0 	//編碼器線數 13
#define Reduction_Ratio  30.0			//減速比 30
#define Perimeter  210.4867 			//輪周長 mm

/* 速度環設定值換算：Movement（兩輪 5 ms 計數和）每 1 單位 = 多少 mm/s */
#define MMPS_PER_MOVEMENT  ((float)(Control_Frequency * PI * Diameter_67 / (EncoderMultiples * Encoder_precision * Reduction_Ratio)) / 2.0f)
#define PWM_LIMIT  6900

/* 給遙測 / OLED 的控制環內部量 */
extern volatile int      Ctrl_BalancePwm, Ctrl_VelocityPwm, Ctrl_TurnPwm;
extern volatile float    Ctrl_Heading;     /* Z 軸陀螺積分航向（度，會漂移） */
extern volatile uint16_t Ctrl_LoopUs;      /* 控制中斷耗時 */
extern volatile uint8_t  Ctrl_ImuResync;   /* 寫 Flash 後設 1：DMP 模式下清空 MPU FIFO 積壓 */

void Motor_Kill(void);                     /* 立即切斷馬達（任何中斷/主迴圈皆可呼叫） */

int Balance(float angle,float gyro);
int Velocity(int encoder_left,int encoder_right);
int Turn(float gyro);
void Set_Pwm(int motor_left,int motor_right);
int PWM_Limit(int IN,int max,int min);
u8 Turn_Off(void);
void Get_Angle(u8 way);
int myabs(int a);
int Pick_Up(float Acceleration,float Angle,int encoder_left,int encoder_right);
int Put_Down(float Angle,int encoder_left,int encoder_right);
void Get_Velocity_Form_Encoder(int encoder_left,int encoder_right);

#endif /* INC_CONTROL_H_ */
