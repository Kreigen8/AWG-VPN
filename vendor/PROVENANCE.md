# Bundled AmneziaWG engine

The Android tunnel Java source is copied from the official repository:
https://github.com/amnezia-vpn/amneziawg-android/tree/v3.1.20260814

- Tag: `v3.1.20260814`
- Commit: `5c16489e2cd9ed3a0a7a27c7445bba5238132f86`
- Source archive: https://codeload.github.com/amnezia-vpn/amneziawg-android/zip/refs/tags/v3.1.20260814
- Source archive SHA-256: `f0b3d8c1bd11df22451ba78db5f786474ec82c541beb0cd77cdf71c3cd4bd88b`
- License: Apache-2.0; original copyright headers preserved, full text in `AMNEZIAWG-LICENSE.txt`.
- `tunnel/routevpn.gradle` is the local build integration. The original `tunnel/build.gradle.kts` and native build files are retained for reference and are not executed by the root build.
- Two stale upstream test fixtures were corrected without changing production engine behavior: the invalid-integer test now uses `ListenPort = 0L` because v3 accepts persistent keepalive strings; the syntax-error test now omits `=` because an empty endpoint correctly produces an invalid-value error.
- Local production lifecycle patch: `GoBackend.VpnService.onDestroy()` calls `owner.stopStatusJob()` before tunnel cleanup so Android VPN revocation also stops the handshake polling thread.

The `libwg-go.so` files are extracted unchanged from the official signed release APK:
https://github.com/amnezia-vpn/amneziawg-android/releases/download/v3.1.20260814/AmneziaWG-3.1.202060814.apk

Release page: https://github.com/amnezia-vpn/amneziawg-android/releases/tag/v3.1.20260814

The APK SHA-256 was checked against the GitHub release asset digest before extraction:
`74f109a948f012e8b90b4055e98bb9bee77bbb8e5d0fe7d5a057dd9698009697`

| ABI | libwg-go.so SHA-256 |
| --- | --- |
| arm64-v8a | `8bd1d044fcf1828924981bb8b1d66571b884c16d2d779de603d5b359d6ed4416` |
| armeabi-v7a | `f0f7e76889b4fd19c5f58eb940ece4424aeb1d754426f421c2e8d38472722045` |
| x86 | `14d137cb95139fae1461ff75215f6dbf2203830d03a6c2d09d0830be0e59ab86` |
| x86_64 | `05dd7e0df77dda53d070848d8f1b176578629ff0ed0eacbe36f451239637ad7d` |

The JNI bridge retains the original `org.amnezia.awg.GoBackend` Java package and method names, required by these native files. Only the userspace VPN engine is packaged; root-only command line executables are not bundled or used.

The upstream native `go.mod` pins `github.com/amnezia-vpn/amneziawg-go/v3` to `v3.1.20260814`, `golang.org/x/sys` to `v0.36.0`, `golang.org/x/crypto` to `v0.42.0`, and `golang.org/x/net` to `v0.44.0`. Upstream Go sources are MIT licensed; Go and the included x libraries use BSD-style licenses. Corresponding notices are in `licenses/`. Windows-only wintun is not linked into Android binaries.

The native binaries have not been rebuilt locally. Their functional provenance is the signed upstream release, not a claim of independently reproduced native builds. The complete native build wrapper remains under `tunnel/tools/libwg-go/`; a suitable Linux/macOS Android NDK/Go toolchain is required for rebuilding it.

Other runtime libraries in 0.1.2: AndroidX CameraX 1.5.3 (Camera2, Lifecycle, View and their transitive modules), Activity 1.10.1, Core 1.15.0, annotation, collection and their AndroidX dependencies (Apache-2.0), Kotlin standard library aligned with BOM 2.0.21 (Apache-2.0), desugar JDK libraries 2.1.5 (Apache-2.0), ZXing core 3.5.3 (Apache-2.0), and Guava ListenableFuture 1.0 (Apache-2.0). JourneyApps is no longer a runtime dependency; its license is retained as a historical notice for earlier source versions. JSR305 is compile-only; JUnit, JSON and Robolectric 4.16.1 are test-only. Dependency versions are declared in Gradle files.

CameraX 1.5.3 was selected for its newer-device fixes, including the Android 17 dynamic-range crash fix introduced in 1.5.2. Official release notes: https://developer.android.com/jetpack/androidx/releases/camera . Its camera-core/camera2/lifecycle/view AAR metadata requires compile SDK 35 and AGP 8.6.0, compatible with this project's SDK 35 and AGP 8.9.2. CameraX includes `libimage_processing_util_jni.so` and `libsurface_util_jni.so` for each supported ABI; these are provided by Google Maven and use 16 KiB ELF segment alignment. CameraX's Apache license and the BSD-style libyuv license are included in `licenses/camerax-LICENSE.txt` and `licenses/libyuv-LICENSE.txt`.

Connection application-layer study for version 0.2.0: official master commit ff15093bf3856fea0947923474870b86efb682f0 (2026-11-02). The current GoBackend source was compared with the bundled version: the only difference remains the two-line status-job cleanup documented above. Native binaries are unchanged. New restoration, network monitoring and Quick Settings code adapts the upstream connection flow for encrypted single-profile storage; details and source references are in docs/UPSTREAM-CONNECTION.md.
