/*
 * key.c
 *
 *  Created on: Jun 14, 2022
 *      Author: lf
 *  B570 Pilot：事件式按鍵狀態機（含去彈跳），取代原本的 click_N_Double / Long_Press。
 */
#include "key.h"

#define DEBOUNCE_TICKS   2     /* 20 ms */
#define DOUBLE_GAP_TICKS 30    /* 放開後 300 ms 內再按 = 雙擊 */
#define LONG_TICKS       200   /* 按住 2 s = 長按 */

enum { K_IDLE = 0, K_PRESSED, K_WAIT_SECOND, K_SECOND, K_LONG_HELD };

/**************************************************************************
函式功能：按鍵掃描（每 10 ms 呼叫一次）
返回值  ：KEY_EVT_NONE / CLICK / DOUBLE / LONG
**************************************************************************/
u8 Key_Scan(void)
{
	static u8 state = K_IDLE;
	static u16 cnt;
	u8 down = (KEY == 0);

	switch (state) {
	case K_IDLE:
		if (down) { state = K_PRESSED; cnt = 0; }
		break;
	case K_PRESSED:
		if (down) {
			if (++cnt >= LONG_TICKS) { state = K_LONG_HELD; return KEY_EVT_LONG; }
		} else {
			state = (cnt >= DEBOUNCE_TICKS) ? K_WAIT_SECOND : K_IDLE;
			cnt = 0;
		}
		break;
	case K_WAIT_SECOND:
		if (down) { state = K_SECOND; cnt = 0; }
		else if (++cnt > DOUBLE_GAP_TICKS) { state = K_IDLE; return KEY_EVT_CLICK; }
		break;
	case K_SECOND:
		if (!down) {
			state = K_IDLE;
			return (cnt >= DEBOUNCE_TICKS - 1) ? KEY_EVT_DOUBLE : KEY_EVT_CLICK;
		}
		cnt++;
		break;
	default:  /* K_LONG_HELD：等放開 */
		if (!down) state = K_IDLE;
		break;
	}
	return KEY_EVT_NONE;
}
