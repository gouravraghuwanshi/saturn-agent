# Runs on the phone inside the test app (via phone-env.sh). For each package in list.txt: `pi install npm:<pkg>`
# through Saturn's npm wrapper, then load it with pi's own extension loader (probe.mjs). Writes results.jsonl.
# Starts from an empty extension folder so earlier runs don't affect this one.
cd ~/exttest
PI=$(ls -d ~/../pi/node_modules/@earendil-works/pi-coding-agent)
export PI_LOADER=$PI/dist/core/extensions/loader.js
node -e 'const f=process.env.HOME+"/.pi/agent/settings.json";const fs=require("fs");const s=JSON.parse(fs.readFileSync(f));delete s.packages;fs.writeFileSync(f,JSON.stringify(s,null,2))'
mkdir -p ~/.pi/agent/npm && cd ~/.pi/agent/npm && rm -rf node_modules package-lock.json && echo '{"name":"pi-extensions","private":true}' > package.json
cd ~/exttest; : > results.jsonl
while read -r n; do
  [ -z "$n" ] && continue
  t0=$(date +%s); timeout 600 node "$PI/dist/bundle/cli.js" install "npm:$n" > install.log 2>&1; rc=$?; t1=$(date +%s)
  d=~/.pi/agent/npm/node_modules/$n
  if [ -d "$d" ]; then pr=$(timeout 90 node ~/exttest/probe.mjs "$d" 2>/dev/null | tail -1); fi
  [ -z "$pr" ] && pr='{}'
  log=$(grep -iE "saturn:|error|ERR!" install.log | tail -4 | cut -c1-200 | node -e 'process.stdout.write(JSON.stringify(require("fs").readFileSync(0,"utf8")))')
  echo "{\"pkg\":\"$n\",\"rc\":$rc,\"secs\":$((t1-t0)),\"log\":$log,\"probe\":$pr}" >> results.jsonl
  pr=''
done < list.txt
echo DONE >> results.jsonl
