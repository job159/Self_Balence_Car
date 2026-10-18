/*
 * rc_link.h
 *
 * USART2 ⇄ ESP-01 通訊：DMA 環形接收 + DMA 佇列傳送、指令分派、遙測/事件上傳。
 */
#ifndef INC_RC_LINK_H_
#define INC_RC_LINK_H_

#include <stdint.h>

void        RC_Link_Init(void);
void        RC_Link_Tick(void);      /* 控制中斷內，每 10 ms：遙測、事件、CRED_REQ */
void        RC_Link_PollRx(void);    /* 控制中斷開頭呼叫：主動處理 DMA 已收到的位元組（不依賴 IDLE 中斷，指令最多延遲 10 ms） */
void        RC_Link_Task(void);      /* 主迴圈：Flash 存檔等耗時工作、接收看門狗 */
void        RC_Link_RequestSave(uint8_t reply_ack);   /* 交給主迴圈寫 Flash；reply_ack=1 會回 ACK(PARAM_SAVE) */

/* ESP 回報的 Wi-Fi 帳密（OLED 配對 QR 用） */
uint8_t     RC_Link_CredVersion(void);   /* 0 = 尚未取得，每次更新 +1 */
void        RC_Link_GetCred(char *ssid, uint8_t ssid_sz, char *pw, uint8_t pw_sz);
uint32_t    RC_Link_CrcErrors(void);

/* ESP 回報的網路模式（NET）：0 直連、1 搜尋熱點中、2 熱點已連上、3 找不到熱點已改回直連；0xFF = 尚未收到 */
uint8_t     RC_Link_NetMode(void);
void        RC_Link_GetNet(uint8_t ip[4], char *ssid, uint8_t ssid_sz);

#endif /* INC_RC_LINK_H_ */
