#!/bin/bash
# Tests Pi extensions on a real phone: installs each one with `pi install` (through Saturn's npm wrapper) and
# loads it with Pi's own loader, then prints which work. Use it after a Pi update or to grow compat/extensions.json.
#
#   runtime/test-extensions.sh pi-lens openwiki        # these packages
#   runtime/test-extensions.sh --top 50                 # the 50 most-downloaded Pi packages on npm
#
# Needs: the phone on adb, unlocked, and the debug test app installed and open on screen (Android cuts network
# for apps that aren't on screen):  ./gradlew :app:assembleDebug -PappId=io.github.pimobile.spike && adb install -r …
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
S="${ANDROID_SERIAL:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}"
[ -n "$S" ] || { echo "no phone on adb" >&2; exit 1; }
LIST=$(mktemp)
if [ "${1:-}" = "--top" ]; then
  curl -fsS "https://registry.npmjs.org/-/v1/search?text=keywords:pi-package&size=250&popularity=1.0&quality=0.0&maintenance=0.0" |
    python3 -c "import sys,json;d=json.load(sys.stdin)['objects'];d.sort(key=lambda o:-o.get('downloads',{}).get('weekly',0));print('\n'.join(o['package']['name'] for o in d[:int(sys.argv[1])]))" "${2:-50}" > "$LIST"
else printf '%s\n' "$@" > "$LIST"; fi
[ -s "$LIST" ] || { echo "usage: $0 <package>... | --top N" >&2; exit 2; }
adb -s "$S" push "$HERE/phone-env.sh" "$HERE/on-phone.sh" "$HERE/probe.mjs" /data/local/tmp/ >/dev/null
adb -s "$S" push "$LIST" /data/local/tmp/list.txt >/dev/null
ph() { local q; q=$(printf '%s' "$1" | sed "s/'/'\\\\''/g"); adb -s "$S" shell "run-as io.github.pimobile.spike sh /data/local/tmp/phone-env.sh '$q'"; }
adb -s "$S" shell monkey -p io.github.pimobile.spike -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
ph 'mkdir -p ~/exttest && rm -f ~/exttest/results.jsonl && cp /data/local/tmp/{on-phone.sh,probe.mjs,list.txt} ~/exttest/ && cd ~/exttest && (nohup bash on-phone.sh > on-phone.log 2>&1 &)'
echo "testing $(wc -l < "$LIST") package(s) on $S ..."
until ph 'grep -q DONE ~/exttest/results.jsonl 2>/dev/null && echo FIN' 2>/dev/null | grep -q FIN; do sleep 20; done
ph 'cat ~/exttest/results.jsonl' | python3 -c '
import sys,json,re
t=sys.stdin.read()
for m in re.finditer(r"\{\"pkg\":.*?(?=\n\{\"pkg\":|\nDONE)", t, re.S):
    try: r=json.loads(m.group(0).replace("\n","\\n"))
    except Exception: continue
    p=r.get("probe",{}); e=(p.get("errors") or [p.get("error","")]); e=e[0] if e else ""
    ok=r["rc"]==0 and (p.get("loaded",0)>0 or p.get("skillsOnly")) and not e
    why="" if ok else "  "+(e or r.get("log","")).strip().replace("\n"," | ")[:200]
    print(("WORKS " if ok else "FAILS ")+r["pkg"]+" ("+str(r["secs"])+"s)"+why)
'
