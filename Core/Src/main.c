/* USER CODE BEGIN Header */
/**
  ******************************************************************************
  * @file           : main.c
  * @brief          : Main program body
  ******************************************************************************
  * @attention
  *
  * <h2><center>&copy; Copyright (c) 2022 STMicroelectronics.
  * All rights reserved.</center></h2>
  *
  * This software component is licensed by ST under BSD 3-Clause license,
  * the "License"; You may not use this file except in compliance with the
  * License. You may obtain a copy of the License at:
  *                        opensource.org/licenses/BSD-3-Clause
  *
  ******************************************************************************
  */
/* USER CODE END Header */
/* Includes ------------------------------------------------------------------*/
#include "main.h"
#include "adc.h"
#include "dma.h"
#include "tim.h"
#include "usart.h"
#include "gpio.h"

/* Private includes ----------------------------------------------------------*/
/* USER CODE BEGIN Includes */

/* USER CODE END Includes */

/* Private typedef -----------------------------------------------------------*/
/* USER CODE BEGIN PTD */

/* USER CODE END PTD */

/* Private define ------------------------------------------------------------*/
/* USER CODE BEGIN PD */
/* USER CODE END PD */

/* Private macro -------------------------------------------------------------*/
/* USER CODE BEGIN PM */

/* USER CODE END PM */

/* Private variables ---------------------------------------------------------*/

/* USER CODE BEGIN PV */
u8 Way_Angle=2;                             //姿態演算法：1 DMP  2 卡爾曼  3 互補濾波（由參數 ANGLE_ALGO 設定）
u8 Flag_front,Flag_back,Flag_Left,Flag_Right,Flag_velocity=2; //原廠藍牙遙控旗標（APP_LEGACY_BT）
volatile u8 Flag_Stop=1;                    //1 = 馬達停止（開機預設停止）
int Motor_Left,Motor_Right;                 //馬達 PWM
int Temperature;                            //溫度
int Voltage;                                //電池電壓（0.01 V）
float Angle_Balance,Gyro_Balance,Gyro_Turn; //平衡傾角、平衡角速度、轉向角速度
u32 Distance;                               //超音波測距
u8 PID_Send;
volatile u8 delay_50,delay_flag;            //主迴圈 50 ms 節拍
float Velocity_Left,Velocity_Right;         //輪速 mm/s
float Acceleration_Z;                       //Z 軸加速度
float Balance_Kp=22500,Balance_Kd=108,Velocity_Kp=16000,Velocity_Ki=80,Turn_Kp=4200,Turn_Kd=0; //PID（放大 100 倍），開機後由 Params_Load() 覆蓋
/* USER CODE END PV */

/* Private function prototypes -----------------------------------------------*/
void SystemClock_Config(void);
/* USER CODE BEGIN PFP */

/* USER CODE END PFP */

/* Private user code ---------------------------------------------------------*/
/* USER CODE BEGIN 0 */

/* USER CODE END 0 */

/**
  * @brief  The application entry point.
  * @retval int
  */
int main(void)
{
  /* USER CODE BEGIN 1 */

  /* USER CODE END 1 */

  /* MCU Configuration--------------------------------------------------------*/

  /* Reset of all peripherals, Initializes the Flash interface and the Systick. */
  HAL_Init();

  /* USER CODE BEGIN Init */

  /* USER CODE END Init */

  /* Configure the system clock */
  SystemClock_Config();

  /* USER CODE BEGIN SysInit */

  /* USER CODE END SysInit */

  /* Initialize all configured peripherals */
  MX_GPIO_Init();
  MX_DMA_Init();
  MX_ADC1_Init();
  MX_TIM1_Init();
  MX_TIM2_Init();
  MX_TIM3_Init();
  MX_TIM4_Init();
  MX_USART1_UART_Init();
  MX_USART3_UART_Init();
  MX_USART2_UART_Init();
  /* USER CODE BEGIN 2 */
  HAL_NVIC_DisableIRQ(EXTI15_10_IRQn); //先關閉 MPU6050 外部中斷，避免初始化過程中進入控制
  delay_init();                        //釋放 SysTick，HAL 時基改用 DWT

  HAL_ADCEx_Calibration_Start(&hadc1); //ADC 校準（電壓量測）

  /* 馬達 PWM：TIM1 CH1 = 右輪，CH4 = 左輪 */
  HAL_TIM_PWM_Start(&htim1,TIM_CHANNEL_1);
  HAL_TIM_PWM_Start(&htim1,TIM_CHANNEL_4);

  /* 編碼器：TIM2 左輪、TIM4 右輪 */
  HAL_TIM_Encoder_Start(&htim2,TIM_CHANNEL_ALL);
  HAL_TIM_Encoder_Start(&htim4,TIM_CHANNEL_ALL);

#if USE_ULTRASONIC
  HAL_TIM_IC_Start_IT(&htim3,TIM_CHANNEL_3); //超音波回波輸入捕獲
  HAL_TIM_Base_Start_IT(&htim3);
#endif

  OLED_Init();
  UI_Boot();                           //開機畫面
  Params_Load();                       //從 Flash 載入整定參數（無存檔則用預設）
  RC_Link_Init();                      //USART2 ⇄ ESP-01（DMA 收發），先啟動才收得到 ESP 開機送來的帳密
  MPU6050_initialize();
  DMP_Init();                          //約 1 秒，期間請保持車身靜止
  Motion_Init();

#if APP_LEGACY_BT
  RetargetInit(&huart3);               //printf → USART3 給原廠藍牙 APP
  HAL_UART_Receive_IT(&huart3,Usart3_Receive_buf,sizeof(Usart3_Receive_buf));
#endif

  HAL_NVIC_EnableIRQ(EXTI15_10_IRQn);  //開始 200 Hz 控制
  /* USER CODE END 2 */

  /* Infinite loop */
  /* USER CODE BEGIN WHILE */
  while (1)
  {
	UI_Task();                         //OLED 介面
	RC_Link_Task();                    //Flash 存檔等耗時工作、接收看門狗
#if APP_LEGACY_BT
	APP_Show();
#endif
	/* 50 ms 節拍由 MPU6050 中斷提供；IMU 異常時最多等 100 ms，介面仍會更新 */
	delay_flag=1;
	delay_50=0;
	{
	  static uint8_t imu_timeouts;
	  uint32_t t0 = DWT_CNT_GET();
	  while(delay_flag && (DWT_CNT_GET() - t0) < 7200000u);
	  if (delay_flag) {                  //控制中斷沒有在跑（IMU 斷線/當機）：馬達不能停在最後的 PWM
		if (++imu_timeouts >= 2) { Motion_ImuFault(); RC_Link_Tick(); }
	  } else imu_timeouts = 0;
	}
    /* USER CODE END WHILE */

    /* USER CODE BEGIN 3 */
  }
  /* USER CODE END 3 */
}

/**
  * @brief System Clock Configuration
  * @retval None
  */
void SystemClock_Config(void)
{
  RCC_OscInitTypeDef RCC_OscInitStruct = {0};
  RCC_ClkInitTypeDef RCC_ClkInitStruct = {0};
  RCC_PeriphCLKInitTypeDef PeriphClkInit = {0};

  /** Initializes the RCC Oscillators according to the specified parameters
  * in the RCC_OscInitTypeDef structure.
  */
  RCC_OscInitStruct.OscillatorType = RCC_OSCILLATORTYPE_HSE;
  RCC_OscInitStruct.HSEState = RCC_HSE_ON;
  RCC_OscInitStruct.HSEPredivValue = RCC_HSE_PREDIV_DIV1;
  RCC_OscInitStruct.HSIState = RCC_HSI_ON;
  RCC_OscInitStruct.PLL.PLLState = RCC_PLL_ON;
  RCC_OscInitStruct.PLL.PLLSource = RCC_PLLSOURCE_HSE;
  RCC_OscInitStruct.PLL.PLLMUL = RCC_PLL_MUL9;
  if (HAL_RCC_OscConfig(&RCC_OscInitStruct) != HAL_OK)
  {
    Error_Handler();
  }

  /** Initializes the CPU, AHB and APB buses clocks
  */
  RCC_ClkInitStruct.ClockType = RCC_CLOCKTYPE_HCLK|RCC_CLOCKTYPE_SYSCLK
                              |RCC_CLOCKTYPE_PCLK1|RCC_CLOCKTYPE_PCLK2;
  RCC_ClkInitStruct.SYSCLKSource = RCC_SYSCLKSOURCE_PLLCLK;
  RCC_ClkInitStruct.AHBCLKDivider = RCC_SYSCLK_DIV1;
  RCC_ClkInitStruct.APB1CLKDivider = RCC_HCLK_DIV2;
  RCC_ClkInitStruct.APB2CLKDivider = RCC_HCLK_DIV1;

  if (HAL_RCC_ClockConfig(&RCC_ClkInitStruct, FLASH_LATENCY_2) != HAL_OK)
  {
    Error_Handler();
  }
  PeriphClkInit.PeriphClockSelection = RCC_PERIPHCLK_ADC;
  PeriphClkInit.AdcClockSelection = RCC_ADCPCLK2_DIV6;
  if (HAL_RCCEx_PeriphCLKConfig(&PeriphClkInit) != HAL_OK)
  {
    Error_Handler();
  }
}

/* USER CODE BEGIN 4 */

/* USER CODE END 4 */

/**
  * @brief  This function is executed in case of error occurrence.
  * @retval None
  */
void Error_Handler(void)
{
  /* USER CODE BEGIN Error_Handler_Debug */
  /* User can add his own implementation to report the HAL error return state */
  __disable_irq();
  while (1)
  {
  }
  /* USER CODE END Error_Handler_Debug */
}

#ifdef  USE_FULL_ASSERT
/**
  * @brief  Reports the name of the source file and the source line number
  *         where the assert_param error has occurred.
  * @param  file: pointer to the source file name
  * @param  line: assert_param error line source number
  * @retval None
  */
void assert_failed(uint8_t *file, uint32_t line)
{
  /* USER CODE BEGIN 6 */
  /* User can add his own implementation to report the file name and line number,
     ex: printf("Wrong parameters value: file %s on line %d\r\n", file, line) */
  /* USER CODE END 6 */
}
#endif /* USE_FULL_ASSERT */
