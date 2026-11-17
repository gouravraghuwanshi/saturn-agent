// pimobile-app — built-in extension of the Phi Android app.
// Bridges pi built-ins that RPC mode does not expose (login/logout with pi's
// own provider flows, session-tree navigation, reload) to the app's native UI.
// The app invokes these hidden commands via RPC `prompt`; results go back as
// notify messages prefixed with "pimobile:" + JSON, which the app consumes.
// Installed into ~/.pi/agent/extensions/ by the app on every start.

// ctx.modelRegistry wraps pi's model runtime; provider listing, login and
// logout live on `.runtime` (the same calls pi's own /login dialog makes).
const runtimeOf = (ctx) => ctx.modelRegistry.runtime ?? ctx.modelRegistry;

const emit = (ctx, payload) => ctx.ui.notify("pimobile:" + JSON.stringify(payload), "info");
const parse = (args) => { try { return JSON.parse(args || "{}"); } catch { return {}; } };

export default function (pi) {
  if (!process.env.PIMOBILE_TASK_ID && !process.env.PIMOBILE_CONTROL) return; // not inside the Phi app

  let loginAbort = null;

  pi.registerCommand("pimobile-providers", {
    description: "(Phi app) list login providers",
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
    description: "(Phi app) log in with pi's provider flow",
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
        notify(e) { emit(ctx, { type: "login_" + e.type, ...e }); },
      };
      try {
        emit(ctx, { type: "login_started", provider, authType: type });
        await runtimeOf(ctx).login(provider, type === "oauth" ? "oauth" : "api_key", interaction);
        emit(ctx, { type: "login_done", provider });
      } catch (e) {
        emit(ctx, { type: "login_failed", provider, message: String(e?.message ?? e) });
      } finally {
        if (loginAbort === abort) loginAbort = null;
      }
    },
  });

  pi.registerCommand("pimobile-login-cancel", {
    description: "(Phi app) cancel the running login",
    handler: async () => { loginAbort?.abort(); },
  });

  pi.registerCommand("pimobile-logout", {
    description: "(Phi app) log out of a provider",
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
    description: "(Phi app) move to another point of the session tree",
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
    description: "(Phi app) reload extensions, skills, prompts and context files",
    handler: async (_args, ctx) => {
      try { await ctx.reload(); emit(ctx, { type: "reloaded" }); }
      catch (e) { emit(ctx, { type: "error", scope: "reload", message: String(e?.message ?? e) }); }
    },
  });
}
