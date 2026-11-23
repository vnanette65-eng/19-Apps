# AppVault Android

Native Android companion for the existing AppVault Base44 app.

## Architecture
- **AppVault website / Base44:** catalogue, admin publishing, accounts, reviews, download history and app metadata.
- **This Android project:** hybrid Android client with a WebView for the existing AppVault experience and native Android download / package-installer handoff.
- **GitHub Releases:** APK distribution location. Large APK binaries should not be committed to the source tree.
- **Device storage:** Android DownloadManager downloads APKs to app-specific external storage; Android's package installer handles installation after user confirmation.

## Starter status
This is an initial project scaffold, not a release-ready app. The AppVault published URL was not available when this project was started because the Base44 app is not currently published. Set `APPVAULT_BASE_URL` in `app/build.gradle.kts` to the verified published URL before building.

## Security note
`ApkSecurityScanner` currently performs basic on-device checks: APK metadata parsing, SHA-256 calculation and warnings for selected sensitive permissions. It is **not a complete antivirus engine**, has no malware-signature database, and cannot guarantee that an APK is virus-free. Production requires a backend scanning pipeline, trusted threat-intelligence/signature updates, certificate reputation checks, quarantine storage and auditable scan reports. Android's own installer and device security controls remain in force.

## Build
Open in Android Studio with JDK 17 and Android SDK 35. The first build requires internet access to download Gradle plugins and Android dependencies.
