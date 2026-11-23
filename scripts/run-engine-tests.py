#!/usr/bin/env python3
"""Nimbus VM phase-1 engine tests. ARM64-first, TCG only (matches Android)."""
from __future__ import annotations

import argparse
import json
import os
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / "evidence"
WORK = ROOT / "tests" / "work"
SHARE = ROOT / "tests" / "share"
FIRMWARE = ROOT / "firmware" / "edk2"
IMAGES = ROOT / "tests" / "images"


def log(msg: str) -> None:
    ts = time.strftime("%Y-%m-%d %H:%M:%S")
    line = f"[{ts} America/Chicago] {msg}"
    print(line, flush=True)
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    with (EVIDENCE / "engine-tests.log").open("a") as f:
        f.write(line + "\n")


class Qmp:
    def __init__(self, path: Path):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(30)
        self.sock.connect(str(path))
        self.buf = b""
        self.greeting = self._read_obj()

    def _read_obj(self):
        while True:
            if b"\n" in self.buf:
                line, self.buf = self.buf.split(b"\n", 1)
                if not line.strip():
                    continue
                return json.loads(line)
            data = self.sock.recv(1 << 16)
            if not data:
                raise RuntimeError("QMP socket closed")
            self.buf += data

    def cmd(self, execute: str, arguments=None, timeout=60):
        self.sock.settimeout(timeout)
        msg = {"execute": execute}
        if arguments is not None:
            msg["arguments"] = arguments
        self.sock.sendall((json.dumps(msg) + "\n").encode())
        while True:
            obj = self._read_obj()
            if "error" in obj or "return" in obj:
                return obj
            # async events are ignored

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


def find_qemu(prefer: Path | None, name: str) -> Path:
    candidates = []
    if prefer:
        prefer = Path(prefer)
        if prefer.is_file() and prefer.name.startswith(name.split()[0][:8]):
            # exact binary passed (qemu-system-* or qemu-img)
            candidates.append(prefer)
        if prefer.is_dir():
            candidates.append(prefer / name)
        elif prefer.is_file():
            candidates.append(prefer.parent / name)
    candidates += [
        ROOT / "native" / "qemu-host-bld" / name,
        ROOT / "native" / "qemu-host-prefix" / "bin" / name,
        Path(shutil.which(name) or ("/usr/bin/" + name)),
    ]
    for c in candidates:
        if c and c.is_file() and os.access(c, os.X_OK):
            return c
    raise SystemExit(f"missing {name}")


def qemu_version(bin_path: Path) -> str:
    out = subprocess.check_output([str(bin_path), "--version"], text=True)
    return out.splitlines()[0]


def wait_for_markers(serial: Path, share_log: Path, markers: list[str], timeout: int):
    deadline = time.time() + timeout
    last_report = 0.0
    blob = ""
    while time.time() < deadline:
        parts = []
        if serial.exists():
            parts.append(serial.read_text(errors="replace"))
        if share_log.exists():
            parts.append(share_log.read_text(errors="replace"))
        blob = "\n".join(parts)
        missing = [m for m in markers if m not in blob]
        if not missing:
            return blob
        now = time.time()
        if now - last_report >= 30:
            ser_sz = serial.stat().st_size if serial.exists() else 0
            log(f"waiting for {missing}; serial_bytes={ser_sz}")
            last_report = now
        time.sleep(2)
    return blob


def start_swtpm(state_dir: Path, sock: Path) -> subprocess.Popen:
    if sock.exists():
        sock.unlink()
    state_dir.mkdir(parents=True, exist_ok=True)
    cmd = [
        "swtpm",
        "socket",
        "--tpm2",
        "--tpmstate",
        f"dir={state_dir}",
        "--ctrl",
        f"type=unixio,path={sock}",
        "--flags",
        "not-need-init",
        "--log",
        f"file={state_dir}/swtpm.log,level=5",
    ]
    log("starting swtpm: " + " ".join(cmd))
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    for _ in range(50):
        if sock.exists():
            return p
        if p.poll() is not None:
            out = p.stdout.read().decode(errors="replace") if p.stdout else ""
            raise RuntimeError(f"swtpm exited {p.returncode}: {out}")
        time.sleep(0.1)
    raise RuntimeError("swtpm socket did not appear")


def arm64_cmd(qemu: Path, work: Path, overlay: Path, seed: Path, qmp: Path, serial: Path, tpm_sock: Path) -> list[str]:
    vars_qcow = work / "AAVMF_VARS.qcow2"
    vars_fd = vars_qcow if vars_qcow.exists() else work / "AAVMF_VARS.fd"
    if not vars_fd.exists():
        shutil.copy(FIRMWARE / "AAVMF_VARS.fd", vars_fd)
    fmt = "qcow2" if vars_fd.suffix == ".qcow2" else "raw"
    # ARM64 performance defaults we will also use on the phone (TCG, no KVM)
    return [
        str(qemu),
        "-accel", "tcg,thread=multi,tb-size=128",
        "-machine", "virt,gic-version=3,virtualization=off,acpi=on",
        "-cpu", "max",
        "-smp", "2",
        "-m", "1024",
        "-display", "none",
        "-serial", f"file:{serial}",
        "-qmp", f"unix:{qmp},server,nowait",
        "-drive", f"if=pflash,format=raw,readonly=on,file={FIRMWARE / 'AAVMF_CODE.fd'}",
        "-drive", f"if=pflash,format={fmt},file={vars_fd}",
        "-drive", f"if=virtio,id=hd0,format=qcow2,file={overlay},cache=writeback,aio=threads,discard=unmap,detect-zeroes=unmap",
        "-drive", f"if=virtio,id=cidata,format=raw,file={seed},readonly=on",
        "-netdev", "user,id=net0",
        "-device", "virtio-net-pci,netdev=net0",
        "-fsdev", f"local,id=fsdev0,path={SHARE},security_model=none",
        "-device", "virtio-9p-pci,fsdev=fsdev0,mount_tag=share",
        "-chardev", f"socket,id=chrtpm,path={tpm_sock}",
        "-tpmdev", "emulator,id=tpm0,chardev=chrtpm",
        "-device", "tpm-tis-device,tpmdev=tpm0",
        "-device", "qemu-xhci,id=xhci",
        "-device", "usb-kbd,bus=xhci.0",
        "-device", "usb-tablet,bus=xhci.0",
        "-device", "virtio-gpu-pci",
        "-name", "nimbus-arm64-test",
    ]


def qmp_sequence(qmp_path: Path, work: Path) -> dict:
    results = {}
    q = Qmp(qmp_path)
    results["greeting"] = q.greeting
    cap = q.cmd("qmp_capabilities")
    results["capabilities"] = cap
    st = q.cmd("query-status")
    results["status_running"] = st
    log(f"QMP status: {st}")

    # stop / cont  (suspend-in-place / resume)
    r = q.cmd("stop")
    results["stop"] = r
    st = q.cmd("query-status")
    results["status_stopped"] = st
    log(f"after stop: {st}")
    r = q.cmd("cont")
    results["cont"] = r
    st = q.cmd("query-status")
    results["status_resumed"] = st
    log(f"after cont: {st}")

    # Persistent suspend: first try savevm (needs snapshot-capable pflash + disk),
    # then always also try migrate-to-file (full VM state).
    snap = q.cmd("human-monitor-command", {"command-line": "savevm nimbus0"}, timeout=180)
    results["savevm"] = snap
    log(f"savevm: {snap}")
    savevm_ok = "Error" not in json.dumps(snap)
    if savevm_ok:
        load = q.cmd("human-monitor-command", {"command-line": "loadvm nimbus0"}, timeout=180)
        results["loadvm"] = load
        log(f"loadvm: {load}")

    suspend = work / "suspend.bin"
    if suspend.exists():
        suspend.unlink()
    mig = q.cmd("migrate", {"uri": f"file:{suspend}"}, timeout=180)
    results["migrate"] = mig
    log(f"migrate: {mig}")
    for _ in range(60):
        st = q.cmd("query-migrate", timeout=30)
        status = (st.get("return") or {}).get("status")
        results["migrate_status"] = st
        log(f"query-migrate: {status}")
        if status in ("completed", "failed", "cancelled"):
            break
        time.sleep(1)
    results["suspend_bytes"] = suspend.stat().st_size if suspend.exists() else 0
    # Source VM is paused after a completed migrate; resume so ACPI powerdown still works.
    try:
        q.cmd("cont")
        results["status_after_migrate"] = q.cmd("query-status")
        log(f"after migrate+cont: {results['status_after_migrate']}")
    except Exception as e:
        log(f"cont after migrate: {e}")

    # ACPI shutdown while the OS is still running (do not reset first)
    pd = q.cmd("system_powerdown")
    results["system_powerdown"] = pd
    log("issued system_powerdown")
    q.close()
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--qemu", type=Path, default=None)
    parser.add_argument("--timeout", type=int, default=1500, help="seconds to wait for guest ready")
    parser.add_argument("--skip-boot", action="store_true")
    args = parser.parse_args()

    EVIDENCE.mkdir(parents=True, exist_ok=True)
    WORK.mkdir(parents=True, exist_ok=True)
    SHARE.mkdir(parents=True, exist_ok=True)

    qemu = find_qemu(args.qemu, "qemu-system-aarch64")
    qemu_img = find_qemu(args.qemu.parent if args.qemu else None, "qemu-img")
    log(f"using {qemu}")
    log(qemu_version(qemu))
    log(f"qemu-img: {qemu_img} {qemu_version(qemu_img)}")

    image = IMAGES / "debian-13-genericcloud-arm64.qcow2"
    if not image.exists():
        raise SystemExit(f"missing image {image}")

    seed = WORK / "seed-arm64.iso"
    subprocess.check_call(
        [
            "cloud-localds",
            str(seed),
            str(ROOT / "tests" / "cloud-init" / "user-data"),
            str(ROOT / "tests" / "cloud-init" / "meta-data"),
        ]
    )

    overlay = WORK / "debian13-arm64-overlay.qcow2"
    if overlay.exists():
        overlay.unlink()
    subprocess.check_call(
        [
            str(qemu_img),
            "create",
            "-f",
            "qcow2",
            "-F",
            "qcow2",
            "-b",
            str(image.resolve()),
            str(overlay),
            "8G",
        ]
    )
    raw_vars = WORK / "AAVMF_VARS.raw"
    shutil.copy(FIRMWARE / "AAVMF_VARS.fd", raw_vars)
    vars_qcow = WORK / "AAVMF_VARS.qcow2"
    if vars_qcow.exists():
        vars_qcow.unlink()
    subprocess.check_call(
        [
            str(qemu_img),
            "create",
            "-f",
            "qcow2",
            "-F",
            "raw",
            "-b",
            str(raw_vars.resolve()),
            str(vars_qcow),
        ]
    )

    share_log = SHARE / "probe.log"
    if share_log.exists():
        share_log.unlink()
    from_guest = SHARE / "from-guest.txt"
    if from_guest.exists():
        from_guest.unlink()

    qmp_path = WORK / "arm64.qmp"
    serial = EVIDENCE / "arm64-serial.log"
    if serial.exists():
        serial.unlink()
    if qmp_path.exists():
        qmp_path.unlink()

    tpm_dir = WORK / "swtpm-arm64"
    tpm_sock = tpm_dir / "swtpm.sock"
    swtpm = start_swtpm(tpm_dir, tpm_sock)

    cmd = arm64_cmd(qemu, WORK, overlay, seed, qmp_path, serial, tpm_sock)
    (EVIDENCE / "arm64-qemu-cmd.txt").write_text(" ".join(cmd) + "\n")
    log("launching ARM64 Debian 13 UEFI guest under TCG")
    t0 = time.time()
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        # wait for QMP
        for _ in range(100):
            if qmp_path.exists():
                break
            if proc.poll() is not None:
                out = proc.stdout.read().decode(errors="replace") if proc.stdout else ""
                raise RuntimeError(f"qemu exited early {proc.returncode}: {out}")
            time.sleep(0.1)
        log("QMP socket is up")
        blob = wait_for_markers(
            serial,
            share_log,
            ["NIMBUS_READY", "NIMBUS_UNAME", "NIMBUS_ARCH"],
            args.timeout,
        )
        (EVIDENCE / "arm64-serial-complete.log").write_text(blob)
        elapsed = time.time() - t0
        log(f"marker wait finished in {elapsed:.1f}s")
        ready = "NIMBUS_READY" in blob
        tpm_ok = "NIMBUS_TPM present" in blob
        net_ok = "NIMBUS_HTTP ok" in blob or "NIMBUS_DNS ok" in blob
        p9_ok = "NIMBUS_9P ok" in blob
        log(f"RESULTS ready={ready} tpm={tpm_ok} net={net_ok} 9p={p9_ok}")

        qmp_results = {}
        qmp_err = None
        if qmp_path.exists() and proc.poll() is None:
            try:
                qmp_results = qmp_sequence(qmp_path, WORK)
            except Exception as e:
                qmp_err = repr(e)
                log(f"QMP sequence error: {e}")

        # wait for guest to power down, then kill if needed
        try:
            proc.wait(timeout=120)
            log(f"qemu exited {proc.returncode} after powerdown wait")
        except subprocess.TimeoutExpired:
            log("guest did not exit after powerdown; quitting via kill")
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()

        summary = {
            "qemu": str(qemu),
            "qemu_version": qemu_version(qemu),
            "elapsed_sec": elapsed,
            "ready": ready,
            "tpm": tpm_ok,
            "net": net_ok,
            "ninep": p9_ok,
            "from_guest": from_guest.read_text() if from_guest.exists() else None,
            "qmp": qmp_results,
            "qmp_error": qmp_err,
            "serial_excerpt": "\n".join(
                [
                    ln
                    for ln in blob.splitlines()
                    if "NIMBUS_" in ln or "Debian GNU/Linux" in ln or "login:" in ln
                ][-40:]
            ),
        }
        (EVIDENCE / "arm64-summary.json").write_text(json.dumps(summary, indent=2, default=str))
        log("wrote evidence/arm64-summary.json")
        if not ready:
            sys.exit(2)
    finally:
        if proc.poll() is None:
            proc.kill()
        if swtpm.poll() is None:
            swtpm.terminate()
            try:
                swtpm.wait(timeout=5)
            except subprocess.TimeoutExpired:
                swtpm.kill()


if __name__ == "__main__":
    main()
