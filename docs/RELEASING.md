# Releasing TripSplit

This runbook is for the owner. It covers the iOS TestFlight build, the Android APK, and the privacy page.
Tracking issue: olcayertas/trip-split#1. Plan: `docs/PLAN.md` §14.

## What the apps must provide

The release tools assume these values. The iOS and Android tracks must keep them.

### iOS

| Item | Value |
|---|---|
| Xcode project | `apple/TripSplit.xcodeproj` (made by `xcodegen generate` from `apple/project.yml`) |
| Scheme | `TripSplit` |
| Bundle id | `com.olcayertas.TripSplit` |
| Team | `SNZ29V4PJZ` |
| Signing | Automatic |
| Info.plist key | `ITSAppUsesNonExemptEncryption` = `NO` (see "Export compliance") |
| Version | `MARKETING_VERSION` in `project.yml` |

The `beta` lane sets `CURRENT_PROJECT_VERSION` from the command line. Do not hard-code a build number that the lane needs to change.

### Android

| Item | Value |
|---|---|
| Application id | `com.olcayertas.tripsplit` |
| Module | `:app` |
| Wrapper | `android/gradlew` |
| Release signing | A `release` signingConfig that reads `android/keystore.properties` |

The properties file has `storeFile` and `keyAlias`. The passwords come from the environment. `scripts/release-android.sh` reads them from the macOS keychain and sets `TRIPSPLIT_STORE_PASSWORD` and `TRIPSPLIT_KEY_PASSWORD` for the Gradle call.

Paste this into `android/app/build.gradle.kts`. Put the `signingConfigs` block inside `android { }` and before `buildTypes`:

```kotlin
import java.util.Properties

val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                keyAlias = keystoreProps.getProperty("keyAlias")
                storePassword = keystoreProps.getProperty("storePassword")
                    ?: System.getenv("TRIPSPLIT_STORE_PASSWORD")
                keyPassword = keystoreProps.getProperty("keyPassword")
                    ?: System.getenv("TRIPSPLIT_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
}
```

Without `android/keystore.properties`, the release build is unsigned. The build does not fail.

## One-time setup: iOS

1. Join the Apple Developer Program on team `SNZ29V4PJZ`. Accept all agreements in App Store Connect.
2. Make the App Store Connect API key:
   1. Open App Store Connect. Go to Users and Access, then Integrations, then App Store Connect API.
   2. Make a team key. Set the role to **App Manager**.
   3. Download `AuthKey_<KEY_ID>.p8`. Apple lets you download it one time only.
   4. Save the file outside the repo, for example in `~/.appstoreconnect/`.
   5. Note the key id and the issuer id.
3. Copy `apple/fastlane/.env.example` to `apple/fastlane/.env`. Fill in `ASC_KEY_ID`, `ASC_ISSUER_ID` and `ASC_KEY_PATH`. Git ignores this file.
4. Make the app record. In App Store Connect, go to Apps, then New App. Use bundle id `com.olcayertas.TripSplit`. Choose the SKU you want.
5. Install the tools:

   ```bash
   brew install xcodegen
   cd apple && bundle install
   ```

6. Check the setup. This command does not upload anything:

   ```bash
   cd apple && bundle exec fastlane ios inspect
   ```

### Export compliance

Set `ITSAppUsesNonExemptEncryption` to `NO` in the app. Use `INFOPLIST_KEY_ITSAppUsesNonExemptEncryption: NO` in `project.yml`. Then App Store Connect does not ask the question for each build.

Reason: TripSplit uses only the encryption that Apple's operating system provides (CryptoKit and system networking). Apple treats this as exempt. This holds only while the app has no own crypto code and no third-party crypto library.

If a later version adds its own crypto library, change the answer. Choose "uses encryption" and "standard algorithms only". That claim needs an annual self-classification report to the US BIS. Ask the owner before you ship that change.

### Privacy label

In App Store Connect, open App Privacy. Choose **Data Not Collected**. Set the privacy policy URL to `https://olcayertas.github.io/trip-split/`. The Pages workflow publishes that page from `docs/privacy/`.

### TestFlight testers

- Internal testers: up to 100 people with an App Store Connect role. Add them in Users and Access, then open TestFlight and add them to an internal group. They get builds without review.
- External testers: add them to an external group by email or public link. Apple does a beta review for the first build of each version. Builds expire after 90 days.

## One-time setup: Android

1. Make the keystore:

   ```bash
   scripts/make-keystore.sh
   ```

   The script makes `~/.tripsplit/tripsplit-release.jks`. It stores the passwords in the macOS keychain under the service `tripsplit-android-keystore`. It writes `android/keystore.properties` without passwords. It prints no password.
2. Back up the keystore. See the next section. Do this now.

### Keystore backup

Android updates work only when the new APK has the **same** signing key. If you lose the keystore or its passwords, nobody can update the app. They must uninstall it and lose their data.

1. Copy `~/.tripsplit/tripsplit-release.jks` to at least two places. Use an encrypted disk image or a password manager attachment. One copy must be off this Mac.
2. Save the password. Open Keychain Access. Find the item `tripsplit-android-keystore` with the account `storePassword`. Copy the password to your password manager. The key password has the same value.
3. Write the alias (`tripsplit`) in the same entry.
4. Test the backup once. Run this command on the copy, and enter the password when asked:

   ```bash
   keytool -list -keystore /path/to/copy/tripsplit-release.jks
   ```

5. Never commit the keystore or the properties file. Git ignores `*.jks`, `*.keystore` and `android/keystore.properties`.

## Release checklist

Do these steps for every release.

1. Make sure `main` is green in CI. Make sure the vectors pass on both platforms.
2. Set the version. iOS: `MARKETING_VERSION` in `apple/project.yml`. Android: `versionName` and `versionCode` in `android/app/build.gradle.kts`. Keep both platforms on the same version.
3. Merge the version change to `main`.
4. Build iOS. Run:

   ```bash
   cd apple && bundle exec fastlane ios beta
   ```

   The lane builds, signs and uploads. It does not wait for Apple to process the build. Wait for the email from App Store Connect. Then open TestFlight and add the build to your groups.
5. Build Android. Run:

   ```bash
   scripts/release-android.sh --github-release
   ```

   The script builds, verifies the signature, copies the APK to `dist/TripSplit-<versionName>.apk`, prints the SHA-256, and makes a **draft** GitHub release.
6. Check the printed certificate digest. It must match the digest of the previous release. A different digest means the wrong keystore.
7. Open the draft release on GitHub. Check the APK. Publish it. Share the release link.
8. Install both builds on a real phone. Make a group. Add one expense.
9. Record the build number and the SHA-256 in issue olcayertas/trip-split#1.

## Install the Android APK (sideload)

Tell your group to follow these steps:

1. Open the release link on the phone. Download the APK.
2. When Android asks, allow the browser (or the file manager) to **install unknown apps**.
3. Open the APK. Tap Install.
4. For an update, install the new APK over the old one. Do not uninstall first. The data stays.
5. Optional: compare the SHA-256 of the file with the value in the release notes.

After the install, switch off "install unknown apps" for that browser.

## Privacy page

The workflow `.github/workflows/pages.yml` publishes `docs/privacy/` when a change reaches `main`. The page is at `https://olcayertas.github.io/trip-split/`. GitHub Pages is set to the "GitHub Actions" source for this repo. To publish by hand, run the workflow from the Actions tab.

## Known traps

- `gym` passes `xcargs` to the export step too. The `beta` lane sends the same auth arguments to the archive and the export. Check the export step on the first real run.
- `latest_testflight_build_number` needs the app record. Make the record first, or the lane fails.
- A key with a role lower than App Manager cannot make signing assets. Make the key again with the correct role.
