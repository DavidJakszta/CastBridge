# CastBridge

A universal Android casting app for Chromecast and Roku. This repository currently implements
**Milestone 1**: reliable local-network discovery of Google Cast and Roku devices in one unified,
selectable device list.

## Current status

| Milestone | Status |
| --- | --- |
| 1. Project, architecture, unified device discovery | ✅ Implemented |
| 2. Screen / app-window capture | ⏳ Next |
| 3. Video encoding and low-latency streaming | ⏳ Planned |
| 4. Chromecast and Roku receiver apps | ⏳ Planned |

The app deliberately does **not** include a Cast SDK session or streaming code yet. Discovery,
device identity, stale-device handling, and reachability checks are in place so the streaming layer
can be built on a stable foundation.

## Features

- Discovers Google Cast devices over mDNS (`_googlecast._tcp`) using Android `NsdManager`.
- Discovers Roku devices over SSDP (`roku:ecp`) and enriches them through the Roku ECP
  `/query/device-info` endpoint.
- Falls back to mDNS (`_display._tcp`, `_airplay._tcp`) when SSDP yields no Roku, so Roku TVs on
  networks that filter SSDP multicast can still be found. Every fallback candidate is verified
  through Roku ECP, so other AirPlay TVs are never listed.
- Merges both transports into one list behind a common `CastDevice` interface.
- Keeps a stable list across scan cycles: a device is removed only after two consecutive successful
  scans without it.
- Runs transports in parallel and publishes results incrementally.
- Reports per-protocol discovery problems with troubleshooting hints.
- Shows device name, model, address, protocol-specific details, and selection state.
- Verifies reachability on demand (Roku ECP HTTP or Chromecast TCP port 8009).
- Material 3 Compose UI with light/dark themes, filters, empty states, and animated radar.

No runtime permissions are required for discovery. No backend, account, analytics, or cloud service
is used.

## Architecture

```
:app
  MainActivity, Compose UI, ViewModel, manual DI container
    │
    ├── :core:discovery (Android library)
    │     ChromecastDiscovery ── NsdManager (mDNS)
    │     RokuDiscovery ──────── SSDP + mDNS fallback + Roku ECP (HTTP)
    │     DeviceDiscoveryRepository ── parallel scans, merge, prune, health
    │     DefaultDeviceProbe ─── reachability checks
    │
    └── :core:model (pure Kotlin)
          CastDevice, ChromecastDevice, RokuDevice
          DeviceDiscovery, DeviceProbe, DiscoveryState
```

- **MVVM:** `DeviceListViewModel` combines repository state, selection, filters, and probe state into
  one immutable UI state. The Compose screen is stateless and receives callbacks.
- **Common device interface:** `CastDevice` lets the UI and future connection layer treat Chromecast
  and Roku uniformly. Protocol-specific data stays in `ChromecastDevice` / `RokuDevice`.
- **Discovery transports stay separate:** each implements `DeviceDiscovery.scan(timeoutMillis)`.
  Adding another TV platform means adding one implementation and one protocol entry.
- **The repository owns merging:** transports never mutate each other's state. The repository runs
  one bounded scan per transport in parallel, merges by stable device ID, tracks misses, and exposes
  a single `StateFlow<DiscoveryState>`.
- **Manual DI:** `AppContainer` is intentionally small. It can be replaced by Hilt/Koin without
  changing feature code.

### Why no Cast SDK yet?

Chromecast discovery is standard mDNS; Android already provides it. Keeping discovery independent of
Google Play Services makes this milestone work on devices without Play Services and keeps the
transport testable. The Cast SDK will be added for session/connection management after receiver
compatibility is validated, per the roadmap.

## How discovery works

### Chromecast

1. `NsdManager.discoverServices("_googlecast._tcp.", PROTOCOL_DNS_SD, listener)` runs for one scan
   window (12 seconds).
2. Each advertised service is resolved serially because Android permits only one active resolve.
3. The TXT record supplies `fn` (friendly name), `md` (model), `id` (stable device UUID), and more.
4. The resolved host and port are mapped to `ChromecastDevice`.
5. Transient resolve failures are retried once within the same scan.

The next scan cycle rediscovers devices. Any device absent for two cycles is removed.

### Roku

1. A `MulticastSocket` sends `M-SEARCH` for `ST: roku:ecp` to `239.255.255.250:1900` three times
   (`MAN: "ssdp:discover"`, `MX: 2`). Roku validates this header strictly and ignores requests that
   send an invalid `MAN` value such as `ssdp:all`.
2. Replies are parsed case-insensitively; `LOCATION`, `USN`, and `SERVER` identify the device.
3. A multicast lock is held during the scan because Android filters multicast traffic otherwise.
4. `/query/device-info` is fetched in parallel for every responder to obtain the friendly name,
   model, serial number, and vendor.
5. The serial number is preferred as the stable ID, so the same TV keeps one card across DHCP
   address changes.

If SSDP returns nothing, the scan falls back to mDNS:

6. After a short settle delay (Chromecast discovery may hold the system resolver), `_display._tcp`
   and `_airplay._tcp` are browsed for up to 6 seconds.
7. `_display._tcp` is Roku-specific and always resolved. `_airplay._tcp` is shared with Apple,
   Samsung, and LG TVs, so those advertisements are only resolved when the service name or TXT
   record mentions Roku.
8. Every fallback candidate must answer `/query/device-info` before it becomes a device, so a
   non-Roku AirPlay TV can never be listed as a Roku.
9. If SSDP itself failed *and* the fallback finds nothing, the SSDP error is reported. If SSDP
   succeeded but returned nothing, a silent empty result is returned and the repository keeps
   previously seen devices for one more cycle.

## Requirements

- Android Studio Ladybug or newer (or a JDK 17 + Android SDK command-line setup)
- JDK 17
- Android SDK Platform 35 and Build-Tools 35.0.0
- Android 8.0 (API 26) or newer

## Build and test

```bash
# Debug APK
./gradlew :app:assembleDebug

# JVM unit tests (SSDP/ECP/TXT parsers, registry, scan orchestration)
./gradlew :core:discovery:testDebugUnitTest

# All checks
./gradlew build
```

On Windows use `gradlew.bat`. The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

## Permissions

| Permission | Why |
| --- | --- |
| `INTERNET` | Roku ECP HTTP requests; local sockets |
| `ACCESS_NETWORK_STATE` | Network availability |
| `ACCESS_WIFI_STATE` | Multicast lock |
| `CHANGE_WIFI_MULTICAST_STATE` | Receive SSDP traffic on Android |

All are normal permissions; the app has no runtime permission prompt. `usesCleartextTraffic` is
enabled because Roku ECP is a local HTTP API on port 8060. No internet server is contacted.

## Troubleshooting discovery

- **Phone and TV must be on the same subnet/Wi-Fi.** Guest networks and client isolation block both
  mDNS and SSDP.
- **VPNs** can route multicast away from the TV network. Disable the VPN or allow local network
  traffic.
- **Chromecast not found:** ensure the device is powered and on the same network; mDNS must be
  allowed by the router. Some managed networks block multicast.
- **Roku not found:** confirm the Roku is on the same network and "Control by mobile apps" /
  network access is enabled in Roku settings. The app searches for `roku:ecp` on UDP 1900 and then
  calls `http://<device-ip>:8060/query/device-info`. If SSDP is blocked, the app automatically
  retries over mDNS using Roku's `_display._tcp` service or AirPlay (`_airplay._tcp`) when the TV
  advertises it. AirPlay must be enabled on the TV for that fallback to work.
- **Device appears after a delay:** Roku is usually published within a few seconds; Chromecast mDNS
  resolution can take a little longer on congested networks.
- **Device disappears:** it was missing from two consecutive successful scans. Reconnect it to the
  network; it will be rediscovered automatically.

## Testing

Unit tests cover:

- SSDP reply parsing, header normalisation, Roku identification, endpoint/serial extraction.
- SSDP request format (`MAN: "ssdp:discover"`, `ST: roku:ecp`).
- Roku mDNS fallback matching: `_display._tcp` always, `_airplay._tcp` only when it names Roku.
- Roku device-info XML parsing, missing fields, malformed XML, and DOCTYPE/XXE rejection.
- Chromecast TXT parsing and name derivation.
- Unified registry merge, sorting, two-miss removal, reappearance, and per-protocol isolation.
- Repository parallel scans, incremental publication, issue reporting/recovery, and cancellation.

Discovery transports themselves need a real device or a virtual network with multicast support;
there is no emulator shortcut for SSDP/mDNS visibility.

## Privacy and safety

- No DRM circumvention and no attempt to bypass OS capture restrictions.
- No data leaves the local network in this milestone.
- Roku XML parsing disables DOCTYPEs and external entities.
- Device details are read-only; the reachability probe sends no cast or control command.

## License

CastBridge is **source-available**, not open source. It is licensed under the
[PolyForm Noncommercial License 1.0.0](LICENSE), with this Required Notice:

> Required Notice: Copyright David Jakszta (https://github.com/DavidJakszta)

| Use | Allowed? |
| --- | --- |
| The author uses the software commercially | Yes (the author retains all rights) |
| Others use it for personal projects | Yes |
| Others modify the source code | Yes |
| Others redistribute modified versions | Yes, under the same license and notices |
| Others sell the software | No |
| Others use it commercially | No |

Any commercial use by anyone other than the copyright holder requires a separate written license
from the author.

## Next step

Milestone 2 adds `MediaProjection` capture (entire screen, then Android 14 app-window capture) and
the encoder pipeline. Before committing to WebRTC vs. HLS for Roku, validate receiver compatibility:
Chromecast requires a registered Web Receiver application for custom streams, while Roku needs an
HLS/H.264 playback channel with sufficient buffering for low-latency mirroring.
