"""
Disko (Windows) — ekranın baskın rengini Yeelight ampule senkronlar.
Ekran yakalama: dxcam (DXGI Desktop Duplication), olmazsa mss.
"""
import colorsys
import json
import os
import re
import socket
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import numpy as np

APP_DIR = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "Disko")
CFG_PATH = os.path.join(APP_DIR, "config.json")

CAP_W, CAP_H = 96, 54
FRAME_S = 0.08        # ~12 analiz/sn
SEND_S = 0.11         # ampule en fazla ~9 güncelleme/sn
TRANSITION_MS = 150
ALPHA = 0.4           # yumuşatma
WARM = (255, 170, 90)


# ---------------------------------------------------------------- Yeelight
class Yeelight:
    PORT = 55443

    def __init__(self, host):
        self.host = host
        self.control = None
        self.out = None
        self.music = False
        self._id = 1
        self._last_bright = -1
        self._last_slow = 0.0

    def connect(self):
        c = socket.create_connection((self.host, self.PORT), timeout=3)
        c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        c.settimeout(None)
        self.control = c
        threading.Thread(target=self._drain, args=(c,), daemon=True).start()
        self._send(c, "set_power", ["on", "smooth", 300])

        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            srv.bind(("", 0))
            srv.listen(1)
            srv.settimeout(4)
            local_ip = c.getsockname()[0]
            self._send(c, "set_music", [1, local_ip, srv.getsockname()[1]])
            m, _ = srv.accept()
            m.settimeout(None)
            m.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.out = m
            self.music = True
        except Exception:
            self.out = c
            self.music = False
        finally:
            srv.close()

    def set_color(self, rgb, bright, duration_ms):
        o = self.out
        if o is None:
            return
        r, g, b = rgb
        color = (r << 16) | (g << 8) | b or 1
        bright = max(1, min(100, int(bright)))
        if not self.music:
            now = time.time()
            if now - self._last_slow < 1.1:
                return
            self._last_slow = now
            self._send(o, "set_scene", ["color", color, bright])
            self._last_bright = bright
            return
        d = max(30, duration_ms)
        self._send(o, "set_rgb", [color, "smooth", d])
        if bright != self._last_bright:
            self._send(o, "set_bright", [bright, "smooth", d])
            self._last_bright = bright

    def close(self):
        socks = {id(s): s for s in (self.out, self.control) if s is not None}
        self.out = None
        self.control = None
        for s in socks.values():
            try:
                s.close()
            except Exception:
                pass

    def _send(self, sock, method, params):
        msg = json.dumps({"id": self._id, "method": method, "params": params}) + "\r\n"
        self._id += 1
        sock.sendall(msg.encode())

    @staticmethod
    def _drain(sock):
        try:
            while sock.recv(1024):
                pass
        except Exception:
            pass


def _local_prefixes():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))  # paket gitmez, sadece yerel IP'yi öğrenir
        ips.add(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    try:
        ips.update(socket.gethostbyname_ex(socket.gethostname())[2])
    except Exception:
        pass
    pref = []
    for ip in ips:
        p = ip.split(".")
        if len(p) == 4 and (p[0] == "192" and p[1] == "168" or p[0] == "10"
                            or p[0] == "172" and 16 <= int(p[1]) <= 31):
            pfx = ".".join(p[:3])
            if pfx not in pref:
                pref.append(pfx)
    return pref


def discover(timeout=3.0):
    found = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(0.5)
        msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1982\r\n"
               "MAN: \"ssdp:discover\"\r\nST: wifi_bulb\r\n").encode()
        for _ in range(2):
            s.sendto(msg, ("239.255.255.250", 1982))
        end = time.time() + timeout
        while time.time() < end:
            try:
                data, _ = s.recvfrom(2048)
            except socket.timeout:
                continue
            m = re.search(r"yeelight://([0-9.]+):", data.decode(errors="ignore"))
            if m and m.group(1) not in found:
                found.append(m.group(1))
        s.close()
    except Exception:
        pass
    if found:
        return found

    def probe(host):
        try:
            with socket.create_connection((host, Yeelight.PORT), timeout=0.4):
                return host
        except Exception:
            return None

    hosts = [f"{p}.{i}" for p in _local_prefixes() for i in range(1, 255)]
    with ThreadPoolExecutor(64) as ex:
        return sorted(h for h in ex.map(probe, hosts) if h)


# ---------------------------------------------------------------- renk analizi
def _normalize(r, g, b):
    m = max(r, g, b)
    if m <= 0:
        return WARM
    k = 255.0 / m
    return tuple(int(min(255, max(0, c * k))) for c in (r, g, b))


def _boost(rgb):
    h, s, _ = colorsys.rgb_to_hsv(*(c / 255.0 for c in rgb))
    r, g, b = colorsys.hsv_to_rgb(h, min(1.0, s * 1.15), 1.0)
    return int(r * 255), int(g * 255), int(b * 255)


def analyze(frame):
    """frame: HxWx3 uint8 RGB (küçültülmüş). -> (rgb | None, parlaklık 0..1)"""
    px = frame.reshape(-1, 3).astype(np.float32)
    r, g, b = px[:, 0], px[:, 1], px[:, 2]
    luma = float((0.2126 * r + 0.7152 * g + 0.0722 * b).mean() / 255.0)

    mx = px.max(axis=1)
    mn = px.min(axis=1)
    valid = mx >= 24                      # siyah barlar ve karanlık hariç
    if not valid.any():
        return None, luma

    s = np.zeros_like(mx)
    s[valid] = (mx[valid] - mn[valid]) / mx[valid]
    v = mx / 255.0

    neu = valid & (s < 0.22)
    sat = valid & (s >= 0.22)

    nw = v[neu] * 0.6
    nW = float(nw.sum())

    W = np.zeros(24)
    total = 0.0
    if sat.any():
        rs, gs, bs = r[sat], g[sat], b[sat]
        mxs, mns = mx[sat], mn[sat]
        d = mxs - mns
        h = np.empty_like(d)
        m1 = mxs == rs
        m2 = (mxs == gs) & ~m1
        m3 = ~m1 & ~m2
        h[m1] = ((gs[m1] - bs[m1]) / d[m1]) % 6
        h[m2] = (bs[m2] - rs[m2]) / d[m2] + 2
        h[m3] = (rs[m3] - gs[m3]) / d[m3] + 4
        bins = np.clip((h / 6.0 * 24).astype(int), 0, 23)
        w = (s[sat] ** 2) * v[sat]
        W = np.bincount(bins, weights=w, minlength=24)
        total = float(W.sum())

    if total < 1e-3 and nW < 1e-3:
        return None, luma

    if total < 0.2 * nW:
        p = px[neu]
        avg = (p * nw[:, None]).sum(axis=0) / nW
        return _normalize(*avg), luma

    score = W + 0.5 * (np.roll(W, 1) + np.roll(W, -1))
    best = int(np.argmax(score))
    sel = (bins == best) | (bins == (best + 1) % 24) | (bins == (best - 1) % 24)
    p = px[sat][sel]
    ws = w[sel]
    avg = (p * ws[:, None]).sum(axis=0) / ws.sum()
    return _boost(_normalize(*avg)), luma


# ---------------------------------------------------------------- ekran yakalama
class Grabber:
    def __init__(self):
        self.cam = None
        self.sct = None
        try:
            import dxcam
            self.cam = dxcam.create(output_color="RGB")
        except Exception:
            self.cam = None
        if self.cam is None:
            import mss
            self.sct = mss.mss()
        self.last = None

    @property
    def method(self):
        return "DXGI" if self.cam is not None else "GDI"

    def grab(self):
        if self.cam is not None:
            f = self.cam.grab()
            if f is None:            # ekran değişmediyse dxcam None döner
                return self.last
        else:
            shot = self.sct.grab(self.sct.monitors[1])
            f = np.asarray(shot)[:, :, :3][:, :, ::-1]
        h, w = f.shape[:2]
        sy, sx = max(1, h // CAP_H), max(1, w // CAP_W)
        self.last = np.ascontiguousarray(f[::sy, ::sx])
        return self.last

    def close(self):
        try:
            if self.cam is not None:
                self.cam.release()
        except Exception:
            pass


# ---------------------------------------------------------------- senkron motoru
class Engine(threading.Thread):
    def __init__(self, ip, max_bright, on_status):
        super().__init__(daemon=True)
        self.ip = ip
        self.max_bright = max(1, min(100, max_bright))
        self.on_status = on_status
        self.stop_evt = threading.Event()

    def run(self):
        bulb = None
        grab = None
        try:
            self.on_status(f"Ampule bağlanıyor ({self.ip})…")
            bulb = Yeelight(self.ip)
            bulb.connect()
            grab = Grabber()
            mode = "music mode" if bulb.music else "YAVAŞ MOD: music mode açılamadı (güvenlik duvarı?)"
            self.on_status(f"Çalışıyor — {self.ip} ({mode}, {grab.method})")
        except Exception as e:
            self.on_status(f"Ampule bağlanamadı ({self.ip}): {e}")
            if bulb:
                bulb.close()
            return

        t = list(WARM)
        c = [float(x) for x in WARM]
        cb = 30.0
        sent = [-100.0, -100.0, -100.0]
        sent_b = -100
        last_send = 0.0

        while not self.stop_evt.is_set():
            t0 = time.time()
            try:
                frame = grab.grab()
                if frame is not None:
                    rgb, lum = analyze(frame)
                    if rgb is not None:
                        t = list(rgb)
                    lvl = min(1.0, max(0.0, lum / 0.45))
                    tb = 1 + (self.max_bright - 1) * (lvl ** 0.8)
                    for i in range(3):
                        c[i] += (t[i] - c[i]) * ALPHA
                    cb += (tb - cb) * ALPHA
                    bi = max(1, min(100, round(cb)))
                    diff = sum(abs(c[i] - sent[i]) for i in range(3))
                    now = time.time()
                    if (diff > 10 or abs(bi - sent_b) >= 2) and now - last_send >= SEND_S:
                        out = tuple(max(0, min(255, round(x))) for x in c)
                        try:
                            bulb.set_color(out, bi, TRANSITION_MS)
                        except OSError:
                            self.on_status("Bağlantı koptu, yeniden bağlanıyor…")
                            bulb.close()
                            bulb = Yeelight(self.ip)
                            bulb.connect()
                            sent_b = -100
                            self.on_status(f"Çalışıyor — {self.ip}")
                            continue
                        sent = list(c)
                        sent_b = bi
                        last_send = now
            except Exception as e:
                self.on_status(f"Hata: {e}")
                time.sleep(1)
                try:
                    grab.close()
                    grab = Grabber()   # çözünürlük değişimi vb. sonrası yeniden kur
                except Exception:
                    pass
            dt = time.time() - t0
            if dt < FRAME_S:
                self.stop_evt.wait(FRAME_S - dt)

        bulb.close()
        grab.close()
        self.on_status("Durduruldu")

    def stop(self):
        self.stop_evt.set()


# ---------------------------------------------------------------- ayarlar
def load_cfg():
    try:
        with open(CFG_PATH, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {"ip": "", "maxb": 100}


def save_cfg(cfg):
    try:
        os.makedirs(APP_DIR, exist_ok=True)
        with open(CFG_PATH, "w", encoding="utf-8") as f:
            json.dump(cfg, f)
    except Exception:
        pass


# ---------------------------------------------------------------- arayüz
def run_gui():
    import tkinter as tk
    from tkinter import ttk

    cfg = load_cfg()
    root = tk.Tk()
    root.title("Disko")
    root.resizable(False, False)

    frm = ttk.Frame(root, padding=16)
    frm.grid()

    ttk.Label(frm, text="Ampul IP adresi").grid(row=0, column=0, sticky="w")
    ip_var = tk.StringVar(value=cfg.get("ip", ""))
    ttk.Entry(frm, textvariable=ip_var, width=24).grid(row=1, column=0, columnspan=3, sticky="we", pady=(0, 8))

    ttk.Label(frm, text="Maks. parlaklık (%)").grid(row=2, column=0, sticky="w")
    mb_var = tk.StringVar(value=str(cfg.get("maxb", 100)))
    ttk.Entry(frm, textvariable=mb_var, width=8).grid(row=3, column=0, sticky="w", pady=(0, 8))

    status = tk.StringVar(value="Durduruldu")
    state = {"engine": None, "msg": None}

    def set_status(msg):
        state["msg"] = msg

    def poll():
        if state["msg"] is not None:
            status.set(state["msg"])
            state["msg"] = None
        root.after(300, poll)

    def start():
        ip = ip_var.get().strip()
        if not ip:
            status.set("Önce IP gir ya da Ampul Bul'a bas.")
            return
        e = state["engine"]
        if e and e.is_alive():
            status.set("Zaten çalışıyor.")
            return
        try:
            mb = max(1, min(100, int(mb_var.get())))
        except ValueError:
            mb = 100
        save_cfg({"ip": ip, "maxb": mb})
        eng = Engine(ip, mb, set_status)
        state["engine"] = eng
        eng.start()

    def stop():
        e = state["engine"]
        if e and e.is_alive():
            e.stop()

    def find():
        status.set("Ampul aranıyor…")

        def work():
            lst = discover()
            if lst:
                ip_var.set(lst[0])
                set_status("Bulundu: " + ", ".join(lst))
            else:
                set_status("Ampul bulunamadı. LAN Control açık mı?")
        threading.Thread(target=work, daemon=True).start()

    ttk.Button(frm, text="Başlat", command=start).grid(row=4, column=0, sticky="we")
    ttk.Button(frm, text="Durdur", command=stop).grid(row=4, column=1, sticky="we", padx=6)
    ttk.Button(frm, text="Ampul Bul", command=find).grid(row=4, column=2, sticky="we")
    ttk.Label(frm, textvariable=status, wraplength=320).grid(row=5, column=0, columnspan=3, sticky="w", pady=(12, 0))

    def on_close():
        stop()
        root.after(200, root.destroy)

    root.protocol("WM_DELETE_WINDOW", on_close)
    poll()
    root.mainloop()


# ---------------------------------------------------------------- CI testi
def selftest():
    f = np.zeros((CAP_H, CAP_W, 3), np.uint8)
    assert analyze(f)[0] is None
    f[10:44, :, :] = (200, 30, 20)          # kırmızı sahne + siyah barlar
    rgb, lum = analyze(f)
    assert rgb[0] == 255 and rgb[1] < 80 and rgb[2] < 80, rgb
    f[:, :] = (120, 120, 120)               # gri sahne
    rgb, _ = analyze(f)
    assert abs(rgb[0] - rgb[1]) < 3 and abs(rgb[1] - rgb[2]) < 3, rgb
    f[:, :] = (128, 128, 128)
    f[:, :30] = (20, 60, 230)               # mavi baskın
    rgb, _ = analyze(f)
    assert rgb[2] == 255 and rgb[0] < 100, rgb
    print("selftest ok")


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
    else:
        run_gui()
