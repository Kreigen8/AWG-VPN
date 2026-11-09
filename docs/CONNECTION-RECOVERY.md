# Tunnel recovery in 0.2.2

## Problem and scope

The previous connection owner rebuilt a tunnel on physical-network changes and startup errors, but did not detect a native tunnel that remained UP while its traffic stopped. Matching Android sleep/resume reports exist upstream:

- https://github.com/amnezia-vpn/amnezia-client/issues/3255
- https://github.com/amnezia-vpn/amnezia-client/issues/3304

Those reports establish that this symptom exists; they do not prove the native root cause on every affected device. The official native binaries and all AWG server parameters remain unchanged in this release. This is recovery in the Android application layer.

## Recovery policy

- The existing automatic-restoration setting controls automatic health checks and recovery.
- All health decisions use elapsed realtime. Wall-clock changes and custom AWG rekey timers do not determine whether a tunnel is broken.
- Fresh RX or a new handshake cancels a pending failure series. An old handshake or an idle connection is never sufficient to restart.
- After a 15-second startup grace, a tunnel without replies can be checked. Established tunnels are checked after 30 seconds without received progress, at most once per minute while the phone is interactive and outside deep Doze.
- Screen-on, user-present and exit-from-idle events ask the existing worker to check again. They do not acquire a permanent wake lock or force the device out of idle.
- Each probe sends a DNS root NS query, with no user domain, to at most two DNS addresses already in the config and covered by AllowedIPs. The socket must bind to a VPN Network owned by this app before sending. There is no default-network fallback, hard-coded external probe address, HTTP tracking request or public-IP lookup.
- Any matching DNS response, including SERVFAIL/refusal response codes, proves a response traversed the tunnel. Binding denial, a missing suitable VPN, missing routed DNS or socket errors that are not timeouts are unavailable diagnostics, not evidence of a dead tunnel.
- Three consecutive failed probes, separated by at least ten seconds in automatic operation, can recreate the same tunnel. The minimum restart interval is one minute, with at most three watchdog restarts per fifteen-minute window. Physical-network changes and explicit user actions are handled separately.
- A result is discarded after network/link changes, tunnel replacement, screen-off cancellation, manual disconnect or service destruction. A reply received while the probe was running overrides its timeout.

In allowlist mode, the validated runtime config also allows the AWG VPN package itself so its control socket can use the tunnel. This does not change the saved profile, user application selection or any other app's routing. An explicit disallow rule for the controller remains authoritative, in which case binding may be unavailable.

Android 10 keeps the existing passive restoration behavior. Active bound probes require Android 11 or newer to verify VPN ownership using the public owner-UID API. Configs with no suitable DNS retain network-change restoration; no probe target is invented for them.

## Physical network monitoring

Callback notifications are the source of truth. The monitor no longer seeds unobserved alternative networks from getAllNetworks, which can leave stale alternatives in a best-matching subscription. Relevant address, route, interface and DNS changes trigger restoration even when the Network identifier is unchanged. An initial physical-network callback does not unnecessarily recreate a tunnel being started on that network.

## Diagnostics and data preservation

The non-exported connection service provides a system dumpsys summary:

```text
adb shell dumpsys activity service ru.routevpn.app/.RouteVpnService
adb shell dumpsys activity service ru.routevpn.app/.RouteVpnService --probe
```

The second command explicitly requests a probe even while the screen is off. If automatic recovery is enabled, confirmed failures can trigger its normal bounded recovery policy.

The summary contains phase, aggregate RX/TX counters, handshake age, routing mode, counts, MTU, boolean flags and a bounded in-memory event list. It never contains profile names or IDs, keys, config text, DNS addresses, endpoint addresses, application names or browsing domains. It is not an exported app API and writes no log files. This also allows troubleshooting on devices whose vendor logcat output is encrypted.

Package ID, release certificate, encrypted profile format, Keystore alias, selected-profile preferences and routing preferences remain unchanged. APK installation uses replacement, never uninstall or clear-data. Device diagnostics stay in ignored local cache and are not included in source or release archives.

## Verification

Pure policy tests cover spacing, startup grace, sleep, traffic overriding a timeout, unavailable binding, cooldown and retry budgets. UDP loopback tests cover the real query codec, mismatched reply rejection, socket timeout, cancellation and no-send-on-bind-denial; loopback does not model Android VPN routing. Service tests use an explicit fake backend and fake probe transport to verify recovery, cancellation, disabled restoration and secret-free dumps. Network-monitor tests cover callback ownership and same-network address changes on API 29 and 35.

An Honor Magic 8 Pro running Android 17 was used for signed in-place APK installation, restoration of existing profiles/routing and successful explicit VPN-bound DNS probes. Live RX/TX and handshakes continued after installation. This is a short device check, not a multi-day stability result or a reproduction of the original native stall. Forced device-wide idle, root access and global power-policy changes were not used.
