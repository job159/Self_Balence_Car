#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
產生可列印的配對 QR 貼紙（OLED 看不清楚或想貼在車殼上時使用）。
SSID / 密碼可在 OLED 配對頁或 App「設定 → 車輛 Wi-Fi」看到。

  pip install segno
  python tools/make_pair_qr.py B570-A1B2 k7m3x9qa            → pair_B570-A1B2.png
  python tools/make_pair_qr.py B570-A1B2 k7m3x9qa --svg      → pair_B570-A1B2.svg
"""
import argparse

import segno


def escape(s: str) -> str:
    return "".join("\\" + c if c in '\\;,:"' else c for c in s)


def main():
    ap = argparse.ArgumentParser(description="B570 Pilot 配對 QR 產生器")
    ap.add_argument("ssid")
    ap.add_argument("password")
    ap.add_argument("--svg", action="store_true", help="輸出向量 SVG（列印最清晰）")
    ap.add_argument("--scale", type=int, default=12, help="每個模組的像素數（PNG）")
    a = ap.parse_args()

    if not (1 <= len(a.ssid) <= 12) or not (8 <= len(a.password) <= 16):
        raise SystemExit("SSID 需 1~12 字元、密碼需 8~16 字元（與車上 ESP-01 限制相同）")
    payload = "WIFI:T:WPA;S:%s;P:%s;;" % (escape(a.ssid), escape(a.password))
    qr = segno.make_qr(payload, error="m")
    out = "pair_%s.%s" % (a.ssid, "svg" if a.svg else "png")
    if a.svg:
        qr.save(out, scale=4, border=4)
    else:
        qr.save(out, scale=a.scale, border=4)
    print("已輸出", out, "（QR 版本 %s，內容 %s）" % (qr.version, payload))


if __name__ == "__main__":
    main()
