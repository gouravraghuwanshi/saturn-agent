#!/bin/bash
# Fetches musl's loader (which is also its libc) and libgcc_s from Alpine Linux, checks their hashes, and
# puts them in jniLibs so Saturn can run Linux musl programs that npm packages download (see piexec.c).
# musl: MIT. libgcc: GPL-3.0 with the GCC Runtime Library Exception.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../app/src/main/jniLibs/arm64-v8a"
B=https://dl-cdn.alpinelinux.org/alpine/v3.22/main/aarch64
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
get() { # file, expected sha256 of the file inside, path inside the apk, output name
  curl -fsSL -o "$T/$1" "$B/$1"; mkdir -p "$T/x"; tar xzf "$T/$1" -C "$T/x" 2>/dev/null
  echo "$2  $T/x/$3" | sha256sum -c --quiet; install -m 755 "$T/x/$3" "$OUT/$4"; echo "$4 <- $1/$3"
}
get musl-1.2.5-r13.apk 42fb025bc758b76e60e7b3b0fcdcfab93b6ba1694b363244c2e8e6f51847e28c lib/ld-musl-aarch64.so.1 libx_ldmusl.so
get libgcc-14.2.0-r6.apk 2d945d6ce360769b41fbffc652c2440014bbd69416f13fe206fbac19d32c9431 usr/lib/libgcc_s.so.1 libx_musl_gcc_s.so
