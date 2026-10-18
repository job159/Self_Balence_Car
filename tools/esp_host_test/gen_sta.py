#!/usr/bin/env python3
"""產生熱點模式的差分測試資料（期望值來自 tools/b570_proto.py 與 Python 嚴格 UTF-8 解碼器）。
用法: python gen_sta.py <project_root> <out_file> [cases] [seed]
輸出格式（每行）:
  U <bytes_hex or -> <0|1>                         UTF-8 合法（且不含 NUL）= 1
  N <mode> <a.b.c.d> <ssid_hex> <frame_hex>        NET 幀（seq = 序號 & 0xFF）
  D <nonce> <chip> <mode> <a.b.c.d> <busy> <ssid_hex> <seq> <frame_hex>   DISCOVER_ACK 幀
  S <enable> <ssid_hex or -> <pw_hex or -> <seq> <frame_hex>               STA_SET 幀
"""
import os
import random
import sys

root, out = sys.argv[1], sys.argv[2]
cases = int(sys.argv[3]) if len(sys.argv) > 3 else 400
seed = int(sys.argv[4]) if len(sys.argv) > 4 else 12345
sys.path.insert(0, os.path.join(root, "tools"))
import b570_proto as bp  # noqa: E402

rnd = random.Random(seed)


def hx(b: bytes) -> str:
    return b.hex() or "-"


def rand_char():
    k = rnd.randint(0, 9)
    if k <= 3:
        return chr(rnd.randint(0x20, 0x7E))
    if k == 4:
        return chr(rnd.randint(0x80, 0x7FF))
    if k == 5:
        return chr(rnd.choice([rnd.randint(0x800, 0xD7FF), rnd.randint(0xE000, 0xFFFF)]))
    if k == 6:
        return chr(rnd.randint(0x10000, 0x10FFFF))
    if k == 7:
        return rnd.choice("中文熱點車子手機的’”…")
    if k == 8:
        return chr(rnd.randint(0x01, 0x1F))
    return rnd.choice(["😀", "🚗", "é", "ß", "ñ"])


def rand_str(max_bytes, min_bytes=1):
    while True:
        s = "".join(rand_char() for _ in range(rnd.randint(1, max_bytes)))
        b = s.encode("utf-8")
        while len(b) > max_bytes:
            s = s[:-1]
            b = s.encode("utf-8")
        if len(b) >= min_bytes:
            return s


def utf8_expected(b: bytes) -> int:
    if 0 in b:
        return 0
    try:
        b.decode("utf-8", errors="strict")
        return 1
    except UnicodeDecodeError:
        return 0


def mutate(b: bytes) -> bytes:
    b = bytearray(b)
    for _ in range(rnd.randint(1, 3)):
        k = rnd.randint(0, 4)
        if k == 0 and b:
            b[rnd.randrange(len(b))] = rnd.randint(0, 255)
        elif k == 1:
            b.insert(rnd.randint(0, len(b)), rnd.choice([0x80, 0xBF, 0xC0, 0xC1, 0xC2, 0xE0, 0xED, 0xF0, 0xF4, 0xF5, 0xFF, 0x00]))
        elif k == 2 and b:
            del b[rnd.randrange(len(b))]
        elif k == 3 and b:
            del b[rnd.randint(0, len(b) - 1):]
        else:
            b += bytes(rnd.randint(0, 255) for _ in range(rnd.randint(1, 3)))
    return bytes(b)


HAND = [
    b"", b"A", b"\x00", b"a\x00b", b"\xc0\x80", b"\xc1\xbf", b"\xc2\x80", b"\xdf\xbf", b"\xe0\x80\x80",
    b"\xe0\x9f\xbf", b"\xe0\xa0\x80", b"\xed\x9f\xbf", b"\xed\xa0\x80", b"\xed\xbf\xbf", b"\xee\x80\x80",
    b"\xef\xbf\xbf", b"\xf0\x8f\xbf\xbf", b"\xf0\x90\x80\x80", b"\xf4\x8f\xbf\xbf", b"\xf4\x90\x80\x80",
    b"\xf5\x80\x80\x80", b"\xf8\x88\x80\x80\x80", b"\xfe", b"\xff", b"\x80", b"\xbf", b"\xe4\xb8",
    b"\xe4\xb8\xad", b"\xf0\x9f\x98", b"\xf0\x9f\x98\x80", b"\x7f", b"\x01\x1f",
    "Ming’s iPhone".encode(), "我的手機熱點".encode(), "AndroidAP_5G".encode(),
]

with open(out, "w") as fo:
    for b in HAND:
        fo.write("U %s %d\n" % (hx(b), utf8_expected(b)))
    for _ in range(cases * 4):
        b = rand_str(36).encode("utf-8")
        if rnd.random() < 0.5:
            b = mutate(b)
        fo.write("U %s %d\n" % (hx(b), utf8_expected(b)))
    for i in range(cases):
        mode = rnd.randint(0, 3)
        ip = ".".join(str(rnd.randint(0, 255)) for _ in range(4)) if mode == 2 else "0.0.0.0"
        ssid = rand_str(32)
        f = bp.build_frame(bp.T_NET, i & 0xFF, bp.p_net(mode, ip, ssid))
        fo.write("N %d %s %s %s\n" % (mode, ip, hx(ssid.encode()), hx(f)))
    for i in range(cases):
        nonce, chip = rnd.getrandbits(32), rnd.getrandbits(32)
        mode, busy = rnd.randint(0, 1), rnd.randint(0, 1)
        ip = ".".join(str(rnd.randint(0, 255)) for _ in range(4))
        ssid = rand_str(32)
        seq = rnd.randint(0, 255)
        f = bp.build_frame(bp.T_DISCOVER_ACK, seq, bp.p_discover_ack(nonce, chip, mode, ip, busy, ssid))
        fo.write("D %d %d %d %s %d %s %d %s\n" % (nonce, chip, mode, ip, busy, hx(ssid.encode()), seq, hx(f)))
    for i in range(cases):
        en = rnd.randint(0, 1)
        ssid = rand_str(32) if (en or rnd.random() < 0.7) else ""
        k = rnd.randint(0, 3)
        pw = "" if k == 0 or not ssid else "".join(chr(rnd.randint(0x20, 0x7E)) for _ in range(rnd.choice([8, 63, rnd.randint(8, 63)])))
        seq = rnd.randint(0, 255)
        f = bp.build_frame(bp.T_STA_SET, seq, bp.p_sta_set(bool(en), ssid, pw))
        fo.write("S %d %s %s %d %s\n" % (en, hx(ssid.encode()), hx(pw.encode()), seq, hx(f)))
print("wrote sta cases to %s" % out)
