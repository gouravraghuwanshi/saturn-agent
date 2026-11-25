// pimobile-app — built-in extension of the Saturn Agent Android app.
// Bridges pi built-ins that RPC mode does not expose (login/logout with pi's
// own provider flows, session-tree navigation, reload) to the app's native UI.
// The app invokes these hidden commands via RPC `prompt`; results go back as
// notify messages prefixed with "pimobile:" + JSON, which the app consumes.
// Installed into ~/.pi/agent/extensions/ by the app on every start.

// ctx.modelRegistry wraps pi's model runtime; provider listing, login and
// logout live on `.runtime` (the same calls pi's own /login dialog makes).
const runtimeOf = (ctx) => ctx.modelRegistry.runtime ?? ctx.modelRegistry;

// Sign in with ChatGPT identifies the installation by a stable UUID. pi's own
// /login passes settingsManager.getOrCreateDeviceId(); extensions can't reach
// the settings manager, so read/create the same `deviceId` field ourselves.
import { readFileSync, writeFileSync, mkdirSync, statSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { join } from "node:path";
import { createRequire, syncBuiltinESMExports } from "node:module";

// ---- Child processes with a stripped environment (e.g. pi-subagents' sandboxed workflow runner, started
// with `env: {}`). On a desktop that's fine; here node and the other binaries only start when they can find
// their shared libraries (LD_LIBRARY_PATH) and OpenSSL's config, so a bare environment made the child die at
// once and the parent's first write failed with EPIPE. Add back just those variables when they're missing;
// everything else in the caller's environment is left exactly as it asked.
const ESSENTIAL_ENV = ["LD_LIBRARY_PATH", "LD_PRELOAD", "OPENSSL_CONF", "SSL_CERT_FILE"];
function patchChildEnv() {
  const cp = createRequire(import.meta.url)("node:child_process");
  if (cp.__saturnEnv) return;
  const fill = (o) => {
    if (!o || typeof o !== "object" || !o.env || typeof o.env !== "object") return o;
    const add = {};
    for (const k of ESSENTIAL_ENV) if (process.env[k] && o.env[k] === undefined) add[k] = process.env[k];
    return Object.keys(add).length ? { ...o, env: { ...o.env, ...add } } : o;
  };
  for (const name of ["spawn", "fork", "execFile", "exec", "spawnSync", "execFileSync", "execSync"]) {
    const orig = cp[name];
    if (typeof orig !== "function") continue;
    cp[name] = function (...args) {
      // The options object is the first plain object after the command (and its args array).
      const i = args.findIndex((a, n) => n > 0 && a && typeof a === "object" && !Array.isArray(a));
      if (i > 0) args[i] = fill(args[i]);
      return orig.apply(this, args);
    };
  }
  cp.__saturnEnv = true;
  // Modules that already did `import { fork } from "node:child_process"` see the patched functions too.
  syncBuiltinESMExports();
}
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function deviceId() {
  const dir = process.env.PI_CODING_AGENT_DIR ?? join(process.env.HOME ?? "", ".pi", "agent");
  const file = join(dir, "settings.json");
  let s = {};
  try { s = JSON.parse(readFileSync(file, "utf8")); } catch {}
  if (typeof s.deviceId === "string" && UUID_RE.test(s.deviceId)) return s.deviceId;
  s.deviceId = randomUUID();
  mkdirSync(dir, { recursive: true });
  writeFileSync(file, JSON.stringify(s, null, 2));
  return s.deviceId;
}

const emit = (ctx, payload) => ctx.ui.notify("pimobile:" + JSON.stringify(payload), "info");
const parse = (args) => { try { return JSON.parse(args || "{}"); } catch { return {}; } };

export default function (pi) {
  if (!process.env.PIMOBILE_TASK_ID && !process.env.PIMOBILE_CONTROL) return; // not inside the Saturn app
  patchChildEnv();

  // ---- Android paths for the core tools (read, write, edit, bash, and grep/find/ls).
  // Models assume a desktop Linux: /tmp, /home/user, /usr/bin/env, sudo. None of
  // those exist (or are writable) here, so calls are quietly pointed at the real places.
  const HOME = process.env.HOME ?? "";
  const TMP = process.env.TMPDIR ?? (HOME ? HOME + "/.tmp" : "");
  const PREFIX = process.env.PREFIX ?? "";
  const exists = (p) => { try { statSync(p); return true; } catch { return false; } };
  const fixPath = (p) => {
    if (typeof p !== "string" || !p) return p;
    if (TMP && /^\/(var\/)?tmp(\/|$)/.test(p)) return TMP + p.replace(/^\/(var\/)?tmp/, "");
    if (HOME && p === "~") return HOME;
    if (HOME && p.startsWith("~/")) return HOME + p.slice(1);
    if (HOME && !exists(p)) {
      const m = p.match(/^\/(?:home\/[^/]+|root)(\/.*)?$/);
      if (m) return HOME + (m[1] ?? "");
    }
    if (PREFIX && /^\/usr\//.test(p) && !exists(p) && exists(PREFIX + p.slice(4))) return PREFIX + p.slice(4);
    return p;
  };
  const fixCommand = (c) => {
    if (typeof c !== "string") return c;
    let out = c;
    // /tmp → the real temp folder (a literal path, so it works inside quotes too).
    if (TMP) out = out.replace(/(^|[\s'"=:>(<|;&,])\/(?:var\/)?tmp(?=\/|[\s'";|&)]|$)/g, (_m, pre) => pre + TMP);
    // Absolute paths to standard binaries: let PATH find them.
    out = out.replace(/(^|[\s;&|(`]|\$\()(?:\/usr)?\/bin\/(env|bash|sh|node|npm|npx|git|curl|python3?|cat|ls|rm|mkdir|cp|mv|grep|sed|awk|find|chmod|echo|tar|gzip|unzip|wget|which)\b/g,
      (_m, pre, name) => pre + name);
    // No root on a phone, and everything here is already yours.
    out = out.replace(/(^|[;&|]\s*|\n\s*)sudo\s+(-\S+\s+)*/g, (_m, pre) => pre);
    return out;
  };
  pi.on("tool_call", async (event) => {
    const input = event.input;
    if (!input || typeof input !== "object") return;
    for (const k of ["path", "file_path", "filePath", "cwd", "dir", "directory"]) if (typeof input[k] === "string") input[k] = fixPath(input[k]);
    if (event.toolName === "bash" && typeof input.command === "string") input.command = fixCommand(input.command);
  });
  // When a system path still fails, say where to go instead, so the next try works.
  pi.on("tool_result", async (event) => {
    if (!event.isError) return;
    const text = (event.content ?? []).map((c) => c?.text ?? "").join("\n");
    if (!/read-only file system|permission denied|no such file or directory|EROFS|EACCES|ENOENT/i.test(text)) return;
    const hint = `\n\n[Saturn: this is Android, not a desktop Linux. Writable places: the project folder, ` +
      `$HOME (${HOME}), $TMPDIR (${TMP}) for temporary files. System folders like /, /etc, /usr, /bin are read-only; ` +
      `tools from $PREFIX (${PREFIX}) are on PATH.]`;
    return { content: [...(event.content ?? []), { type: "text", text: hint }] };
  });

  let loginAbort = null;

  // Each task saves artifacts in its project's folder; say where, every run.
  if (process.env.SATURN_ARTIFACTS) {
    pi.on("before_agent_start", async (event) => {
      const o = event.systemPromptOptions;
      if (o) {
        o.sections ??= {};
        o.sections["saturn-artifacts"] =
          `Project: ${process.env.SATURN_PROJECT ?? "general"}\nArtifacts folder: ${process.env.SATURN_ARTIFACTS}`;
      }
    });
  }

  pi.registerCommand("pimobile-providers", {
    description: "(Saturn app) list login providers",
    handler: async (_args, ctx) => {
      try {
        const reg = ctx.modelRegistry;
        const providers = (runtimeOf(ctx).getProviders?.() ?? []).map((p) => {
          let status = {};
          try { status = reg.getProviderAuthStatus?.(p.id) ?? {}; } catch {}
          return {
            id: p.id,
            name: p.name ?? p.id,
            oauth: p.auth?.oauth ? {
              subscription: p.auth.oauth.isSubscription === true,
              label: p.auth.oauth.loginLabel ?? p.auth.oauth.name ?? null,
            } : null,
            apiKey: !!p.auth?.apiKey,
            configured: status.configured === true,
            source: status.label ?? status.source ?? null,
          };
        }).filter((p) => p.oauth || p.apiKey);
        emit(ctx, { type: "providers", providers });
      } catch (e) {
        emit(ctx, { type: "error", scope: "providers", message: String(e?.message ?? e) });
      }
    },
  });

  pi.registerCommand("pimobile-login", {
    description: "(Saturn app) log in with pi's provider flow",
    handler: async (args, ctx) => {
      const { provider, type } = parse(args);
      loginAbort?.abort();
      const abort = (loginAbort = new AbortController());
      const interaction = {
        signal: abort.signal,
        async prompt(p) {
          const signal = p.signal;
          const onAbort = () => emit(ctx, { type: "prompt_cancelled" });
          signal?.addEventListener("abort", onAbort, { once: true });
          try {
            if (p.type === "select") {
              const labels = p.options.map((o) => o.label);
              const pick = await ctx.ui.select(p.message, labels, { signal });
              if (pick === undefined) throw new Error("cancelled");
              return p.options[labels.indexOf(pick)].id;
            }
            emit(ctx, { type: "prompt_kind", kind: p.type, message: p.message ?? "" });
            const value = await ctx.ui.input(p.message ?? "Paste the code or redirect URL", p.placeholder ?? "", { signal });
            if (value === undefined) throw new Error(signal?.aborted ? "aborted" : "cancelled");
            return value;
          } finally {
            signal?.removeEventListener("abort", onAbort);
          }
        },
        notify(e) { emit(ctx, { ...e, type: "login_" + e.type }); }, // type last: e.type must not overwrite it
      };
      try {
        emit(ctx, { type: "login_started", provider, authType: type });
        await runtimeOf(ctx).login(provider, type === "oauth" ? "oauth" : "api_key", interaction, { getDeviceId: deviceId });
        emit(ctx, { type: "login_done", provider });
      } catch (e) {
        emit(ctx, { type: "login_failed", provider, message: String(e?.message ?? e) });
      } finally {
        if (loginAbort === abort) loginAbort = null;
      }
    },
  });

  pi.registerCommand("pimobile-login-cancel", {
    description: "(Saturn app) cancel the running login",
    handler: async () => { loginAbort?.abort(); },
  });

  pi.registerCommand("pimobile-logout", {
    description: "(Saturn app) log out of a provider",
    handler: async (args, ctx) => {
      const { provider } = parse(args);
      try {
        await runtimeOf(ctx).logout(provider);
        emit(ctx, { type: "logout_done", provider });
      } catch (e) {
        emit(ctx, { type: "error", scope: "logout", message: String(e?.message ?? e) });
      }
    },
  });

  pi.registerCommand("pimobile-tree", {
    description: "(Saturn app) move to another point of the session tree",
    handler: async (args, ctx) => {
      const { entryId, summarize } = parse(args);
      try {
        await ctx.waitForIdle();
        const result = await ctx.navigateTree(entryId, { summarize: !!summarize });
        emit(ctx, { type: "tree_done", entryId, result: result ?? null });
      } catch (e) {
        emit(ctx, { type: "error", scope: "tree", message: String(e?.message ?? e) });
      }
    },
  });

  pi.registerCommand("pimobile-reload", {
    description: "(Saturn app) reload extensions, skills, prompts and context files",
    handler: async (_args, ctx) => {
      const out = { ui: ctx.ui }; // ctx goes stale after reload(); its ui getter would throw
      try { await ctx.reload(); emit(out, { type: "reloaded" }); }
      catch (e) { emit(out, { type: "error", scope: "reload", message: String(e?.message ?? e) }); }
    },
  });
}
