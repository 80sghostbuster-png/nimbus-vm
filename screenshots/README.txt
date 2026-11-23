Nimbus VM 0.2.0 frames

UI (rendered to match the app layouts; no Android emulator on this box):
  01-library-empty.png    library with the large Add ISO button
  02-library-detected.png after ISO detect (Debian ARM64 / BOOTAA64.EFI)
  03-advanced.png         optional RAM/CPU/disk, x86, share, TPM, Secure Boot
  04-display-osk.png      fullscreen display + on-screen keyboard overlay

Box proof — official debian-13.8.0-arm64-netinst.iso, exact app argv
(-L, -vnc unix, virtio-gpu, 32G qcow2, slirp, 9p; system QEMU datadirs hidden).
QMP screendump is PPM (host QEMU was built without libpng) converted to PNG:
  debian-grub-menu.png                     GRUB, default "Install" highlighted
  debian-grub-graphical-highlighted.png    after Down: "Graphical install"
  debian-installer.png                     Debian 13 GTK "Select a language"
  debian-netinst-latest.png                same as the last captured frame
