# Agent Instructions (GitHub & Build Guidelines)

## ⚠️ Security & Sensitive Files
- **NEVER commit keystores:** `*.keystore`, `*.jks`, `keystore_credentials*.txt`. Keystores must stay strictly local.
- **NEVER commit build outputs:** `.gradle/`, `build/`, `app/build/`, `local.properties`.

## Environment Requirements
- **Java:** JDK 17 or 21 (Temurin).
- **Android SDK:** CompileSdk 34. Use runner's pre-installed SDK. Do NOT add `android-actions/setup-android`.

## Canonical Build Commands
| Task | Command |
|---|---|
| Build Debug APK | `./gradlew assembleDebug` |
| Build Release APK | `./gradlew assembleRelease` |
| Clean build | `./gradlew clean` |

## Releases & Version Bumping
1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Commit: `git commit -m "chore(release): bump version to x.y.z"`.
3. Tag: `git tag -a vx.y.z -m "Release vx.y.z"`.
4. Push: `git push origin main --tags`.
5. GitHub Actions (`.github/workflows/build-apk.yml`) will automatically compile the APK and publish the GitHub Release.

## F-Droid & Anti-Features
- This app uses Google ML Kit for on-device translation. F-Droid recipe requires `AntiFeatures: - NonFreeComp`.
- Store descriptions live in `fastlane/metadata/android/de-DE/` and `en-US/`.

## Automatic F-Droid & Package Source Updates
- GitHub Actions automatically compiles the APK, creates the GitHub Release, and triggers Lauju1909/fdroid-repo via PAT_TRIGGER.
- Lauju's custom F-Droid repository updates within 2 minutes: all users with Neo Store or F-Droid receive the update notification automatically.
- Always add the new build block to droid/*.yml and update CurrentVersion / CurrentVersionCode for upstream F-Droid (MR !51047).
