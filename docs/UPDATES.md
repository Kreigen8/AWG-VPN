# GitHub updates

AWG VPN 0.2.1 adds an in-app updater for the public repository:
https://github.com/Kreigen8/AWG-VPN

## User flow

Open **Обновления → Проверить обновления GitHub**. The screen shows the installed version and a newer compatible release, if present. Tap **Загрузить APK**, wait for verification, then **Установить обновление**. Android may first ask to allow installation from AWG VPN, then shows its normal update confirmation. The APK is installed over the existing package; the updater does not uninstall the application or touch profiles, selections, routing rules, Keystore or connection preferences.

Automatic checks are enabled by default and can be disabled. They run while the app starts, at most once per 24 hours after a successful check, or once per hour after an unsuccessful attempt. Downloading/installing always requires a user action. Both regular releases and preview releases are considered, because this project publishes preview builds.

The first updater-enabled APK must be installed manually over 0.2.0 or earlier. Future releases can then be downloaded and installed from the app.

## Release contract

`scripts/package.ps1` generates the signed APK, source ZIP, `update.json`, SHA-256 checksums and signature information. **Upload `update.json` and the matching signed APK into the same GitHub release.** Its tag must be `v` followed by the APK version name, for example `v0.2.1-preview`.

`update.json` schema version 1 contains:

| Field | Meaning |
| --- | --- |
| schemaVersion | 1 |
| packageName | ru.routevpn.app |
| versionName / versionCode | Actual APK version; the code must increase |
| minSdk | Actual minimum Android SDK |
| apkName | AWG-VPN-<versionName>.apk |
| apkSize | Exact byte count |
| sha256 | SHA-256 of the signed APK |
| signerSha256 | SHA-256 of the APK signing certificate |

The app reads the public releases list, ignores drafts and legacy releases without an update manifest, and inspects up to five manifest-enabled releases among the latest twenty entries. It chooses the highest compatible version code. Release assets must be uploaded, have the expected names, and point to the exact repository/tag. The APK's GitHub asset digest, when present, must agree with the manifest.

## Verification and storage

- HTTPS only; redirects are restricted to the repository's GitHub release download paths and official GitHub asset hosts. No GitHub token is embedded or sent. No profile/device data is included in requests.
- Metadata is bounded to 2 MiB, manifests to 16 KiB, APKs to 128 MiB. Connections have timeouts; downloads can be canceled.
- Downloaded bytes must match the exact size and SHA-256. The APK package name, version code, minimum SDK and signing certificate must match the manifest and installed app. Downgrades, split packages and signer changes are rejected.
- The APK is verified again before the installer is opened, including after the permission-settings round trip.
- Only `cache/updates/` is exposed by a non-exported FileProvider, and the installer receives a temporary read-only content-URI grant. No storage permission is required. Profiles and preferences lie outside that provider root.
- Android's package installer performs the final APK verification and update. Installation requires Android's standard permission/confirmation.
- A verified APK can be restored from the app-private cache after process recreation and installed without downloading it again. Corrupt/partial files are deleted. Android may independently evict cache files; in that case the app downloads again.

App ID, signing key, encrypted profile file/schema, AES key alias and existing preference namespaces remain unchanged. `routevpn_updates` stores only updater preferences and public release metadata. Private signing keys stay local and are never uploaded.

## Tests and limits

Tests cover preview discovery, malformed/substituted metadata, HTTPS redirect restrictions, bounded downloads, SHA-256 failures, package/signer/version checks, cached-file tampering, recovery after process recreation, offline installation preparation, permission flow, and preservation of profile/selection files.

PackageManager archive/certificate fixtures and the installer-launch test seam are explicit fakes; they do not claim to perform a real Android installation. FileProvider's URI/root test runs on Linux GitHub CI: its POSIX '/' path contract cannot be modeled by Java's Windows canonical paths. Physical installation on the user's phone still requires device validation.
