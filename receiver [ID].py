#!/usr/bin/env python3
"""
ETS2 Head Tracker - PC receiver

  python receiver.py [--port N] [--phone-port N] [--phone-ip IP] [--smooth K] [--selftest freeze|sine]

Tanpa argumen: tampil pengaturan tersimpan (config.json di sebelah program), Enter = pakai itu.

PC listen UDP 47991 dan ping HP (UDP 47992) tiap 1 detik sampai HP balas PONG.
Tanpa paket 3 detik -> putus -> ping lagi. Pose ditulis ke shared memory
FT_SharedMem (format FreeTrack, radian, 120 Hz), jadi ETS2_HeadTracker.dll tidak dipakai.

Smoothing eksponensial sama dengan DLL lama: tiap 16 ms nilai bergerak K (default 0.15)
menuju target. K kecil = lebih halus dan lambat, K besar = lebih responsif, 1 = tanpa smoothing.

Paket (32 byte, little-endian "<2sBBI6f"):
  magic "HT", versi 1, tipe (0 PONG, 1 POSE, 2 PING, 3 ACK), seq uint32,
  yaw, pitch, roll (deg), x, y, z (cm)
"""
import argparse
import ctypes
import json
import math
import mmap
import os
import socket
import struct
import sys
import threading
import time
from collections import deque
from dataclasses import dataclass

PC_PORT = 47991
PHONE_PORT = 47992
DEFAULTS = {"port": PC_PORT, "phone_port": PHONE_PORT, "smooth": 0.15, "phone_ip": ""}
MAGIC = b"HT"
VERSION = 1
PACKET_FMT = "<2sBBI6f"
PACKET_SIZE = struct.calcsize(PACKET_FMT)
T_PONG, T_POSE, T_PING, T_ACK = 0, 1, 2, 3

PING_INTERVAL = 1.0
LINK_TIMEOUT = 3.0
SEQ_RESET_GAP = 1.0
MAX_ABS_VALUE = 1000.0
SHM_NAME = "FT_SharedMem"
SHM_SIZE = 1024
SMOOTH_TICK = 0.016


def pack_packet(ptype, seq, vals=(0.0,) * 6):
    return struct.pack(PACKET_FMT, MAGIC, VERSION, ptype, seq & 0xFFFFFFFF, *vals)


def unpack_packet(data):
    if len(data) < PACKET_SIZE:
        return None
    magic, ver, ptype, seq, *vals = struct.unpack(PACKET_FMT, data[:PACKET_SIZE])
    if magic != MAGIC or ver != VERSION:
        return None
    return ptype, seq, vals


def app_dir():
    base = sys.executable if getattr(sys, "frozen", False) else __file__
    return os.path.dirname(os.path.abspath(base))


CONFIG_PATH = os.path.join(app_dir(), "config.json")


def load_config():
    cfg = dict(DEFAULTS)
    try:
        with open(CONFIG_PATH, encoding="utf-8") as f:
            saved = json.load(f)
        for k, d in DEFAULTS.items():
            v = saved.get(k)
            if k == "smooth":
                if isinstance(v, (int, float)) and not isinstance(v, bool):
                    cfg[k] = float(v)
            elif type(v) is type(d):
                cfg[k] = v
    except (OSError, ValueError):
        pass
    return cfg


def save_config(cfg):
    try:
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
    except OSError:
        pass


def ask(label, cur, cast, ok):
    while True:
        raw = input(f"  {label} [{cur}]: ").strip()
        if not raw:
            return cur
        try:
            v = cast(raw)
            if ok(v):
                return v
        except ValueError:
            pass
        print("    nilai tidak valid")


def settings_menu(cfg):
    print("\n ETS2 Head Tracker - Pengaturan")
    print(" (Enter = pakai nilai di dalam kurung)\n")
    cfg["port"] = ask("Port PC (HP kirim ke sini)", cfg["port"], int, lambda v: 1024 <= v <= 65535)
    cfg["phone_port"] = ask("Port HP (harus sama dengan app)", cfg["phone_port"], int, lambda v: 1024 <= v <= 65535 and v != cfg["port"])
    cfg["smooth"] = ask("Smoothing 0.01-1 (kecil = halus)", cfg["smooth"], float, lambda v: 0.01 <= v <= 1.0)
    cfg["phone_ip"] = ask("IP HP (kosong = otomatis)", cfg["phone_ip"] or "", str, lambda v: True)
    return cfg


def local_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    return sorted(i for i in ips if not i.startswith("127."))


def broadcast_targets():
    targets = {"255.255.255.255"}
    for ip in local_ips():
        targets.add(".".join(ip.split(".")[:3] + ["255"]))
    return sorted(targets)


@dataclass
class Pose:
    yaw: float = 0.0
    pitch: float = 0.0
    roll: float = 0.0
    x: float = 0.0
    y: float = 0.0
    z: float = 0.0


class FTData(ctypes.Structure):
    _fields_ = (
        [("DataID", ctypes.c_uint32), ("CamWidth", ctypes.c_int32), ("CamHeight", ctypes.c_int32)]
        + [(n, ctypes.c_float) for n in ("Yaw", "Pitch", "Roll", "X", "Y", "Z")]
        + [("Pad", ctypes.c_float * 14)]
    )


class GameOutput:
    def __init__(self, smooth=0.15, rate=120.0):
        self.real = sys.platform == "win32"
        if self.real:
            self.shm = mmap.mmap(-1, SHM_SIZE, tagname=SHM_NAME, access=mmap.ACCESS_WRITE)
        else:
            self.shm = mmap.mmap(-1, SHM_SIZE)
        self.k = min(1.0, max(0.001, smooth))
        self.period = 1.0 / rate
        self.lock = threading.Lock()
        self.target = [0.0] * 6
        self.cur = [0.0] * 6
        self.data_id = 0
        self.running = False
        self.thread = None

    def start(self):
        with self.lock:
            self._write(self.cur)
        self.running = True
        self.thread = threading.Thread(target=self._run, name="game-out", daemon=True)
        self.thread.start()

    def stop(self):
        self.running = False
        if self.thread:
            self.thread.join(timeout=1.0)
        with self.lock:
            self.target = [0.0] * 6
            self.cur = [0.0] * 6
            self._write(self.cur)
        self.shm.close()

    def update(self, pose):
        vals = [max(-math.pi, min(math.pi, math.radians(a))) for a in (pose.yaw, pose.pitch, pose.roll)]
        vals += [pose.x, pose.y, pose.z]
        with self.lock:
            self.target = vals

    def reset(self):
        with self.lock:
            self.target = [0.0] * 6

    def status(self):
        with self.lock:
            yaw, pitch, roll = (math.degrees(v) for v in self.cur[:3])
            return {"yaw": yaw, "pitch": pitch, "roll": roll, "id": self.data_id}

    def _write(self, v):
        self.data_id = (self.data_id + 1) & 0xFFFFFFFF
        self.shm.seek(0)
        self.shm.write(bytes(FTData(self.data_id, 640, 480, *v)))

    def _run(self):
        winmm = ctypes.windll.winmm if self.real else None
        if winmm:
            winmm.timeBeginPeriod(1)
        nxt = time.perf_counter()
        last = time.monotonic()
        try:
            while self.running:
                now = time.monotonic()
                a = 1.0 - (1.0 - self.k) ** (max(0.0, now - last) / SMOOTH_TICK)
                last = now
                with self.lock:
                    self.cur = [c + (t - c) * a for c, t in zip(self.cur, self.target)]
                    self._write(self.cur)
                nxt += self.period
                delay = nxt - time.perf_counter()
                if delay > 0:
                    time.sleep(delay)
                else:
                    nxt = time.perf_counter()
        finally:
            if winmm:
                winmm.timeEndPeriod(1)


class Receiver:
    def __init__(self, out=None, phone_ip=None, log=print, port=PC_PORT, phone_port=PHONE_PORT):
        self.out = out
        self.port = port
        self.phone_port = phone_port
        self.phone_ip = phone_ip
        self.log = log

        self.lock = threading.Lock()
        self.running = False
        self.sock = None
        self.thread = None

        self.connected = False
        self.phone_addr = None
        self.last_phone_ip = None
        self.last_rx = 0.0

        self.ping_count = 0
        self._ping_seq = 0
        self._last_ping = 0.0
        self._last_send_err = 0.0
        self._last_ack = 0.0
        self._ack_seq = 0
        self.ping_targets = []

        self.pose = Pose()
        self.have_pose = False
        self._pose_seq = None
        self._last_pose_t = 0.0
        self._recent = deque()
        self.rx_pose = 0
        self.lost = 0

    def start(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        if hasattr(socket, "SIO_UDP_CONNRESET"):
            try:
                s.ioctl(socket.SIO_UDP_CONNRESET, False)
            except OSError:
                pass
        s.bind(("0.0.0.0", self.port))
        s.settimeout(0.1)
        self.sock = s
        self.ping_targets = [self.phone_ip] if self.phone_ip else broadcast_targets()
        self.running = True
        self.thread = threading.Thread(target=self._run, name="rx", daemon=True)
        self.thread.start()
        self.log(f"Listening UDP :{self.port}")

    def stop(self):
        self.running = False
        if self.thread:
            self.thread.join(timeout=1.0)
        if self.sock:
            self.sock.close()

    def _run(self):
        while self.running:
            now = time.monotonic()
            with self.lock:
                if self.connected and now - self.last_rx > LINK_TIMEOUT:
                    self._disconnect(f"tidak ada paket {LINK_TIMEOUT:.0f} detik")
                need_ping = not self.connected and now - self._last_ping >= PING_INTERVAL
            if need_ping:
                self._send_pings(now)
            try:
                data, addr = self.sock.recvfrom(256)
            except (socket.timeout, ConnectionResetError):
                continue
            except OSError:
                if not self.running:
                    break
                time.sleep(0.2)
                continue
            self._handle(data, addr, time.monotonic())

    def _send_pings(self, now):
        targets = list(self.ping_targets)
        if not self.phone_ip and self.last_phone_ip:
            targets.insert(0, self.last_phone_ip)
        self._last_ping = now
        self.ping_count += 1
        pkt = pack_packet(T_PING, self._ping_seq)
        self._ping_seq += 1
        for ip in targets:
            try:
                self.sock.sendto(pkt, (ip, self.phone_port))
            except OSError as e:
                if now - self._last_send_err > 5:
                    self._last_send_err = now
                    self.log(f"gagal kirim ping ke {ip}: {e}")

    def _handle(self, data, addr, now):
        pkt = unpack_packet(data)
        if pkt is None or pkt[0] not in (T_PONG, T_POSE):
            return
        ptype, seq, vals = pkt
        with self.lock:
            if self.connected and addr[0] != self.phone_addr[0]:
                return
            if not self.connected:
                self._connect(addr, now)
            self.last_rx = now
            if self.sock and now - self._last_ack >= 1.0:
                self._last_ack = now
                try:
                    self.sock.sendto(pack_packet(T_ACK, self._ack_seq), addr)
                    self._ack_seq += 1
                except OSError:
                    pass
            if ptype == T_POSE:
                self._handle_pose(seq, vals, now)

    def _handle_pose(self, seq, vals, now):
        if any(not math.isfinite(v) or abs(v) > MAX_ABS_VALUE for v in vals):
            return
        if self._pose_seq is not None and now - self._last_pose_t < SEQ_RESET_GAP:
            gap = (seq - self._pose_seq) & 0xFFFFFFFF
            if gap == 0 or gap >= 0x80000000:
                return
            self.lost += gap - 1
        self._pose_seq = seq
        self._last_pose_t = now
        self.rx_pose += 1
        self._recent.append(now)
        self.pose = Pose(*vals)
        self.have_pose = True
        if self.out:
            self.out.update(self.pose)

    def _connect(self, addr, now):
        self.connected = True
        self.phone_addr = addr
        self.last_phone_ip = addr[0]
        self.last_rx = now
        self._pose_seq = None
        self.rx_pose = self.lost = 0
        self._recent.clear()
        self.log(f"CONNECTED  HP {addr[0]}:{addr[1]}  (berhenti ping setelah {self.ping_count}x)")

    def _disconnect(self, reason):
        self.connected = False
        self.have_pose = False
        self.ping_count = 0
        self._last_ping = 0.0
        self.log(f"DISCONNECTED ({reason}) - mulai ping lagi")
        if self.out:
            self.out.reset()

    def snapshot(self):
        now = time.monotonic()
        with self.lock:
            while self._recent and now - self._recent[0] > 1.0:
                self._recent.popleft()
            total = self.rx_pose + self.lost
            return {
                "connected": self.connected,
                "phone": self.phone_addr,
                "link_age": (now - self.last_rx) if self.last_rx else None,
                "pings": self.ping_count,
                "targets": list(self.ping_targets),
                "pps": float(len(self._recent)),
                "loss_pct": (100.0 * self.lost / total) if total else 0.0,
                "pose": self.pose if self.have_pose else None,
            }


class Logger:
    def __init__(self, plain):
        self.plain = plain
        self.lines = deque(maxlen=10)
        self.lock = threading.Lock()

    def __call__(self, msg):
        line = f"{time.strftime('%H:%M:%S')}  {msg}"
        with self.lock:
            self.lines.append(line)
        if self.plain:
            print(line, flush=True)

    def tail(self):
        with self.lock:
            return list(self.lines)


GREEN, RED, YELLOW, DIM, RESET = "\033[92m", "\033[91m", "\033[93m", "\033[2m", "\033[0m"


def render(rx, logger, ips, out, test):
    s = rx.snapshot()
    g = out.status()
    lines = [" ETS2 Head Tracker - Receiver".ljust(52) + "[Q] quit", " " + "-" * 60]
    lines.append(f" Listen   : UDP {rx.port} (HP :{rx.phone_port})   PC IP: {', '.join(ips) or '?'}")
    if s["connected"]:
        lines.append(f" Status   : {GREEN}● CONNECTED{RESET}  HP {s['phone'][0]}  (paket terakhir {s['link_age']:.2f}s lalu)")
        lines.append(f" Ping     : {DIM}berhenti (sudah konek){RESET}")
    else:
        lines.append(f" Status   : {RED}○ DISCONNECTED{RESET}  menunggu HP...")
        lines.append(f" Ping     : {YELLOW}ping #{s['pings']} -> {', '.join(s['targets'])}{RESET}")
    lines.append(f" Stream   : {s['pps']:.1f} pose/s   loss {s['loss_pct']:.1f}%")
    tag = f"  {YELLOW}[TEST {test}]{RESET}" if test else ""
    where = f"{SHM_NAME}" if out.real else "bukan Windows, tidak terlihat game"
    lines.append(f" Game     : {GREEN}●{RESET} {where}  smooth {out.k:g}  id {g['id']}{tag}")
    lines.append(f" Output   : yaw {g['yaw']:+8.2f}   pitch {g['pitch']:+8.2f}   roll {g['roll']:+8.2f}   (deg)")
    p = s["pose"]
    if p:
        lines.append(f" Posisi   : x   {p.x:+8.2f}   y     {p.y:+8.2f}   z    {p.z:+8.2f}   (cm)")
    else:
        lines.append(" Posisi   : -")
    lines.append(" " + "-" * 60)
    lines.append(" Log")
    lines += ["  " + l for l in logger.tail()]
    return "\033[H" + "\n".join(l + "\033[K" for l in lines) + "\n\033[J"


def key_pressed():
    try:
        import msvcrt
        if msvcrt.kbhit():
            return msvcrt.getwch().lower()
    except ImportError:
        pass
    return None


def selftest_loop(out, mode, stop):
    t0 = time.monotonic()
    while not stop.is_set():
        t = time.monotonic() - t0
        yaw = 0.0 if mode == "freeze" else 20.0 * math.sin(2.0 * math.pi * 0.25 * t)
        out.update(Pose(yaw))
        stop.wait(1.0 / 30.0)


def main():
    ap = argparse.ArgumentParser(description="ETS2 Head Tracker receiver")
    ap.add_argument("--port", type=int)
    ap.add_argument("--phone-port", type=int)
    ap.add_argument("--phone-ip")
    ap.add_argument("--smooth", type=float)
    ap.add_argument("--selftest", choices=["freeze", "sine"])
    args = ap.parse_args()

    cfg = load_config()
    cli = {"port": args.port, "phone_port": args.phone_port, "phone_ip": args.phone_ip, "smooth": args.smooth}
    given = {k: v for k, v in cli.items() if v is not None}
    interactive = sys.stdout.isatty() and sys.stdin.isatty() and not given and not args.selftest
    if interactive:
        os.system("")
        print("\n ETS2 Head Tracker")
        print(f"  Port PC {cfg['port']} | Port HP {cfg['phone_port']} | Smoothing {cfg['smooth']} | IP HP {cfg['phone_ip'] or 'otomatis'}")
        if input("  Ubah pengaturan? (y/N): ").strip().lower() == "y":
            cfg = settings_menu(cfg)
    cfg.update(given)
    if not (1024 <= cfg["port"] <= 65535 and 1024 <= cfg["phone_port"] <= 65535) or cfg["port"] == cfg["phone_port"]:
        print("Port tidak valid (1024-65535, port PC dan HP harus beda)")
        return 1
    cfg["smooth"] = min(1.0, max(0.01, cfg["smooth"]))
    if not args.selftest:
        save_config(cfg)

    plain = not sys.stdout.isatty()
    logger = Logger(plain)
    try:
        out = GameOutput(cfg["smooth"])
    except OSError as e:
        print(f"Gagal membuat shared memory {SHM_NAME}: {e}")
        return 1
    rx = Receiver(None if args.selftest else out, cfg["phone_ip"] or None, logger, cfg["port"], cfg["phone_port"])
    ips = local_ips()

    if not plain:
        os.system("")
        sys.stdout.write("\033[2J")

    try:
        rx.start()
    except OSError as e:
        print(f"Gagal bind UDP {cfg['port']}: {e}\nSudah ada receiver lain yang jalan, atau port dipakai program lain?")
        if interactive:
            input("Tekan Enter untuk keluar...")
        return 1
    out.start()
    stop_test = threading.Event()
    if args.selftest:
        logger(f"SELFTEST '{args.selftest}': data HP diabaikan")
        threading.Thread(target=selftest_loop, args=(out, args.selftest, stop_test), daemon=True).start()

    last_plain = 0.0
    try:
        while True:
            if plain:
                if time.monotonic() - last_plain > 2.0:
                    last_plain = time.monotonic()
                    s, g = rx.snapshot(), out.status()
                    state = "CONNECTED" if s["connected"] else f"DISCONNECTED ping#{s['pings']}"
                    print(
                        f"[status] {state} | {s['pps']:.0f} pose/s loss {s['loss_pct']:.1f}% | "
                        f"out yaw {g['yaw']:+.1f} pitch {g['pitch']:+.1f} roll {g['roll']:+.1f}",
                        flush=True,
                    )
            else:
                sys.stdout.write(render(rx, logger, ips, out, args.selftest))
                sys.stdout.flush()
                if key_pressed() == "q":
                    break
            time.sleep(0.2)
    except KeyboardInterrupt:
        pass
    finally:
        stop_test.set()
        for stop in (rx.stop, out.stop):
            try:
                stop()
            except KeyboardInterrupt:
                pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
