// b570_preset_local.example.h - 內建手機熱點的範本
//
// 用法：把這個檔案複製成 b570_preset_local.h（同一個資料夾），填好你的手機熱點，再燒錄 ESP-01。
// b570_preset_local.h 已列入 .gitignore：你的熱點密碼不會被 commit / push。
// 沒有 b570_preset_local.h 時 PRESET_ID = 0：不使用內建熱點（第一次用 App 直連後再設定熱點模式）。
#pragma once

#define PRESET_ID           1            // 1..255；改了名稱 / 密碼要加 1（下次開機會再套用一次）
#define PRESET_STA_SSID     "MyPhone"    // 手機個人熱點名稱（1..32 bytes，大小寫要相同）
#define PRESET_STA_PW       "12345678"   // 手機個人熱點密碼（8..63 字元；"" = 開放熱點）
