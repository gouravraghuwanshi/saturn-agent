#!/bin/bash
# Builds Android (arm64, API 29) versions of native Node add-ons that popular Pi extensions need and npm
# can't compile on a phone. They're Node-API add-ons, so one build works across Node versions. Output goes
# to app/src/main/assets/saturn-compat/addons/, and saturn-compat.cjs copies each into a matching package
# after `pi install`. Needs the Android NDK (ndkVersion in app/build.gradle.kts).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/29.0.14206865}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CXX="$TC/aarch64-linux-android29-clang++"
OUT="$HERE/../app/src/main/assets/saturn-compat/addons"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$OUT"
pack() { (cd "$T" && npm pack "$1" --silent >/dev/null && mkdir -p "$2" && tar xzf "$(ls -t *.tgz | head -1)" -C "$2" && rm -f *.tgz); }
pack node-addon-api@7.1.1 naa; pack node-api-headers@1.5.0 nah
INC="-I$T/nah/package/include -I$T/naa/package"
FLAGS="-shared -fPIC -O2 -std=c++17 -fexceptions -DNAPI_CPP_EXCEPTIONS -static-libstdc++ -Wl,-z,max-page-size=16384 -Wl,-z,undefs"

# node-pty 1.1.x: terminals for extensions (src/unix/pty.cc; Android's libc has forkpty).
pack node-pty@1.1.0 pty
"$CXX" $FLAGS $INC "$T/pty/package/src/unix/pty.cc" -o "$OUT/node-pty@1.1.node"
"$TC/llvm-strip" "$OUT/node-pty@1.1.node"
echo "node-pty@1.1.node ($(du -h "$OUT/node-pty@1.1.node" | cut -f1))"
