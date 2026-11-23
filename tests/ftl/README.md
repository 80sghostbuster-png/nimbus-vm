# Firebase Test Lab kit (v0.2.5-test)

gcloud is already installed at `/home/box/google-cloud-sdk/bin/gcloud` (Google Cloud SDK 594.0.1). It was not on PATH when HOME was overridden; no extra install is needed. There is no Google account or project configured on this box yet.

Firebase Test Lab itself is deprecated and scheduled to shut down on 30 September 2027; the command below still works today.

## Artifacts

- App (signed, versionCode 8, versionName 0.2.5-test, minSdk 28):  
  `/workspace/Nimbus-VM-0.2.5-test.apk`  
  also `/workspace/orion-vm/tests/work/v025-test/Nimbus-VM.apk`
- Instrumentation APK (built with `./gradlew :app:assembleDebugAndroidTest`; AGP did not offer a release androidTest variant):  
  `app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
  also `/workspace/orion-vm/tests/work/v025-test/app-debug-androidTest.apk`

The test does **not** bundle Kali. It starts `LibraryActivity` with `iso_url` / `iso_sha256` extras, downloads the ISO on the device, creates a VM, boots it, waits up to 10 minutes after QEMU starts, and takes a UiAutomation screenshot every 30 seconds. Logs (`serial.log`, `qemu-stderr.log`, `selftest.txt`) are copied into the test output dir.

Default image: Debian 13 arm64 **gtk** netboot `mini.iso` (~233 MB), which is the current size — not the historical ~50 MB x86 mini.iso. Text `mini.iso` is ~197 MB. Debian arm64 netinst is ~703 MB. Kali 2026.3 installer is ~3.8 GB and can be passed via `isoUrl` if you want that instead.

SHA-256 of the default gtk mini.iso (from Debian `SHA256SUMS` as of 2026-11-16):

`391245851f4dc87f8d3d4323f80a5bf26fbff717de49139278a58f551dd6a600`

IsoInspector looks for `/install.a64`, `/debian-installer/arm64/linux`, and root `/linux` plus gtk/text `initrd.gz`.

## Command (once a Google account / project exists)

```bash
export PATH="/home/box/google-cloud-sdk/bin:$PATH"
export HOME=/home/box
gcloud auth login
gcloud config set project PROJECT_ID

# Confirm current physical Pixel ids/versions if needed:
# gcloud firebase test android models list --filter="form=PHYSICAL"

APP=/workspace/Nimbus-VM-0.2.5-test.apk
TEST=/workspace/orion-vm/app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

gcloud firebase test android run   --type instrumentation   --app "$APP"   --test "$TEST"   --timeout 45m   --device model=oriole,version=33,locale=en,orientation=landscape   --device model=panther,version=33,locale=en,orientation=landscape   --device model=shiba,version=34,locale=en,orientation=landscape   --environment-variables isoUrl=https://deb.debian.org/debian/dists/stable/main/installer-arm64/current/images/netboot/gtk/mini.iso,isoSha256=391245851f4dc87f8d3d4323f80a5bf26fbff717de49139278a58f551dd6a600   --directories-to-pull /sdcard/googletest/test_outputfiles,/sdcard/Android/data/com.lex.nimbusvm/files/ftl
```

Device ids (typical): Pixel 6 = `oriole`, Pixel 7 = `panther`, Pixel 8 = `shiba`. Versions must match what `models list` shows as available. Timeout is 45 minutes so a ~233 MB download plus a 10 minute boot wait can finish.

To use Kali instead, set `isoUrl` to the official installer-arm64 ISO and `isoSha256` to its SUMS digest.

Screenshots land in `/sdcard/googletest/test_outputfiles/shot-*.png` and the app's `files/ftl/` dir, which FTL pulls with `--directories-to-pull`.

## v0.2.9 short real-device run (what actually worked)

- Build: `./gradlew :app:assembleRelease :app:assembleReleaseAndroidTest -PtestBuildType=release` (test APK must carry the release signature).
- Push the ISO with `--other-files /sdcard/nimbus/x.iso=<local iso>` and pass `isoPushed=/sdcard/nimbus/x.iso,isoSha256=...,setupMin=3,bootMin=5`. Do not push into `/sdcard/Android/data/<pkg>` (pushed before install, so the app gets EACCES). On-device downloads of 600 MB took more than 5 minutes.
- `--device model=oriole,version=33,locale=en,orientation=landscape --timeout 10m --no-record-video`.
