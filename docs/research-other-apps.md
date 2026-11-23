# How other Android/TCG QEMU front-ends boot arm64 Linux (research for v0.2.8-test)

Monday Nov 23, 2026, America/Chicago.

## What the working apps / users do

| Project | QEMU | Boot path / args (arm64 guest, no KVM) | Serial handling |
|---|---|---|---|
| Vectras VM (Community) — https://github.com/xoureldeen/Vectras-VM-Android | Not built into the APK: Alpine/Termux-style `qemu-system-*` package downloaded at first run into a PRoot Alpine env; version depends on the package (3dfx-patched builds for arm64-v8a). | `-M virt` + two pflash (`QEMU_EFI.img`, `QEMU_VARS.img`), ISO as cdrom, display via VNC/SDL. Source walk-through of `StartVM.java`: https://www.cnblogs.com/qsbye/p/23207880 | No app client sitting on a serial socket; console is the display (or the integrated terminal, which always reads). |
| Termux users (Debian/Kali arm64) — https://www.reddit.com/r/termux/comments/1uepopn/running_real_debian_in_qemu_from_termux/ , https://github.com/kavyamali/qemuontermux-debian , https://github.com/Willie169/termux-sh/blob/main/qemu-debian-arm64-install.sh | Termux `qemu-system-aarch64` (upstream, bionic build) | `-M virt -cpu max[,sve=off,pauth-impdef=on] -accel tcg,thread=multi[,tb-size=512] -smp 2 -m 2048 -bios edk2-aarch64-code.fd`, disk/cdrom virtio, `virtio-gpu-pci` + VNC or `-nographic`. Reports: GUI very slow under TCG; works on Pixels. | `-nographic` / `-serial mon:stdio`: the terminal always drains serial. |
| UTM / UTM SE (reference for TCG flags) — https://github.com/utmapp/UTM/blob/main/Configuration/UTMQemuConfiguration+Arguments.swift | UTM fork of QEMU | `-machine virt,gic-version=3[,virtualization=on]`, `-accel tcg,tb-size=<jit cache>[,thread=multi][,split-wx=on when no JIT entitlement]`, `-device virtio-ramfb` (or virtio-gpu), pflash `edk2-aarch64-code.fd` | Serial = spiceport (built-in terminal) or TCP socket the user connects to; the built-in terminal always reads. |
| Limbo PC Emulator — https://github.com/limboemu/limbo | Old QEMU (2.x–5.x) as JNI | x86-focused; arm `virt` with `-kernel` or EFI. Not a better reference for 10.x. | n/a |

Android-specific TCG concerns checked: W^X/`split-wx` is needed on iOS without JIT entitlement, not for Android app processes (ART JIT itself maps RWX; our QEMU already runs TCG and reaches the kernel on the Pixel, so the code buffer works). `tb-size` only affects speed. No source points to SELinux/seccomp blocking TCG on Android 14/15 for an app-lib QEMU.

## The difference that matters for us

Nobody else connects a client to the guest serial socket and then **doesn't read it**. Nimbus VM did:
`DisplayActivity.connectVnc()` → `connectSerialIo()` → `SerialConsole.connect()` opened `serial.sock` for keyboard input but never read from it.

QEMU 10.2.4 `hw/char/pl011.c` still uses the blocking `qemu_chr_fe_write_all()` (comment: "XXX this blocks entire thread"). When the unix-socket buffer fills, it spins forever holding the BQL: the vCPU freezes mid-character and QMP stops answering. Upstream discussion of exactly this:
- PMM: write_all blocks guest when "a unix domain socket where there's nothing on the other end reading" — https://lists.nongnu.org/archive/html/qemu-devel/2020-06/msg01037.html
- "If a chardev socket is not read, it will eventually fill and QEMU can block" — https://lists.libreplanet.org/archive/html/qemu-devel/2023-11/msg04621.html
- pl011 best-effort patch (2020, not merged): https://lists.libreplanet.org/archive/html/qemu-devel/2020-02/msg05450.html
- pl011 async TX FIFO series (2024–2025, not in our 10.2.4 tree): https://patchew.org/QEMU/20250310012825.79614-1-philmd@linaro.org/20250310012825.79614-8-philmd@linaro.org/

That matches every phone report: v0.2.2–0.2.4 "UEFI banner then `[=3h`" (firmware serial ConOut blocked) and v0.2.5 "froze mid-line at `OF: reserved mem: Res`" (earlycon blocked). Box tests never had a socket client attached, so they always booted.

## Box reproduction (same QEMU 10.2.4 source, x86 host build, Kali 2026.3 installer kernel/initrd, AAVMF, ramfb, cortex-a72, thread=single)

`tests/work/serial-block/run.sh`:
- client connected, never reads (= Nimbus ≤0.2.7): serial log stops at 278 B mid-line (`Linux version 7.1.5+kali-arm64 (devel@kali.org) (aarch64-`), QMP times out, guest frozen.
- client connected and draining: boots to the Kali "Select a language" screen (`serial-block/drain/screen.png`).
- patched pl011 + non-reading client: see `serial-block/patched-noread/`.

## What v0.2.8-test changes

1. `SerialConsole` drains the socket continuously on a daemon thread (bytes are discarded; QEMU's logfile has them).
2. QEMU `pl011` TX is best-effort (patch `native/patches/0004-pl011-nonblocking-tx.patch`): retries ~10 ms, then drops output until the backend accepts again. The guest can never freeze on serial again, even if the app is slow.
3. Main area is always the guest display (Kali's own console on ramfb/efifb with `console=tty0`, then the installer). Serial is a separate toggle panel.

Not adopted: switching to prebuilt Termux/Vectras QEMU (same upstream pl011 code; the bug is our client, not the build), `split-wx` (iOS-only need), virtio-gpu + GRUB (ramfb + efifb already shows the kernel console and the installer on the box; changing boot path now adds risk).
