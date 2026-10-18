#!/usr/bin/env python3
"""產生解析器差分測試資料：用 tools/b570_proto.py 的 Parser 算出期望結果。
用法: python gen_fuzz.py <project_root> <out_file> [cases] [seed]
輸出格式（每行）:
  C <stream_hex>
  F <type> <seq> <payload_hex or ->
  E <crc_errors>
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


def rand_frame():
    ln = rnd.choice([0, 1, 2, 4, 5, 12, 40, rnd.randint(0, 200), 200])
    payload = bytes(rnd.choice([0xA5, 0x5A, rnd.randint(0, 255)]) for _ in range(ln))
    return bp.build_frame(rnd.randint(0, 255), rnd.randint(0, 255), payload)


def chunk():
    k = rnd.randint(0, 9)
    if k <= 3:
        return rand_frame()
    if k == 4:  # 雜訊（含 SOF 片段）
        return bytes(rnd.choice([0xA5, 0x5A, 0x00, 0xFF, rnd.randint(0, 255)]) for _ in range(rnd.randint(1, 30)))
    if k == 5:  # CRC 或內容損壞的幀
        f = bytearray(rand_frame())
        i = rnd.randint(2, len(f) - 1)
        f[i] ^= 1 << rnd.randint(0, 7)
        return bytes(f)
    if k == 6:  # 截斷的幀
        f = rand_frame()
        return f[: rnd.randint(1, len(f) - 1)]
    if k == 7:  # LEN > 200
        return bytes([0xA5, 0x5A, rnd.randint(201, 255)]) + bytes(rnd.randint(0, 255) for _ in range(rnd.randint(0, 10)))
    if k == 8:  # 假 header 把後面的真幀吞進去
        return bytes([0xA5, 0x5A, rnd.randint(0, 60), rnd.randint(0, 255), rnd.randint(0, 255)])
    return bytes([0xA5])  # 單獨 SOF0


with open(out, "w") as fo:
    for _ in range(cases):
        stream = b"".join(chunk() for _ in range(rnd.randint(1, 25)))
        p = bp.Parser()
        frames = p.feed(stream)
        fo.write("C %s\n" % (stream.hex() or "-"))
        for t, s, pl in frames:
            fo.write("F %d %d %s\n" % (t, s, pl.hex() or "-"))
        fo.write("E %d\n" % p.crc_errors)
print("wrote %d cases to %s" % (cases, out))
