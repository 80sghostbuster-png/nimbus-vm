#!/usr/bin/env bash
# Simulate the Android harness on this box: same argv, fresh dirs, no system QEMU data.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
QEMU="${QEMU:-$ROOT/native/qemu-host-bld/qemu-system-aarch64}"
IMG="${IMG:-$ROOT/native/qemu-host-bld/qemu-img}"
WORK="${1:-$ROOT/tests/work/android-sim}"
rm -rf "$WORK"
mkdir -p "$WORK"/{data,data/keymaps,firmware,vm,tmp,cache,runtime}

unpack_gz_tree() {
  local src="$1" dest="$2"
  mkdir -p "$dest"
  find "$src" -type f | while read -r f; do
    rel="${f#"$src"/}"
    out="$dest/${rel%.gz}"
    mkdir -p "$(dirname "$out")"
    if [[ "$f" == *.gz ]]; then
      gzip -dc "$f" > "$out"
    else
      cp "$f" "$out"
    fi
  done
}
unpack_gz_tree "$ROOT/app/app/src/main/assets/qemu-data" "$WORK/data"
if [[ -s "$ROOT/firmware/edk2/AAVMF_CODE.fd" ]]; then
  cp -a "$ROOT/firmware/edk2/." "$WORK/firmware/"
else
  unpack_gz_tree "$ROOT/app/app/src/main/assets/firmware" "$WORK/firmware"
fi

[[ -s "$WORK/data/efi-virtio.rom" ]] || { echo "MISSING efi-virtio.rom in staged data"; exit 1; }
[[ -s "$WORK/firmware/AAVMF_CODE.fd" ]] || { echo "MISSING AAVMF_CODE.fd"; exit 1; }

cp "$WORK/firmware/AAVMF_VARS.fd" "$WORK/vm/vars-arm64.fd"
"$IMG" create -f qcow2 -F raw -b "$WORK/vm/vars-arm64.fd" "$WORK/vm/vars-arm64.qcow2" >/dev/null

SERIAL="$WORK/vm/serial.log"
QMP="$WORK/vm/qmp.sock"
STDERR="$WORK/vm/qemu-stderr.log"

stop_qemu() {
  local pid
  pid=$(cat "$WORK/vm/qemu.pid" 2>/dev/null || true)
  if [[ -n "${pid:-}" ]]; then
    python3 -c '
import socket, sys, time
path=sys.argv[1]
s=socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.settimeout(2)
try:
    s.connect(path)
    s.recv(4096)
    s.sendall(b"{\"execute\":\"qmp_capabilities\"}\n")
    s.recv(4096)
    s.sendall(b"{\"execute\":\"quit\"}\n")
    time.sleep(0.3)
except Exception:
    pass
try:
    s.close()
except Exception:
    pass
' "$QMP" || true
    kill "$pid" 2>/dev/null || true
    sleep 0.4
    kill -9 "$pid" 2>/dev/null || true
    pkill -9 -f "$WORK/vm/vars-arm64.qcow2" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      if ! kill -0 "$pid" 2>/dev/null; then
        break
      fi
      sleep 0.3
    done
  fi
  rm -f "$QMP"
}

run_qemu() {
  local netdev_dev="$1"
  local tag="$2"
  stop_qemu
  : > "$SERIAL"
  : > "$STDERR"
  bwrap \
    --bind / / \
    --dev /dev \
    --proc /proc \
    --tmpfs /usr/share/qemu \
    --tmpfs /usr/share/AAVMF \
    --tmpfs /usr/share/OVMF \
    --tmpfs /usr/share/qemu-efi-aarch64 \
    --tmpfs /workspace/orion-vm/native/qemu-host-prefix \
    --tmpfs /workspace/orion-vm/native/android-prefix \
    --tmpfs /workspace/orion-vm/native/qemu-host-bld/qemu-bundle \
    --bind "$WORK" "$WORK" \
    --chdir "$WORK/vm" \
    --setenv HOME "$WORK/vm" \
    --setenv TMPDIR "$WORK/tmp" \
    --setenv TMP "$WORK/tmp" \
    --setenv TEMP "$WORK/tmp" \
    --setenv XDG_CACHE_HOME "$WORK/cache" \
    --setenv XDG_RUNTIME_DIR "$WORK/runtime" \
    --setenv XDG_DATA_HOME "$WORK/vm" \
    "$QEMU" \
      -L "$WORK/data" \
      -accel tcg,thread=multi,tb-size=128 \
      -smp 2 \
      -m 512 \
      -display none \
      -serial "file:$SERIAL" \
      -qmp "unix:$QMP,server,nowait" \
      -netdev user,id=net0 \
      -device "$netdev_dev" \
      -device qemu-xhci,id=xhci \
      -device usb-kbd,bus=xhci.0 \
      -device usb-tablet,bus=xhci.0 \
      -machine virt,gic-version=3,virtualization=off,acpi=on \
      -cpu max \
      -device virtio-gpu-pci \
      -drive "if=pflash,format=raw,readonly=on,file=$WORK/firmware/AAVMF_CODE.fd" \
      -drive "if=pflash,format=qcow2,file=$WORK/vm/vars-arm64.qcow2" \
      > "$STDERR" 2>&1 &
  echo $! > "$WORK/vm/qemu.pid"
  local pid
  pid=$(cat "$WORK/vm/qemu.pid")
  local i
  for i in $(seq 1 60); do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "$tag: qemu exited early: $(cat "$STDERR")"
      return 1
    fi
    if grep -a -q "UEFI firmware" "$SERIAL" 2>/dev/null; then
      echo "$tag: OK — UEFI firmware banner on serial after ${i}s"
      return 0
    fi
    if grep -a -q "failed to find romfile" "$STDERR" 2>/dev/null; then
      echo "$tag: FAIL — missing romfile"
      cat "$STDERR"
      return 1
    fi
    sleep 1
  done
  echo "$tag: timeout. stderr:"
  cat "$STDERR"
  echo "--- serial ---"
  strings "$SERIAL" | head -20
  return 1
}

echo "efi-virtio.rom size=$(wc -c < "$WORK/data/efi-virtio.rom")"
echo "=== Test A: -L only, default virtio-net ROM (the phone crash) ==="
if run_qemu "virtio-net-pci,netdev=net0" A; then
  strings "$SERIAL" | head -15
  cp "$SERIAL" "$ROOT/evidence/sim-android-serial-A.log"
  cp "$STDERR" "$ROOT/evidence/sim-android-stderr-A.log"
  stop_qemu
else
  stop_qemu
  exit 1
fi

echo "=== Test B: exact app argv with romfile= ==="
if run_qemu "virtio-net-pci,netdev=net0,romfile=" B; then
  strings "$SERIAL" | head -15
  cp "$SERIAL" "$ROOT/evidence/sim-android-serial-B.log"
  cp "$STDERR" "$ROOT/evidence/sim-android-stderr-B.log"
  stop_qemu
else
  stop_qemu
  exit 1
fi

echo "ALL SIM TESTS PASSED"
