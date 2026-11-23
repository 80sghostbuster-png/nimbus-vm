#!/usr/bin/env bash
# Package the Phase-1 debug APK (signed). Never prints keystore passwords.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$HOME/sdk/env.sh"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/sdk/android}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="${JAVA_HOME:-$HOME/sdk/jdk}"
export PATH="$JAVA_HOME/bin:$PATH"

"$ROOT/scripts/unpack-firmware.sh"

# Stage firmware assets (gzipped) for the app to unpack on first run
ASSET_FW="$ROOT/app/app/src/main/assets/firmware"
mkdir -p "$ASSET_FW"
cp -a "$ROOT/firmware/edk2-gz/." "$ASSET_FW/"

# Stage any built native qemu libs (optional at first — APK can still build the JNI harness)
JNILIBS="$ROOT/app/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNILIBS"

SIGNING_DIR="$ROOT/signing"
PROPS="$ROOT/app/keystore.properties"
KEEP="$SIGNING_DIR/KEYSTORE-KEEP-SAFE.txt"
STORE="$SIGNING_DIR/nimbus-vm.release.jks"

mkdir -p "$SIGNING_DIR"
if [[ ! -f "$STORE" ]]; then
  # Generate a password to a mode-600 file; never echo it.
  umask 077
  python3 - << 'PY'
import secrets, pathlib
p = pathlib.Path("/workspace/orion-vm/signing/KEYSTORE-KEEP-SAFE.txt")
if not p.exists():
    pw = secrets.token_urlsafe(24)
    p.write_text(
        "Nimbus VM release keystore (KEEP PRIVATE, do not commit, do not share)\n"
        "storeFile=nimbus-vm.release.jks\n"
        "storePassword=" + pw + "\n"
        "keyAlias=nimbusvm\n"
        "keyPassword=" + pw + "\n"
    )
PY
  # Feed keytool passwords from the keep-safe file without printing them
  python3 - << 'PY'
import subprocess, pathlib, tempfile, os, stat
text = pathlib.Path("/workspace/orion-vm/signing/KEYSTORE-KEEP-SAFE.txt").read_text()
vals = {}
for line in text.splitlines():
    if "=" in line and not line.startswith("Nimbus"):
        k, _, v = line.partition("=")
        vals[k.strip()] = v.strip()
pw_file = pathlib.Path("/workspace/orion-vm/signing/.pw")
pw_file.write_text(vals["storePassword"])
pw_file.chmod(0o600)
cmd = [
    os.environ.get("JAVA_HOME", "/home/box/sdk/jdk") + "/bin/keytool",
    "-genkeypair",
    "-keystore", "/workspace/orion-vm/signing/nimbus-vm.release.jks",
    "-alias", vals["keyAlias"],
    "-keyalg", "RSA",
    "-keysize", "4096",
    "-validity", "10000",
    "-dname", "CN=Nimbus VM, OU=ORION, O=Lex, C=US",
    "-storepass:file", str(pw_file),
    "-keypass:file", str(pw_file),
]
subprocess.check_call(cmd)
pw_file.unlink()
print("keystore created (password not printed)")
PY
fi

# Write gradle keystore.properties from the keep-safe file (gitignored)
python3 - << 'PY'
from pathlib import Path
vals = {}
for line in Path("/workspace/orion-vm/signing/KEYSTORE-KEEP-SAFE.txt").read_text().splitlines():
    if "=" in line and not line.startswith("Nimbus"):
        k, _, v = line.partition("=")
        vals[k.strip()] = v.strip()
Path("/workspace/orion-vm/app/keystore.properties").write_text(
    f"storeFile=/workspace/orion-vm/signing/nimbus-vm.release.jks\n"
    f"storePassword={vals['storePassword']}\n"
    f"keyAlias={vals['keyAlias']}\n"
    f"keyPassword={vals['keyPassword']}\n"
)
Path("/workspace/orion-vm/app/keystore.properties").chmod(0o600)
print("keystore.properties written")
PY

# Hook signing into the app module if not already
python3 - << 'PY'
from pathlib import Path
p = Path("/workspace/orion-vm/app/app/build.gradle.kts")
t = p.read_text()
if "signingConfigs" not in t:
    hook = '''
val ks = rootProject.file("keystore.properties")
val ksProps = java.util.Properties()
if (ks.exists()) ks.inputStream().use { ksProps.load(it) }

'''
    t = t.replace("android {", hook + "android {", 1)
    t = t.replace(
        "    buildTypes {",
        '''    signingConfigs {
        create("release") {
            val store = ksProps.getProperty("storeFile")
            if (store != null) {
                storeFile = file(store)
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {''',
        1,
    )
    t = t.replace(
        '        getByName("release") {\n            isMinifyEnabled = false\n',
        '        getByName("release") {\n            signingConfig = signingConfigs.getByName("release")\n            isMinifyEnabled = false\n',
        1,
    )
    p.write_text(t)
    print("signingConfig hooked")
else:
    print("signingConfig already present")
PY

cd "$ROOT/app"
./gradlew :app:assembleRelease --no-daemon
# Copy to a stable name
OUT=$(find "$ROOT/app/app/build/outputs/apk" -name '*.apk' | head -1)
mkdir -p "$ROOT/dist-apk"
# Use dist-apk (not dist/ or build/) so it is not wiped
cp -a "$OUT" "$ROOT/dist-apk/Nimbus-VM.apk"
ls -lh "$ROOT/dist-apk/Nimbus-VM.apk"
echo "APK ready: $ROOT/dist-apk/Nimbus-VM.apk"
