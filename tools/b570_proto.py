#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
B570 Pilot 通訊協定 — Python 參考實作 + PC 端測試工具

這份檔案是 docs/PROTOCOL.md 的「可執行版本」，三端（STM32 / ESP-01 / Android）
的編解碼都以這裡產生的測試向量為準。

用法：
  python b570_proto.py vectors                 # 印出測試向量
  python b570_proto.py monitor                 # 電腦連上車子的 Wi-Fi 後，即時顯示遙測與控制延遲（v1.1 韌體）
  python b570_proto.py params                  # 讀取全部參數
  python b570_proto.py set BAL_KP 23000        # 設定參數
  python b570_proto.py motor on|off|estop      # 啟動 / 停止馬達
  python b570_proto.py wifi                    # 讀取 ESP-01 Wi-Fi 狀態
"""
import socket
import struct
import sys
import time

PROTO_VERSION = 1
SOF0, SOF1 = 0xA5, 0x5A
MAX_PAYLOAD = 200
DEFAULT_HOST = "192.168.4.1"
DEFAULT_PORT = 8888

# ---------------------------------------------------------------- frame types
# App -> STM32 (ESP 透傳)
T_CTRL, T_PING, T_MOTOR, T_ZERO_CAL = 0x01, 0x02, 0x03, 0x04
T_PARAM_GET, T_PARAM_SET, T_PARAM_SAVE, T_PARAM_DEFAULTS = 0x10, 0x11, 0x12, 0x13
T_SEQ_LOAD, T_SEQ_PLAY, T_SEQ_STOP = 0x20, 0x21, 0x22
T_INFO_GET = 0x30
# App -> ESP (ESP 自行處理，不轉送)
T_HELLO, T_WIFI_GET, T_WIFI_SET, T_ESP_PING, T_BYE, T_WIFI_FACTORY = 0x40, 0x41, 0x42, 0x43, 0x44, 0x45
T_STA_SET, T_STA_GET, T_DISCOVER = 0x46, 0x47, 0x48
# ESP <-> STM32 (本地，不會出現在 UDP 上)
T_LINK, T_CRED, T_CRED_REQ, T_NET = 0x50, 0x51, 0x52, 0x53
# STM32 -> App
T_TELEM, T_PONG, T_PARAM_VALUES, T_ACK, T_INFO, T_EVENT = 0x81, 0x82, 0x90, 0x91, 0xA0, 0xA1
# ESP -> App
T_HELLO_ACK, T_WIFI_INFO, T_ESP_PONG, T_ESP_ACK, T_STA_INFO, T_DISCOVER_ACK = 0xC0, 0xC1, 0xC2, 0xC3, 0xC4, 0xC5

ACK_STATUS = {0: "OK", 1: "BAD_ARG", 2: "BUSY", 3: "FAIL", 4: "NOT_READY", 5: "UNKNOWN_TYPE"}
STOP_REASON = {0: "RUNNING", 1: "BOOT", 2: "KEY", 3: "APP", 4: "PICKUP", 5: "FALL", 6: "LOW_BATT", 7: "ESTOP", 8: "IMU"}
EVENTS = {1: "MOTORS_ON", 2: "MOTORS_OFF", 3: "PICKED_UP", 4: "FELL", 5: "LOW_BATTERY", 6: "SEQ_DONE",
          7: "SEQ_ABORTED", 8: "ZERO_CAL", 9: "PARAMS_SAVED", 10: "BOOT", 11: "LINK_LOST"}

# id, key, default, min, max
PARAMS = [
    (0, "BAL_KP", 22500.0, 0.0, 60000.0),
    (1, "BAL_KD", 108.0, 0.0, 600.0),
    (2, "VEL_KP", 16000.0, 0.0, 40000.0),
    (3, "VEL_KI", 80.0, 0.0, 400.0),
    (4, "TURN_KP", 4200.0, 0.0, 12000.0),
    (5, "TURN_KD", 0.0, 0.0, 300.0),
    (6, "ZERO_ANGLE", 0.0, -10.0, 10.0),
    (7, "MAX_SPEED", 500.0, 100.0, 1000.0),
    (8, "MAX_TURN", 40.0, 5.0, 80.0),
    (9, "ACCEL", 2000.0, 100.0, 6000.0),
    (10, "TURN_ACCEL", 12000.0, 500.0, 20000.0),
    (11, "FAILSAFE_MS", 400.0, 150.0, 2000.0),
    (12, "TELEM_HZ", 50.0, 10.0, 100.0),
    (13, "ANGLE_ALGO", 2.0, 1.0, 3.0),
    (14, "PICKUP_EN", 1.0, 0.0, 1.0),
    (15, "LOWBAT_V", 10.0, 9.0, 12.0),
    (16, "FALL_ANGLE", 40.0, 20.0, 60.0),
]
PARAM_BY_KEY = {p[1]: p for p in PARAMS}
PARAM_BY_ID = {p[0]: p for p in PARAMS}


# ---------------------------------------------------------------- codec
def crc16(data: bytes, crc: int = 0xFFFF) -> int:
    """CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, 不反射, xorout 0。'123456789' -> 0x29B1"""
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def build_frame(ftype: int, seq: int, payload: bytes = b"") -> bytes:
    if len(payload) > MAX_PAYLOAD:
        raise ValueError("payload too long")
    body = bytes([len(payload), ftype & 0xFF, seq & 0xFF]) + payload
    return bytes([SOF0, SOF1]) + body + struct.pack("<H", crc16(body))


class Parser:
    """位元組串流解析器（UART 與 UDP 共用）。feed() 回傳完整的 (type, seq, payload) 列表。"""

    def __init__(self):
        self.buf = bytearray()
        self.crc_errors = 0

    def feed(self, data: bytes):
        self.buf += data
        out = []
        while True:
            i = self.buf.find(bytes([SOF0, SOF1]))
            if i < 0:
                # 保留最後一個位元組（可能是被切開的 SOF0）
                del self.buf[:-1 if self.buf[-1:] == bytes([SOF0]) else len(self.buf)]
                return out
            del self.buf[:i]
            if len(self.buf) < 3:
                return out
            ln = self.buf[2]
            if ln > MAX_PAYLOAD:
                del self.buf[:1]
                continue
            total = 5 + ln + 2
            if len(self.buf) < total:
                return out
            body = bytes(self.buf[2:5 + ln])
            got = self.buf[5 + ln] | (self.buf[6 + ln] << 8)
            if crc16(body) == got:
                out.append((body[1], body[2], body[3:]))
                del self.buf[:total]
            else:
                self.crc_errors += 1
                del self.buf[:1]


# ---------------------------------------------------------------- payload helpers
def p_ctrl(speed_mmps: int, turn_permille: int, flags: int = 0) -> bytes:
    return struct.pack("<hhB", speed_mmps, turn_permille, flags)


def p_param_set(pairs) -> bytes:
    return b"".join(struct.pack("<Bf", pid, val) for pid, val in pairs)


def lenstr(b: bytes) -> bytes:
    return bytes([len(b)]) + b


def p_sta_set(enable: bool, ssid: str, pw: str) -> bytes:
    return bytes([1 if enable else 0]) + lenstr(ssid.encode("utf-8")) + lenstr(pw.encode("ascii"))


def p_net(mode: int, ip: str, ssid: str) -> bytes:
    return bytes([mode]) + bytes(int(x) for x in ip.split(".")) + lenstr(ssid.encode("utf-8"))


def p_discover_ack(nonce: int, chip_id: int, mode: int, ip: str, busy: int, ssid: str) -> bytes:
    return struct.pack("<IIB", nonce, chip_id, mode) + bytes(int(x) for x in ip.split(".")) + bytes([busy]) + lenstr(ssid.encode())


def p_sta_info(enabled: int, state: int, ip: str, rssi_dbm: int, ssid: str, pw: str) -> bytes:
    """STA_INFO（0xC4）：rssi_dbm 0 = 無；pw 只回給 session 擁有者（其他人傳空字串）"""
    return (bytes([enabled, state]) + bytes(int(x) for x in ip.split(".")) + struct.pack("<b", rssi_dbm)
            + lenstr(ssid.encode("utf-8")) + lenstr(pw.encode("ascii")))


def p_seq_load(offset: int, total: int, steps) -> bytes:
    """steps: [(speed, turn, dur_ms, mode, period_20ms), ...]"""
    return bytes([offset, total, len(steps)]) + b"".join(struct.pack("<hhHBB", *s) for s in steps)


TELEM_FMT = "<H" + "h" * 13 + "H" + "B" * 6 + "hH"
TELEM_KEYS = ["tick", "pitch", "roll", "gyro_bal", "gyro_turn", "vel_l", "vel_r", "pwm_l", "pwm_r",
              "pwm_bal", "pwm_vel", "pwm_turn", "tgt_speed", "tgt_turn", "battery", "flags", "stop_reason",
              "seq_step", "seq_loop", "src", "link_q", "heading", "loop_us"]
assert struct.calcsize(TELEM_FMT) == 40

# §2 測試向量用的遙測（原始整數單位）：pitch -5.23°、roll 1.50°、11.56 V、heading 90.00°、loop 412 µs
TELEM_EXAMPLE = dict(tick=1234, pitch=-523, roll=150, gyro_bal=-820, gyro_turn=33, vel_l=120, vel_r=118, pwm_l=1500,
                     pwm_r=1480, pwm_bal=1200, pwm_vel=250, pwm_turn=50, tgt_speed=120, tgt_turn=0, battery=1156,
                     flags=0x03, stop_reason=0, seq_step=0xFF, seq_loop=0, src=1, link_q=50, heading=9000, loop_us=412)


def p_telem(d: dict) -> bytes:
    """TELEM payload（原始整數單位，鍵同 TELEM_KEYS）；有 ctrl_seq 就編成 v1.1 的 42 bytes"""
    p = struct.pack(TELEM_FMT, *(d[k] for k in TELEM_KEYS))
    if d.get("ctrl_seq") is not None:
        p += bytes([d["ctrl_seq"], d.get("ctrl_age", 0)])
    return p


def decode_telem(p: bytes) -> dict:
    d = dict(zip(TELEM_KEYS, struct.unpack(TELEM_FMT, p[:40])))
    # v1.1：ctrl_seq / ctrl_age（42 bytes）；舊韌體 40 bytes 時為 None。解碼只要求 >= 40 bytes
    d["ctrl_seq"], d["ctrl_age"] = (p[40], p[41]) if len(p) >= 42 else (None, None)
    d["pitch"] /= 100.0
    d["roll"] /= 100.0
    d["heading"] /= 100.0
    d["battery"] /= 100.0
    return d


CTRL_AGE_SATURATED = 255
TICK_MS = 10


def control_latency_ms(sent_ms: float, telem_rx_ms: float, ctrl_age: int):
    """
    §4.1 控制延遲（手機送出 CTRL → 主控板真正套用，含等下一個控制週期，再加上這筆遙測的下行時間）。
    主控板 ctrl_age = 收到 CTRL 之後經過的控制週期數；收到後的「下一個」週期才套用，套用那一週期送出的遙測 ctrl_age = 1，
    所以已套用 max(ctrl_age - 1, 0) 個週期。ctrl_age = 255（還沒收過 / 飽和）回傳 None。
    """
    if ctrl_age is None or ctrl_age >= CTRL_AGE_SATURATED:
        return None
    return max(0.0, (telem_rx_ms - sent_ms) - max(ctrl_age - 1, 0) * TICK_MS)


def decode_param_values(p: bytes):
    n = p[0]
    return [struct.unpack_from("<Bf", p, 1 + 5 * i) for i in range(n)]


# ---------------------------------------------------------------- test vectors
def vectors():
    print("CRC16('123456789') = 0x%04X  (必須是 0x29B1)" % crc16(b"123456789"))
    cases = [
        ("CTRL speed=300 turn=-250 flags=0 seq=7", build_frame(T_CTRL, 7, p_ctrl(300, -250, 0))),
        ("PING t=0x12345678 seq=1", build_frame(T_PING, 1, struct.pack("<I", 0x12345678))),
        ("MOTOR start seq=2", build_frame(T_MOTOR, 2, bytes([1]))),
        ("PARAM_GET all seq=3", build_frame(T_PARAM_GET, 3, bytes([0xFF]))),
        ("PARAM_SET BAL_KP=23000.0 seq=4", build_frame(T_PARAM_SET, 4, p_param_set([(0, 23000.0)]))),
        ("SEQ_LOAD 1 step (200,0,1000,0,0) seq=5", build_frame(T_SEQ_LOAD, 5, p_seq_load(0, 1, [(200, 0, 1000, 0, 0)]))),
        ("HELLO session=0xCAFEBABE seq=0", build_frame(T_HELLO, 0, struct.pack("<I", 0xCAFEBABE))),
        ("CRED_REQ seq=9", build_frame(T_CRED_REQ, 9)),
        ("ACK ref=0x12 status=2 seq=10", build_frame(T_ACK, 10, bytes([0x12, 2]))),
        ("STA_SET on 'MyPhone'/'pass1234' seq=11", build_frame(T_STA_SET, 11, p_sta_set(True, "MyPhone", "pass1234"))),
        ("DISCOVER nonce=0x01020304 seq=12", build_frame(T_DISCOVER, 12, struct.pack("<I", 0x01020304))),
        ("DISCOVER_ACK sta 172.20.10.5 seq=12", build_frame(T_DISCOVER_ACK, 12, p_discover_ack(0x01020304, 0x00A1B2C3, 1, "172.20.10.5", 0, "B570-A1B2"))),
        ("NET mode=2 172.20.10.5 'MyPhone' seq=13", build_frame(T_NET, 13, p_net(2, "172.20.10.5", "MyPhone"))),
        ("STA_INFO on/2 172.20.10.5 -52dBm seq=14", build_frame(T_STA_INFO, 14, p_sta_info(1, 2, "172.20.10.5", -52, "MyPhone", "pass1234"))),
        ("STA_GET seq=15", build_frame(T_STA_GET, 15)),
        ("STA_SET off (keep creds) seq=16", build_frame(T_STA_SET, 16, p_sta_set(False, "", ""))),
        ("TELEM v1.0 (40 B) seq=42", build_frame(T_TELEM, 42, p_telem(TELEM_EXAMPLE))),
        ("TELEM v1.1 (42 B) ctrl 0x2A/3 seq=42", build_frame(T_TELEM, 42, p_telem(dict(TELEM_EXAMPLE, ctrl_seq=0x2A, ctrl_age=3)))),
    ]
    for name, fr in cases:
        print("%-42s %s" % (name, fr.hex(" ").upper()))
    # 解析器自我測試：雜訊 + 黏包 + 拆包
    pr = Parser()
    stream = b"\x00\xA5\xFF" + cases[0][1] + cases[1][1][:5]
    got = pr.feed(stream)
    got += pr.feed(cases[1][1][5:] + b"\xA5")
    got += pr.feed(b"\x5A\x00")  # 不完整
    assert [g[0] for g in got] == [T_CTRL, T_PING], got
    print("parser self-test OK")
    t = decode_telem(p_telem(dict(TELEM_EXAMPLE, ctrl_seq=0x2A, ctrl_age=3)))
    assert t["ctrl_seq"] == 0x2A and t["ctrl_age"] == 3 and t["tick"] == 1234 and decode_telem(p_telem(TELEM_EXAMPLE))["ctrl_seq"] is None
    # 控制延遲：送出 → 收到遙測 45 ms；ctrl_age 1 = 這一週期剛套用（不扣）、2 = 上一週期套用（扣 10 ms）、255 = 無效
    assert control_latency_ms(1000, 1045, 1) == 45 and control_latency_ms(1000, 1045, 2) == 35
    assert control_latency_ms(1000, 1045, 0) == 45 and control_latency_ms(1000, 1045, 255) is None
    print("telem / control latency self-test OK")


# ---------------------------------------------------------------- UDP client (PC 測試)
class Client:
    def __init__(self, host=DEFAULT_HOST, port=DEFAULT_PORT):
        self.addr = (host, port)
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.settimeout(0.05)
        self.seq = 0
        self.parser = Parser()

    def send(self, ftype, payload=b""):
        self.seq = (self.seq + 1) & 0xFF
        self.sock.sendto(build_frame(ftype, self.seq, payload), self.addr)
        return self.seq

    def recv(self):
        """等第一個 datagram（最多 50 ms），再把已經到的全部讀完（不讀完會越積越多，遙測與控制延遲都變成舊的）。"""
        out = []
        try:
            data, _ = self.sock.recvfrom(2048)
        except socket.timeout:
            return out
        out += self.parser.feed(data)
        self.sock.settimeout(0.0)
        try:
            while True:
                data, _ = self.sock.recvfrom(2048)
                out += self.parser.feed(data)
        except (socket.timeout, BlockingIOError):
            pass
        finally:
            self.sock.settimeout(0.05)
        return out

    def hello(self):
        for _ in range(20):
            self.send(T_HELLO, struct.pack("<I", 0x50435445))
            t0 = time.time()
            while time.time() - t0 < 0.2:
                for t, s, p in self.recv():
                    if t == T_HELLO_ACK:
                        return p[0] == 0
        raise SystemExit("找不到車子：請確認電腦已連上 B570-XXXX 的 Wi-Fi")

    def wait_for(self, want, timeout=1.5):
        t0 = time.time()
        while time.time() - t0 < timeout:
            self.send(T_CTRL, p_ctrl(0, 0))
            for t, s, p in self.recv():
                if t == want:
                    return p
        return None


def main(argv):
    if len(argv) < 2 or argv[1] == "vectors":
        vectors()
        return
    c = Client()
    c.hello()
    cmd = argv[1]
    if cmd == "monitor":
        last_ping = 0
        ctrl_sent = {}   # SEQ -> 送出時間 ms（只記 CTRL；PING 用掉的 SEQ 會清掉）
        lat = None
        while True:
            now = time.time()
            seq = c.send(T_CTRL, p_ctrl(0, 0))
            ctrl_sent[seq] = now * 1000.0
            if now - last_ping > 0.5:
                ctrl_sent.pop(c.send(T_PING, struct.pack("<I", int(now * 1000) & 0xFFFFFFFF)), None)
                last_ping = now
            for t, s, p in c.recv():
                if t == T_TELEM:
                    d = decode_telem(p)
                    if d["ctrl_seq"] in ctrl_sent:
                        ms = control_latency_ms(ctrl_sent[d["ctrl_seq"]], time.time() * 1000.0, d["ctrl_age"])
                        if ms is not None and ms < 2000:
                            lat = ms if lat is None else lat * 0.9 + ms * 0.1
                    print("\rpitch %+6.2f  vel %+5d/%+5d  pwm %+5d/%+5d  bat %5.2fV  %-8s  loop %4dus  ctrl %s ms " % (
                        d["pitch"], d["vel_l"], d["vel_r"], d["pwm_l"], d["pwm_r"], d["battery"],
                        STOP_REASON.get(d["stop_reason"], "?"), d["loop_us"], "--" if lat is None else "%3.0f" % lat), end="")
                elif t == T_PONG:
                    rtt = (int(time.time() * 1000) & 0xFFFFFFFF) - struct.unpack("<I", p)[0]
                    print("\n RTT %d ms" % rtt)
                elif t == T_EVENT:
                    print("\n EVENT", EVENTS.get(p[0], p[0]), struct.unpack("<h", p[1:3])[0])
            time.sleep(0.02)
    elif cmd == "params":
        c.send(T_PARAM_GET, bytes([0xFF]))
        p = c.wait_for(T_PARAM_VALUES)
        for pid, val in decode_param_values(p or b"\x00"):
            print("%2d %-12s %10.3f" % (pid, PARAM_BY_ID[pid][1], val))
    elif cmd == "set":
        pid = PARAM_BY_KEY[argv[2]][0]
        c.send(T_PARAM_SET, p_param_set([(pid, float(argv[3]))]))
        p = c.wait_for(T_PARAM_VALUES)
        print(decode_param_values(p) if p else "no reply")
    elif cmd == "motor":
        c.send(T_MOTOR, bytes([{"off": 0, "on": 1, "estop": 2}[argv[2]]]))
        p = c.wait_for(T_ACK)
        print("ACK", ACK_STATUS.get(p[1]) if p else "none")
    elif cmd == "wifi":
        c.send(T_WIFI_GET)
        p = c.wait_for(T_WIFI_INFO)
        print(p.hex(" ") if p else "no reply")
    c.send(T_BYE)


if __name__ == "__main__":
    main(sys.argv)
