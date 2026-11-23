# v0.2.4 (versionCode 7) — installer boot progress and TCG load

Box-verified Sunday Nov 22, 2026, America/Chicago. Not yet phone-tested at publish time.

Lex's v0.2.3 phone boot extracted the gtk initrd successfully (overlay “Booting… (installer)”) but the display stayed black with “Display connected”, and Serial never moved past the AAVMF banner and `[=3h`. The phone argv was the 0.2.3 installer path: AAVMF pflash + `-kernel` + ramfb, `-cpu max`, `-accel tcg,thread=multi`, `-smp 2`, `-m 5775` (half of device RAM), `acpi=off console=tty0`.

## What the box showed

The 0.2.3 box proofs already used pflash + `-kernel` (firmware still runs, then the EFI stub). Reproducing the *exact* phone argv (`-m 5775`, `-cpu max`, `thread=multi`, pflash, ramfb, 9p, the same append) reached the gtk “Select a language” screen in about **40 seconds**. Serial stayed on the firmware banner only — so a quiet Serial panel does not mean the kernel failed to start when `console=tty0` is the only console. A no-pflash `-kernel` boot starts Linux immediately and `earlycon` works, but ramfb stays on “Guest has not initialized the display (yet.)” because AAVMF is what programs ramfb via fw_cfg. Firmware is kept.

## What changed

- **Keep pflash** for installer `-kernel` boots so ramfb/efifb works.
- **`earlycon=pl011,0x9000000`** plus `console=tty0` last (`acpi=off`). Serial shows `Booting Linux` after the AAVMF banner; earlycon then disables itself when tty0/efifb comes up, so debian-installer still uses the framebuffer (unlike `console=ttyAMA0`, which disabled the gtk frontend).
- **`-cpu max,pauth-impdef=on`** on ARM64. Pointer auth on `-cpu max` is very expensive in TCG.
- **Installer RAM cap 2048 MB** in `buildArgv` (a VM saved at 5775 is capped only for the `-kernel` installer boot; disk/firmware boots keep the user's size). New non-Windows VMs default to at most 3072 MB.
- **Elapsed time** on the boot overlay, updated every second.

## Box proof

Official Kali 2026.3 installer ISO, `-kernel` + gtk initrd, ramfb, `display=ramfb0`, pflash, `acpi=off`, append `acpi=off earlycon=pl011,0x9000000 console=tty0`, `-cpu max,pauth-impdef=on`, `-m 2048`, `-accel tcg,thread=multi`, `-smp 2`. Serial showed the AAVMF banner, then `Booting Linux` / earlycon, then earlycon disabled at tty0. The app VNC path reached “Select a language” in about **39 seconds**. Proof: `screenshots/kali-024-graphical-install-vnc.png`. APK 39,161,364 bytes, SHA-256 `db6b604b8c0b2d9003e61387b4f752cb2383a9d89b44a97e0179afc615dee01f`.

The box emulates ARM64 on x86 TCG, which is typically slower than same-arch TCG on Lex's phone — but a phone can still be slower under thermal or memory pressure. Expect Serial to show `Booting Linux` within a minute or two, and the language screen often within a few minutes (wait up to five). The overlay timer is the wait cue. If Serial *never* moves past the AAVMF banner after several minutes, firmware has not handed off to the kernel yet.

## What Lex should do

Install `Nimbus-VM.apk` over 0.2.3 (same signing key, versionCode 7). The existing Kali VM is fine — installer RAM is capped automatically. Boot it and watch the overlay timer. Open Serial: after the UEFI banner you should see `Booting Linux` and earlycon lines. The display can go black briefly between kernel messages and the gtk screen (the box did that around the 24 second mark). If the language screen is not up in about five minutes, or Serial never shows `Booting Linux`, report that with a Serial screenshot and the elapsed time.

0.3.0 work remains paused until this phone bug is confirmed fixed.


# v0.2.3 (versionCode 6) — phone ISO-boot hotfix

Box-verified Sunday Nov 22, 2026, America/Chicago. Not yet phone-tested at publish time.

Lex's v0.2.2 phone boot of the official Kali ARM64 installer stayed on a black "Display connected" screen with the overlay "Booting… (firmware)". Serial showed only the AAVMF banner and `[=3h`. That serial text is consistent with *both* a silent firmware/GRUB fallback (ISO extract missed `/install.a64`) *and* a successful `-kernel` boot with `console=tty0` only (the box 0.2.2 proofs also left only the firmware banner on Serial while the installer ran on ramfb). 0.2.3 fixes both interpretations.

## What changed

- **Re-detect every ISO boot.** `QemuEngine.buildArgv` calls `IsoInspector.tryExtractArm64Installer` whenever an ISO is attached. There is no sticky boot-mode flag, so a VM created under 0.2.0/0.2.1 is re-evaluated on the first 0.2.3 start.
- **ISO extraction no longer depends on Joliet.** ISO 9660 stores `VMLINUZ.;1`, which used to index as `INSTALL.A64/VMLINUZ.` — `findEntry("INSTALL.A64/VMLINUZ")` only hit if Joliet also ran. Trailing dots are now stripped, and a Joliet walk failure keeps the ISO 9660 index. A Java line-for-line port of this logic, ISO9660-only, found Kali and Debian `/install.a64/vmlinuz` plus gtk/text initrds; SHA-256 matched the known-good extracts.
- **Visible extract errors.** `work/installer-extract.txt` is always written. The Serial panel shows that file, the QEMU command line / stderr (`qemu-stderr.log`), and `serial.log` even when Serial is only the firmware banner. The boot overlay title/body reflects extract success (`Booting… (installer)`), kernel logs (`Booting… (kernel)`), or a failed extract (the error text, then firmware/GRUB fallback — no silent fallback).
- **Installer cmdline stays `acpi=off console=tty0`.** A trial of `console=ttyAMA0 console=tty0` (tty0 last) did put kernel logs on Serial after the AAVMF banner, but debian-installer then printed `Framebuffer not available; disabling graphical frontend` even though efifb/ramfb was up, and ran the text language selector on the UART. 0.2.3 therefore does not add ttyAMA0. Serial still distinguishes the boot path: `installer-extract.txt` plus the QEMU command line in `qemu-stderr.log` show whether `-kernel` ran.
- **Installer `-kernel` stays on ramfb.** gtk and text initrds both use `-device ramfb,id=ramfb0` and `-vnc …,display=ramfb0`. A gtk boot on virtio-gpu alone (DRM, no `/dev/fb0`) printed `Framebuffer not available; disabling graphical frontend` and fell back to the text UI. Firmware/GRUB fallback stays virtio-gpu + `display=gpu0`. ARM64 installer `-kernel` still uses `-machine …,acpi=off`.

## Box proof

Official Kali 2026.3 installer ISO, `-kernel` + gtk initrd, ramfb, `display=ramfb0`, `acpi=off`, append `acpi=off console=tty0`, `-accel tcg,thread=single`, `-smp 2`. The app VNC path reached the gtk "Select a language" screen. Proof: `screenshots/kali-023-graphical-install-vnc.png`. APK 39,159,360 bytes, SHA-256 `555cac41d04aa2dd4801e05fef15db987c4f1df5bb11bdaa0fbea26e3a61b502`.

Firmware/GRUB is the fallback when extract fails (now visible in Serial/overlay). That path is not hung on the box: under the same `-accel tcg,thread=single -smp 2`, AAVMF reached the Kali GRUB menu on virtio-gpu (VNC `display=gpu0`) in about a minute (mean ~6.5, which clears the boot overlay). Serial stays at the AAVMF banner until BdsDxe, then shows `GNU GRUB version …` — so a Serial panel that *never* moves past the banner is more consistent with a successful `-kernel` boot (`console=tty0` only) than with a stuck firmware boot. Proof: `screenshots/kali-023-firmware-grub-vnc.png`.

## What Lex should do

Install `Nimbus-VM.apk` over 0.2.2 (same signing key, versionCode 6). Boot the same Kali arm64 ISO (a VM created under 0.2.1/0.2.2 is fine — the ISO is re-detected). Open Serial: you should see `--- installer ---` (`Extracted ARM64 installer kernel… gtk initrd`) and, after the UEFI banner, kernel lines such as `Booting Linux`. If extract failed, Serial and the overlay show the error instead of a silent firmware boot. The display can stay dark until the installer paints; the overlay will say `Booting… (installer)` or `Booting… (kernel)` rather than only `(firmware)`.

0.3.0 work remains paused until this phone bug is confirmed fixed.


# Nimbus VM — Phase 1 notes

Living work log. Times are America/Chicago (UTC-6).

## Decisions (2026-11-22)

- Project name: **Nimbus VM**, package `com.lex.nimbusvm`.
- Free and open source: GPL-2.0-only (QEMU), no paid deps or services.
- **ARM64-first**, as Lex requested ("Then use arm").
  `qemu-system-aarch64` is the default for every supported OS.
  `qemu-system-x86_64` is a secondary "compatibility (slow)" path for
  custom x86 ISOs only.
- Linux Mint has no ARM64 build. Phase 2 will offer a Debian 13 ARM64
  Cinnamon desktop image, labeled honestly: "Cinnamon desktop (Debian
  ARM64). Linux Mint itself is not available for ARM."
- Windows 11 = Windows 11 on ARM. User supplies licensed ISO/VHDX.
  Engine wires AAVMF Secure Boot firmware + swtpm 2.0 via `tpm-tis-device`.
  No Microsoft media or keys are ever bundled.
- Stock Android cannot use KVM. TCG (multi-threaded) is expected.
- Raw Bluetooth passthrough to a guest is not available to an unprivileged
  Android app. Phase 2 will pair keyboards/mice/controllers with Android
  and forward them into the guest as USB HID / virtio-input.
- Android 10+ cannot `exec` from app data. Phase 1 packages QEMU as
  `libqemu-system-*.so` in `jniLibs` with `extractNativeLibs=true`, and a
  JNI wrapper (`posix_spawn`) that starts the process from `nativeLibraryDir`.
- Firmware 64 MiB AAVMF flash images are mostly padding; we ship
  `firmware/edk2-gz/*.fd.gz` (~7 MiB total) and unpack at build/run time.
- Default ARM64 performance flags (used in tests and in `QemuEngine`):
  `-accel tcg,thread=multi,tb-size=128`
  `-cpu max` on `virt,gic-version=3`
  virtio-blk (`cache=writeback,aio=threads,discard=unmap`)
  virtio-net + slirp, virtio-gpu, qemu-xhci + usb-kbd + usb-tablet
  virtio-9p, AAVMF pflash, `tpm-tis-device` + swtpm.

## Toolchain

- Box: x86_64 Linux, 8 CPUs, 15 GiB RAM + 8 GiB swap. No KVM used
  (tests force `-accel tcg`). **No Android emulator, nothing tested on a phone.**
- Host packages: QEMU 10.0.13 (Debian, first boot test), swtpm 0.7.1,
  libtpms 0.9.2, OVMF/AAVMF 2025.02-8, NDK 27.3.13750724, JDK 21,
  Android SDK 35.
- Engine source: QEMU **10.2.4**
  (`qemu-10.2.4.tar.xz`, 141137724 bytes,
  SHA-512 `62a2a71e5bad12741bad264673aaf824152a1b797d3de66582c4a1beb7f76ef75f5d2e0aa705702910287d3906b98610902aa723f9b7d38500d694e39bcf2fd5`,
  SHA-256 `821b545b92f165e57dddccac5077d76d4d436a226595b8813ad59306bbfd0746`
  — SHA-512 matches the Gentoo distfile manifest).
- Test image: Debian 13 genericcloud arm64 from cloud.debian.org
  (336592896 bytes, SHA-512
  `ecbb14df864d2f8842539733c10d7251c58294f86b85a487b23b2c959a9b75f003df6b289deb20e1aee03ef65de2b1429818689e1fbc4b727d579c61b247fee9`).

## Host engine test results (real boots, TCG, on the x86_64 box)

These tests are **cross-architecture** (aarch64 guest on x86_64 TCG), so they
are *slower* than the same guest will be on Lex's ARM64 phone (same-arch TCG).

### A. QEMU 10.0.13 (Debian package) — first run

- Command: see `evidence/arm64-qemu-cmd.txt` (multi-thread TCG, AAVMF,
  virtio-blk/net/gpu/9p, qemu-xhci, `tpm-tis-device` + swtpm).
- Guest reached probe + login path in **68.1 s**.
- `NIMBUS_UNAME Linux nimbus-arm64 6.12.117+deb13-cloud-arm64 ... aarch64`
- UEFI: "UEFI firmware (version 2025.02-8+deb13u1)", "EFI stub: Booting Linux Kernel"
- ACPI table `TPM2` present; guest saw **`/dev/tpm0` and `/dev/tpmrm0`, TPM version=2**
- slirp internet: DNS ok, **HTTP status=200** from deb.debian.org
- QMP: `query-status running`, `stop` → paused, `cont` → running
- savevm failed (raw pflash VARS does not support snapshots) — fixed in run B
- 9p failed (cloud kernel has no 9p modules) — see below
- powerdown issued after a reset (too early); guest did not exit in 45 s

### B. Our QEMU **10.2.4** host build — primary result

Binary: `/workspace/orion-vm/native/qemu-host-bld/qemu-system-aarch64`
(NINJA_EXIT 0, ~4 minutes, `-j 3`).

- Boot to `NIMBUS_READY` in **70.1 s**; serial ends with
  `Debian GNU/Linux 13 nimbus-arm64 ttyAMA0` and `nimbus-arm64 login:`
- TPM 2.0 again present (`/dev/tpm0 /dev/tpmrm0`, version=2)
- slirp: DNS ok, HTTP 200
- QMP stop/cont: paused → running
- **savevm / loadvm: success** after switching VARS pflash to qcow2
- **migrate-to-file: status=completed** in ~3 s, suspend file **411,694,138 bytes**
- `cont` after migrate: running
- **ACPI `system_powerdown`: qemu exited 0 in 6 seconds**

Evidence: `evidence/arm64-summary-qemu-10.2.4.json`,
`evidence/arm64-serial-qemu-10.2.4.log`.

### C. virtio-9p

The Debian 13 **genericcloud** ARM64 kernel ships **no 9p modules**
(`NIMBUS_9P_MODS` empty). Alpine 6.18 `virt` aarch64 also has **no**
`CONFIG_9P_FS` (no module, no builtin, `/proc/config.gz` has no 9P).
QEMU still created the virtio-9p-pci device (guest saw `virtio0`/`virtio1`;
virtio-net + slirp DHCP got `10.0.2.15`).

**Honest status:** 9p is wired on the QEMU side (`-fsdev local` +
`virtio-9p-pci`, `security_model=none`). A successful *guest mount* was
not demonstrated, because the two slim kernels we booted omit 9p. Ubuntu
desktop / Debian netinst / Kali images normally include 9p; Phase 2 should
test 9p against one of those (or a generic arm64 kernel).

### D. What was *not* tested

- No real Android phone, no Android emulator.
- No Windows 11 media (correctly not bundled). TPM 2.0 + AAVMF Secure Boot
  firmware are present and the TPM path was proven with a Linux guest.
- No x86_64 OS boot (secondary; smoke not run in the interest of ARM64).
- Persistent suspend *restore* (`-incoming file:suspend.bin`) was not
  separately booted; migrate completed and savevm/loadvm succeeded in-session.
- Bluetooth HID forwarding is Phase 2.

## Android cross-compile

NDK 27.3, API 28, prefix `native/android-prefix`. Built as static libs:

- zlib 1.3.1, libiconv 1.17, libffi 3.4.6, pcre2 10.44, GLib 2.80.5,
  pixman 0.44.2, libslirp 4.8.0, OpenSSL 3.0.16, **libtpms 0.9.6**
- Empty `librt.a` / `libutil.a` stubs (bionic provides those in libc)
- swtpm: configure needs libtasn1 (and likely json-glib). **Not finished.**
  swtpm is a separate process from QEMU; host tests already prove the QEMU
  TPM hookup. Remaining work: cross-compile libtasn1 + swtpm into jniLibs.

QEMU 10.2.4 Android configure: **succeeded** (meson cross file, host_machine
aarch64, glib/pixman/zlib/slirp found, TPM support YES, virtfs YES, vnc YES).
Must pass `--cross-prefix=` (empty is fine) so meson treats it as a cross
build. vhost-* disabled (Android kernel headers clash with QEMU's bundled
virtio headers; vhost is not usable from an unprivileged app anyway).

One source patch: `native/patches/0001-android-shm-open.patch` plus
`native/compat/android_shm.h` — file-backed `shm_open`/`shm_unlink` because
API 28 bionic headers do not declare them.

## Known limits

- x86-64 guests under TCG on an ARM phone will be very slow. Warn in the UI.
- Windows 11 on ARM still needs several GB of RAM and a user-supplied
  licensed image; first boot on a phone will take a long time under TCG.
- KVM, VFIO, and raw Bluetooth passthrough are not available to a normal
  Android app.
- Performance on this x86_64 box is **not** representative of an ARM64
  phone: here an ARM64 guest is the *cross*-architecture case.
- Debian/Alpine *cloud/virt* kernels may omit 9p; pick desktop/netinst
  images for shared folders.

## Phase 2 plan (UI + device)

1. Install wizard: ARM64 defaults for Ubuntu/Debian/Kali, Windows 11 on ARM
   (user ISO/VHDX), "Cinnamon desktop (Debian ARM64)" labeled as the Mint
   substitute, custom ISO with an explicit "x86 compatibility (slow)" toggle.
2. VM library: independent qcow2 disks, RAM/CPU/disk sliders, theme later.
3. Display: VNC or a custom ramfb/virtio-gpu frame sink into a fullscreen
   SurfaceView (PlayStation-like, smooth).
4. Virtual keyboard + touch pointer → usb-kbd / usb-tablet (already in argv).
5. Bluetooth: Android-paired HID forwarded as virtio-input / USB HID.
   No raw controller passthrough.
6. Shared-folder picker (9p) with a note if the guest kernel lacks 9p.
7. Finish swtpm for Android, package it as `libswtpm.so`, start it beside QEMU.
8. Test on Lex's ARM64 phone; tune `tb-size`, SMP, and RAM defaults there.

## Repo / APK

Scripts, app skeleton, compressed firmware, notes, and patches are the
source of record. Keystore lives in `signing/` and is gitignored. Release
asset name (when published): `Nimbus-VM.apk`.

## Repo and APK (2026-11-22, America/Chicago)

- Source: https://github.com/80sghostbuster-png/nimbus-vm
- Release: https://github.com/80sghostbuster-png/nimbus-vm/releases/tag/v0.1.0-engine
- Stable download: https://github.com/80sghostbuster-png/nimbus-vm/releases/latest/download/Nimbus-VM.apk
- APK: 34,969,515 bytes, SHA-256 `7966def057638a4e450fbbc8fb72745e501078cc79f20491a4122a8c58ec0821`
  (GitHub latest-download verified byte-identical).
- Signed with the project keystore in `signing/` (gitignored, never printed).
- Package `com.lex.nimbusvm`, minSdk 28, arm64-v8a only.
- Native libs in the APK: `libqemu-system-aarch64.so` (38 MiB stripped),
  `libqemu-system-x86_64.so` (32 MiB), `libqemu-img.so` (3.8 MiB),
  `libnimbusvm.so` (JNI), plus AAVMF/OVMF firmware.
- Android binaries are ELF aarch64 PIE, interpreter `/system/bin/linker64`,
  for Android 28, NDK r27d; they only need bionic `libc`/`libm`/`libdl`.
  User-mode `--version` on the x86 box failed because `/system/bin/linker64`
  is not present here. Nothing has been run on a real phone.

## Hotfix 0.1.1-engine (2026-11-22, America/Chicago)

Phone install of 0.1.0-engine started QEMU, then immediately failed:

    libqemu-system-aarch64.so: -device virtio-net-pci,netdev=net0: failed to find romfile "efi-virtio.rom"

Cause: the Android-built QEMU has `CONFIG_QEMU_DATADIR` baked to a box path
that does not exist on the phone, and pc-bios option ROMs were not bundled.
`virtio-net-pci` still loads `efi-virtio.rom` under UEFI.

Fix in 0.1.1-engine (`versionCode` 2):

- Bundle QEMU 10.2.4 pc-bios option ROMs and keymaps as gzipped assets under
  `app/app/src/main/assets/qemu-data/` (`scripts/stage-qemu-data.sh`).
- On first run (and whenever `DATA_PACK_VERSION` changes) extract them to
  `filesDir/qemu-data/<version>/` and pass `-L` on every QEMU invocation.
- Set `TMPDIR`/`TEMP`/`TMP`/`HOME`/`XDG_*` under `cacheDir`/`filesDir` (never `/tmp`).
- `virtio-net-pci,romfile=` as belt-and-braces; `-L` is the real fix.
- Debug console live-tails serial and QEMU stderr so a future boot failure is visible.

Isolated box proof (`scripts/sim-android-aavmf-boot.sh`): fresh work dir,
system `/usr/share/qemu` and baked prefix datadirs hidden with bwrap tmpfs.
Test A (`-L` only, default virtio-net ROM — the phone crash) and Test B
(exact app argv with `romfile=`) both reached the AAVMF serial banner
("UEFI firmware (version 2025.02-8+deb13u1 …)") in 2s. `efi-virtio.rom`
is 160,768 bytes.

Signed APK: 37,144,030 bytes, SHA-256 `a79aa5c87c7470d4360a56e5693466318d8f64ea670880f8e0de14a0c4661dea`.
Package `com.lex.nimbusvm`, versionCode 2, versionName 0.1.1-engine, signed v2
with the same project keystore. `assets/qemu-data/efi-virtio.rom` is present
in the APK (160,768 bytes; AAPT2 stores the decompressed form of the .gz asset).

- Release: https://github.com/80sghostbuster-png/nimbus-vm/releases/tag/v0.1.1-engine
- Stable download: https://github.com/80sghostbuster-png/nimbus-vm/releases/latest/download/Nimbus-VM.apk


## Phase 2 — 0.2.0 (2026-11-22, America/Chicago)

Core flow is now: big **Add ISO** button → Android file picker → auto-detect
arch from the ISO's EFI boot files (`BOOTAA64.EFI` vs `BOOTX64.EFI`) and OS
type, pick defaults (RAM ≈ 50% of device RAM with a sensible cap, CPU = big
cores, 32 GB sparse qcow2 for Linux / 64 GB for Windows, TPM + Secure Boot
automatically for Windows), create the VM and boot it immediately fullscreen
on a graphical VNC display (unix socket; not serial). Touch is an absolute
tablet; an on-screen keyboard and Bluetooth/USB HID keyboards (system
KeyEvents) type into the guest. Internet (slirp) and a per-VM 9p shared
folder are on by default.

Advanced settings (RAM/CPU/disk sliders, shared folder, x86-64 toggle, TPM,
Secure Boot, eject ISO) stay optional. The VM library can re-launch a VM and
boot from disk after install (ISO ejected). Power menu: shutdown, restart,
suspend (QMP stop), resume (QMP cont), force stop.

swtpm is started when `libswtpm.so` is present; this APK still does not ship
an Android-built swtpm, so Windows 11 ARM TPM is wired in the engine but not
yet proven on device. Distro download presets were deferred until after the
ISO flow.

Box proof: official `debian-13.8.0-arm64-netinst.iso` (SHA-256
`f4e05f67b5c74e3ff7b24c2a8f889960f9eb31fe0c1ac8513d27d5656958b6b3`)
auto-detected as Debian ARM64 via `EFI/BOOT/BOOTAA64.EFI`. The same app argv
(`-L`, `-vnc unix:…`, virtio-gpu, ISO + 32G disk, slirp, 9p) is booted with
system QEMU datadirs hidden; QMP screendumps land in `screenshots/`.

UI frames matching the layouts are in `screenshots/01-library-empty.png` etc.
They were rendered on the box (no Android emulator / phone capture).

Signed APK: 37,355,177 bytes, SHA-256 `6242f1e9c6471df69dd4e62ea2bbf9b3fa01014c9cedd6cc03cb60d765ef2622`.
Package `com.lex.nimbusvm`, versionCode 3, versionName 0.2.0, signed v2
with the same project keystore. `assets/qemu-data/efi-virtio.rom` is still bundled.

- Release: https://github.com/80sghostbuster-png/nimbus-vm/releases/tag/v0.2.0
- Stable download: https://github.com/80sghostbuster-png/nimbus-vm/releases/latest/download/Nimbus-VM.apk

## Hotfix 0.2.1 (2026-11-22, America/Chicago)

Phone bug on 0.2.0: official Kali ARM64 installer ISO copied and booted, the
fullscreen viewer opened, but the display stayed on QEMU's centered text
"Display output is not active."

Box investigation with the exact 0.2.0 argv (`-display none`, `-vnc unix:…`,
`virtio-gpu-pci`) against Debian 13.8.0 arm64 netinst and official
`kali-linux-2026.3-installer-arm64.iso` (SHA-256
`c4a7f9bf3a0ff07c8263fe9e9b0edba0cc3d9b408f1d8f06c066df0afce23072`):

- That string is QEMU's virtio-gpu reset placeholder (the installer kernel
  binds the GPU and clears scanout). It is *not* a VNC socket failure.
- The same unix VNC path the app uses (RFB raw + CopyRect + DesktopSize)
  shows GNU GRUB and, once scanout returns, the Debian GTK "Select a language"
  screen. QMP screendumps match the VNC frames.
- 0.2.0 never asked for a full framebuffer after a DesktopSize resize, so
  the viewer could stay on the placeholder even after the guest recovered.
- Kali GRUB (`GNU GRUB version 2.14-2+kali1`, Install / Graphical install)
  is visible on that VNC path with the 0.2.1 argv. After the installer kernel
  starts, the display can stay black / on the placeholder for a long time
  under TCG while d-i is already talking on serial — which is why the
  overlay and the live serial log matter.

Fixes in 0.2.1 (`versionCode` 4):

- Bind VNC to the GPU explicitly: `-device virtio-gpu-pci,id=gpu0,xres=1280,yres=800`
  and `-vnc unix:…,display=gpu0` (x86 uses `virtio-vga,id=gpu0`).
- VNC client requests a full refresh after a resize, understands
  ExtendedDesktopSize / LastRect / Cursor so it does not drop the session,
  and periodically forces a full update (every 2s while the placeholder is
  showing, every 10s otherwise).
- "Booting… (firmware)" overlay while the frame is still the placeholder
  or a blank scanout, with a **Serial** toggle that tails the live firmware
  and installer log.
- First-class touch (default **Touch**): tap = left click at the finger via
  usb-tablet absolute coordinates (letterbox, viewer zoom and pan are
  mapped correctly), long-press = right click, two-finger drag = scroll,
  pinch = zoom the viewer. **Trackpad** and **Multitouch** cycle on the
  toolbar button (long-press the button to reset viewer zoom). Hidden
  toolbar comes back with a tap along the top edge.
- Linux guest multi-touch: `-device virtio-multitouch-pci,id=touch0` and
  QMP `input-send-event` of type `mtt` (absolute range 0..0x7fff). Do **not**
  pass `device=touch0` — QEMU treats that argument as a display console name
  and `qemu_console_lookup_by_device()` can abort on the serial text console
  (`Property 'qemu-fixed-text-console.device' not found`).

Box-proven with the 0.2.1 argv: Debian GRUB + GTK installer and Kali GRUB
on the exact VNC path; QMP `input-send-event` mtt accepted and QEMU stayed
running. Not yet tested on a real phone. The 0.3.0 work (Android swtpm,
distro presets, 9p proof, x86 smoke, gamepad, migrate) is still unfinished
and is not in this hotfix.

Signed APK: 39,138,720 bytes, SHA-256
`fcbf262b5ad863e31ae09bae9648993510ae62bd39cb1d0c328997f05e868a61`.
Package `com.lex.nimbusvm`, versionCode 4, versionName 0.2.1, signed v2
with the same project keystore.

- Release: https://github.com/80sghostbuster-png/nimbus-vm/releases/tag/v0.2.1
- Stable download: https://github.com/80sghostbuster-png/nimbus-vm/releases/latest/download/Nimbus-VM.apk

## Hotfix 0.2.2 (2026-11-22, America/Chicago)

Phone follow-up on 0.2.1: Kali ARM64 GRUB was visible, then the GPU stayed
black while the text installer ran on serial.

Root cause (box, official `kali-linux-2026.3-installer-arm64.iso`, SHA-256
`c4a7f9bf3a0ff07c8263fe9e9b0edba0cc3d9b408f1d8f06c066df0afce23072`):

- Kali's default GRUB entry is text **Install** (`/install.a64/initrd.gz`).
  **Graphical install** uses the same vmlinuz and the gtk initrd. Neither
  entry passes `console=ttyAMA0`; both use `console=tty0`.
- The installer kernel *does* have efifb, simplefb, fbcon, and virtio-gpu
  (module in both initrds). QEMU `ramfb` is a firmware GOP that efifb can
  bind (`fb0: EFI VGA`, 800x600) when the kernel is started as an EFI
  stub (`-kernel`).
- Booting through GRUB leaves no usable framebuffer for the text
  installer. ACPI SPCR also marks the pl011 UART as the preferred
  console, so debian-installer starts on serial even when `console=tty0`
  is on the cmdline. Adding ramfb next to virtio-gpu and pointing VNC
  at either head is not enough if you stay on the ISO's GRUB path.
- A kernel cmdline cannot be injected from outside once GRUB owns the
  boot (no `-append` with a CD EFI boot).

Fix in 0.2.2 (`versionCode` 5):

- For ARM64 ISOs that contain `/install.a64/vmlinuz`, extract the kernel
  and prefer the gtk initrd, still attach the ISO as a virtio CD, and
  boot with `-kernel` / `-initrd` / `-append`. Append keeps the ISO's
  preseed args, forces `acpi=off console=tty0` (no UART console, no
  `quiet`), and uses `-machine virt,…,acpi=off` plus `-device ramfb,id=ramfb0`
  only (no virtio-gpu on this path) with `-vnc unix:…,display=ramfb0`.
- Disk-only and generic ISO boots stay on AAVMF + GRUB, `acpi=on`,
  `virtio-gpu-pci,id=gpu0`, VNC `display=gpu0`.
- Serial is interactive: `-chardev socket,…,logfile=serial.log` +
  `-serial chardev:serial0`. The Serial panel sends OSK and hardware
  keyboard input to `serial.sock` as a fallback if a guest stays on
  the UART.

Box-proven on the exact app VNC path (unix socket, RFB raw + CopyRect +
DesktopSize, `display=ramfb0`):

- Default text Install (`/install.a64/initrd.gz`): newt "Select a language".
  Proof: `screenshots/kali-022-default-install-vnc.png`.
- Graphical install (`/install.a64/gtk/initrd.gz`): GTK "KALI / Select a
  language". Proof: `screenshots/kali-022-graphical-install-vnc.png`.
- Serial stays the firmware banner only; the installer is on the display.

Not yet tested on a real phone. Install over 0.2.1 with the same keystore.
The 0.3.0 work (Android swtpm, distro presets, 9p proof, x86 smoke,
gamepad, migrate) is still unfinished and is not in this hotfix.

Signed APK: 39,155,100 bytes, SHA-256
`17ab6297e3eb42070f36727767b6c18a1c5021107b6741d174baf5aed98bcf48`.
Package `com.lex.nimbusvm`, versionCode 5, versionName 0.2.2, signed v2
with the same project keystore.

- Release: https://github.com/80sghostbuster-png/nimbus-vm/releases/tag/v0.2.2
- Stable download: https://github.com/80sghostbuster-png/nimbus-vm/releases/latest/download/Nimbus-VM.apk

