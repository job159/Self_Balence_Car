/*
 * app_config.h
 *
 * B570 Pilot 韌體功能開關與常數（集中在這裡調整）。
 */
#ifndef INC_APP_CONFIG_H_
#define INC_APP_CONFIG_H_

#define FW_VER_MAJOR        2
#define FW_VER_MINOR        1
#define FW_VER_PATCH        0
#define PROTO_VERSION       1
#define MODEL_NAME          "B570"

/* ESP-01 接在 USART2（PA2=TX → ESP RX，PA3=RX ← ESP TX），鮑率在 CubeMX/usart.c 設為 230400，
   必須與 ESP01/B570_Bridge 的 LINK_BAUD 相同。 */

#define USE_ULTRASONIC      0   /* 1 = 啟用超音波測距（PB0/PB1），預設不使用 */
#define APP_LEGACY_BT       0   /* 1 = 保留原廠 MiniBalance 藍牙 APP（USART3 9600） */

/* 若實車測試發現方向相反，改成 1 即可，不必改 App */
#define MOTION_INVERT_SPEED 0
#define MOTION_INVERT_TURN  0

#define SEQ_MAX_STEPS       48
#define BAT_FULL_CV         1260  /* 3S 鋰電滿電 12.60 V（OLED 電量條用） */
#define BAT_EMPTY_CV        1000

#endif /* INC_APP_CONFIG_H_ */
