#!/bin/bash
# 在電腦上執行 STM32 通訊 / 參數 / 運動邏輯測試（不需要硬體）
# 用法：bash tools/fw_host_test/run.sh      （可用 CC=... 指定編譯器）
cd "$(dirname "$0")"
FW=../../WHEELTEC
CC=${CC:-gcc}
$CC -std=gnu11 -O1 -Wall -Wextra -Wno-unused-parameter -Istub -I$FW/Inc \
    test_fw.c $FW/proto.c $FW/rc_link.c $FW/motion.c $FW/params.c -lm -o test_fw.exe && ./test_fw.exe
