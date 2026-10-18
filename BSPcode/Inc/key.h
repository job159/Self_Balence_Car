/*
 * key.h
 *
 *  Created on: Jun 14, 2022
 *      Author: lf
 *  B570 Pilot：改為事件式按鍵（單擊 / 雙擊 / 長按），每 10 ms 呼叫 Key_Scan()。
 */

#ifndef INC_KEY_H_
#define INC_KEY_H_
#include "main.h"

#define KEY	 HAL_GPIO_ReadPin(User_key_GPIO_Port, User_key_Pin)

enum {
	KEY_EVT_NONE = 0,
	KEY_EVT_CLICK,      /* 單擊：啟動 / 停止平衡 */
	KEY_EVT_DOUBLE,     /* 雙擊：切換 OLED 頁面 */
	KEY_EVT_LONG        /* 長按 2 秒：機械零點校正（馬達停止時） */
};

u8 Key_Scan(void);

#endif /* INC_KEY_H_ */
