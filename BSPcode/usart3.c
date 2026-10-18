/*
 * usart3.c
 *
 *  Created on: Jun 14, 2022
 *      Author: lf
 *  原廠 MiniBalance 藍牙 APP（USART3 9600）。B570 Pilot 預設關閉，
 *  需要時在 app_config.h 設定 APP_LEGACY_BT = 1。
 */
#include "usart3.h"
#include "usart.h"

u8 Usart3_Receive_buf[1];          //串口3接收中斷資料緩衝
u8 Usart3_Receive;                 //從串口3讀到的資料

#if APP_LEGACY_BT
void HAL_UART_RxCpltCallback(UART_HandleTypeDef *UartHandle) //接收回呼
{
	if(UartHandle == &huart3)
	{
		static u8 Flag_PID,i,Receive[50];
		int uart_receive=Usart3_Receive_buf[0];
		Usart3_Receive=uart_receive;
		if(uart_receive==0x59)  Flag_velocity=2;  //低速檔（預設）
		if(uart_receive==0x58)  Flag_velocity=1;  //高速檔

		if(uart_receive>10)  //MiniBalance V3.5 之後的遙控指令
		{
			if(uart_receive==0x5A)	    Flag_front=0,Flag_back=0,Flag_Left=0,Flag_Right=0;//煞車
			else if(uart_receive==0x41)	Flag_front=1,Flag_back=0,Flag_Left=0,Flag_Right=0;//前
			else if(uart_receive==0x45)	Flag_front=0,Flag_back=1,Flag_Left=0,Flag_Right=0;//後
			else if(uart_receive==0x42||uart_receive==0x43||uart_receive==0x44)
										Flag_front=0,Flag_back=0,Flag_Left=0,Flag_Right=1;  //右
			else if(uart_receive==0x46||uart_receive==0x47||uart_receive==0x48)
										Flag_front=0,Flag_back=0,Flag_Left=1,Flag_Right=0;  //左
			else Flag_front=0,Flag_back=0,Flag_Left=0,Flag_Right=0;//煞車
		}
		if(uart_receive<10)     //MiniBalance V1.0 的遙控指令為 0x00~0x08
		{
			Flag_velocity=1;//切換到高速檔
			if(uart_receive==0x00)	Flag_front=0,Flag_back=0,Flag_Left=0,Flag_Right=0;//煞車
			else if(uart_receive==0x01)	Flag_front=1,Flag_back=0,Flag_Left=0,Flag_Right=0;//前
			else if(uart_receive==0x05)	Flag_front=0,Flag_back=1,Flag_Left=0,Flag_Right=0;//後
			else if(uart_receive==0x02||uart_receive==0x03||uart_receive==0x04)
										Flag_front=0,Flag_back=0,Flag_Left=0,Flag_Right=1;  //右
			else if(uart_receive==0x06||uart_receive==0x07||uart_receive==0x08)
										Flag_front=0,Flag_back=0,Flag_Left=1,Flag_Right=0;  //左
			else Flag_front=0,Flag_back=0,Flag_Left=0,Flag_Right=0;//煞車
		}

		if(Usart3_Receive==0x7B) Flag_PID=1;   //APP 調參指令起始位
		if(Usart3_Receive==0x7D) Flag_PID=2;   //APP 調參指令停止位

		if(Flag_PID==1 && i<sizeof(Receive))  //採集資料
		{
			Receive[i++]=Usart3_Receive;
		}
		if(Flag_PID==2)  //分析資料（格式：{ id # 數字... }）
		{
			if(Receive[3]==0x50) PID_Send=1;
			else if(Receive[1]!=0x23 && i>3)
			{
				int32_t Data=0;
				for(u8 j=3;j<i;j++) Data=Data*10+(Receive[j]-'0');   //數字在 Receive[3..i-1]；取代 pow()，省下約 3 KB Flash
				switch(Receive[1])
				{
					case 0x30:  Params_Set(P_BAL_KP,(float)Data);break;
					case 0x31:  Params_Set(P_BAL_KD,(float)Data);break;
					case 0x32:  Params_Set(P_VEL_KP,(float)Data);break;
					case 0x33:  Params_Set(P_VEL_KI,(float)Data);break;
					case 0x34:  Params_Set(P_TURN_KP,(float)Data);break;
					case 0x35:  Params_Set(P_TURN_KD,(float)Data);break;
					default: break;
				}
			}
			Flag_PID=0;
			i=0;
			memset(Receive, 0, sizeof(Receive));
		}

		HAL_UART_Receive_IT(&huart3,Usart3_Receive_buf,sizeof(Usart3_Receive_buf));//重新開啟接收中斷
	}
}
#endif
