# Nimbus VM

A free, open-source, native Android app that runs real operating systems
locally on the phone with QEMU. No cloud, no paid services, no fake
desktops.

The app is a simple **Add ISO** flow: pick an image, auto-detect ARM64 vs
x86-64 and the OS, apply good defaults, and boot fullscreen with a graphical
display, direct touch (long-press, scroll, pinch-zoom the viewer),
and an on-screen keyboard. Advanced sliders stay optional.
The Phase 1 engine (QEMU 10.2.4, AAVMF, slirp, 9p) is still what actually runs
the guest.

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
- Display: ARM64 debian-installer ISOs (Kali, Debian netinst, …) boot the gtk installer via `-kernel` on `ramfb` (VNC `display=ramfb0`) with AAVMF pflash so the language screen has a framebuffer; disk and generic ISO boots keep virtio-gpu (VNC `gpu0`). Installer boots cap RAM at 2048 MB and use `-cpu max,pauth-impdef=on`. Serial shows the extract log, the QEMU command line, and kernel progress (`earlycon=pl011,0x9000000` plus `console=tty0`). qemu-xhci + usb-kbd + usb-tablet, virtio-multitouch. The Serial panel is interactive.
- virtio-9p shared folders
- AAVMF UEFI; `tpm-tis-device` + swtpm for Windows 11 on ARM

## License

GPL-2.0-only (required because we ship QEMU). Firmware is BSD-licensed EDK2.
Windows media is never bundled.

## Repo layout

- `scripts/` — fetch, build, firmware unpack, engine tests
- `native/` — JNI/C++ control layer and QEMU build trees (local)
- `app/` — Android app (library, Add ISO, fullscreen display, optional advanced settings)
- `screenshots/` — UI frames and QMP installer proof dumps
- `firmware/edk2-gz/` — compressed AAVMF/OVMF images
- `NOTES.md` — work log, test results, known limits
- `evidence/` — serial logs and summaries from engine tests (local)

## Build (host engine tests)

See `NOTES.md` and `scripts/run-engine-tests.py`. The box used for Phase 1
tests is x86_64 Linux; ARM64 guests run under TCG here too, which is slower
than they will be on an ARM64 phone.
