/* 主機端測試用的 main.h 替身：只提供韌體邏輯模組需要的型別 / 全域變數 / HAL 介面 */
#ifndef __MAIN_H
#define __MAIN_H
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <stddef.h>
#include <math.h>

typedef uint8_t u8;
typedef uint16_t u16;
typedef uint32_t u32;

typedef enum { HAL_OK = 0, HAL_ERROR, HAL_BUSY, HAL_TIMEOUT } HAL_StatusTypeDef;
typedef enum { HAL_UART_STATE_RESET = 0, HAL_UART_STATE_READY = 0x20, HAL_UART_STATE_BUSY_TX = 0x21, HAL_UART_STATE_BUSY_RX = 0x22 } HAL_UART_StateTypeDef;
typedef struct { int dummy; } USART_TypeDef;
extern USART_TypeDef usart2_inst, usart3_inst;
#define USART2 (&usart2_inst)
#define USART3 (&usart3_inst)
typedef struct { volatile uint32_t ndtr; } DMA_HandleTypeDef;
#define __HAL_DMA_GET_COUNTER(h) ((h)->ndtr)
typedef struct { USART_TypeDef *Instance; volatile HAL_UART_StateTypeDef gState, RxState; DMA_HandleTypeDef *hdmarx; } UART_HandleTypeDef;
HAL_StatusTypeDef HAL_UART_Transmit_DMA(UART_HandleTypeDef *h, const uint8_t *p, uint16_t n);
HAL_StatusTypeDef HAL_UARTEx_ReceiveToIdle_DMA(UART_HandleTypeDef *h, uint8_t *p, uint16_t n);

static inline uint32_t __get_PRIMASK(void) { return 0; }
static inline void __disable_irq(void) {}
static inline void __set_PRIMASK(uint32_t v) { (void)v; }

typedef struct { uint32_t TypeErase, PageAddress, NbPages; } FLASH_EraseInitTypeDef;
#define FLASH_TYPEERASE_PAGES 0
#define FLASH_TYPEPROGRAM_HALFWORD 1
HAL_StatusTypeDef HAL_FLASH_Unlock(void);
HAL_StatusTypeDef HAL_FLASH_Lock(void);
HAL_StatusTypeDef HAL_FLASHEx_Erase(FLASH_EraseInitTypeDef *e, uint32_t *err);
HAL_StatusTypeDef HAL_FLASH_Program(uint32_t type, uint32_t addr, uint64_t data);
extern uint8_t fake_flash[1024];
#define PARAMS_FLASH_ADDR ((uintptr_t)fake_flash)

#include "app_config.h"
#include "proto.h"
#include "params.h"
#include "motion.h"
#include "rc_link.h"

#define MMPS_PER_MOVEMENT 13.4927f

extern u8 Way_Angle, Flag_front, Flag_back, Flag_Left, Flag_Right, Flag_velocity;
extern volatile u8 Flag_Stop;
extern int Motor_Left, Motor_Right, Voltage;
extern float Angle_Balance, Gyro_Balance, Gyro_Turn, Roll, Velocity_Left, Velocity_Right;
extern float Balance_Kp, Balance_Kd, Velocity_Kp, Velocity_Ki, Turn_Kp, Turn_Kd;
extern volatile int Ctrl_BalancePwm, Ctrl_VelocityPwm, Ctrl_TurnPwm;
extern volatile float Ctrl_Heading;
extern volatile uint16_t Ctrl_LoopUs;
extern volatile uint8_t Ctrl_ImuResync;
void Motor_Kill(void);
#endif
