#!/usr/bin/env bash
# Cross-compile QEMU 10.2.4 (aarch64 + x86_64 system emulators, TCG) for Android arm64.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$HOME/sdk/env.sh"
NDK="${ANDROID_NDK_HOME:-$HOME/sdk/android/ndk/27.3.13750724}"
API=28
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="$TOOLCHAIN/bin:$PATH"
export CC=aarch64-linux-android${API}-clang
export CXX=aarch64-linux-android${API}-clang++
export AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip
PREFIX="$ROOT/native/android-prefix"
SRC="$ROOT/third_party/qemu-10.2.4"
BLD="$ROOT/native/android-qemu-bld"
JOBS="${JOBS:-3}"
mkdir -p "$BLD"

export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
export PKG_CONFIG_PATH="$PKG_CONFIG_LIBDIR"
export PKG_CONFIG_SYSROOT_DIR=""

# Meson cross file for QEMU's configure
cat > "$BLD/android.cross" << CROSS
[binaries]
c = '$CC'
cpp = '$CXX'
ar = 'llvm-ar'
strip = 'llvm-strip'
pkg-config = 'pkg-config'
windres = ''

[built-in options]
c_args = ['-fPIC', '-O2', '-I$PREFIX/include', '-D__ANDROID_API__=$API']
c_link_args = ['-fPIC', '-L$PREFIX/lib', '-static-libstdc++']

[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[properties]
android = true
CROSS

if [[ ! -f "$BLD/config-host.mak" ]]; then
  cd "$BLD"
  "$SRC/configure" \
    --cc="$CC" \
    --host-cc=gcc \
    --cxx="$CXX" \
    --cpu=aarch64 \
    --target-list=aarch64-softmmu,x86_64-softmmu \
    --prefix="$PREFIX" \
    --enable-tcg \
    --disable-kvm \
    --disable-xen \
    --disable-hax \
    --disable-whpx \
    --disable-hvf \
    --disable-docs \
    --disable-user \
    --disable-guest-agent \
    --disable-werror \
    --disable-gtk \
    --disable-sdl \
    --disable-opengl \
    --disable-virglrenderer \
    --disable-vhost-user \
    --disable-vhost-net \
    --disable-vhost-kernel \
    --disable-libnfs \
    --disable-libusb \
    --disable-gnutls \
    --disable-nettle \
    --disable-gcrypt \
    --disable-curl \
    --disable-rdma \
    --disable-libiscsi \
    --disable-linux-aio \
    --disable-linux-io-uring \
    --disable-selinux \
    --disable-cap-ng \
    --disable-seccomp \
    --disable-modules \
    --disable-libudev \
    --disable-spice \
    --disable-brlapi \
    --disable-smartcard \
    --disable-usb-redir \
    --disable-lzo \
    --disable-snappy \
    --disable-bzip2 \
    --disable-lzfse \
    --disable-zstd \
    --disable-oss \
    --disable-alsa \
    --disable-pa \
    --disable-sndio \
    --disable-jack \
    --disable-coreaudio \
    --disable-dsound \
    --audio-drv-list= \
    --enable-slirp \
    --enable-vnc \
    --enable-tools \
    --enable-virtfs \
    --enable-attr \
    --extra-cflags="-I$PREFIX/include -fPIC" \
    --extra-ldflags="-L$PREFIX/lib -fPIC" \
    --with-pkgversion="nimbus-vm-android" \
    2>&1 | tee "$ROOT/evidence/qemu-android-configure.log"
fi

cd "$BLD"
ninja -j "$JOBS" 2>&1 | tee "$ROOT/evidence/qemu-android-ninja.log"

# Package as lib*.so (PIE executables renamed for jniLibs)
OUT="$ROOT/app/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"
for b in qemu-system-aarch64 qemu-system-x86_64 qemu-img; do
  if [[ -x "$BLD/$b" ]]; then
    llvm-strip -s "$BLD/$b" -o "$OUT/lib${b}.so" || cp "$BLD/$b" "$OUT/lib${b}.so"
    chmod 0755 "$OUT/lib${b}.so"
    ls -lh "$OUT/lib${b}.so"
  fi
done

# Also copy any needed shared STL / system helpers if we linked them
if [[ -f "$TOOLCHAIN/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" ]]; then
  cp "$TOOLCHAIN/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$OUT/" || true
fi

echo "Android QEMU binaries staged in $OUT"
