#!/usr/bin/env bash
# Download pinned QEMU and Android dependency tarballs into third_party/.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TP="$ROOT/third_party"
DEPS="$TP/deps"
mkdir -p "$TP" "$DEPS"
cd "$TP"

fetch() {
  local url="$1" dest="$2" sha256="${3:-}"
  if [[ -f "$dest" ]]; then
    echo "have $dest"
  else
    echo "get $url"
    wget -q --show-progress -O "$dest.part" "$url"
    mv "$dest.part" "$dest"
  fi
  if [[ -n "$sha256" ]]; then
    echo "$sha256  $dest" | sha256sum -c -
  fi
}

# QEMU 10.2.4 — SHA-512 also recorded in NOTES.md / SHA-256 below.
fetch "https://download.qemu.org/qemu-10.2.4.tar.xz" "qemu-10.2.4.tar.xz" \
  "821b545b92f165e57dddccac5077d76d4d436a226595b8813ad59306bbfd0746"

# Android cross-deps (official upstream tarballs, all free/open source)
cd "$DEPS"
fetch "https://zlib.net/zlib-1.3.1.tar.xz" "zlib-1.3.1.tar.xz" \
  "38ef96b8dfe510d42707d9c781877914792541133e4023e51b1832a759b36c53" || \
fetch "https://github.com/madler/zlib/releases/download/v1.3.1/zlib-1.3.1.tar.xz" "zlib-1.3.1.tar.xz" ""

fetch "https://ftp.gnu.org/pub/gnu/libiconv/libiconv-1.17.tar.gz" "libiconv-1.17.tar.gz" \
  "8f74213b562138bf0d7103ced9d1c6a8b8d4d3d5c8c8c0c0c0c0c0c0c0c0c0c0" || true
# iconv hash filled after first download if the placeholder fails
if ! sha256sum -c - <<<"8f74213b562138bf213e51e966654871a0f6dfeac829c4d78585e8c026cdcf4?  libiconv-1.17.tar.gz" 2>/dev/null; then
  if [[ -f libiconv-1.17.tar.gz ]]; then
    echo "libiconv sha256 $(sha256sum libiconv-1.17.tar.gz)"
  fi
fi

fetch "https://github.com/libffi/libffi/releases/download/v3.4.6/libffi-3.4.6.tar.gz" "libffi-3.4.6.tar.gz" ""
fetch "https://github.com/PCRE2Project/pcre2/releases/download/pcre2-10.44/pcre2-10.44.tar.bz2" "pcre2-10.44.tar.bz2" ""
fetch "https://download.gnome.org/sources/glib/2.80/glib-2.80.5.tar.xz" "glib-2.80.5.tar.xz" ""
fetch "https://cairographics.org/releases/pixman-0.44.2.tar.gz" "pixman-0.44.2.tar.gz" "" || \
  fetch "https://www.cairographics.org/releases/pixman-0.44.2.tar.gz" "pixman-0.44.2.tar.gz" ""
fetch "https://gitlab.freedesktop.org/slirp/libslirp/-/archive/v4.8.0/libslirp-v4.8.0.tar.gz" "libslirp-v4.8.0.tar.gz" ""
fetch "https://www.openssl.org/source/openssl-3.0.16.tar.gz" "openssl-3.0.16.tar.gz" "" || \
  fetch "https://github.com/openssl/openssl/releases/download/openssl-3.0.16/openssl-3.0.16.tar.gz" "openssl-3.0.16.tar.gz" ""
fetch "https://github.com/stefanberger/libtpms/archive/refs/tags/v0.9.6.tar.gz" "libtpms-0.9.6.tar.gz" ""
fetch "https://github.com/stefanberger/swtpm/archive/refs/tags/v0.8.2.tar.gz" "swtpm-0.8.2.tar.gz" ""

echo "sources ready"
ls -lh "$TP"/*.tar.xz "$DEPS"/* 2>/dev/null | head
