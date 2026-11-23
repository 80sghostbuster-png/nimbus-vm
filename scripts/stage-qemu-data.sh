#!/usr/bin/env bash
# Stage QEMU pc-bios option ROMs + keymaps as gzipped assets for -L on Android.
# Source: third_party/qemu-10.2.4/pc-bios (not sparc/ppc/s390/hppa/riscv/openbios).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/third_party/qemu-10.2.4/pc-bios"
DEST="$ROOT/app/app/src/main/assets/qemu-data"
if [[ ! -d "$SRC" ]]; then
  echo "QEMU pc-bios not found at $SRC — assets already staged?"
  if [[ -s "$DEST/efi-virtio.rom.gz" ]]; then
    echo "using existing $DEST"
    exit 0
  fi
  exit 1
fi
rm -rf "$DEST"
mkdir -p "$DEST/keymaps"
# Option ROMs / BIOS blobs needed by aarch64 virt and x86_64 q35
for name in \
  efi-virtio.rom efi-e1000e.rom efi-e1000.rom efi-ne2k_pci.rom \
  efi-rtl8139.rom efi-vmxnet3.rom efi-eepro100.rom efi-pcnet.rom \
  pxe-virtio.rom pxe-e1000.rom pxe-eepro100.rom pxe-ne2k_pci.rom \
  pxe-pcnet.rom pxe-rtl8139.rom \
  vgabios.bin vgabios-stdvga.bin vgabios-virtio.bin vgabios-qxl.bin \
  vgabios-vmware.bin vgabios-cirrus.bin vgabios-ati.bin \
  bios.bin bios-microvm.bin bios-256k.bin \
  kvmvapic.bin linuxboot.bin linuxboot_dma.bin multiboot.bin multiboot_dma.bin \
  pvh.bin qboot.rom vgabios-isavga.bin
 do
  if [[ -f "$SRC/$name" ]]; then
    gzip -n -c -9 "$SRC/$name" > "$DEST/$name.gz"
  fi
done
# keymaps (text files)
if [[ -d "$SRC/keymaps" ]]; then
  find "$SRC/keymaps" -maxdepth 1 -type f ! -name 'meson.build' ! -name '*.txt' | while read -r f; do
    base="$(basename "$f")"
    gzip -n -c -9 "$f" > "$DEST/keymaps/$base.gz"
  done
fi
# Require the file that failed on the phone
[[ -s "$DEST/efi-virtio.rom.gz" ]] || { echo "failed to stage efi-virtio.rom"; exit 1; }
echo "staged $(find "$DEST" -type f | wc -l) files, $(du -sh "$DEST" | awk '{print $1}')"
