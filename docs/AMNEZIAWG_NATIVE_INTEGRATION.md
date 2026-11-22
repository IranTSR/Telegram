# Native AmneziaWG integration plan

Target branch: `feature/amneziawg-native-engine`

## Product requirements

- WireGuard must stop using the Xray outbound and run through a dedicated AmneziaWG engine.
- Xray remains responsible for VLESS, VMess, Trojan, Shadowsocks and Hysteria2.
- The tunnel is per-app: only the Telegram application package is included in the Android VPN.
- The WireGuard editor must expose these actions:
  - **Show Amnezia properties**: show/hide advanced Amnezia fields.
  - **Enable Amnezia compatibility**: enable the compatibility preset.
  - **Mimic QUIC**, **Mimic DNS**, **Mimic SIP**: apply the exact presets from the upstream WG Tunnel implementation; do not invent parameter values.
- Preserve standard WireGuard configuration support as well as AmneziaWG configuration support.
- Keep AmneziaWG configuration fields in the existing proxy profile persistence path.

## Verified upstream integration facts

- Upstream Android implementation: https://github.com/amnezia-vpn/amneziawg-android
- Upstream release selected for evaluation: `v3.1.20260814` (release commit `5c16489`).
- The upstream Android project contains a `:tunnel` library module and uses `org.amnezia.awg.backend.GoBackend`.
- `GoBackend` creates the Android VPN interface, converts the parsed config to the AmneziaWG userspace format, and supports included/excluded application package lists.
- Android per-app routing is implemented with `VpnService.Builder.addAllowedApplication()`; only the Telegram package should be included.
- The upstream backend declares its own VPN service component. Integration must register the correct upstream service in the app manifest and respect the backend's process/static-state assumptions.

## Existing Telegram implementation findings

- Current WireGuard profile data is stored on `SharedConfig.ProxyInfo` as `wgPrivateKey`, `wgPublicKey`, `wgEndpoint`, `wgAllowedIps`, `wgLocalAddress`, `wgMtu`, `wgKeepAlive`, `wgPresharedKey`, and `wgDns`.
- Current WireGuard execution is implemented in `XrayProxyManager.buildWireguardOutbound()` and selected by `buildConfig()`. This path must be removed only when the native engine is wired end-to-end.
- `XrayProxyService` is a foreground service for the Xray process. The AmneziaWG lifecycle must be separate and must not share Xray's SOCKS readiness checks.
- The Telegram Android application package is configured by the root Gradle property `APP_PACKAGE`; per-app routing must use the actual application ID of the built variant, not a hard-coded assumption.

## Implementation sequence

1. Import/pin the upstream AmneziaWG tunnel module and make its Gradle catalog, native CMake/NDK build, package namespace and manifest service compatible with this repository.
2. Add a dedicated native AmneziaWG manager/service with explicit start, stop, state, error and statistics handling. Do not route WireGuard through Xray.
3. Convert the existing Telegram WireGuard profile into the upstream AmneziaWG config model. Apply the exact upstream Mimic presets and serialize all Amnezia fields.
4. Configure per-app VPN routing for the current Telegram application ID only.
5. Wire the WireGuard proxy selection/lifecycle to the new manager; remove the Xray WireGuard outbound after native startup and shutdown paths are complete.
6. Add the WireGuard editor actions and persistence, including **Show Amnezia properties**.
7. Add CI build coverage for supported Android ABIs and run a full GitHub Actions build.

## Safety and correctness gates

- Never silently fall back to Xray for a profile that requests AmneziaWG; report a clear startup error.
- Never establish an unrestricted VPN: the allowed-application list must contain only the current Telegram application ID.
- Android allows only one active VPN service per user/profile. Starting AmneziaWG may replace another app's VPN; surface this limitation clearly.
- Keep the existing Xray protocols unchanged.
- Do not claim a successful build or working tunnel until CI and device tests confirm it.
