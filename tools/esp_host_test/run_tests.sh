#!/usr/bin/env bash
# 在電腦上執行 ESP-01 橋接韌體的單元測試（不需要硬體）：
# 協定 / 解析器（含與 tools/b570_proto.py 的差分比對）、session、Wi-Fi 設定、熱點狀態機、UART 佇列、內建熱點。
# 用法：bash tools/esp_host_test/run_tests.sh      （可用 CXX=... 指定 C++17 編譯器，預設 g++）
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
CXX=${CXX:-g++}
FLAGS=(-std=c++17 -O2 -Wall -Wextra -Wpedantic -Wshadow -Wconversion -Wno-sign-conversion -I "$ROOT/ESP01/B570_Bridge")
cd "$HERE"
python gen_fuzz.py "$ROOT" fuzz_cases.txt 600 20261016
python gen_sta.py "$ROOT" sta_cases.txt 500 20261017
"$CXX" "${FLAGS[@]}" test_main.cpp -o esp_host_test.exe
./esp_host_test.exe fuzz_cases.txt sta_cases.txt
"$CXX" "${FLAGS[@]}" test_preset.cpp -o test_preset.exe
./test_preset.exe
