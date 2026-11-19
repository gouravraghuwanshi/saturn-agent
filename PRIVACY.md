# Privacy

Short version: Saturn has no server, no accounts, no ads and no analytics. We (the people who make Saturn) never see your data.

## What stays on your phone
- Your projects, files, artifacts, chats and settings. They live in Saturn's private app storage.
- Your API keys, encrypted with the Android Keystore. Account sign-ins (like ChatGPT or Claude subscriptions) are kept in Saturn's private storage.
- Nothing is backed up to Google Drive or copied to a new phone. We turned Android backup off on purpose.

## What leaves your phone, and only when you use the feature
- **Your chats go to the AI provider you choose** (for example OpenRouter, Anthropic, OpenAI or Google), so the model can answer. That includes files, photos and command output the agent reads while working on your request. That provider's privacy policy applies to what you send them.
- **Web pages, packages and downloads** are fetched from the internet when you or the agent ask for them (for example the in-app browser, `npm install`, or installing an extension from pi.dev).
- **Sign-in pages** open in your browser and talk to that provider directly.

## Permissions, and why
- **Internet:** to talk to your AI provider and fetch what you ask for.
- **Notifications and foreground service:** to keep a task running while you're in another app, and to tell you when it's done.
- **Camera, microphone, location, contacts:** only asked for the first time the agent needs them for something you requested (for example "take a photo"). You can say no.
- **Exact alarms and run at startup:** so jobs you schedule ("remind me at 7") run on time and survive a reboot.

## Deleting your data
Uninstall Saturn, or clear its storage in Android settings. That removes everything; there's no copy anywhere else.

## Contact
Questions? Open an issue at https://github.com/gouravraghuwanshi/saturn-agent/issues

## Phone Control (separate app)
The optional Phone Control app sends nothing anywhere itself. When an agent you connected calls it, what's on screen (text, and any screenshot) goes back to that agent, and from there to that agent's AI provider. It only listens on your phone (127.0.0.1) and only answers requests with its secret token.
