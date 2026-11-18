# Getting Saturn onto Google Play

Everything here is ready to copy-paste. Steps you have to do yourself are marked **you**.

## 0. Things to know before you pay the $25
- **New personal accounts must run a closed test first:** at least **12 testers opted in for 14 days in a row** before you can apply for production. Line up friends early (they just need a Google account and the opt-in link).
- **Identity verification:** Google asks for ID and, for personal accounts, a verified phone number. It can take a few days.
- **Policy risk, honestly:** Saturn may get pushback in review on three points.
  1. *Accessibility API*: allowed for non-disability use only with the declaration below, a prominent in-app disclosure (we have it) and a demo video.
  2. *Executing code*: Saturn runs node/bash and can install npm packages and pi.dev extensions. Play bans apps that download *native* executable code from outside Play; JavaScript run by an interpreter is generally accepted, but reviewers vary. If they object, the fix is a Play build flavor that turns off "Run scripts directly".
  3. *Exact alarms*: we use `SCHEDULE_EXACT_ALARM` (user-granted), which is fine for user-set timers and reminders.
  If any of these gets rejected, GitHub and F-Droid releases are unaffected.

## 1. Create the app (you)
Play Console → Create app → Name **Saturn Agent**, default language English (US), **App**, **Free**.
Package name comes from the bundle: `io.github.gouravraghuwanshi.saturn`.
Turn on **Play App Signing** (default). Upload `app-release.aab`; our key becomes the *upload key*.

## 2. Store listing
Copy from `fastlane/metadata/android/en-US/`:
- App name: `title.txt`
- Short description: `short_description.txt`
- Full description: `full_description.txt`
- App icon: `images/icon.png` (512×512)
- Feature graphic: `images/featureGraphic.png` (1024×500)
- Phone screenshots: `images/phoneScreenshots/1.png` … `6.png` (1080×1920)
- Category: **Productivity**. Tags: AI assistant, productivity, developer tools.
- Contact email: **you** (use an address you're happy to make public).
- Privacy policy URL: `https://github.com/gouravraghuwanshi/saturn-agent/blob/main/PRIVACY.md`

## 3. App content forms
**Data safety**
- Does your app collect or share user data? **No.** Saturn's developer receives nothing. Data the user sends to their own chosen AI provider is user-initiated transfer to a service the user picks (Play counts that as not "collection" by you; note it in the description, which we do).
- Is data encrypted in transit? **Yes** (HTTPS to providers).
- Can users request deletion? **Yes**: uninstall or clear storage; nothing is stored off-device by us.

**Ads:** No. **Target audience:** 18+. **Content rating questionnaire:** utility/productivity, no user-generated content shared with others, unrestricted internet access: Yes.
**Government / financial / health app:** No.

**Foreground service (specialUse)** declaration:
> Saturn runs AI agent tasks the user started (for example building a web page or running a script). The task keeps running while the user switches apps and stops as soon as it finishes. Users can see and stop running tasks from the notification and in the app.

**Accessibility API** declaration (choose *not* an accessibility tool):
> When the user turns on "Let Saturn use your apps" and asks Saturn to do something in another app (for example "open Maps and find a café"), Saturn uses the Accessibility API to read the on-screen elements and perform taps, typing and swipes for that request. It acts only on the user's request, shows a persistent Stop button while acting, asks the user before sending, paying, deleting or installing, and never types into password fields, acts on the lock screen or answers permission prompts. Screen content is sent only to the AI provider the user configured, to decide the next step.
Demo video (**you**): screen-record ~60s: enable in Settings → ask "open Calculator and work out 7×6" → show the Stop pill → ask it to send a message → show the Allow card → tap Don't allow.

**Exact alarm** (if asked): user-scheduled timers, reminders and repeating jobs created in chat.

## 4. Closed testing (you)
Testing → Closed testing → create track → upload the AAB → add 12+ testers' emails → share the opt-in link → wait 14 days → apply for production.
