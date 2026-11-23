# Nimbus VM

A free, open-source, native Android app that runs real operating systems
locally on the phone with QEMU. No cloud, no paid services, no fake
desktops.

Phase 1 (this tree) is the **virtualization engine**. The full UI
(install wizard, library, display, keyboard, touch mouse) is Phase 2.

## ARM64 first

Lex's phone is ARM64, and stock Android apps cannot use KVM, so everything
runs under TCG. Same-architecture guests are far smoother than
cross-architecture ones:

| Guest | Default on Nimbus | Notes |
| --- | --- | --- |
| Ubuntu, Debian, Kali | ARM64 | Official arm64 images, recommended |
| Windows 11 | Windows 11 on ARM | User-supplied ISO/VHDX, AAVMF + TPM 2.0 |
| Linux Mint | *No official ARM64 build* | Honest substitute: Debian 13 ARM64 with the Cinnamon desktop, labeled as such |
| Custom x86-64 ISO | Compatibility mode | `qemu-system-x86_64` under TCG — expected to be **slow** |

Defaults for ARM64 guests (also used in the engine tests):

- `-accel tcg,thread=multi,tb-size=128` (multi-threaded TCG)
- `-cpu max` on `virt,gic-version=3`
- virtio-blk (`cache=writeback,aio=threads,discard=unmap`)
- virtio-net with slirp (user-mode networking / internet)
- virtio-gpu, qemu-xhci + usb-kbd + usb-tablet
- virtio-9p shared folders
- AAVMF UEFI; `tpm-tis-device` + swtpm for Windows 11 on ARM

## License

GPL-2.0-only (required because we ship QEMU). Firmware is BSD-licensed EDK2.
Windows media is never bundled.

## Repo layout

- `scripts/` — fetch, build, firmware unpack, engine tests
- `native/` — JNI/C++ control layer and QEMU build trees (local)
- `app/` — debug APK harness (Phase 1 is a single debug screen)
- `firmware/edk2-gz/` — compressed AAVMF/OVMF images
- `NOTES.md` — work log, test results, known limits
- `evidence/` — serial logs and summaries from engine tests (local)

## Build (host engine tests)

See `NOTES.md` and `scripts/run-engine-tests.py`. The box used for Phase 1
tests is x86_64 Linux; ARM64 guests run under TCG here too, which is slower
than they will be on an ARM64 phone.
