#!/usr/bin/env bash
# Builds app/src/main/jniLibs/arm64-v8a from Termux's prebuilt aarch64 packages.
# Termux is used only as a source of Bionic-compiled binaries; the app does
# not need Termux installed.
#
# Android only packages files named lib*.so, so versioned sonames
# (libssl.so.3 ...) are renamed and every DT_NEEDED reference is rewritten.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../app/src/main/jniLibs/arm64-v8a"
WORK="${WORK:-$HERE/build/termux}"
REPO=https://packages.termux.dev/apt/termux-main

PKGS=(
  pool/main/n/nodejs-lts/nodejs-lts_24.22.3_aarch64.deb
  pool/main/libc/libc++/libc++_30_aarch64.deb
  pool/main/o/openssl/openssl_1:3.6.5_aarch64.deb
  pool/main/c/c-ares/c-ares_1.34.9_aarch64.deb
  pool/main/libi/libicu/libicu_79.1_aarch64.deb
  pool/main/libs/libsqlite/libsqlite_3.54.1-1_aarch64.deb
  pool/main/z/zlib/zlib_1.4_aarch64.deb
)

# old soname -> new packageable name (new must not be longer than old)
MAP=(
  libz.so.1=libz1.so
  libcrypto.so.3=libcrypto3.so
  libssl.so.3=libssl3.so
  libicui18n.so.79=libicui18n79.so
  libicuuc.so.79=libicuuc79.so
  libicudata.so.79=libicudata79.so
)

# source path inside the Termux prefix -> file name in jniLibs
FILES=(
  bin/node:libnode.so
  lib/libz.so.1.4:libz1.so
  lib/libcrypto.so.3:libcrypto3.so
  lib/libssl.so.3:libssl3.so
  lib/libicui18n.so.79.1:libicui18n79.so
  lib/libicuuc.so.79.1:libicuuc79.so
  lib/libicudata.so.79.1:libicudata79.so
  lib/libsqlite3.so.3.54.1:libsqlite3.so
  lib/libcares.so:libcares.so
  lib/libc++_shared.so:libc++_shared.so
)

rm -rf "$WORK" && mkdir -p "$WORK/debs" "$WORK/root" "$OUT"
for p in "${PKGS[@]}"; do
  echo "fetch $(basename "$p")"
  curl -fsSL -o "$WORK/debs/$(basename "$p")" "$REPO/$p"
done
for d in "$WORK"/debs/*.deb; do
  x="$WORK/x" && rm -rf "$x" && mkdir -p "$x"
  (cd "$x" && ar x "$d" && tar -xf data.tar.* -C "$WORK/root")
done
rm -rf "$WORK/x"

PREFIX="$WORK/root/data/data/com.termux/files/usr"
for f in "${FILES[@]}"; do
  echo "patch ${f##*:}"
  python3 -I "$HERE/tools/elf_rename.py" "$OUT/${f##*:}" "$PREFIX/${f%%:*}" "${MAP[@]}"
done
echo "done -> $OUT"
