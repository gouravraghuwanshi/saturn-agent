// node probe.mjs <package dir> : load a Pi package's extensions with Pi's own loader, print JSON
import fs from "node:fs"; import path from "node:path";
const dir = path.resolve(process.argv[2]);
const L = await import(process.env.PI_LOADER);
const pj = JSON.parse(fs.readFileSync(path.join(dir, "package.json"), "utf8"));
let entries = (pj.pi?.extensions ?? []).map(e => path.resolve(dir, e));
const expand = p => { try { if (fs.statSync(p).isDirectory()) {
    for (const i of ["index.ts", "index.js"]) if (fs.existsSync(path.join(p, i))) return [path.join(p, i)];
    return fs.readdirSync(p).filter(f => /\.(ts|js)$/.test(f)).map(f => path.join(p, f)); } return [p]; } catch { return []; } };
if (!entries.length && !pj.pi) for (const i of ["index.ts", "index.js", "dist/index.js"]) if (fs.existsSync(path.join(dir, i))) { entries = [path.join(dir, i)]; break; }
entries = entries.flatMap(expand);
const out = { entries: entries.length, skillsOnly: !!pj.pi && !pj.pi.extensions };
setTimeout(() => { console.log(JSON.stringify({ ...out, error: "timeout loading" })); process.exit(0); }, 60000).unref();
try { const r = await L.loadExtensions(entries, process.cwd());
  out.loaded = r.extensions.length; out.errors = r.errors.map(e => String(e.error?.message ?? e.error).slice(0, 400));
  out.tools = r.extensions.flatMap(x => [...(x.tools?.keys?.() ?? [])]).slice(0, 12);
} catch (e) { out.error = String(e?.stack ?? e).slice(0, 400); }
console.log(JSON.stringify(out)); process.exit(0);
