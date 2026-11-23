#!/usr/bin/env bash
# Boot the official Debian arm64 netinst ISO with the exact app argv (graphical + VNC + QMP).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
QEMU="${QEMU:-$ROOT/native/qemu-host-bld/qemu-system-aarch64}"
IMG="${IMG:-$ROOT/native/qemu-host-bld/qemu-img}"
ISO="${ISO:-$ROOT/tests/images/debian-13.8.0-arm64-netinst.iso}"
WORK="${1:-$ROOT/tests/work/debian-netinst}"
SHOTS="$ROOT/screenshots"
mkdir -p "$WORK"/{data,firmware,vm,tmp,cache,runtime,share} "$SHOTS"

unpack_gz_tree() {
  local src="$1" dest="$2"
  mkdir -p "$dest"
  find "$src" -type f | while read -r f; do
    rel="${f#"$src"/}"
    out="$dest/${rel%.gz}"
    mkdir -p "$(dirname "$out")"
    if [[ "$f" == *.gz ]]; then gzip -dc "$f" > "$out"; else cp "$f" "$out"; fi
  done
}
unpack_gz_tree "$ROOT/app/app/src/main/assets/qemu-data" "$WORK/data"
if [[ -s "$ROOT/firmware/edk2/AAVMF_CODE.fd" ]]; then
  cp -a "$ROOT/firmware/edk2/." "$WORK/firmware/"
else
  unpack_gz_tree "$ROOT/app/app/src/main/assets/firmware" "$WORK/firmware"
fi
[[ -s "$WORK/data/efi-virtio.rom" ]] || { echo "missing efi-virtio.rom"; exit 1; }
[[ -s "$ISO" ]] || { echo "missing ISO $ISO"; exit 1; }

# Host qemu-img for vars + 32G sparse disk (same as app Linux default)
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

# Hide system QEMU datadirs so only -L can supply ROMs (same as the hotfix test).
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
    -vnc "unix:$VNC" \
    -boot order=cd \
    -machine virt,gic-version=3,virtualization=off,acpi=on \
    -cpu max \
    -device virtio-gpu-pci \
    -drive "if=pflash,format=raw,readonly=on,file=$WORK/firmware/AAVMF_CODE.fd" \
    -drive "if=pflash,format=qcow2,file=$WORK/vm/vars-arm64.qcow2" \
    -drive "if=virtio,format=qcow2,file=$WORK/vm/disk.qcow2,cache=writeback,aio=threads,discard=unmap,detect-zeroes=unmap" \
    -drive "if=virtio,format=raw,media=cdrom,readonly=on,file=$ISO" \
    -fsdev "local,id=fsdev0,path=$WORK/share,security_model=none" \
    -device virtio-9p-pci,fsdev=fsdev0,mount_tag=share \
    > "$STDERR" 2>&1 &
echo $! > "$WORK/vm/qemu.pid"
echo "QEMU pid=$(cat "$WORK/vm/qemu.pid")  qmp=$QMP  vnc=$VNC"
# Monitor + screendump is a separate Python helper so this script can just launch QEMU.
