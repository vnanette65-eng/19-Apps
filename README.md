# Nationestore Android

Native Android companion for the existing Nationestore Base44 app.

## Architecture
- **Nationestore website / Base44:** catalogue, admin publishing, accounts, reviews, download history and app metadata.
- **This Android project:** hybrid Android client with a WebView for the existing Nationestore experience and native Android download / package-installer handoff.
- **GitHub Releases:** APK distribution location. Large APK binaries should not be committed to the source tree.
- **Device storage:** Android DownloadManager downloads APKs to app-specific external storage; Android's package installer handles installation after user confirmation.

## Starter status
This is an initial project scaffold, not a release-ready app. The Nationestore published URL was not available when this project was started because the Base44 app is not currently published. Set `APPVAULT_BASE_URL` in `app/build.gradle.kts` to the verified published URL before building.

## Security note
`ApkSecurityScanner` currently performs basic on-device checks: APK metadata parsing, SHA-256 calculation and warnings for selected sensitive permissions. It is **not a complete antivirus engine**, has no malware-signature database, and cannot guarantee that an APK is virus-free. Production requires a backend scanning pipeline, trusted threat-intelligence/signature updates, certificate reputation checks, quarantine storage and auditable scan reports. Android's own installer and device security controls remain in force.

## Build
Open in Android Studio with JDK 17 and Android SDK 35. The first build requires internet access to download Gradle plugins and Android dependencies.


## AAB conversion (Google bundletool)

Nationestore includes a GitHub Actions workflow that converts a directly downloadable Android App Bundle into a universal APK:

1. Open the repository's **Actions** tab.
2. Select **Nationestore AAB to APK converter**.
3. Choose **Run workflow** and paste the direct HTTPS URL ending in `.aab`.
4. Wait for the workflow to finish successfully.
5. Open the run's **Artifacts** section and download `nationestore-converted-universal-apk`.

The generated APK is signed with bundletool's default debug key unless a production signing configuration is added. It is suitable for testing, not as a replacement for an app publisher's original signing identity. Installing an update over an existing app generally requires the same signing certificate. AAB conversion is currently a manual, server-side workflow; the Android client does not yet trigger this workflow automatically. The workflow accepts only direct HTTPS URLs ending in `.aab`, checks the archive, and stores the converted APK as a short-lived GitHub Actions artifact.

XAPK/APKS/APKM handling is separate and uses the native split-package installation path. All installation remains subject to Android's package verification and user confirmation.
