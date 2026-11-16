# pi-android

Standalone Android app running the real, unmodified
[Pi Coding Agent](https://pi.dev) (`@earendil-works/pi-coding-agent`) on-device.

## Status: runtime spike ✅
Node 24 (Bionic arm64) + Pi run from the APK's native library dir, over `pi --mode rpc`.

## Build
```sh
runtime/import-node.sh        # Node runtime -> app/src/main/jniLibs/arm64-v8a
runtime/build-pi-bundle.sh    # official Pi from npm -> app/src/main/assets/pi-bundle.zip
./gradlew assembleDebug
```
Requires JDK 17, Android SDK (platform 37, NDK r29), Node/npm on the build host.
