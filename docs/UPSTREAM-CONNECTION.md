# Connection behavior and upstream study

AWG VPN 0.2.0 was checked against the official repository at commit
`ff15093bf3856fea0947923474870b86efb682f0` (master inspected 2026-11-02):
https://github.com/amnezia-vpn/amneziawg-android/tree/ff15093bf3856fea0947923474870b86efb682f0

Relevant upstream sources:

- `tunnel/src/main/java/org/amnezia/awg/backend/GoBackend.java`: TUN setup, protected UDP sockets, native AWG engine, status callbacks and Always-on callback.
- `ui/src/main/java/org/amnezia/awg/Application.kt`: Always-on restoration and serialized DOWN / cleanup delay / UP on underlying network change.
- `ui/src/main/java/org/amnezia/awg/util/NetworkState.kt`: network callbacks, best-matching registration on Android 12+, network validation and lifecycle cleanup.
- `ui/src/main/java/org/amnezia/awg/model/TunnelManager.kt`: persisted running tunnels and restoration.
- `ui/src/main/java/org/amnezia/awg/QuickTileService.kt`: system Quick Settings, unlock-before-toggle and API 34+ PendingIntent launch.

The bundled GoBackend is identical to the inspected upstream source apart from the already documented two-line cleanup fix that stops handshake polling in `VpnService.onDestroy()`. The verified native AWG libraries are unchanged from the previous signed AWG VPN release. Their provenance remains in `vendor/PROVENANCE.md`.

The application layer is adapted for AWG VPN's encrypted profiles and single selected tunnel. It remembers connection intent separately from profile data, debounces network switches, suppresses expected DOWN events while rebuilding, backs off transient startup errors (2–60 seconds), and cancels reconnect after manual stop or permission revocation. VPN networks are excluded from network observation to prevent feedback loops.

Android Always-on can restore the saved/selected profile through the upstream callback. Boot, user-unlock and package-update receivers request restoration only when the user left VPN enabled and automatic restoration is enabled. Android can restrict a background start, and force-stopped apps do not bypass Android's restrictions; Always-on is the preferred system-managed startup method.

The system's “Block connections without VPN” option also blocks apps excluded from the VPN. It is not enabled automatically, because direct per-app connections are a core feature.

## Update compatibility

- Application ID remains `ru.routevpn.app`.
- Version code increases from 3 to 4.
- Release signing certificate remains unchanged.
- Existing `profiles.enc`, AES key alias, format magic `RVP1`, JSON version 1, selected-profile preferences and profile IDs remain unchanged.
- `previousConfig` is an optional encrypted field; old profiles load with an empty history. A normal save retains app-routing choices and the previous config.
- Connection preferences contain only profile IDs and connection flags. Configs and keys are never placed in intents, logs, GitHub or CI secrets.

## 0.2.2 follow-up

The app now also verifies an idle/stalled tunnel with a socket explicitly bound to its own VPN and implements bounded recovery after repeated failures. Physical-network link changes are tracked even if the Network identifier remains unchanged. See [CONNECTION-RECOVERY.md](CONNECTION-RECOVERY.md) for the implementation, upstream symptom reports, device checks and limitations. These are local application-layer changes, not an upstream native-engine fix.

## Verification limits

Unit tests use an explicit backend fake for lifecycle and network transitions; these are not packet-forwarding tests. Old encrypted-format fixtures use an explicit test AES key instead of Android Keystore. On Windows, a test-only POSIX rename adapter models Android's replace-existing-file semantics; it does not replace production AtomicFile code. Production storage verifies the encrypted bytes after each commit.

Editor screenshots are generated from native Android graphics in Robolectric using synthetic configs. Hardware camera behavior, background restrictions and live VPN traffic still require an Android phone and server.
