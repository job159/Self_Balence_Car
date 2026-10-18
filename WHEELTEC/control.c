/*
 * control.c
 *
 *  Created on: Jun 14, 2022
 *      Author: lf
 *
 *  B570 Pilot 改版說明：
 *   - 直立環 PD、速度環 PI、轉向環 PD 的演算法與原廠調好的係數完全保留。
 *   - 原本離散的「前/後/左/右」旗標改成 motion.c 提供的連續設定值（搖桿可細緻控制、加減速平順）。
 *   - 傾倒、低電壓、拿起偵測改為狀態機：停機會記錄原因並回報 App；拿起/傾倒後放下可自動恢復。
 *   - 修正互補濾波模式使用未初始化變數的問題。
 */
#include "control.h"
#include "usart.h"

volatile int      Ctrl_BalancePwm, Ctrl_VelocityPwm, Ctrl_TurnPwm;
volatile float    Ctrl_Heading;
volatile uint16_t Ctrl_LoopUs;
volatile uint8_t  Ctrl_ImuResync;

/**************************************************************************
函式功能：安全檢查（每 10 ms）
          運轉中：傾倒 / 低電壓 / 被拿起 → 停機並記錄原因
          停機中：因拿起、傾倒或剛開機而停機時，偵測到「放下並輕推」→ 自動啟動
**************************************************************************/
static void Safety_Check(int encoder_left, int encoder_right)
{
	if (!Flag_Stop) {
		float tilt = Angle_Balance - Middle_angle;
		if (tilt > Fall_Angle || tilt < -Fall_Angle) {
			Motion_Stop(STOP_FALL);
			Motion_PushEvent(EV_FELL, (int16_t)(Angle_Balance * 100.0f));
		} else if (Voltage > 0 && Voltage < (int)(LowBat_V * 100.0f)) {
			Motion_Stop(STOP_LOWBAT);
			Motion_PushEvent(EV_LOW_BATTERY, (int16_t)Voltage);
		} else if (Pickup_En > 0.5f && Pick_Up(Acceleration_Z, Angle_Balance, encoder_left, encoder_right)) {
			Motion_Stop(STOP_PICKUP);
			Motion_PushEvent(EV_PICKED_UP, 0);
		}
	} else if (Pickup_En > 0.5f) {
		uint8_t r = Motion_StopReason();
		if ((r == STOP_PICKUP || r == STOP_FALL || r == STOP_BOOT) &&
		    Put_Down(Angle_Balance, encoder_left, encoder_right))
			Motion_Start(START_AUTO);
	}
}

/**************************************************************************
函式功能：按鍵事件處理（每 10 ms）
**************************************************************************/
static void Key_Handle(void)
{
	switch (Key_Scan()) {
	case KEY_EVT_CLICK:
		if (Flag_Stop) {
			if (Motion_Start(START_KEY) != ACK_OK) UI_Toast(TOAST_CANT_START);
		} else {
			Motion_Stop(STOP_KEY);
		}
		break;
	case KEY_EVT_DOUBLE:
		UI_NextPage();
		break;
	case KEY_EVT_LONG:
		if (Flag_Stop) UI_Toast(Motion_ZeroCal() == ACK_OK ? TOAST_ZERO_OK : TOAST_ZERO_FAIL);
		else Motion_Stop(STOP_KEY);
		break;
	default:
		break;
	}
}

/**************************************************************************
函式功能：所有控制都在這裡執行。由 MPU6050 INT 腳每 5 ms 觸發外部中斷，
          嚴格保證取樣與控制的時間同步；控制每 10 ms 執行一次。
**************************************************************************/
void HAL_GPIO_EXTI_Callback(uint16_t GPIO_Pin)
{
	static int Voltage_Count, Voltage_All;		//電壓取樣平均
	static u8 Flag_Target;                      //5 ms / 10 ms 分時旗標
	int Encoder_Left, Encoder_Right;
	int Balance_Pwm, Velocity_Pwm, Turn_Pwm;
	uint32_t t0;

	if (GPIO_Pin != MPU6050_INT_Pin) return;
	t0 = DWT_CNT_GET();

	Flag_Target = !Flag_Target;
	if (Ctrl_ImuResync) {                       //寫 Flash 時 CPU 停頓 20~40 ms，DMP FIFO 會積壓舊資料
		Ctrl_ImuResync = 0;
		if (Way_Angle == 1) mpu_reset_fifo();
	}
	Get_Angle(Way_Angle);                       //更新姿態（5 ms 一次，提高濾波效果）
	Encoder_Left = -Read_Encoder(2);            //讀取左輪編碼器（前進為正）
	Encoder_Right = -Read_Encoder(4);           //讀取右輪編碼器（前進為正）
	Get_Velocity_Form_Encoder(Encoder_Left, Encoder_Right);   //輪速 mm/s
	if (delay_flag == 1) {
		if (++delay_50 == 10) delay_50 = 0, delay_flag = 0;   //給主迴圈 50 ms 的精準節拍
	}
	if (Flag_Target == 1) {                     //這個 5 ms 時槽只做電壓取樣
		Voltage_All += Get_battery_volt();
		if (++Voltage_Count == 100) Voltage = Voltage_All / 100, Voltage_All = 0, Voltage_Count = 0;
		__HAL_GPIO_EXTI_CLEAR_IT(MPU6050_INT_Pin);
		return;
	}

	/* ===================== 10 ms 控制時槽 ===================== */
	RC_Link_PollRx();                           //先收完 UART 已到的指令（不依賴 IDLE 中斷）
#if USE_ULTRASONIC
	Read_Distane();
#endif
	Led_Flash(Flag_Stop ? 100 : (Motion_AppLinked() ? 0 : 25));   //停機慢閃、運轉快閃、App 連線恆亮
	Key_Handle();
	Motion_Tick();                              //App 搖桿 / 編排 → 平滑設定值

	Balance_Pwm = Balance(Angle_Balance, Gyro_Balance);           //直立環
	Velocity_Pwm = Velocity(Encoder_Left, Encoder_Right);         //速度環
	Turn_Pwm = Turn(Gyro_Turn);                                   //轉向環

	Motor_Left = PWM_Limit(Balance_Pwm + Velocity_Pwm + Turn_Pwm, PWM_LIMIT, -PWM_LIMIT);
	Motor_Right = PWM_Limit(Balance_Pwm + Velocity_Pwm - Turn_Pwm, PWM_LIMIT, -PWM_LIMIT);

	Safety_Check(Encoder_Left, Encoder_Right);
	if (Turn_Off() == 0) Set_Pwm(Motor_Left, Motor_Right);
	else Motor_Left = Motor_Right = 0;

	Ctrl_BalancePwm = Balance_Pwm;
	Ctrl_VelocityPwm = Velocity_Pwm;
	Ctrl_TurnPwm = Turn_Pwm;
	Ctrl_Heading += Gyro_Turn / 16.4f * 0.01f;  //±2000 dps 量程：16.4 LSB = 1 °/s
	if (Ctrl_Heading > 180.0f) Ctrl_Heading -= 360.0f;
	if (Ctrl_Heading < -180.0f) Ctrl_Heading += 360.0f;
	Ctrl_LoopUs = (uint16_t)((DWT_CNT_GET() - t0) / 72u);

	RC_Link_Tick();                             //遙測 / 事件上傳（DMA 背景送出）
	__HAL_GPIO_EXTI_CLEAR_IT(MPU6050_INT_Pin);
}

/**************************************************************************
函式功能：直立環 PD 控制
入口參數：Angle：傾角，Gyro：角速度
返回值  ：直立環 PWM
**************************************************************************/
int Balance(float Angle, float Gyro)
{
	float Angle_bias, Gyro_bias;
	int balance;
	Angle_bias = Middle_angle - Angle;                            //偏離機械零點的角度
	Gyro_bias = 0 - Gyro;
	balance = -Balance_Kp / 100 * Angle_bias - Gyro_bias * Balance_Kd / 100;   //Kp、Kd 放大 100 倍
	return balance;
}

/**************************************************************************
函式功能：速度環 PI 控制
入口參數：左右輪編碼器讀數（5 ms）
返回值  ：速度環 PWM
說明    ：Movement 由 motion.c 提供（App 搖桿/編排，已做加速度限制），
          穩態時 兩輪計數和 = Movement，即車速 = 目標速度。
**************************************************************************/
int Velocity(int encoder_left, int encoder_right)
{
	static float velocity, Encoder_Least, Encoder_bias;
	static float Encoder_Integral;

	Encoder_Least = 0 - (encoder_left + encoder_right);          //速度偏差 = 目標(0) - 實際
	Encoder_bias *= 0.84f;                                        //一階低通濾波
	Encoder_bias += Encoder_Least * 0.16f;
	Encoder_Integral += Encoder_bias;                             //積分得位移（10 ms）
	Encoder_Integral += Motion_Movement();                        //加入遙控設定值
	if (Encoder_Integral > 10000) Encoder_Integral = 10000;       //積分限幅
	if (Encoder_Integral < -10000) Encoder_Integral = -10000;
	velocity = -Encoder_bias * Velocity_Kp / 100 - Encoder_Integral * Velocity_Ki / 100;
	if (Flag_Stop) Encoder_Integral = 0;                          //停機時清除積分
	return velocity;
}

/**************************************************************************
函式功能：轉向環控制
入口參數：Z 軸角速度
返回值  ：轉向 PWM（正 = 右轉）
說明    ：Kd（陀螺阻尼）只在前進/後退時作用，原地轉向時關閉，同原廠設計。
**************************************************************************/
int Turn(float gyro)
{
	float Turn_Target = Motion_TurnTarget();
	float ts = Motion_TargetSpeed();
	float Kd = (ts > 20.0f || ts < -20.0f) ? Turn_Kd : 0;
	return (int)(Turn_Target * Turn_Kp / 100 + gyro * Kd / 100);
}

/**************************************************************************
函式功能：輸出 PWM 到馬達
**************************************************************************/
void Set_Pwm(int motor_left, int motor_right)
{
	if (motor_left > 0) { BIN1_SET; BIN2_RESET; }    //前進
	else                { BIN1_RESET; BIN2_SET; }    //後退
	PWMB = myabs(motor_left);
	if (motor_right > 0) { AIN2_SET; AIN1_RESET; }   //前進
	else                 { AIN2_RESET; AIN1_SET; }   //後退
	PWMA = myabs(motor_right);
}

/**************************************************************************
函式功能：立即切斷馬達（緊急停止、IMU 失效時使用，不等下一個控制週期）
**************************************************************************/
void Motor_Kill(void)
{
	AIN1_RESET;
	AIN2_RESET;
	BIN1_RESET;
	BIN2_RESET;
	PWMA = 0;
	PWMB = 0;
}

/**************************************************************************
函式功能：PWM 限幅
**************************************************************************/
int PWM_Limit(int IN, int max, int min)
{
	int OUT = IN;
	if (OUT > max) OUT = max;
	if (OUT < min) OUT = min;
	return OUT;
}

/**************************************************************************
函式功能：停機時關閉馬達（傾倒、低電壓已由 Safety_Check 轉成 Flag_Stop）
返回值  ：1 = 已關閉，0 = 正常
**************************************************************************/
u8 Turn_Off(void)
{
	if (Flag_Stop) {
		AIN1_RESET;
		AIN2_RESET;
		BIN1_RESET;
		BIN2_RESET;
		return 1;
	}
	return 0;
}

/**************************************************************************
函式功能：取得姿態角
入口參數：way：1 = DMP  2 = 卡爾曼  3 = 互補濾波
**************************************************************************/
void Get_Angle(u8 way)
{
	short Accel_Y, Accel_Z, Accel_X, Gyro_X, Gyro_Z, Gyro_Y;
	float gyro_x, gyro_y, accel_x, accel_y, accel_z;
	if (way == 1) {                                //DMP 在資料中斷中讀取，嚴格遵循時序
		Read_DMP();
		Angle_Balance = Pitch;                     //前傾為正
		Gyro_Balance = gyro[0];
		Gyro_Turn = gyro[2];
		Acceleration_Z = accel[2];
	} else {
		Gyro_X = (I2C_ReadOneByte(devAddr, MPU6050_RA_GYRO_XOUT_H) << 8) + I2C_ReadOneByte(devAddr, MPU6050_RA_GYRO_XOUT_L);
		Gyro_Y = (I2C_ReadOneByte(devAddr, MPU6050_RA_GYRO_YOUT_H) << 8) + I2C_ReadOneByte(devAddr, MPU6050_RA_GYRO_YOUT_L);
		Gyro_Z = (I2C_ReadOneByte(devAddr, MPU6050_RA_GYRO_ZOUT_H) << 8) + I2C_ReadOneByte(devAddr, MPU6050_RA_GYRO_ZOUT_L);
		Accel_X = (I2C_ReadOneByte(devAddr, MPU6050_RA_ACCEL_XOUT_H) << 8) + I2C_ReadOneByte(devAddr, MPU6050_RA_ACCEL_XOUT_L);
		Accel_Y = (I2C_ReadOneByte(devAddr, MPU6050_RA_ACCEL_YOUT_H) << 8) + I2C_ReadOneByte(devAddr, MPU6050_RA_ACCEL_YOUT_L);
		Accel_Z = (I2C_ReadOneByte(devAddr, MPU6050_RA_ACCEL_ZOUT_H) << 8) + I2C_ReadOneByte(devAddr, MPU6050_RA_ACCEL_ZOUT_L);
		Gyro_Balance = -Gyro_X;
		accel_x = Accel_X / 1671.84;
		accel_y = Accel_Y / 1671.84;
		accel_z = Accel_Z / 1671.84;
		gyro_x = Gyro_X / 939.8;                     //rad/s（16.4 LSB/°/s × 57.3）
		gyro_y = Gyro_Y / 939.8;
		if (way == 3) {
			/* 互補濾波：與卡爾曼相同的角度定義（atan2(-ay, az)），輸入 °、°/s */
			Pitch = Complementary_Filter_x((float)atan2(-accel_y, accel_z) * 57.29578f, -Gyro_X / 16.4f);
			Roll = Complementary_Filter_y((float)atan2(-accel_x, accel_z) * 57.29578f, Gyro_Y / 16.4f);
		} else {
			Pitch = KF_X(accel_y, accel_z, -gyro_x) / PI * 180;   //卡爾曼濾波
			Roll = KF_Y(accel_x, accel_z, gyro_y) / PI * 180;
		}
		Angle_Balance = Pitch;
		Gyro_Turn = Gyro_Z;
		Acceleration_Z = Accel_Z;
	}
}

/**************************************************************************
函式功能：絕對值
**************************************************************************/
int myabs(int a)
{
	return a < 0 ? -a : a;
}

/**************************************************************************
函式功能：偵測車子是否被拿起（原廠演算法）
          1. 輪子接近靜止  2. 車身接近直立且 Z 軸加速度大  3. 輪子空轉
返回值  ：1 = 被拿起
**************************************************************************/
int Pick_Up(float Acceleration, float Angle, int encoder_left, int encoder_right)
{
	static u16 flag, count0, count1, count2;
	if (flag == 0) {
		if (myabs(encoder_left) + myabs(encoder_right) < 30) count0++;
		else count0 = 0;
		if (count0 > 10) flag = 1, count0 = 0;
	}
	if (flag == 1) {
		if (++count1 > 200) count1 = 0, flag = 0;                 //逾時 2 s 回到第一步
		if (Acceleration > 26000 && (Angle > (-20 + Middle_angle)) && (Angle < (20 + Middle_angle))) flag = 2;
	}
	if (flag == 2) {
		if (++count2 > 100) count2 = 0, flag = 0;                 //逾時 1 s
		if (myabs(encoder_left + encoder_right) > 70) {           //輪子空轉到最大速
			flag = 0;
			return 1;
		}
	}
	return 0;
}

/**************************************************************************
函式功能：偵測車子是否被放下（原廠演算法）
          1. 車身接近直立且輪子靜止  2. 輪子被輕推往前轉
返回值  ：1 = 被放下
**************************************************************************/
int Put_Down(float Angle, int encoder_left, int encoder_right)
{
	static u16 flag, count;
	if (Flag_Stop == 0) return 0;
	if (flag == 0) {
		if (Angle > (-10 + Middle_angle) && Angle < (10 + Middle_angle) && encoder_left == 0 && encoder_right == 0) flag = 1;
	}
	if (flag == 1) {
		if (++count > 50) { count = 0; flag = 0; }               //逾時 500 ms
		if (encoder_left > 3 && encoder_right > 3 && encoder_left < 40 && encoder_right < 40) {
			flag = 0;
			return 1;
		}
	}
	return 0;
}

/**************************************************************************
函式功能：編碼器讀數換算輪速 (mm/s)
          轉速 = 讀數 × 讀取頻率 / 倍頻 / 減速比 / 線數；線速度 = 轉速 × 周長
**************************************************************************/
void Get_Velocity_Form_Encoder(int encoder_left, int encoder_right)
{
	float Rotation_Speed_L, Rotation_Speed_R;
	Rotation_Speed_L = encoder_left * Control_Frequency / EncoderMultiples / Reduction_Ratio / Encoder_precision;
	Velocity_Left = Rotation_Speed_L * PI * Diameter_67;
	Rotation_Speed_R = encoder_right * Control_Frequency / EncoderMultiples / Reduction_Ratio / Encoder_precision;
	Velocity_Right = Rotation_Speed_R * PI * Diameter_67;
}
