// pimobile-browser — built-in extension of the pi Android app.
// Gives pi a real, visible browser: the task's in-app WebView, driven over the
// Chrome DevTools Protocol through the app's token-protected local bridge.
// Installed by the app into ~/.pi/agent/extensions/ (overwritten on each start).
import { Type } from "typebox";

const BASE = process.env.PIMOBILE_BROWSER_URL;   // http://127.0.0.1:<port>/<token>
const TASK = process.env.PIMOBILE_TASK_ID;

let conn = null; // { ws, nextId, pending, waiters }

async function connect() {
  if (conn && conn.ws.readyState === 1) return conn;
  const res = await fetch(`${BASE}/open?task=${encodeURIComponent(TASK)}`, { signal: AbortSignal.timeout(20000) });
  const body = await res.json();
  if (!res.ok) throw new Error(body.error || `bridge HTTP ${res.status}`);
  const url = new URL(BASE);
  const ws = new WebSocket(`ws://${url.host}${body.ws}`);
  await new Promise((ok, fail) => {
    const t = setTimeout(() => { ws.close(); fail(new Error("timed out connecting to the in-app browser")); }, 15000);
    ws.onopen = () => { clearTimeout(t); ok(); };
    ws.onerror = () => { clearTimeout(t); fail(new Error("cannot connect to the in-app browser")); };
  });
  const c = { ws, nextId: 1, pending: new Map(), waiters: [] };
  ws.onmessage = (ev) => {
    const msg = JSON.parse(typeof ev.data === "string" ? ev.data : Buffer.from(ev.data).toString());
    if (msg.id && c.pending.has(msg.id)) {
      const { ok, fail } = c.pending.get(msg.id); c.pending.delete(msg.id);
      msg.error ? fail(new Error(msg.error.message)) : ok(msg.result);
    } else if (msg.method) {
      c.waiters = c.waiters.filter((w) => !(w.methods.includes(msg.method) && (w.ok(msg.params ?? {}), true)));
    }
  };
  ws.onclose = () => { for (const { fail } of c.pending.values()) fail(new Error("browser connection closed")); conn = null; };
  conn = c;
  await send("Page.enable"); await send("Runtime.enable");
  return c;
}

function send(method, params = {}) {
  const c = conn;
  return new Promise((ok, fail) => {
    const id = c.nextId++;
    c.pending.set(id, { ok, fail });
    c.ws.send(JSON.stringify({ id, method, params }));
    setTimeout(() => { if (c.pending.delete(id)) fail(new Error(`${method} timed out`)); }, 30000);
  });
}

// Resolves with the event params when any of `methods` fires, or null after `ms`.
function waitFor(methods, ms) {
  return new Promise((ok) => {
    const w = { methods: [].concat(methods), ok };
    conn.waiters.push(w);
    setTimeout(() => { conn.waiters = conn.waiters.filter((x) => x !== w); ok(null); }, ms);
  });
}

async function evaluate(expression) {
  const r = await send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
  return r.result.value;
}

// Numbers interactive elements (data-pi-ref) and returns a compact page outline.
const SNAPSHOT = `(() => {
  const out = [], seen = new Set();
  const sel = 'a[href],button,input,textarea,select,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[contenteditable=true],[onclick]';
  let n = 0;
  for (const el of document.querySelectorAll(sel)) {
    const r = el.getBoundingClientRect(), cs = getComputedStyle(el);
    if (r.width < 2 || r.height < 2 || cs.visibility === 'hidden' || cs.display === 'none' || seen.has(el)) continue;
    seen.add(el); el.setAttribute('data-pi-ref', ++n);
    const tag = el.tagName.toLowerCase(), type = el.getAttribute('type');
    const label = (el.innerText || el.value || el.getAttribute('aria-label') || el.getAttribute('placeholder') || el.getAttribute('title') || el.getAttribute('alt') || '').trim().replace(/\\s+/g, ' ').slice(0, 80);
    let line = '[' + n + '] ' + tag + (type ? '[' + type + ']' : '') + (label ? ' "' + label + '"' : '');
    if (tag === 'a') line += ' -> ' + (el.getAttribute('href') || '').slice(0, 80);
    if (el.disabled) line += ' (disabled)';
    out.push(line);
    if (out.length >= 150) break;
  }
  const heads = [...document.querySelectorAll('h1,h2,h3')].slice(0, 20)
    .map(h => h.tagName.toLowerCase() + ': ' + h.innerText.trim().replace(/\\s+/g, ' ').slice(0, 100)).filter(h => !h.endsWith(': '));
  const text = (document.body ? document.body.innerText : '').replace(/\\n{3,}/g, '\\n\\n').slice(0, 5000);
  return 'URL: ' + location.href + '\\nTitle: ' + document.title
    + '\\n\\nHeadings:\\n' + (heads.join('\\n') || '(no h1-h3 headings on this page)')
    + '\\n\\nInteractive elements:\\n' + (out.join('\\n') || '(none)') + '\\n\\nText:\\n' + text;
})()`;

async function locate(params) {
  const q = params.ref != null ? `[data-pi-ref="${params.ref}"]` : params.selector;
  if (!q) throw new Error("give ref (from browser_snapshot) or selector");
  // Scroll into view, then wait two frames: WebView applies scrolling
  // asynchronously, and input hit-testing lags the DOM until it does.
  const box = await evaluate(`(async () => { const el = document.querySelector(${JSON.stringify(q)});
    if (!el) return null; el.scrollIntoView({block: 'center', inline: 'center'});
    await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
    const r = el.getBoundingClientRect(), x = r.left + r.width / 2, y = r.top + r.height / 2;
    const hit = document.elementFromPoint(x, y);
    const link = el.closest('a[href]');
    return { x, y, covered: !!hit && hit !== el && !el.contains(hit) && !hit.contains(el),
             link: link && link.target !== '_blank' ? link.href : null }; })()`);
  if (!box) throw new Error(`no element matches ${q} — run browser_snapshot again`);
  return { q, ...box };
}

const text = (t) => ({ content: [{ type: "text", text: t }], details: {} });

async function settle(ms = 400) { await new Promise((r) => setTimeout(r, ms)); }

// Runs an action that may navigate. Listens *before* acting; if a navigation
// starts, waits for the new page to load (slow networks!) before returning.
async function maybeNavigating(action) {
  // These fire as soon as a navigation is requested — long before a slow
  // server answers (frameStartedLoading only comes with the response).
  const started = waitFor(["Page.frameRequestedNavigation", "Page.frameStartedNavigating", "Page.frameScheduledNavigation"], 1500);
  await action();
  if (await started) {
    await waitFor("Page.loadEventFired", 20000);
    await settle(300);
    return true;
  }
  await settle(300);
  return false;
}

export default function (pi) {
  if (!BASE || !TASK) return; // not running inside the pi Android app

  const tool = (def) => pi.registerTool({ ...def, async execute(id, params, signal) {
    await connect();
    return def.run(params, signal);
  } });

  tool({
    name: "browser_navigate", label: "Browser: open URL",
    description: "Open a URL in the in-app browser the user can watch. Returns the page outline.",
    promptSnippet: "Open a web page in the user's visible in-app browser",
    promptGuidelines: [
      "Use browser_navigate, then browser_snapshot to see numbered [ref] elements; act with browser_click / browser_type using those refs.",
      "Use browser_screenshot when layout or visuals matter; the user can see the same browser live.",
    ],
    parameters: Type.Object({ url: Type.String({ description: "Absolute URL, e.g. https://example.com" }) }),
    async run({ url }) {
      const loaded = waitFor("Page.loadEventFired", 20000);
      const r = await send("Page.navigate", { url: /^[a-z]+:/i.test(url) ? url : `https://${url}` });
      if (r.errorText) throw new Error(r.errorText);
      await loaded; await settle();
      return text(await evaluate(SNAPSHOT));
    },
  });

  tool({
    name: "browser_snapshot", label: "Browser: read page",
    description: "Read the current page: URL, title, numbered interactive elements ([ref]) and visible text.",
    parameters: Type.Object({}),
    async run() { return text(await evaluate(SNAPSHOT)); },
  });

  tool({
    name: "browser_click", label: "Browser: click",
    description: "Click an element by its [ref] from browser_snapshot, or by CSS selector.",
    parameters: Type.Object({
      ref: Type.Optional(Type.Integer({ description: "Element number from browser_snapshot" })),
      selector: Type.Optional(Type.String({ description: "CSS selector (alternative to ref)" })),
    }),
    async run(params) {
      const { q, x, y, covered, link } = await locate(params);
      let navigated = await maybeNavigating(async () => {
        for (const type of ["mouseMoved", "mousePressed", "mouseReleased"]) {
          await send("Input.dispatchMouseEvent", { type, x, y, button: type === "mouseMoved" ? "none" : "left", clickCount: type === "mouseMoved" ? 0 : 1 });
        }
      });
      // Safety net: a real click on a link must navigate; if it didn't, activate it directly.
      if (!navigated && link) {
        navigated = await maybeNavigating(() => evaluate(`document.querySelector(${JSON.stringify(q)}).closest('a[href]').click()`));
      }
      if (navigated) return text(`Clicked ${q}; the page navigated.\n\n` + await evaluate(SNAPSHOT));
      if (covered) return text(`Clicked ${q}, but another element covers it (e.g. a banner or dialog). Run browser_snapshot and close the overlay first.`);
      return text(`Clicked ${q}. No navigation; now at ${await evaluate("location.href")}. Run browser_snapshot to see changes.`);
    },
  });

  tool({
    name: "browser_type", label: "Browser: type",
    description: "Type text into an input by [ref] or CSS selector. Optionally press Enter afterwards.",
    parameters: Type.Object({
      text: Type.String(),
      ref: Type.Optional(Type.Integer()),
      selector: Type.Optional(Type.String()),
      clear: Type.Optional(Type.Boolean({ description: "Clear the field first (default true)" })),
      submit: Type.Optional(Type.Boolean({ description: "Press Enter after typing" })),
    }),
    async run(params) {
      const { q } = await locate(params);
      await evaluate(`(() => { const el = document.querySelector(${JSON.stringify(q)}); el.focus();
        if (${params.clear !== false} && 'value' in el) { el.value = ''; el.dispatchEvent(new Event('input', {bubbles: true})); } })()`);
      await send("Input.insertText", { text: params.text });
      if (params.submit && await maybeNavigating(() => pressKey("Enter"))) {
        return text(`Typed into ${q}, pressed Enter; the page navigated.\n\n` + await evaluate(SNAPSHOT));
      }
      await settle(200);
      return text(`Typed into ${q}${params.submit ? " and pressed Enter" : ""}.`);
    },
  });

  const KEYS = { Enter: 13, Tab: 9, Escape: 27, Backspace: 8, ArrowDown: 40, ArrowUp: 38, ArrowLeft: 37, ArrowRight: 39, PageDown: 34, PageUp: 33 };
  async function pressKey(key) {
    const code = KEYS[key] ?? 0;
    const base = { key, code: key, windowsVirtualKeyCode: code, nativeVirtualKeyCode: code };
    await send("Input.dispatchKeyEvent", { type: "keyDown", ...base, text: key === "Enter" ? "\r" : undefined });
    await send("Input.dispatchKeyEvent", { type: "keyUp", ...base });
  }

  tool({
    name: "browser_press", label: "Browser: key",
    description: "Press a key in the browser: Enter, Tab, Escape, Backspace, ArrowUp/Down/Left/Right, PageUp/PageDown.",
    parameters: Type.Object({ key: Type.String() }),
    async run({ key }) {
      if (await maybeNavigating(() => pressKey(key))) return text(`Pressed ${key}; the page navigated.\n\n` + await evaluate(SNAPSHOT));
      return text(`Pressed ${key}.`);
    },
  });

  tool({
    name: "browser_scroll", label: "Browser: scroll",
    description: "Scroll the page up or down by about one screen.",
    parameters: Type.Object({ direction: Type.Union([Type.Literal("down"), Type.Literal("up")]) }),
    async run({ direction }) {
      const y = await evaluate(`window.scrollBy(0, ${direction === "up" ? -1 : 1} * innerHeight * 0.85), Math.round(scrollY)`);
      return text(`Scrolled ${direction}; scrollY=${y}.`);
    },
  });

  tool({
    name: "browser_back", label: "Browser: back",
    description: "Go back to the previous page.",
    parameters: Type.Object({}),
    async run() { await maybeNavigating(() => evaluate("history.back()")); return text(await evaluate(SNAPSHOT)); },
  });

  tool({
    name: "browser_screenshot", label: "Browser: screenshot",
    description: "Capture what the browser currently shows, as an image.",
    parameters: Type.Object({}),
    async run() {
      const { data } = await send("Page.captureScreenshot", { format: "jpeg", quality: 60 });
      return { content: [{ type: "image", data, mimeType: "image/jpeg" }], details: {} };
    },
  });

  tool({
    name: "browser_eval", label: "Browser: run JavaScript",
    description: "Evaluate a JavaScript expression in the page and return its JSON-serializable result.",
    parameters: Type.Object({ expression: Type.String() }),
    async run({ expression }) {
      const v = await evaluate(expression);
      if (v === undefined || v === null) return text(`(${v}) — the expression returned nothing; the element or value may not exist on this page. Use browser_snapshot to see what the page contains.`);
      return text(typeof v === "string" ? v : JSON.stringify(v, null, 2));
    },
  });
}
