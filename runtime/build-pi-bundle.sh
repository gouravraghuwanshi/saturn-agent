#!/usr/bin/env bash
# Installs the official, unmodified Pi Coding Agent from npm and packs it as
# app/src/main/assets/pi-bundle.zip, which the app extracts on first launch.
set -euo pipefail

PI_VERSION="${PI_VERSION:-1.5.2}"
HERE="$(cd "$(dirname "$0")" && pwd)"
STAGE="$HERE/build/pi"
ASSETS="$HERE/../app/src/main/assets"

rm -rf "$STAGE" && mkdir -p "$STAGE" "$ASSETS"
cd "$STAGE"
echo '{"private":true}' > package.json
# --ignore-scripts: as recommended by Pi's own Termux docs.
npm install --omit=dev --ignore-scripts --no-audit --no-fund \
  "@earendil-works/pi-coding-agent@$PI_VERSION"

# Drop desktop-only native prebuilds (Android never loads them).
find node_modules -path '*/prebuilds/*' -type d \
  \( -name 'darwin-*' -o -name 'win32-*' -o -name 'linux-x64' \) -prune -exec rm -rf {} +
find node_modules -type d \( -name darwin -o -name win32 \) -path '*/native/*' -prune -exec rm -rf {} +

python3 -I - "$STAGE" "$ASSETS/pi-bundle.zip" <<'EOF'
import os, sys, zipfile
stage, out = sys.argv[1], sys.argv[2]
n = 0
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for root, dirs, files in os.walk(os.path.join(stage, "node_modules")):
        dirs[:] = [d for d in dirs if d != ".bin"]
        for f in files:
            p = os.path.join(root, f)
            if os.path.islink(p):
                continue
            z.write(p, os.path.relpath(p, stage)); n += 1
print(f"{n} files -> {out} ({os.path.getsize(out)//1024//1024} MB)")
EOF
echo "$PI_VERSION" > "$ASSETS/pi-bundle.version"
