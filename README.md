# pi-android

Standalone Android app running the real, unmodified
[Pi Coding Agent](https://pi.dev) (`@earendil-works/pi-coding-agent`) on-device.

## Status
- ✅ Phase 1 — runtime spike: unmodified Pi over `pi --mode rpc` on bundled Node 24.
- ✅ Phase 2 — toolchain + task engine: bash, git (https), ripgrep, curl, npm;
  one pi process per task (`--session-id`, resumable); foreground service.
- 🟡 Phase 3 — premium Compose UI ("Phosphor"): home task cards, task screen,
  slash palette, settings/model/project sheets, terminal tab (pi's real TUI), in-app browser (System WebView over CDP), extension store ✅

## How the runtime works
Executables ship as `jniLibs/arm64-v8a/libx_<cmd>.so` (Android only allows
exec() from the APK's native lib dir). At startup `PiRuntime` symlinks them into
`files/usr/bin` under their real names and builds the environment (PATH,
LD_LIBRARY_PATH, CA bundle, GIT_EXEC_PATH, BASH_ENV with npm/npx helpers...).

## Build
```sh
python3 -I runtime/import_termux.py  # toolchain -> jniLibs + assets/usr-data.zip (sha256-verified)
runtime/build-pi-bundle.sh    # official Pi from npm -> app/src/main/assets/pi-bundle.zip
./gradlew assembleDebug
```
Requires JDK 17, Android SDK (platform 37, NDK r29), Node/npm on the build host.
