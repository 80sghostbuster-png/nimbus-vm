#!/usr/bin/env bash
# Cross-compile QEMU's open-source dependencies for Android arm64-v8a (API 28).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$HOME/sdk/env.sh"
NDK="${ANDROID_NDK_HOME:-$HOME/sdk/android/ndk/27.3.13750724}"
API=28
HOST_TAG=linux-x86_64
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
export PATH="$TOOLCHAIN/bin:$PATH"
export AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip NM=llvm-nm
export CC=aarch64-linux-android${API}-clang
export CXX=aarch64-linux-android${API}-clang++
export CHOST=aarch64-linux-android
export CFLAGS="-fPIC -O2 -D__ANDROID_API__=${API}"
export CXXFLAGS="$CFLAGS"
export LDFLAGS="-fPIC"

PREFIX="$ROOT/native/android-prefix"
DEPS="$ROOT/third_party/deps"
SRC="$ROOT/native/android-deps-src"
BLD="$ROOT/native/android-deps-bld"
JOBS="${JOBS:-3}"
mkdir -p "$PREFIX" "$SRC" "$BLD" "$DEPS"

need() { command -v "$1" >/dev/null || { echo "missing $1"; exit 1; }; }
need meson; need ninja; need "$CC"

extract() {
  local tarball="$1" dest="$2"
  if [[ ! -d "$dest" ]]; then
    mkdir -p "$(dirname "$dest")"
    tar -xf "$tarball" -C "$(dirname "$dest")"
  fi
}

pkg_cfg_env() {
  export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig:$PREFIX/share/pkgconfig"
  export PKG_CONFIG_PATH="$PKG_CONFIG_LIBDIR"
  export PKG_CONFIG_SYSROOT_DIR=""
  export PKG_CONFIG_DIR=""
  # Do not pick up host Debian libraries.
  export PKG_CONFIG_SYSTEM_INCLUDE_PATH="$PREFIX/include"
  export PKG_CONFIG_SYSTEM_LIBRARY_PATH="$PREFIX/lib"
  export PKG_CONFIG="pkg-config --static"
}

write_cross() {
  cat > "$BLD/android-aarch64.cross" << CROSS
[binaries]
c = '$CC'
cpp = '$CXX'
ar = 'llvm-ar'
strip = 'llvm-strip'
ranlib = 'llvm-ranlib'
pkg-config = 'pkg-config'

[built-in options]
c_args = ['-fPIC', '-O2', '-I$PREFIX/include']
cpp_args = ['-fPIC', '-O2', '-I$PREFIX/include']
c_link_args = ['-L$PREFIX/lib', '-fPIC']
cpp_link_args = ['-L$PREFIX/lib', '-fPIC']

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
CROSS
}

echo "NDK=$NDK  PREFIX=$PREFIX"
write_cross
pkg_cfg_env

# --- zlib ---
if [[ ! -f "$PREFIX/lib/libz.a" ]]; then
  echo "== zlib =="
  extract "$DEPS"/zlib-1.3.1.tar.xz "$SRC/zlib-1.3.1"
  pushd "$SRC/zlib-1.3.1" >/dev/null
  make distclean >/dev/null 2>&1 || true
  CHOST=aarch64-linux-android ./configure --static --prefix="$PREFIX"
  make -j"$JOBS"
  make install
  popd >/dev/null
fi

# --- libiconv ---
if [[ ! -f "$PREFIX/lib/libiconv.a" ]]; then
  echo "== libiconv =="
  extract "$DEPS"/libiconv-1.17.tar.gz "$SRC/libiconv-1.17"
  pushd "$SRC/libiconv-1.17" >/dev/null
  ./configure --host=aarch64-linux-android --prefix="$PREFIX" --enable-static --disable-shared CC="$CC" AR="$AR" RANLIB="$RANLIB"
  make -j"$JOBS"
  make install
  popd >/dev/null
fi

# --- libffi ---
if [[ ! -f "$PREFIX/lib/libffi.a" ]]; then
  echo "== libffi =="
  extract "$DEPS"/libffi-3.4.6.tar.gz "$SRC/libffi-3.4.6"
  pushd "$SRC/libffi-3.4.6" >/dev/null
  ./configure --host=aarch64-linux-android --prefix="$PREFIX" --enable-static --disable-shared --disable-multi-os-directory CC="$CC"
  make -j"$JOBS"
  make install
  popd >/dev/null
fi

# --- pcre2 ---
if [[ ! -f "$PREFIX/lib/libpcre2-8.a" ]]; then
  echo "== pcre2 =="
  extract "$DEPS"/pcre2-10.44.tar.bz2 "$SRC/pcre2-10.44"
  pushd "$SRC/pcre2-10.44" >/dev/null
  ./configure --host=aarch64-linux-android --prefix="$PREFIX" --enable-static --disable-shared --enable-pcre2-8 --disable-pcre2grep --disable-pcre2test CC="$CC"
  make -j"$JOBS"
  make install
  popd >/dev/null
fi

# --- glib ---
if [[ ! -f "$PREFIX/lib/libglib-2.0.a" ]]; then
  echo "== glib =="
  extract "$DEPS"/glib-2.80.5.tar.xz "$SRC/glib-2.80.5"
  meson setup "$BLD/glib" "$SRC/glib-2.80.5" \
    --cross-file "$BLD/android-aarch64.cross" \
    --prefix="$PREFIX" \
    --default-library=static \
    --buildtype=release \
    -Dlibmount=disabled \
    -Dselinux=disabled \
    -Dtests=false \
    -Dsysprof=disabled \
    -Dintrospection=disabled \
    -Dnls=disabled \
    -Dglib_debug=disabled \
    -Dlibelf=disabled \
    -Dxattr=false \
    -Dglib_assert=false \
    -Dman-pages=disabled \
    -Ddtrace=false \
    -Dsystemtap=false
  meson compile -C "$BLD/glib" -j "$JOBS"
  meson install -C "$BLD/glib"
fi

# --- pixman ---
if [[ ! -f "$PREFIX/lib/libpixman-1.a" ]]; then
  echo "== pixman =="
  extract "$DEPS"/pixman-0.44.2.tar.gz "$SRC/pixman-0.44.2"
  meson setup "$BLD/pixman" "$SRC/pixman-0.44.2" \
    --cross-file "$BLD/android-aarch64.cross" \
    --prefix="$PREFIX" \
    --default-library=static \
    --buildtype=release \
    -Dtests=disabled \
    -Dgtk=disabled
  meson compile -C "$BLD/pixman" -j "$JOBS"
  meson install -C "$BLD/pixman"
fi

# --- libslirp ---
if [[ ! -f "$PREFIX/lib/libslirp.a" ]]; then
  echo "== libslirp =="
  extract "$DEPS"/libslirp-v4.8.0.tar.gz "$SRC/libslirp-v4.8.0"
  meson setup "$BLD/libslirp" "$SRC/libslirp-v4.8.0" \
    --cross-file "$BLD/android-aarch64.cross" \
    --prefix="$PREFIX" \
    --default-library=static \
    --buildtype=release
  meson compile -C "$BLD/libslirp" -j "$JOBS"
  meson install -C "$BLD/libslirp"
fi

# --- openssl (for libtpms / swtpm) ---
if [[ ! -f "$PREFIX/lib/libcrypto.a" ]]; then
  echo "== openssl =="
  export ANDROID_NDK_ROOT="$NDK"
  extract "$DEPS"/openssl-3.0.16.tar.gz "$SRC/openssl-3.0.16"
  pushd "$SRC/openssl-3.0.16" >/dev/null
  ./Configure android-arm64 no-shared no-tests --prefix="$PREFIX" --openssldir="$PREFIX/ssl" \
    -D__ANDROID_API__=$API \
    --with-zlib-include="$PREFIX/include" --with-zlib-lib="$PREFIX/lib"
  make -j"$JOBS"
  make install_sw
  popd >/dev/null
fi

# --- libtpms ---
if [[ ! -f "$PREFIX/lib/libtpms.a" ]]; then
  echo "== libtpms =="
  extract "$DEPS"/libtpms-0.9.6.tar.gz "$SRC/libtpms-0.9.6"
  pushd "$SRC/libtpms-0.9.6" >/dev/null
  if [[ ! -f configure ]]; then ./autogen.sh || true; fi
  ./configure --host=aarch64-linux-android --prefix="$PREFIX" --enable-static --disable-shared \
    --with-openssl --with-tpm2 CC="$CC" PKG_CONFIG_PATH="$PKG_CONFIG_LIBDIR"
  make -j"$JOBS"
  make install
  popd >/dev/null
fi

echo "Android deps installed in $PREFIX"
ls -lh "$PREFIX/lib"/*.a | sed -n '1,40p'
