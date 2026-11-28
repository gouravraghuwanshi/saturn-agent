'use strict';
// Saturn's compatibility layer for npm packages on Android (process.platform is "android", libc is bionic).
//
// Loaded into every Node process with --require (NODE_OPTIONS). It:
//  - points `better-sqlite3` at a stand-in built on Node's own SQLite when its compiled part is missing;
//  - lets a package that asks for its "-android-arm64" build use the "-linux-arm64-musl" one instead
//    (the exec shim, libpiexec.so, runs Linux musl programs through Saturn's musl loader);
//  - while an install script runs for a package that ships a Linux musl build, tells that script it's on
//    musl Linux, so it picks that build instead of giving up on "android".
//
// Also a command: `node saturn-compat.cjs npm <args>` wraps npm for `pi install` (install without scripts,
// add musl builds and WebAssembly fallbacks, then run the scripts), and `node saturn-compat.cjs node-gyp`
// stands in for node-gyp, which can't compile on a phone.
const fs = require('fs');
const path = require('path');
const Module = require('module');

const HERE = __filename;
const ANDROID = process.platform === 'android' && process.arch === 'arm64'; // before any install-script spoofing
const SHIMS = path.join(path.dirname(HERE), 'saturn-compat');

function exists(p) { try { fs.accessSync(p); return true; } catch { return false; } }
function readJson(p) { try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch { return null; } }
function fromUrl(u) { try { return u.startsWith('file:') ? require('url').fileURLToPath(u) : u; } catch { return u; } }
function toUrl(p) { return require('url').pathToFileURL(p).href; }

/** Package root containing `file` (walks up to the nearest package.json with a name). */
function packageRoot(file) {
  let d = path.dirname(file);
  for (let i = 0; i < 8; i++) {
    const pj = readJson(path.join(d, 'package.json'));
    if (pj && pj.name) return d;
    const up = path.dirname(d); if (up === d) break; d = up;
  }
  return null;
}

// --- node-gyp, however it's reached (npm's script, node-gyp-build's direct `node .../node-gyp.js`) -----------
// Compiling needs Python, make and a C++ compiler, none of which Android has; skip it and let the install go on.
function skipCompile() {
  const pkg = process.env.npm_package_name || 'this package';
  process.stderr.write(`saturn: skipped compiling ${pkg} (Android has no compiler here). ` +
    `Saturn uses a fallback if it has one; otherwise the part that needs it won't load.\n`);
  process.exit(0);
}
if (ANDROID && process.argv[1] && /[\\/]node-gyp(\.js)?$/.test(process.argv[1]) &&
    /[\\/]node-gyp[\\/]bin[\\/]/.test(process.argv[1])) skipCompile();

// --- install scripts: "you're on musl Linux" for packages that ship a linux-arm64-musl build -------------
function linuxScript() {
  if (!ANDROID) return false;
  const ev = process.env.npm_lifecycle_event;
  if (!['preinstall', 'install', 'postinstall'].includes(ev)) return false;
  const pj = readJson(process.env.npm_package_json || '');
  const opt = Object.keys((pj && pj.optionalDependencies) || {});
  return opt.some(n => /linux-arm64-musl$/.test(n)) && !opt.some(n => /android-arm64/.test(n));
}
const SPOOF = linuxScript();
if (SPOOF) {
  try { Object.defineProperty(process, 'platform', { value: 'linux' }); } catch {}
}

// --- module resolution ---------------------------------------------------------------------------------
function resolveHook(specifier, context, next) {
  try {
    const r = next(specifier, context);
    if (specifier === 'better-sqlite3' && r && r.url) {
      const root = packageRoot(fromUrl(r.url));
      if (root && !exists(path.join(root, 'build/Release/better_sqlite3.node'))) {
        return { url: toUrl(path.join(SHIMS, 'better-sqlite3.cjs')), format: 'commonjs', shortCircuit: true };
      }
    }
    return r;
  } catch (e) {
    if (/android-arm64/.test(specifier)) {
      for (const alt of ['linux-arm64-musl', 'linuxmusl-arm64']) {
        try { return next(specifier.replace('android-arm64', alt), context); } catch {}
      }
    }
    throw e;
  }
}
if (typeof Module.registerHooks === 'function') {
  Module.registerHooks({
    resolve(specifier, context, next) {
      // detect-libc reads the system; on Android it would answer glibc. Musl is what Saturn can run.
      if (SPOOF && specifier === 'detect-libc') {
        return { url: toUrl(path.join(SHIMS, 'detect-libc.cjs')), format: 'commonjs', shortCircuit: true };
      }
      return resolveHook(specifier, context, next);
    },
  });
}

// --- commands ------------------------------------------------------------------------------------------

function main(args) {
  const cmd = args.shift();
  if (cmd === 'node-gyp') skipCompile();
  if (cmd === 'npm') return npm(args);
  if (cmd === 'fix') { fix(args[0] || process.cwd(), console.log); return; }
  process.stderr.write('usage: saturn-compat.cjs npm <args> | fix <dir> | node-gyp\n');
  process.exit(2);
}

function npmCli() { return process.env.SATURN_NPM_CLI; }
function run(argv, opts = {}) {
  const r = require('child_process').spawnSync(process.execPath, [npmCli(), ...argv], { stdio: 'inherit', ...opts });
  return r.status == null ? 1 : r.status;
}

/** `pi install` → npm install without scripts, add Linux musl builds + fallbacks, then run the scripts. */
function npm(args) {
  const i = args.indexOf('--prefix');
  if (args[0] !== 'install' || i < 0 || args.includes('--ignore-scripts')) process.exit(run(args));
  const root = args[i + 1];
  let code = run([...args, '--ignore-scripts']);
  if (code !== 0) process.exit(code);
  fix(root, (m) => process.stderr.write(m + '\n'));
  // Install scripts run for the whole folder, so one package whose script can't work on Android (say, it
  // downloads a desktop-only program) would make every later install fail. Warn instead; Saturn then checks
  // that pi still starts with the result and removes what breaks it.
  if (run(['rebuild', '--prefix', root, '--no-audit', '--no-fund']) !== 0)
    process.stderr.write('saturn: an install script above failed on Android; the package may be missing that part\n');
  process.exit(0);
}

/** Packages under `root`'s node_modules (top level and scoped, plus nested node_modules). */
function* packages(dir, depth = 0) {
  if (depth > 6) return;
  let names; try { names = fs.readdirSync(dir); } catch { return; }
  for (const n of names) {
    if (n.startsWith('.')) continue;
    const p = path.join(dir, n);
    if (n.startsWith('@')) { yield* packages(p, depth); continue; }
    const pj = readJson(path.join(p, 'package.json'));
    if (pj) yield { dir: p, pj };
    yield* packages(path.join(p, 'node_modules'), depth + 1);
  }
}

/** Adds what npm skipped on Android: musl builds of command-line programs, sharp's WebAssembly build. */
function fix(root, log) {
  if (!ANDROID) return;
  const nm = path.join(root, 'node_modules');
  const want = new Map();
  // sharp (0.33+) falls back to @img/sharp-wasm32 of the same version: libvips as WebAssembly.
  const sharpFix = (pj) => {
    if (/^0\.(3[3-9]|[4-9]\d)\./.test(pj.version || '') && !exists(path.join(nm, '@img/sharp-wasm32')))
      want.set('@img/sharp-wasm32', pj.version);
  };
  for (const { pj } of packages(nm)) {
    const opt = pj.optionalDependencies || {};
    const names = Object.keys(opt);
    if (pj.name === 'sharp') { sharpFix(pj); continue; }
    if (!names.length || pj.napi) continue; // napi-rs addons: a musl .node can't load into Android's Node
    if (names.some(n => /android-arm64/.test(n) && exists(path.join(nm, n)))) continue;
    const musl = names.find(n => /(-linux-arm64-musl|-linuxmusl-arm64)$/.test(n) && !/libvips|sharp/.test(n));
    if (musl && !exists(path.join(nm, musl))) want.set(musl, opt[musl]);
  }
  addons(nm, log);
  if (!want.size) return;
  const specs = [...want].map(([n, v]) => `${n}@${v}`);
  log(`saturn: adding Linux/WebAssembly builds npm skips on Android: ${specs.join(', ')}`);
  run(['install', '--prefix', root, '--no-save', '--force', '--ignore-scripts', '--no-audit', '--no-fund', '--legacy-peer-deps', ...specs]);
}

/** Android builds of native add-ons Saturn ships (runtime/build-addons.sh), copied where each package looks. */
const ADDONS = [
  // node-pty 1.1.x loads build/Release/pty.node, else prebuilds/<platform>-<arch>/pty.node.
  { name: 'node-pty', version: /^1\.1\./, file: 'node-pty@1.1.node', dest: 'prebuilds/android-arm64/pty.node',
    has: ['build/Release/pty.node', 'prebuilds/android-arm64/pty.node'] },
];
function addons(nm, log) {
  for (const { dir, pj } of packages(nm)) {
    for (const a of ADDONS) {
      if (pj.name !== a.name || !a.version.test(pj.version || '')) continue;
      if (a.has.some(h => exists(path.join(dir, h)))) continue;
      const src = path.join(SHIMS, 'addons', a.file);
      if (!exists(src)) continue;
      fs.mkdirSync(path.dirname(path.join(dir, a.dest)), { recursive: true });
      fs.copyFileSync(src, path.join(dir, a.dest));
      log(`saturn: added the Android build of ${a.name} ${pj.version}`);
    }
  }
}

// Run as a command. NODE_OPTIONS already loaded this file with --require, and Node then reuses that copy for
// the main script without running it again, so recognise the command by the script path instead.
if (process.argv[1] && path.resolve(process.argv[1]) === HERE && !global.__saturnCompatMain) {
  global.__saturnCompatMain = true;
  main(process.argv.slice(2));
}
