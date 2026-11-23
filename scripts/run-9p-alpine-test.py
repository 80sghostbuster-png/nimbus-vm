#!/usr/bin/env python3
"""Quick ARM64 Alpine virt-kernel test for virtio-9p shared folders."""
from __future__ import annotations
import os, shutil, socket, subprocess, sys, time
from pathlib import Path

ROOT = Path("/workspace/orion-vm")
QEMU = ROOT / "native/qemu-host-bld/qemu-system-aarch64"
KERNEL = ROOT / "tests/images/alpine/vmlinuz-virt"
INITRD = ROOT / "tests/images/alpine/initramfs-virt"
SHARE = ROOT / "tests/share"
EVIDENCE = ROOT / "evidence"
WORK = ROOT / "tests/work"
SERIAL_SOCK = WORK / "alpine-serial.sock"
SERIAL_LOG = EVIDENCE / "alpine-9p-serial.log"

def log(m):
    print(m, flush=True)

def recv_some(sock, timeout=1.0):
    sock.settimeout(timeout)
    data = b""
    try:
        while True:
            chunk = sock.recv(4096)
            if not chunk:
                break
            data += chunk
            sock.settimeout(0.15)
    except socket.timeout:
        pass
    return data

def main():
    EVIDENCE.mkdir(exist_ok=True)
    WORK.mkdir(exist_ok=True)
    SHARE.mkdir(exist_ok=True)
    (SHARE / "hello.txt").write_text("nimbus-engine-9p-ok\n")
    if (SHARE / "from-alpine.txt").exists():
        (SHARE / "from-alpine.txt").unlink()
    if SERIAL_SOCK.exists():
        SERIAL_SOCK.unlink()
    SERIAL_LOG.write_text("")

    cmd = [
        str(QEMU),
        "-accel", "tcg,thread=multi,tb-size=64",
        "-machine", "virt,gic-version=3",
        "-cpu", "max",
        "-smp", "2",
        "-m", "384",
        "-kernel", str(KERNEL),
        "-initrd", str(INITRD),
        "-append", "console=ttyAMA0 earlyprintk=pl011,0x09000000 ip=dhcp",
        "-display", "none",
        "-serial", f"unix:{SERIAL_SOCK},server,nowait",
        "-fsdev", f"local,id=fsdev0,path={SHARE},security_model=none",
        "-device", "virtio-9p-pci,fsdev=fsdev0,mount_tag=share",
        "-netdev", "user,id=net0",
        "-device", "virtio-net-pci,netdev=net0",
    ]
    log("launching Alpine ARM64 9p test")
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        for _ in range(50):
            if SERIAL_SOCK.exists():
                break
            if proc.poll() is not None:
                out = proc.stdout.read().decode(errors="replace") if proc.stdout else ""
                raise SystemExit(f"qemu exited early {proc.returncode}: {out}")
            time.sleep(0.1)
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.connect(str(SERIAL_SOCK))
        buf = b""
        deadline = time.time() + 90
        prompted = False
        while time.time() < deadline:
            buf += recv_some(sock, 1.0)
            SERIAL_LOG.write_bytes(buf)
            text = buf.decode("utf-8", "replace")
            if (not prompted) and ("login:" in text or "localhost" in text or "~#" in text or "~ #" in text or "recovery shell" in text or "\n# " in text):
                prompted = True
                log("got prompt, sending 9p commands")
                # If login prompt, log in as root (Alpine virt, empty password)
                if "login:" in text and "recovery shell" not in text:
                    sock.sendall(b"root\n")
                    time.sleep(1)
                    buf += recv_some(sock, 2)
                cmds = (
                    "set -x; mkdir -p /mnt/share; "
                    "modprobe 9pnet 2>/dev/null; modprobe 9pnet_virtio 2>/dev/null; modprobe 9p 2>/dev/null; "
                    "ls /lib/modules/$(uname -r)/ 2>/dev/null | head; "
                    "grep -R 9P /lib/modules/$(uname -r)/modules.dep 2>/dev/null | head; "
                    "mount -t 9p -o trans=virtio,version=9p2000.L share /mnt/share && "
                    "echo NIMBUS_9P ok content=$(cat /mnt/share/hello.txt) && "
                    "echo alpine-was-here > /mnt/share/from-alpine.txt && "
                    "echo NIMBUS_9P_WRITTEN; "
                    "uname -a; "
                    "poweroff -f\n"
                )
                sock.sendall(cmds.encode())
            if b"NIMBUS_9P ok" in buf:
                log("9p success marker seen on serial")
                break
            if proc.poll() is not None:
                break
        time.sleep(2)
        buf += recv_some(sock, 1)
        SERIAL_LOG.write_bytes(buf)
        sock.close()
    finally:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()

    text = SERIAL_LOG.read_text(errors="replace")
    guest_file = SHARE / "from-alpine.txt"
    ok = "NIMBUS_9P ok" in text or (guest_file.exists() and "alpine-was-here" in guest_file.read_text())
    log(f"9p_ok={ok} guest_file={guest_file.exists()}")
    if guest_file.exists():
        log("from-alpine: " + guest_file.read_text())
    # Print a short excerpt
    for line in text.splitlines():
        if "NIMBUS_" in line or "9p" in line.lower() or "login" in line or line.startswith("Linux "):
            log("EXCERPT: " + line)
    sys.exit(0 if ok else 2)

if __name__ == "__main__":
    main()
