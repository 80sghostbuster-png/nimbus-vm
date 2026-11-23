#!/usr/bin/env bash
# Boot an official arm64 ISO with the v0.2.1 app argv (gpu0 + VNC display=gpu0 + multitouch).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
QEMU="${QEMU:-$ROOT/native/qemu-host-bld/qemu-system-aarch64}"
IMG="${IMG:-$ROOT/native/qemu-host-bld/qemu-img}"
ISO="${ISO:?set ISO=}"
WORK="${1:?work dir}"
mkdir -p "$WORK"/{data,firmware,vm,tmp,cache,runtime,share}

if [[ ! -s "$WORK/data/efi-virtio.rom" ]]; then
  if [[ -s "$ROOT/tests/work/display-fix/data/efi-virtio.rom" ]]; then
    cp -a "$ROOT/tests/work/display-fix/data/." "$WORK/data/"
    cp -a "$ROOT/tests/work/display-fix/firmware/." "$WORK/firmware/"
  elif [[ -s "$ROOT/tests/work/debian-netinst/data/efi-virtio.rom" ]]; then
    cp -a "$ROOT/tests/work/debian-netinst/data/." "$WORK/data/"
    cp -a "$ROOT/tests/work/debian-netinst/firmware/." "$WORK/firmware/"
  else
    echo "missing staged qemu data"; exit 1
  fi
fi
[[ -s "$ISO" ]] || { echo "missing ISO $ISO"; exit 1; }

if [[ ! -s "$WORK/vm/vars-arm64.qcow2" ]]; then
  cp "$WORK/firmware/AAVMF_VARS.fd" "$WORK/vm/vars-arm64.fd"
  "$IMG" create -f qcow2 -F raw -b "$WORK/vm/vars-arm64.fd" "$WORK/vm/vars-arm64.qcow2"
fi
if [[ ! -s "$WORK/vm/disk.qcow2" ]]; then
  "$IMG" create -f qcow2 "$WORK/vm/disk.qcow2" 32G
fi

SERIAL="$WORK/vm/serial.log"
QMP="$WORK/vm/qmp.sock"
VNC="$WORK/vm/vnc.sock"
STDERR="$WORK/vm/qemu-stderr.log"
: > "$SERIAL"
: > "$STDERR"
rm -f "$QMP" "$VNC"

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
  --bind "$ISO" "$ISO" \
  --chdir "$WORK/vm" \
  --setenv HOME "$WORK/vm" \
  --setenv TMPDIR "$WORK/tmp" \
  --setenv TMP "$WORK/tmp" \
  --setenv TEMP "$WORK/tmp" \
  --setenv XDG_CACHE_HOME "$WORK/cache" \
  --setenv XDG_RUNTIME_DIR "$WORK/runtime" \
  --setenv XDG_DATA_HOME "$WORK/vm" \
  --die-with-parent \
  "$QEMU" \
    -L "$WORK/data" \
    -accel tcg,thread=multi,tb-size=128 \
    -smp 4 \
    -m 4096 \
    -display none \
    -serial "file:$SERIAL" \
    -qmp "unix:$QMP,server,nowait" \
    -netdev user,id=net0 \
    -device virtio-net-pci,netdev=net0,romfile= \
    -device qemu-xhci,id=xhci \
    -device usb-kbd,bus=xhci.0 \
    -device usb-tablet,bus=xhci.0 \
    -device virtio-multitouch-pci,id=touch0 \
    -boot order=cd \
    -machine virt,gic-version=3,virtualization=off,acpi=on \
    -cpu max \
    -device virtio-gpu-pci,id=gpu0,xres=1280,yres=800 \
    -drive "if=pflash,format=raw,readonly=on,file=$WORK/firmware/AAVMF_CODE.fd" \
    -drive "if=pflash,format=qcow2,file=$WORK/vm/vars-arm64.qcow2" \
    -drive "if=virtio,format=qcow2,file=$WORK/vm/disk.qcow2,cache=writeback,aio=threads,discard=unmap,detect-zeroes=unmap" \
    -drive "if=virtio,format=raw,media=cdrom,readonly=on,file=$ISO" \
    -fsdev "local,id=fsdev0,path=$WORK/share,security_model=none" \
    -device virtio-9p-pci,fsdev=fsdev0,mount_tag=share \
    -vnc "unix:$VNC,display=gpu0" \
    > "$STDERR" 2>&1 &
echo $! > "$WORK/vm/qemu.pid"
echo "QEMU pid=$(cat "$WORK/vm/qemu.pid")  qmp=$QMP  vnc=$VNC"
