# Getting Saturn onto Google Play

Everything here is ready to copy-paste. Steps you have to do yourself are marked **you**.

## 0. Read this first: what blocks Play (checked Nov 2026)
- **Phone control can't ship on Play.** Since 28 Jan 2026 Google's Accessibility policy prohibits "any use of the API that enables an app to autonomously initiate, plan, and execute actions". An AI agent tapping through apps is exactly that. The Play build must leave the accessibility service out entirely (it stays in the GitHub build).
- **AI-generated content needs an in-app Report button** so users can flag offensive replies without leaving the app.
- **Data safety: Saturn does "collect" data** in Google's sense (anything the app sends off the device, including to the AI provider the user picks). Declare it; see section 3.
- **Downloaded code is a judgment call.** Bundled tools are fine. npm and pi.dev packages are JavaScript run by node; the policy only clearly exempts interpreters with *indirect* Android API access (like a WebView). The Play build should block native addons (`node --no-addons`) and say plainly in the listing that extensions are user-installed.
- **New personal accounts:** closed test with 12+ testers for 14 days before production. Plus ID verification.
- **Developer verification (for the GitHub APK too):** from 30 Sep 2026, in Brazil, Indonesia, Singapore and Thailand first, certified phones only install apps from registered developers. Register the package name *and* the GitHub signing key in Play Console so sideloaded installs keep working.

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
**Data safety** (Saturn's own developer never receives anything, but the app does send user content to the AI provider the user configures, which Google counts as collection)
- Data collected: **Yes.** Messages ("Other in-app messages"), Files and docs, Photos, plus anything the agent reads for a request (Location, Audio, when the user asks for those). Purpose: **App functionality**. Processed ephemerally: **No** (the provider's retention policy applies). Optional: **No** (needed to answer).
- Data shared: **No** (user-initiated transfer to a provider they chose isn't "sharing").
- Encrypted in transit: **Yes**. Users can request deletion: data stays on the device; uninstalling removes it. Point to PRIVACY.md.

**Ads:** No. **Target audience:** 18+. **Content rating questionnaire:** utility/productivity, no user-generated content shared with others, unrestricted internet access: Yes.
**Government / financial / health app:** No.

**Foreground service (specialUse)** declaration:
> Saturn runs AI agent tasks the user started (for example building a web page or running a script). The task keeps running while the user switches apps and stops as soon as it finishes. Users can see and stop running tasks from the notification and in the app.

**Accessibility API:** not used in the Play build (see section 0). Don't upload a build that contains `PhoneControlService`.

**Exact alarm** (if asked): user-scheduled timers, reminders and repeating jobs created in chat.

## 4. Closed testing (you)
Testing → Closed testing → create track → upload the AAB → add 12+ testers' emails → share the opt-in link → wait 14 days → apply for production.
