/*
 * show.h
 *
 *  Created on: Jun 14, 2022
 *      Author: lf
 *  B570 Pilot：OLED 多頁面介面（儀表板 / 配對 QR / 參數資訊）。
 */

#ifndef INC_SHOW_H_
#define INC_SHOW_H_
#include "main.h"

enum { TOAST_NONE = 0, TOAST_CANT_START, TOAST_ZERO_OK, TOAST_ZERO_FAIL };

void UI_Boot(void);              /* 開機畫面 */
void UI_Task(void);              /* 主迴圈每 50 ms 呼叫：繪製目前頁面 */
void UI_NextPage(void);          /* 雙擊按鍵切換頁面（可在中斷呼叫） */
void UI_Toast(uint8_t id);       /* 短暫提示（可在中斷呼叫） */

#if APP_LEGACY_BT
void APP_Show(void);             /* 原廠 MiniBalance 藍牙 APP 資料上傳 */
#endif

#endif /* INC_SHOW_H_ */
