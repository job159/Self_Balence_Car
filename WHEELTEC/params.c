/*
 * params.c
 *
 * 參數表：預設值 / 範圍 / Flash 存取。
 */
#include "main.h"
#include <stddef.h>

float Middle_angle;
float Max_Speed, Max_Turn, Accel_Limit, Turn_Accel_Limit, Failsafe_Ms, Telem_Hz;
float Angle_Algo, Pickup_En, LowBat_V, Fall_Angle;
volatile uint8_t Params_Dirty;

typedef struct {
	float   def, min, max;
	uint8_t is_int;
} param_def_t;

static const param_def_t PDEF[PARAM_COUNT] = {
	{22500, 0,     60000, 0},   /* BAL_KP      */
	{108,   0,     600,   0},   /* BAL_KD      */
	{16000, 0,     40000, 0},   /* VEL_KP      */
	{80,    0,     400,   0},   /* VEL_KI      */
	{4200,  0,     12000, 0},   /* TURN_KP     */
	{0,     0,     300,   0},   /* TURN_KD     */
	{0,     -10,   10,    0},   /* ZERO_ANGLE  */
	{500,   100,   1000,  0},   /* MAX_SPEED   */
	{40,    5,     80,    0},   /* MAX_TURN    */
	{2000,  100,   6000,  0},   /* ACCEL       */
	{12000, 500,   20000, 0},   /* TURN_ACCEL  */
	{400,   150,   2000,  1},   /* FAILSAFE_MS */
	{50,    10,    100,   1},   /* TELEM_HZ    */
	{2,     1,     3,     1},   /* ANGLE_ALGO  */
	{1,     0,     1,     1},   /* PICKUP_EN   */
	{10.0f, 9.0f,  12.0f, 0},   /* LOWBAT_V    */
	{40,    20,    60,    0},   /* FALL_ANGLE  */
};

static float *const PPTR[PARAM_COUNT] = {
	&Balance_Kp, &Balance_Kd, &Velocity_Kp, &Velocity_Ki, &Turn_Kp, &Turn_Kd,
	&Middle_angle, &Max_Speed, &Max_Turn, &Accel_Limit, &Turn_Accel_Limit,
	&Failsafe_Ms, &Telem_Hz, &Angle_Algo, &Pickup_En, &LowBat_V, &Fall_Angle,
};

#define BLOB_MAGIC   0x30373542UL   /* "B570" */
#define BLOB_VERSION 2   /* 2 = fw 2.1 起（加減速預設改快）；1 = fw 2.0，載入時自動升級 */

typedef struct {
	uint32_t magic;
	uint16_t version;
	uint16_t count;
	float    v[PARAM_COUNT];
	uint16_t crc;
	uint16_t pad;
} param_blob_t;

float Params_Get(uint8_t id)
{
	return id < PARAM_COUNT ? *PPTR[id] : 0.0f;
}

static float clamp_param(uint8_t id, float v)
{
	const param_def_t *d = &PDEF[id];
	if (v != v) v = d->def;                         /* NaN */
	if (v < d->min) v = d->min;
	if (v > d->max) v = d->max;
	if (id == P_TELEM_HZ) {                         /* 實際頻率 = 100 Hz / 整數分頻，回報真實值 */
		int div = (int)(100.0f / v + 0.5f);
		v = 100.0f / (float)(div < 1 ? 1 : div);
	}
	if (d->is_int) v = (float)(int)(v + 0.5f);      /* 整數型參數皆 >= 0 */
	return v;
}

static void apply(uint8_t id, float v)
{
	if (*PPTR[id] != v) {
		*PPTR[id] = v;
		Params_Dirty = 1;
	}
}

float Params_Set(uint8_t id, float value)
{
	if (id >= PARAM_COUNT) return 0.0f;
	float v = clamp_param(id, value);
	/* ANGLE_ALGO 只記錄，存檔後下次開機才切換（執行中切換會沿用舊的濾波狀態，角度可能錯誤數秒） */
	apply(id, v);
	return v;
}

void Params_Defaults(void)
{
	for (uint8_t i = 0; i < PARAM_COUNT; i++) apply(i, PDEF[i].def);
}

void Params_Load(void)
{
	const param_blob_t *b = (const param_blob_t *)PARAMS_FLASH_ADDR;

	for (uint8_t i = 0; i < PARAM_COUNT; i++) apply(i, PDEF[i].def);
	if (b->magic == BLOB_MAGIC && (b->version == 1 || b->version == BLOB_VERSION) && b->count <= PARAM_COUNT &&
	    proto_crc16((const uint8_t *)b, offsetof(param_blob_t, crc)) == b->crc) {
		/* 舊版存檔參數較少時，其餘維持預設 */
		for (uint8_t i = 0; i < b->count; i++) apply(i, clamp_param(i, b->v[i]));
		/* v1 存檔（fw 2.0）還是舊的加減速預設：升級成新預設，使用者自己調過的值保留 */
		if (b->version == 1) {
			if (Accel_Limit == 1200.0f) apply(P_ACCEL, PDEF[P_ACCEL].def);
			if (Turn_Accel_Limit == 6000.0f) apply(P_TURN_ACCEL, PDEF[P_TURN_ACCEL].def);
		}
	}
	Way_Angle = (u8)Angle_Algo;                       /* 姿態演算法只在開機時套用 */
	Params_Dirty = 0;
}

uint8_t Params_Save(void)
{
	param_blob_t blob;
	FLASH_EraseInitTypeDef erase;
	uint32_t page_err = 0;
	uint8_t status = ACK_OK;

	if (!Flag_Stop) return ACK_BUSY;

	memset(&blob, 0xFF, sizeof(blob));
	blob.magic = BLOB_MAGIC;
	blob.version = BLOB_VERSION;
	blob.count = PARAM_COUNT;
	for (uint8_t i = 0; i < PARAM_COUNT; i++) blob.v[i] = *PPTR[i];
	blob.crc = proto_crc16((const uint8_t *)&blob, offsetof(param_blob_t, crc));

	HAL_FLASH_Unlock();
	erase.TypeErase = FLASH_TYPEERASE_PAGES;
	erase.PageAddress = PARAMS_FLASH_ADDR;
	erase.NbPages = 1;
	if (HAL_FLASHEx_Erase(&erase, &page_err) != HAL_OK) {
		status = ACK_FAIL;
	} else {
		const uint16_t *src = (const uint16_t *)&blob;
		for (uint32_t i = 0; i < sizeof(blob) / 2; i++) {
			if (HAL_FLASH_Program(FLASH_TYPEPROGRAM_HALFWORD, PARAMS_FLASH_ADDR + i * 2, src[i]) != HAL_OK) {
				status = ACK_FAIL;
				break;
			}
		}
	}
	HAL_FLASH_Lock();

	if (status == ACK_OK && memcmp((const void *)PARAMS_FLASH_ADDR, &blob, sizeof(blob)) != 0) status = ACK_FAIL;
	if (status == ACK_OK) Params_Dirty = 0;
	return status;
}
