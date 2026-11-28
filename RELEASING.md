# Releasing

## Signing key
Release builds are signed with the upload key in `~/saturn-keys/` (outside this repo):
- `saturn-upload.jks`, the key itself
- `keystore.properties`, its path and passwords (read by `app/build.gradle.kts`)

**Back both files up somewhere safe** (a password manager, an encrypted USB stick). If you lose
them you can't ship updates to existing installs with the same signature. Never commit them.

## Build
One build, the whole app. It goes to GitHub Releases (and F-Droid).

```sh
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk   -> GitHub Releases
```
Bump `versionCode` and `versionName` in `app/build.gradle.kts` first, and add
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

To update the old dev install (`io.github.pimobile.spike`) instead of installing a second app:
`./gradlew assembleDebug -PappId=io.github.pimobile.spike`.

## Store images
`fastlane/metadata/android/en-US/` holds the listing text, icon, feature graphic and phone
screenshots. It's the layout F-Droid and fastlane both read. To regenerate the framed screenshots
from raw phone screenshots:
`python3 -I fastlane/make_store_images.py <raw-shots-dir> fastlane/metadata/android/en-US/images app/src/main/res/font`
