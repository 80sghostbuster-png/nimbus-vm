# Bundled UEFI firmware

These are EDK2/OVMF and AAVMF (AARCH64 Virtual Machine Firmware) builds
from the Debian `ovmf` and `qemu-efi-aarch64` packages, version 2025.02-8.
EDK2 is licensed under a BSD-2-Clause-Patent-style license; full text is in
`licenses/`.

ARM64 (default, used for Ubuntu/Debian/Kali and Windows 11 on ARM):

- `edk2/AAVMF_CODE.fd` — AAVMF without Secure Boot (Linux guests)
- `edk2/AAVMF_CODE.secboot.fd` — AAVMF with Secure Boot (Windows 11 ARM)
- `edk2/AAVMF_VARS.fd` — empty variable store template
- `edk2/AAVMF_VARS.ms.fd` — variable store with Microsoft keys enrolled
- `edk2/QEMU_EFI.fd` — compact `-bios` fallback image

x86-64 (compatibility mode only; TCG is slow on a phone):

- `edk2/OVMF_CODE_4M.fd` / `OVMF_CODE_4M.secboot.fd`
- `edk2/OVMF_VARS_4M.fd` / `OVMF_VARS_4M.ms.fd`

Do not commit snakeoil test keys. Users supply their own Windows installation
media; nothing from Microsoft is bundled.
