/*
 * proto.c
 *
 * 通訊幀編解碼，行為與 tools/b570_proto.py 完全一致（可在主機端編譯測試）。
 */
#include "proto.h"
#include <string.h>

uint16_t proto_crc16(const uint8_t *data, uint16_t len)
{
	uint16_t crc = 0xFFFF;
	while (len--) {
		crc ^= (uint16_t)(*data++) << 8;
		for (uint8_t i = 0; i < 8; i++)
			crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021) : (uint16_t)(crc << 1);
	}
	return crc;
}

uint16_t proto_build(uint8_t *out, uint8_t type, uint8_t seq, const void *payload, uint8_t len)
{
	if (len > PROTO_MAX_PAYLOAD) return 0;
	out[0] = PROTO_SOF0;
	out[1] = PROTO_SOF1;
	out[2] = len;
	out[3] = type;
	out[4] = seq;
	if (len) memcpy(&out[5], payload, len);
	wr_u16(&out[5 + len], proto_crc16(&out[2], (uint16_t)(len + 3)));
	return (uint16_t)(len + PROTO_OVERHEAD);
}

static void drop(proto_parser_t *ps, uint16_t k)
{
	if (k >= ps->n) { ps->n = 0; return; }
	memmove(ps->buf, &ps->buf[k], ps->n - k);
	ps->n -= k;
}

static void extract(proto_parser_t *ps, proto_handler_t handler)
{
	for (;;) {
		uint16_t i = 0;
		/* 找 A5 5A；結尾單獨的 A5 先保留（可能是被切開的 SOF） */
		while (i < ps->n && !(ps->buf[i] == PROTO_SOF0 && (i + 1 >= ps->n || ps->buf[i + 1] == PROTO_SOF1))) i++;
		if (i) drop(ps, i);
		if (ps->n < 3) return;

		uint8_t len = ps->buf[2];
		if (len > PROTO_MAX_PAYLOAD) { drop(ps, 1); continue; }
		uint16_t total = (uint16_t)(len + PROTO_OVERHEAD);
		if (ps->n < total) return;

		if (proto_crc16(&ps->buf[2], (uint16_t)(len + 3)) == rd_u16(&ps->buf[5 + len])) {
			ps->frames_ok++;
			handler(ps->buf[3], ps->buf[4], &ps->buf[5], len);
			drop(ps, total);
		} else {
			ps->crc_errors++;
			drop(ps, 1);
		}
	}
}

void proto_feed(proto_parser_t *ps, const uint8_t *data, uint16_t len, proto_handler_t handler)
{
	while (len) {
		uint16_t room = (uint16_t)(sizeof(ps->buf) - ps->n);
		uint16_t k = len < room ? len : room;
		memcpy(&ps->buf[ps->n], data, k);
		ps->n += k;
		data += k;
		len -= k;
		extract(ps, handler);
		if (ps->n == sizeof(ps->buf)) drop(ps, 1);   /* 理論上不會發生（完整幀 ≤ 207） */
	}
}
