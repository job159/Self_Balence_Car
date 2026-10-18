/*
 * proto.h
 *
 * B570 Pilot 通訊幀編解碼（規格：docs/PROTOCOL.md）。
 * 幀：A5 5A | LEN | TYPE | SEQ | PAYLOAD[LEN] | CRC16(LE)，CRC-16/CCITT-FALSE 涵蓋 LEN..PAYLOAD。
 */
#ifndef INC_PROTO_H_
#define INC_PROTO_H_

#include <stdint.h>

#define PROTO_SOF0          0xA5
#define PROTO_SOF1          0x5A
#define PROTO_MAX_PAYLOAD   200
#define PROTO_OVERHEAD      7
#define PROTO_MAX_FRAME     (PROTO_MAX_PAYLOAD + PROTO_OVERHEAD)

/* App -> STM32 */
#define T_CTRL              0x01
#define T_PING              0x02
#define T_MOTOR             0x03
#define T_ZERO_CAL          0x04
#define T_PARAM_GET         0x10
#define T_PARAM_SET         0x11
#define T_PARAM_SAVE        0x12
#define T_PARAM_DEFAULTS    0x13
#define T_SEQ_LOAD          0x20
#define T_SEQ_PLAY          0x21
#define T_SEQ_STOP          0x22
#define T_INFO_GET          0x30
/* ESP <-> STM32 */
#define T_LINK              0x50
#define T_CRED              0x51
#define T_CRED_REQ          0x52
#define T_NET               0x53
/* STM32 -> App */
#define T_TELEM             0x81
#define T_PONG              0x82
#define T_PARAM_VALUES      0x90
#define T_ACK               0x91
#define T_INFO              0xA0
#define T_EVENT             0xA1

/* ACK status */
#define ACK_OK              0
#define ACK_BAD_ARG         1
#define ACK_BUSY            2
#define ACK_FAIL            3
#define ACK_NOT_READY       4
#define ACK_UNKNOWN_TYPE    5

typedef void (*proto_handler_t)(uint8_t type, uint8_t seq, const uint8_t *payload, uint8_t len);

typedef struct {
	uint8_t  buf[256];
	uint16_t n;
	uint32_t crc_errors;
	uint32_t frames_ok;
} proto_parser_t;

uint16_t proto_crc16(const uint8_t *data, uint16_t len);
/* 組幀到 out（至少 len+7 bytes），回傳總長度 */
uint16_t proto_build(uint8_t *out, uint8_t type, uint8_t seq, const void *payload, uint8_t len);
/* 串流解析：可處理雜訊、黏包、拆包；CRC 錯誤丟 1 byte 重新同步 */
void proto_feed(proto_parser_t *ps, const uint8_t *data, uint16_t len, proto_handler_t handler);

static inline int16_t  rd_i16(const uint8_t *p) { return (int16_t)(p[0] | (p[1] << 8)); }
static inline uint16_t rd_u16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static inline void wr_u16(uint8_t *p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static inline void wr_i16(uint8_t *p, int16_t v)  { wr_u16(p, (uint16_t)v); }

#endif /* INC_PROTO_H_ */
