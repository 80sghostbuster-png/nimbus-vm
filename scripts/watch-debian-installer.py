#!/usr/bin/env python3
"""Watch running QEMU (app argv) and capture QMP screendumps (PPM→PNG) until the graphical installer appears."""
import json, os, socket, sys, time, subprocess
from pathlib import Path
from PIL import Image, ImageStat

ROOT = Path("/workspace/orion-vm")
WORK = Path(sys.argv[1] if len(sys.argv) > 1 else ROOT / "tests/work/debian-netinst")
QMP = WORK / "vm/qmp.sock"
PPM = WORK / "vm/watch.ppm"
SHOTS = ROOT / "screenshots"
EVID = ROOT / "evidence"
SHOTS.mkdir(parents=True, exist_ok=True)
EVID.mkdir(parents=True, exist_ok=True)
PIDFILE = WORK / "vm/qemu.pid"
SERIAL = WORK / "vm/serial.log"
STDERR = WORK / "vm/qemu-stderr.log"

KEYWORDS = (
    "select a language", "choose a language", "choose language",
    "select your language", "debian installer", "debian-installer",
    "configure the keyboard", "select a keyboard", "select a locale",
    "cinnamon", "choose a language",
)

def qmp(cmd, args=None, timeout=8):
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(timeout)
    s.connect(str(QMP))
    f = s.makefile("rwb", buffering=0)
    def read_obj():
        line = f.readline()
        if not line:
            raise RuntimeError("eof")
        return json.loads(line.decode())
    greeting = read_obj()
    f.write(b'{"execute":"qmp_capabilities"}\n')
    cap = read_obj()
    msg = {"execute": cmd}
    if args:
        msg["arguments"] = args
    f.write((json.dumps(msg) + "\n").encode())
    deadline = time.time() + timeout
    result = None
    while time.time() < deadline:
        try:
            obj = read_obj()
        except socket.timeout:
            break
        if "error" in obj or "return" in obj:
            result = obj
            break
    s.close()
    return greeting, cap, result

def send_key(qcode, hold=0.08):
    qmp("input-send-event", {"events": [
        {"type": "key", "data": {"down": True, "key": {"type": "qcode", "data": qcode}}},
    ]})
    time.sleep(hold)
    qmp("input-send-event", {"events": [
        {"type": "key", "data": {"down": False, "key": {"type": "qcode", "data": qcode}}},
    ]})

def qemu_alive():
    if not PIDFILE.exists():
        return False
    pid = PIDFILE.read_text().strip()
    return Path(f"/proc/{pid}").exists() or Path("/proc").exists() and any(
        "qemu-system-aarch64" in Path(f"/proc/{p}/cmdline").read_text(errors="ignore").replace("\x00"," ")
        for p in os.listdir("/proc") if p.isdigit() and Path(f"/proc/{p}/cmdline").exists()
    )

def ocr(path):
    try:
        r = subprocess.run(["tesseract", str(path), "stdout", "-l", "eng"], capture_output=True, text=True, timeout=30)
        return (r.stdout or "").strip()
    except Exception:
        return ""

def image_stats(path):
    im = Image.open(path)
    st = ImageStat.Stat(im.convert("L"))
    colors = len(im.convert("RGB").getcolors(maxcolors=1_000_000) or [])
    return {"w": im.width, "h": im.height, "mean": st.mean[0], "stddev": st.stddev[0], "colors": colors}

def dump_png(dest: Path):
    _, _, res = qmp("screendump", {"filename": str(PPM), "format": "ppm"})
    if res and "error" in res:
        raise RuntimeError(res)
    if not PPM.exists():
        raise RuntimeError("screendump produced no file")
    im = Image.open(PPM)
    dest.parent.mkdir(parents=True, exist_ok=True)
    im.save(dest)
    return im, res

def main():
    print("waiting for QMP", QMP)
    for _ in range(600):
        if QMP.exists():
            break
        time.sleep(0.1)
    else:
        print("no QMP"); return 1
    time.sleep(0.5)
    print("query-status", qmp("query-status")[2])

    found = None
    deadline = time.time() + 25 * 60
    i = 0
    while time.time() < deadline:
        i += 1
        dest = SHOTS / f"netinst-{i:03d}.png"
        try:
            im, res = dump_png(dest)
        except Exception as e:
            print(f"screendump {i} failed: {e}")
            if not qemu_alive():
                print("qemu died")
                print(STDERR.read_text()[-4000:] if STDERR.exists() else "")
                return 1
            time.sleep(5)
            continue
        stats = image_stats(dest)
        text = ocr(dest)
        serial_tail = SERIAL.read_text(errors="replace")[-2000:] if SERIAL.exists() else ""
        print(f"[{i}] {dest.name} bytes={dest.stat().st_size} stats={stats} ocr={text[:160]!r}")
        low = text.lower()
        (SHOTS / "debian-netinst-latest.png").write_bytes(dest.read_bytes())
        # Language / installer screen (not GRUB, not a black post-boot frame).
        if stats.get("mean", 0) < 2 or stats.get("colors", 0) < 8:
            time.sleep(10)
            continue
        installer = any(k in low for k in KEYWORDS) or (
            "english" in low and "language" in low and "grub" not in low
        ) or (
            "debian" in low and "install" in low and "grub" not in low
            and stats.get("colors", 0) > 80
        )
        if installer:
            proof = SHOTS / "debian-installer.png"
            proof.write_bytes(dest.read_bytes())
            (EVID / "debian-installer-ocr.txt").write_text(text)
            print("PROOF", proof)
            print(text[:800])
            found = str(proof)
            break
        time.sleep(10)

    if not found:
        print("installer not clearly detected; latest dump kept")
        return 2
    print("OK", found)
    return 0

if __name__ == "__main__":
    sys.exit(main())
