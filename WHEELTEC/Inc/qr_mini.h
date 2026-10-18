/*
 * qr_mini.h
 *
 * 精簡 QR Code 產生器（Model 2，版本 1~3，Byte 模式，ECC L/M，單一區塊）。
 * 專為 128x64 OLED 顯示 Wi-Fi 配對碼設計：版本 3 = 29x29 模組，2 px/模組 = 58 px。
 * 不使用動態記憶體，可在主機端編譯測試（tools/ 內的測試程式）。
 */
#ifndef INC_QR_MINI_H_
#define INC_QR_MINI_H_

#include <stdint.h>

#define QR_MAX_SIZE 29   /* 版本 3 */

typedef struct {
	uint8_t  size;               /* 21 / 25 / 29，0 = 無效 */
	uint8_t  version;            /* 1..3 */
	uint8_t  ecc;                /* 0 = L, 1 = M */
	uint8_t  mask;               /* 0..7 */
	uint32_t rows[QR_MAX_SIZE];  /* rows[y] 的 bit x = 模組 (x,y) 為深色 */
} qr_t;

/* 編碼文字（最多 53 bytes）。成功回傳 1；資料太長回傳 0。 */
int qr_encode(const char *text, qr_t *qr);

static inline int qr_module(const qr_t *qr, int x, int y)
{
	return (int)((qr->rows[y] >> x) & 1u);
}

#endif /* INC_QR_MINI_H_ */
