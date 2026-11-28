# Testing Saturn

How to check a build before a release. Part 1 is automatic and runs on the PC, Part 2 is a checklist you do on the
phone (or hand to an agent with `adb`), and Part 3 is prompts to paste into a Saturn chat so the agent tests its own
phone tools.

Each line is **what to do → what should happen**. Write ✅ or ❌ next to each when you go through it, and note what
you saw for every ❌.

## Before you start

- Build and install the release: `./gradlew :app:assembleRelease` then
  `adb install -r app/build/outputs/apk/release/app-release.apk`.
- Keep the phone unlocked and Saturn on screen while anything downloads. Android cuts network for apps that aren't
  on screen, and that looks like a Saturn bug when it isn't.

## Part 1 — Automatic (PC)

| Check | Command | Pass |
|---|---|---|
| App builds | `./gradlew :app:assembleRelease` | no errors |
| Unit tests | `./gradlew :app:testDebugUnitTest` | all pass |
| Extensions still install and load | build + install the debug test app: `./gradlew :app:assembleDebug -PappId=io.github.pimobile.spike` and `adb install -r app/build/outputs/apk/debug/app-debug.apk`, open it once, then `runtime/test-extensions/test-extensions.sh pi-lens openwiki pi-subagents pi-web-access context-mode @onkernel/browser-loop` | every line `WORKS` |
| After a Pi update | `runtime/test-extensions/test-extensions.sh --top 50` (takes about an hour) | results match `compat/extensions.json`; update that file if they changed |

The extension test installs each package with the real `pi install` (through Saturn's npm wrapper) and loads it with
Pi's own extension loader, inside the test app, so it sees exactly what a user's chat would.

## Part 2 — On the phone

### Install and update
- Install over the previous release → opens without "app not installed" and old chats are still there.
- Settings → Check for updates (on an older version) → offers the new version, downloads, installs.
- First start on a fresh install → onboarding, then the runtime setup finishes in under a minute.

### Sign-in and models
- Sign in with one provider (OpenRouter key, or ChatGPT / Claude / Copilot sign-in) → model list fills in.
- In a chat, **tap the little Saturn** in the header → model + thinking popup opens.
  - Pick another model → the header shows it; the next reply uses it.
  - Change thinking level → it sticks for that chat after leaving and coming back.
  - Turn on "use for every chat in this project" → other chats in the project follow it.
- On Home, **hold a project chip** → the same popup for the whole project.
- One-time hints ("tap Saturn for model & thinking", "hold for model & thinking") show once and disappear after you
  use the gesture.
- Star a model → it's under FAVOURITES at the top; the star filter shows only favourites.

### Chats
- Send a message → reply streams; the stop button stops it.
- Two chats at once → both run; switching between them doesn't mix them up.
- Leave a chat idle for 5+ minutes, come back, send → it answers (wakes in about a second).
- Ask it to make a file ("write a small HTML page about Saturn") → it appears in Artifacts, opens, shares.
- Attach a photo and a file (composer +, and share-to-Saturn from another app) → the agent can read them.
- Voice input (mic in composer) → text appears.
- Slash commands: type `/` → list opens; `/model`, `/thinking` work.
- Read aloud on a reply → phone voice reads it; Settings → Voice changes speed.

### Extensions
- Extensions → **✓ works on Saturn** tab → list of tested extensions, most used first, each with a green ✓ works.
- Open one → "✓ Tested on Saturn …" line; for gentle-pi a grey note under it.
- Install **pi-lens** → log shows `saturn: adding Linux/WebAssembly builds … ast-grep … musl`, then ✓ Installed.
  In a chat: "use ast_grep_search to find console.log calls in this project" → it runs and returns matches.
- Install **openwiki** → installs with no errors (uses Saturn's SQLite stand-in).
- Remove one → ✓ Removed; chats keep working.
- glimpseui shows "known issue" (desktop only) and isn't in the works tab.

### Terminal
- Open the terminal → bash prompt. `node -v`, `git --version`, `rg --version`, `jq --version` all print versions.
- `npm i -g cowsay && cowsay hi` → works (scripts run from app storage).

### Background, notifications, widgets
- Start a long task, leave the app → notification shows progress; reply from the notification works.
- Home-screen widget and quick-settings tile open Saturn.
- `saturn-schedule` routine (Part 3) fires while the app is closed.

### Other
- GitHub sign-in (Settings) → `gh auth status` in a chat works.
- Backup / export a chat → file is created and opens.
- Usage screen → shows numbers, no crash.
- Rotate the phone / use split screen → layout adapts, nothing cut off.

### Light when idle
- Open one chat, wait 5 minutes, check memory (Settings → Apps → Saturn → Memory, or
  `adb shell dumpsys meminfo <package>`) → roughly 260 MB or less with one chat.

## Part 3 — Paste into a Saturn chat

Saturn's agent can test its own phone tools. Paste these one at a time and check the result:

```
Turn the torch on for 2 seconds, then off.
```
→ torch blinks.

```
Show a toast saying "Saturn test", then send me a notification titled "Test" with the text "It works".
```
→ toast and notification appear.

```
List my installed apps and open the Calculator.
```
→ list printed, Calculator opens (or Android asks to confirm).

```
Read aloud: "This is Saturn's voice test."
```
→ phone voice speaks.

```
Run jq to pretty-print {"a":1,"b":[2,3]} and tell me the Node, git and ripgrep versions.
```
→ formatted JSON and three versions.

```
Schedule a routine 2 minutes from now that sends me a notification saying "routine ran".
```
→ close Saturn; the notification arrives about 2 minutes later.

```
Install the pi-lens extension, then use ast_grep_search to find every function call named log in this project.
```
→ install succeeds and the search returns results.

## Reporting a problem

For each ❌: what you did, what happened, a screenshot, and (for installs) the install log from the extension sheet.
`adb logcat -d | grep -i saturn` right after a crash helps a lot.
