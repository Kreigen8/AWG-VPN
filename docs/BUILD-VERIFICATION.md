# Verification — 0.2.1-preview, 2026-11-02

The local `assembleDebug`, `assembleRelease`, `testDebugUnitTest` and `lintDebug` build passed. There are 111 tests in the suite: 110 passed locally on Windows, 1 FileProvider POSIX-filesystem test is reserved for Linux CI, and there are no failures/errors. The complete suite also runs in GitHub Actions on Linux.

New updater checks cover public preview releases, strict release manifests, draft/legacy filtering, hostile URLs and redirects, bounded metadata/APK downloads, cancellation, checksums, installed/archive PackageManager identities, signer changes, downgrades, incompatible packages, cached-file tampering, cache recovery, offline install preparation, unknown-source permission flow and unchanged profile/selection files. Native Android graphics screenshots of available/ready states were inspected.

The release keeps app ID `ru.routevpn.app`, AES key alias and profile schema, preference namespaces, and the same signing certificate. Version code increases from 4 to 5. The new updater stores only public release metadata and its own preferences, and exposes only the cache/updates directory to Android's installer using a temporary read-only URI grant. It never uninstalls AWG VPN or edits the encrypted profile store.

The new packaging script generates `update.json` from the signed APK's metadata, byte size, SHA-256 and signing certificate. APK signing, 21 required DEX class definitions and 16 KiB ZIP alignment are checked before publication.

Network/PackageManager fixtures and the install-launch seam are explicit test fakes. FileProvider's real root/URI check runs on Linux because AndroidX's POSIX path boundary is incompatible with Windows canonical file paths. No phone is attached; actual Android installer confirmation and on-device replacement still require phone validation. See [UPDATES.md](UPDATES.md).

---

# Verification — 0.2.0-preview, 2026-11-02

The final local build passed `assembleDebug`, `assembleRelease`, `testDebugUnitTest` and `lintDebug`.

- 88 tests passed: 78 app tests and 10 upstream tunnel tests. No failures, errors or skips.
- New checks cover unchanged basic-editor round trips; IPv6 endpoint editing; preservation of other peers, AWG parameters and comments; bounded input validation; reading a byte-compatible 0.1.2 encrypted profile fixture; retaining selection, app rules and optional history; corrupt-file protection; persistent restoration intent; manual stop; network rebuild and cancellation; sticky process restart; revoked permission; and Quick Settings state.
- Editor basic/text portrait and text landscape screenshots were rendered with native Android graphics in Robolectric and visually inspected. Saving from the editor preserves profile ID, app rules and the previous config.
- The encrypted old-format fixture uses an explicit AES test key, not a device Keystore. On Windows, a test-only POSIX rename adapter models Android AtomicFile's replace-existing-file behavior. The released storage additionally confirms encrypted bytes after each commit.
- Service tests use a backend fake and do not claim to test actual packet forwarding. Tile tests cover tile state; Robolectric's unrelated base TileService teardown shadow has a known type mismatch.
- App ID remains `ru.routevpn.app`, version code 4, minimum SDK 29, target SDK 35. The release signing certificate was compared against the actual 0.1.2 APK and is unchanged: `d3a4a4939f479bc3d29070d2a8e7e820186e06571fe0c34e27547fa572038f1f`.
- Profile file name, AES key alias, RVP1 magic, JSON schema version 1 and selected-profile preference keys remain unchanged. Upgrade does not erase or migrate away existing user settings.
- No ADB device was attached. Physical Honor camera behavior, live VPN traffic and actual device background/boot restrictions remain device-test items.

The upstream study and compatibility decisions are recorded in [UPSTREAM-CONNECTION.md](UPSTREAM-CONNECTION.md). Packaging verifies 17 required DEX classes, APK signing and 16 KiB ZIP alignment. Private signing keys, local caches and user configs are excluded from source publication.

---

# Verification — 0.1.2-preview, 2026-10-16

The final local build completed successfully with:

```powershell
.\scripts\build.ps1 -Tasks @('assembleDebug', 'assembleRelease', 'testDebugUnitTest', 'lintDebug')
```

Toolchain: Eclipse Temurin JDK 17.0.20.1+1, Gradle 8.11.1, Android Gradle Plugin 8.9.2, Android SDK 35, build-tools 35.0.0. Tools and build caches are local to this project.

## Automated checks

All 65 tests passed, with zero failures, errors or skips:

| Suite | Tests |
| --- | ---: |
| Configuration and application-routing policy | 8 |
| Editing configurations while preserving profile identity and routing | 3 |
| QR payload decoding | 9 |
| QR image decoding | 10 |
| Camera frame buffer decoding | 4 |
| Camera Activity lifecycle, permissions, errors and layout | 14 |
| Photo resolution and allocation limits | 3 |
| Android photo decoding and region retry orchestration | 4 |
| Vendored tunnel JVM tests | 10 |

The image decoder tests cover dense version-40 QR codes rendered at 352 pixels, small off-center QR codes in tall screenshots, perspective, low contrast, transparent and inverted images, and bounded rejection of blank/noisy inputs. Camera-frame tests cover row/pixel strides, buffer offsets, rotations, inversion, truncated buffers and codes outside a center crop. The user's supplied screenshot was also decoded locally and accepted by configuration validation; its image, decoded content and credentials are not included in the source archive or test fixtures.

Camera lifecycle tests use Robolectric 4.16.1 on SDK 29 and 35 with explicit camera hardware fakes. They verify permission requests and denial, recoverable provider failure, protected preview windows, successful result delivery, and portrait/landscape preview layout. They do not test optical autofocus or actual Camera2 hardware.

Photo integration tests use Robolectric's native Android bitmap codecs on SDK 35 for PNG screenshots, sampled JPEG images and invalid-image rejection. The dense JPEG regional-retry test uses an explicitly named test-only `NativeCropRegionShadow`: Robolectric's built-in `ShadowBitmapRegionDecoder.decodeRegion()` returns an empty bitmap even in native graphics mode. The replacement decodes with native `BitmapFactory` and crops the decoded image to test region coordinates and retry flow. It does not validate the real device's region-decoding API or its memory usage. Production code was not changed to accommodate the empty test bitmap.

Debug and signed release APKs assembled successfully. Android lint reported zero errors: 14 app warnings (8 Russian localization warnings and 6 notices about newer dependency versions), plus 7 upstream tunnel warnings (locale use, obsolete SDK checks and the native-library loader). The intentional installed-app visibility permission has a narrow manifest suppression explaining its per-app VPN routing purpose.

## APK and source integrity

- Release identity: `ru.routevpn.app`, version code 3, version `0.1.2-preview`, label `AWG VPN`, minimum SDK 29 (Android 10), target SDK 35. Release is not debuggable and is signed with the same key as 0.1.0 and 0.1.1, allowing installation over existing profiles.
- Four native ABIs: arm64-v8a, armeabi-v7a, x86 and x86_64. Each includes the AWG engine and two CameraX native libraries; all 12 ELF files have 16 KiB LOAD-segment alignment.
- Every packaged `libwg-go.so` matches the verified upstream binary hash. All six required `org.amnezia.awg.GoBackend` JNI exports are defined in each ABI.
- `scripts/verify-apk-runtime.ps1` checks actual class definitions across all APK DEX files for 12 required CameraX, AndroidX, ZXing and app classes. It rejects the old 0.1.1 scanner APK and accepts 0.1.2, so test-only dependencies cannot hide missing packaged runtime classes.
- `scripts/package.ps1` verifies the APK signature using `apksigner verify --verbose --print-certs` and ZIP alignment using `zipalign -c -P 16 4` before copying it to `dist`.
- Signing key: local RSA 3072-bit. Certificate SHA-256: `d3a4a4939f479bc3d29070d2a8e7e820186e06571fe0c34e27547fa572038f1f`.
- `dist/SHA256SUMS.txt` contains the APK and source archive hashes; `dist/APK-SIGNATURE.txt` contains signature-verification output. Earlier release artifacts are retained.
- Source packaging uses an explicit allowlist and excludes `.signing`, `.cache`, `.tools`, generated build outputs and credentials. The bundled AWG engine comes from a verified official release; its native binaries were not rebuilt locally. Provenance and licenses are in `vendor/`.

## Camera and import regression history

Version 0.1.0 omitted AndroidX implementation dependencies needed by JourneyApps. Four isolated startup cases reproduced `NoClassDefFoundError: androidx/core/content/ContextCompat` in `CaptureManager.openCameraWithPermission()`. Version 0.1.1 included those dependencies and fixed that startup failure.

Version 0.1.2 replaces the legacy scanner with CameraX 1.5.3 and ZXing core 3.5.3, adds focus controls, zoom and torch, and preserves dense QR detail during photo import. CameraX 1.5.3 includes the Android 17 dynamic-range fix introduced in 1.5.2. The selected AARs require compile SDK 35 and AGP 8.6.0, compatible with this project's SDK 35 and AGP 8.9.2. Dependency metadata and duplicate-class checks passed. Kotlin standard-library artifacts are aligned using BOM 2.0.21.

## Device testing limits

ADB reported no connected devices at verification time; no full Android emulator was run. The user's Honor Magic8 Pro reported as Android 17 has not been physically tested. Real autofocus, camera exposure/zoom/torch, hardware frame delivery, gallery-picker integration, actual Android region decoding, VPN handshake and traffic forwarding against a server, per-app traffic separation, Android Keystore persistence, notifications and background lifecycle still need device validation. Automated checks do not prove a working VPN connection or camera behavior on a particular phone.

