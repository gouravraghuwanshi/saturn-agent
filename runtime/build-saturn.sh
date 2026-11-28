#!/usr/bin/env bash
# Builds Saturn's native tool binary (native/saturn: the phone-command bridge, jq, fold) for
# arm64 Android with the NDK, as app/src/main/jniLibs/arm64-v8a/libx_saturn.so. The app links
# each command name in ~/usr/bin to it (runtime/PiRuntime.kt). Needs rustup's aarch64-linux-android target.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
NDK="$SDK/ndk/29.0.14206865"
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
[ -x "$CLANG" ] || { echo "NDK r29 not found at $NDK" >&2; exit 1; }
CARGO="${CARGO:-$(command -v cargo || echo "$HOME/.cargo/bin/cargo")}"
cd "$HERE/../native/saturn"
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CLANG" "$CARGO" build --release --locked --target aarch64-linux-android
OUT="$HERE/../app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"
cp target/aarch64-linux-android/release/saturn "$OUT/libx_saturn.so"
echo "saturn -> $OUT/libx_saturn.so ($(du -h "$OUT/libx_saturn.so" | cut -f1))"
