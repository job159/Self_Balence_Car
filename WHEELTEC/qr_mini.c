/*
 * qr_mini.c
 *
 * 精簡 QR Code 產生器：版本 1~3、Byte 模式、ECC L/M（這些組合都只有一個 RS 區塊，不需交錯）。
 * 演算法依 ISO/IEC 18004，結構參考 Nayuki QR Code generator（MIT）。
 */
#include "qr_mini.h"
#include <string.h>

/* [version-1][ecc]：資料碼字數 / 錯誤更正碼字數（ecc 0 = L, 1 = M） */
static const uint8_t DATA_CW[3][2] = { {19, 16}, {34, 28}, {55, 44} };
static const uint8_t ECC_CW[3][2]  = { { 7, 10}, {10, 16}, {15, 26} };
static const uint8_t ECC_FORMAT_BITS[2] = { 1, 0 };   /* L = 01, M = 00 */

static uint32_t fn_rows[QR_MAX_SIZE];   /* 功能模組遮罩（finder/timing/format...） */

/* ---------------------------------------------------------------- module helpers */
static void set_mod(qr_t *q, int x, int y, int dark)
{
	if (dark) q->rows[y] |= (1u << x);
	else      q->rows[y] &= ~(1u << x);
}

static void set_fn(qr_t *q, int x, int y, int dark)
{
	set_mod(q, x, y, dark);
	fn_rows[y] |= (1u << x);
}

static int is_fn(int x, int y)
{
	return (int)((fn_rows[y] >> x) & 1u);
}

/* ---------------------------------------------------------------- Reed-Solomon, GF(2^8) poly 0x11D */
static uint8_t gf_mul(uint8_t x, uint8_t y)
{
	uint8_t z = 0;
	for (int i = 7; i >= 0; i--) {
		z = (uint8_t)((z << 1) ^ ((z >> 7) * 0x1D));
		z ^= (uint8_t)(((y >> i) & 1) * x);
	}
	return z;
}

static void rs_compute(const uint8_t *data, int dlen, uint8_t *ecc, int degree)
{
	uint8_t gen[26];
	uint8_t root = 1;

	memset(gen, 0, sizeof(gen));
	gen[degree - 1] = 1;
	for (int i = 0; i < degree; i++) {
		for (int j = 0; j < degree; j++) {
			gen[j] = gf_mul(gen[j], root);
			if (j + 1 < degree) gen[j] ^= gen[j + 1];
		}
		root = gf_mul(root, 0x02);
	}

	memset(ecc, 0, (size_t)degree);
	for (int i = 0; i < dlen; i++) {
		uint8_t factor = data[i] ^ ecc[0];
		memmove(&ecc[0], &ecc[1], (size_t)(degree - 1));
		ecc[degree - 1] = 0;
		for (int j = 0; j < degree; j++) ecc[j] ^= gf_mul(gen[j], factor);
	}
}

/* ---------------------------------------------------------------- function patterns */
static void draw_format(qr_t *q, int ecc, int mask)
{
	int size = q->size;
	uint32_t data = ((uint32_t)ECC_FORMAT_BITS[ecc] << 3) | (uint32_t)mask;
	uint32_t rem = data;
	for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >> 9) * 0x537u);
	uint32_t bits = ((data << 10) | rem) ^ 0x5412u;

	for (int i = 0; i <= 5; i++) set_fn(q, 8, i, (bits >> i) & 1);
	set_fn(q, 8, 7, (bits >> 6) & 1);
	set_fn(q, 8, 8, (bits >> 7) & 1);
	set_fn(q, 7, 8, (bits >> 8) & 1);
	for (int i = 9; i < 15; i++) set_fn(q, 14 - i, 8, (bits >> i) & 1);

	for (int i = 0; i < 8; i++) set_fn(q, size - 1 - i, 8, (bits >> i) & 1);
	for (int i = 8; i < 15; i++) set_fn(q, 8, size - 15 + i, (bits >> i) & 1);
	set_fn(q, 8, size - 8, 1);   /* dark module */
}

static void draw_finder(qr_t *q, int cx, int cy)
{
	for (int dy = -4; dy <= 4; dy++) {
		for (int dx = -4; dx <= 4; dx++) {
			int x = cx + dx, y = cy + dy;
			int adx = dx < 0 ? -dx : dx, ady = dy < 0 ? -dy : dy;
			int dist = adx > ady ? adx : ady;
			if (x >= 0 && x < q->size && y >= 0 && y < q->size)
				set_fn(q, x, y, dist != 2 && dist != 4);
		}
	}
}

static void draw_function_patterns(qr_t *q, int ecc)
{
	int size = q->size;
	for (int i = 0; i < size; i++) {
		set_fn(q, 6, i, i % 2 == 0);
		set_fn(q, i, 6, i % 2 == 0);
	}
	draw_finder(q, 3, 3);
	draw_finder(q, size - 4, 3);
	draw_finder(q, 3, size - 4);
	if (q->version >= 2) {             /* 版本 2/3 只有右下一個對位圖形 */
		int c = size - 7;
		for (int dy = -2; dy <= 2; dy++)
			for (int dx = -2; dx <= 2; dx++) {
				int adx = dx < 0 ? -dx : dx, ady = dy < 0 ? -dy : dy;
				set_fn(q, c + dx, c + dy, (adx > ady ? adx : ady) != 1);
			}
	}
	draw_format(q, ecc, 0);            /* 先佔位，選定 mask 後再重畫 */
}

static void draw_codewords(qr_t *q, const uint8_t *cw, int len)
{
	int size = q->size, i = 0;
	for (int right = size - 1; right >= 1; right -= 2) {
		if (right == 6) right = 5;
		for (int vert = 0; vert < size; vert++) {
			for (int j = 0; j < 2; j++) {
				int x = right - j;
				int upward = ((right + 1) & 2) == 0;
				int y = upward ? size - 1 - vert : vert;
				if (!is_fn(x, y) && i < len * 8) {
					set_mod(q, x, y, (cw[i >> 3] >> (7 - (i & 7))) & 1);
					i++;
				}
			}
		}
	}
}

static int mask_bit(int m, int x, int y)
{
	switch (m) {
	case 0:  return (x + y) % 2 == 0;
	case 1:  return y % 2 == 0;
	case 2:  return x % 3 == 0;
	case 3:  return (x + y) % 3 == 0;
	case 4:  return (x / 3 + y / 2) % 2 == 0;
	case 5:  return x * y % 2 + x * y % 3 == 0;
	case 6:  return (x * y % 2 + x * y % 3) % 2 == 0;
	default: return ((x + y) % 2 + x * y % 3) % 2 == 0;
	}
}

static void apply_mask(qr_t *q, int m)
{
	for (int y = 0; y < q->size; y++)
		for (int x = 0; x < q->size; x++)
			if (!is_fn(x, y) && mask_bit(m, x, y))
				q->rows[y] ^= (1u << x);
}

/* ---------------------------------------------------------------- penalty (N1..N4) */
static int get_xy(const qr_t *q, int x, int y, int transpose)
{
	return transpose ? qr_module(q, y, x) : qr_module(q, x, y);
}

static int light_run(const qr_t *q, int line, int from, int to, int transpose)
{
	for (int i = from; i <= to; i++)
		if (i >= 0 && i < q->size && get_xy(q, i, line, transpose)) return 0;
	return 1;   /* 超出邊界視為淺色 */
}

static long penalty(const qr_t *q)
{
	static const uint8_t FINDER[7] = {1, 0, 1, 1, 1, 0, 1};
	int size = q->size;
	long score = 0;
	int dark = 0;

	for (int t = 0; t < 2; t++) {           /* t = 0 列, t = 1 行 */
		for (int line = 0; line < size; line++) {
			int run = 1;
			for (int i = 1; i <= size; i++) {      /* N1：連續同色 */
				if (i < size && get_xy(q, i, line, t) == get_xy(q, i - 1, line, t)) {
					run++;
				} else {
					if (run >= 5) score += 3 + (run - 5);
					run = 1;
				}
			}
			for (int s = 0; s + 7 <= size; s++) {  /* N3：1:1:3:1:1 類 finder 圖形 */
				int k = 0;
				while (k < 7 && get_xy(q, s + k, line, t) == FINDER[k]) k++;
				if (k == 7 && (light_run(q, line, s - 4, s - 1, t) || light_run(q, line, s + 7, s + 10, t)))
					score += 40;
			}
		}
	}
	for (int y = 0; y < size; y++) {
		for (int x = 0; x < size; x++) {
			int c = qr_module(q, x, y);
			dark += c;
			if (x + 1 < size && y + 1 < size &&       /* N2：2x2 同色 */
			    c == qr_module(q, x + 1, y) && c == qr_module(q, x, y + 1) && c == qr_module(q, x + 1, y + 1))
				score += 3;
		}
	}
	int total = size * size;                     /* N4：深淺比例 */
	int diff = dark * 20 - total * 10;
	if (diff < 0) diff = -diff;
	int k = (diff + total - 1) / total - 1;
	if (k > 0) score += (long)k * 10;
	return score;
}

/* ---------------------------------------------------------------- public */
int qr_encode(const char *text, qr_t *qr)
{
	uint8_t cw[70];
	int len = (int)strlen(text);
	int ver = 0, ecc = 0;

	for (int v = 1; v <= 3 && !ver; v++) {
		for (int e = 1; e >= 0; e--) {               /* 優先 M，放不下才用 L */
			if (len <= (DATA_CW[v - 1][e] * 8 - 12) / 8) { ver = v; ecc = e; break; }
		}
	}
	memset(qr, 0, sizeof(*qr));
	if (!ver) return 0;

	/* 資料碼字：模式 0100 + 8 位元長度 + 資料 + 終止符 + 補齊 */
	int dcw = DATA_CW[ver - 1][ecc], ecw = ECC_CW[ver - 1][ecc];
	int bit = 0, cap = dcw * 8;
	memset(cw, 0, sizeof(cw));
#define PUT_BITS(val, n) do { for (int _i = (n) - 1; _i >= 0; _i--, bit++) \
		if (((val) >> _i) & 1) cw[bit >> 3] |= (uint8_t)(0x80 >> (bit & 7)); } while (0)
	PUT_BITS(0x4, 4);
	PUT_BITS(len, 8);
	for (int i = 0; i < len; i++) PUT_BITS((uint8_t)text[i], 8);
#undef PUT_BITS
	bit += (cap - bit < 4) ? cap - bit : 4;          /* 終止符（已是 0） */
	bit = (bit + 7) & ~7;
	for (int i = bit / 8, pad = 0xEC; i < dcw; i++, pad ^= (0xEC ^ 0x11)) cw[i] = (uint8_t)pad;
	rs_compute(cw, dcw, &cw[dcw], ecw);

	/* 繪製並選擇懲罰最小的 mask */
	qr->version = (uint8_t)ver;
	qr->ecc = (uint8_t)ecc;
	qr->size = (uint8_t)(17 + 4 * ver);
	memset(fn_rows, 0, sizeof(fn_rows));
	draw_function_patterns(qr, ecc);
	draw_codewords(qr, cw, dcw + ecw);

	long best = -1;
	int best_mask = 0;
	for (int m = 0; m < 8; m++) {
		apply_mask(qr, m);
		draw_format(qr, ecc, m);
		long p = penalty(qr);
		if (best < 0 || p < best) { best = p; best_mask = m; }
		apply_mask(qr, m);                            /* XOR 還原 */
	}
	apply_mask(qr, best_mask);
	draw_format(qr, ecc, best_mask);
	qr->mask = (uint8_t)best_mask;
	return 1;
}
