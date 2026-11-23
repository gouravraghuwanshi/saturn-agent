// Small stand-ins for the desktop tools models reach for first (run as `node saturn-tools.js <name> ...`).
// Saturn is Android: there's no python, apt or jq, so these either do the common job in JavaScript
// or say plainly what to use instead, so the next try works.
"use strict";
const fs = require("fs");
const path = require("path");
const [, , name, ...args] = process.argv;

const fail = (msg, code = 1) => { process.stderr.write(msg.endsWith("\n") ? msg : msg + "\n"); process.exit(code); };
const readInput = (files) => files.length ? files.map((f) => fs.readFileSync(f, "utf8")).join("") : fs.readFileSync(0, "utf8");

const NO_PYTHON = `${name}: Saturn runs on Android and has no Python. Use node (JavaScript) instead:
  node -e 'console.log(require("fs").readFileSync("file.txt", "utf8").length)'
  node -p '2n ** 64n'            # quick maths (BigInt for big numbers)
  npm i -g <package>             # libraries; require() them from anywhere`;

const tools = {
  python: () => fail(NO_PYTHON, 127),
  python3: () => fail(NO_PYTHON, 127),
  pip: () => fail(NO_PYTHON, 127),
  pip3: () => fail(NO_PYTHON, 127),
  apt: () => fail(`${name}: Saturn runs on Android: there is no apt/pkg. Command-line tools come from npm (npm i -g <pkg>); for scripts use node.`, 127),

  // jq: the everyday subset (paths, [], |, keys, length, -r, -c).
  jq: () => {
    let raw = false, compact = false, filter = null; const files = [];
    for (const a of args) {
      if (a === "-r" || a === "--raw-output") raw = true;
      else if (a === "-c" || a === "--compact-output") compact = true;
      else if (/^-[rc]+$/.test(a)) { raw ||= a.includes("r"); compact ||= a.includes("c"); }
      else if (a.startsWith("-")) fail(`jq: Saturn's jq is a small built-in and doesn't support ${a}. Use node: node -e 'const d=JSON.parse(require("fs").readFileSync(0,"utf8")); …'`, 2);
      else if (filter === null) filter = a; else files.push(a);
    }
    filter ??= ".";
    const text = readInput(files);
    let docs;
    try { docs = [JSON.parse(text)]; } catch { docs = text.split(/\n(?=\s*[\[{"\d-tfn])/).filter((s) => s.trim()).map((s) => JSON.parse(s)); }
    const step = (values, part) => {
      part = part.trim();
      if (part === "" || part === ".") return values;
      if (part === "keys") return values.map((v) => Object.keys(v ?? {}).sort());
      if (part === "length") return values.map((v) => v == null ? 0 : typeof v === "object" ? (Array.isArray(v) ? v.length : Object.keys(v).length) : String(v).length);
      if (!part.startsWith(".")) fail(`jq: Saturn's jq is a small built-in: it supports . .a.b .a[0] .[] .["key"] | keys length, with -r and -c. For "${part}" use node -e.`, 3);
      let out = values;
      const re = /\.?([A-Za-z_$][\w$]*)|\.?\[\s*(-?\d+)?\s*\]|\.?\[\s*"([^"]*)"\s*\]|\.\s*$/gy;
      let s = part, i = 0;
      while (i < s.length) {
        re.lastIndex = i; const m = re.exec(s);
        if (!m || m.index !== i || m[0] === "") fail(`jq: can't read "${part}" (Saturn's jq is a small built-in; use node -e for this).`, 3);
        i = re.lastIndex;
        if (m[1] !== undefined) out = out.map((v) => v?.[m[1]] ?? null);
        else if (m[3] !== undefined) out = out.map((v) => v?.[m[3]] ?? null);
        else if (m[0].includes("[") && m[2] !== undefined) { const n = +m[2]; out = out.map((v) => Array.isArray(v) ? v[n < 0 ? v.length + n : n] ?? null : null); }
        else if (m[0].includes("[")) out = out.flatMap((v) => Array.isArray(v) ? v : v && typeof v === "object" ? Object.values(v) : []);
      }
      return out;
    };
    let results = docs;
    for (const part of filter.split("|")) results = step(results, part);
    for (const r of results) process.stdout.write((raw && typeof r === "string" ? r : JSON.stringify(r, null, compact ? 0 : 2)) + "\n");
  },

  // fold: wrap lines to a width (-w N, -s to break at spaces).
  fold: () => {
    let width = 80, spaces = false; const files = [];
    for (let i = 0; i < args.length; i++) {
      const a = args[i];
      if (a === "-s") spaces = true;
      else if (a === "-w") width = +args[++i];
      else if (/^-w\d+$/.test(a)) width = +a.slice(2);
      else if (/^-s?w\d+$|^-ws$/.test(a)) { spaces = true; if (/\d/.test(a)) width = +a.replace(/\D/g, ""); else width = +args[++i]; }
      else if (/^-\d+$/.test(a)) width = +a.slice(1);
      else files.push(a);
    }
    const out = readInput(files).split("\n").map((line) => {
      const parts = [];
      while (line.length > width) {
        let cut = width;
        if (spaces) { const sp = line.lastIndexOf(" ", width - 1); if (sp > 0) cut = sp + 1; }
        parts.push(line.slice(0, cut)); line = line.slice(cut);
      }
      parts.push(line); return parts.join("\n");
    }).join("\n");
    process.stdout.write(out);
  },

  // pdftotext file.pdf [out.txt | -]: the text of a PDF (installs pdf-parse on first use).
  pdftotext: async () => {
    const files = args.filter((a) => !a.startsWith("-") || a === "-");
    const src = files[0]; if (!src) fail("usage: pdftotext <file.pdf> [out.txt | -]", 2);
    const dest = files[1] ?? src.replace(/\.pdf$/i, "") + ".txt";
    const globalDir = process.env.NODE_PATH?.split(":")[0] ?? "";
    const pkg = path.join(globalDir, "pdf-parse");
    if (!fs.existsSync(pkg)) {
      process.stderr.write("pdftotext: installing pdf-parse (first use)…\n");
      const r = require("child_process").spawnSync(process.execPath, [process.env.SATURN_NPM_CLI, "i", "-g", "--silent", "pdf-parse"], { stdio: ["ignore", "ignore", "inherit"] });
      if (r.status !== 0 || !fs.existsSync(pkg)) fail("pdftotext: couldn't install pdf-parse (no internet?).", 1);
    }
    const mod = require(pkg);
    const buf = fs.readFileSync(src);
    let data;
    if (mod.PDFParse) { // pdf-parse 2.x
      const parser = new mod.PDFParse({ data: new Uint8Array(buf) });
      const r = await parser.getText(); await parser.destroy?.();
      data = { text: r.text, numpages: r.total ?? r.pages?.length ?? "?" };
    } else data = await (typeof mod === "function" ? mod : require(path.join(pkg, "lib", "pdf-parse.js")))(buf); // 1.x
    if (dest === "-") process.stdout.write(data.text + "\n");
    else { fs.writeFileSync(dest, data.text); process.stderr.write(`pdftotext: ${data.numpages} page(s) → ${dest}\n`); }
  },
};
tools["apt-get"] = tools.apt; tools.pkg = tools.apt;

const run = tools[name];
if (!run) fail(`saturn-tools: unknown tool ${name}`, 127);
Promise.resolve().then(run).catch((e) => fail(`${name}: ${e?.message ?? e}`));
