#!/usr/bin/env bash
# Unpack gzip-compressed EDK2 firmware into firmware/edk2 (gitignored raw .fd files).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/firmware/edk2-gz"
DST="$ROOT/firmware/edk2"
mkdir -p "$DST"
for f in "$SRC"/*.fd.gz; do
  base="$(basename "$f" .gz)"
  if [[ ! -s "$DST/$base" ]]; then
    echo "unpack $base"
    gzip -dc "$f" > "$DST/$base"
  fi
done
echo "firmware ready in $DST"
ls -lh "$DST"
