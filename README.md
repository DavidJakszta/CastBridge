# CastBridge

A universal Android casting app for Chromecast and Roku. This repository currently implements
**Milestones 1–2**: reliable local-network discovery of Google Cast and Roku devices in one
unified, selectable device list, plus screen capture and a hardware H.264 encoder that records to
an MP4.

## Current status

| Milestone | Status |
| --- | --- |
| 1. Project, architecture, unified device discovery | ✅ Implemented |
| 2. Screen / app-window capture and encoder pipeline | ✅ Implemented (local MP4) |
| 3. Low-latency streaming transport | ⏳ Next |
| 4. Chromecast and Roku receiver apps | ⏳ Planned |

The app deliberately does **not** include a Cast SDK session or a streaming transport yet.
Discovery, device identity, stale-device handling, reachability checks, screen capture, and H.264
encoding are in place so the streaming layer can be built on a tested foundation.

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
- Captures the screen through `MediaProjection` and encodes H.264 with a hardware `MediaCodec`
  surface, recording to an MP4 for validation.
- On Android 14+ the consent dialog offers the system's **single app** or **entire screen** capture
  mode.
- Runs capture in a media-projection foreground service, so recording continues while you switch
  apps, with a notification that stops the session.
- Material 3 Compose UI with light/dark themes, filters, empty states, and animated radar.

No runtime permissions are required for discovery. The notification permission is requested only
when you start a recording. No backend, account, analytics, or cloud service is used.

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
    ├── :core:capture (Android library)
    │     ScreenCaptureService ── media-projection foreground service
    │     H264ScreenRecorder ─── MediaCodec surface encoder + MediaMuxer
    │     CaptureStore ───────── process-wide capture state
    │
    └── :core:model (pure Kotlin)
          CastDevice, ChromecastDevice, RokuDevice
          DeviceDiscovery, DeviceProbe, DiscoveryState
```

- **MVVM:** `DeviceListViewModel` combines repository state, selection, filters, probe state, and
  capture state into one immutable UI state. The Compose screen is stateless and receives callbacks.
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

## How screen capture works

1. The UI asks Android for capture consent with `MediaProjectionManager`. On Android 14+ the system
   dialog lets the user share the entire screen or a single app window.
2. The consent result is handed to `ScreenCaptureService`, a foreground service of type
   `mediaProjection`. Android 14+ requires the service to be in the foreground before
   `getMediaProjection()` is called, so the notification is posted first.
3. `CaptureConfigFactory` derives encoder settings from the current display: the long edge is capped
   at 1920 px, the aspect ratio is preserved, both dimensions are rounded to even numbers, and the
   bitrate is scaled from the pixel count (1.5–12 Mbps). The size is fixed for the session because a
   surface-backed encoder cannot change resolution without a restart.
4. `H264ScreenRecorder` configures a hardware H.264 `MediaCodec` encoder with
   `COLOR_FormatSurface`, creates its input surface, and mirrors the display into it with a
   `VirtualDisplay`. Frames are encoded on the GPU/encoder path and never copied through the CPU.
5. Encoded access units are muxed into an MP4 with `MediaMuxer` in the app-specific
   `Movies/captures` directory, so no storage permission is needed.
6. Stopping the session (UI button, notification action, or the system stop control) signals
   end-of-stream, drains the remaining frames, closes the container, and reads the duration and
   dimensions back with `MediaMetadataRetriever`.

The capture pipeline publishes live stats (elapsed time, resolution, bytes) through `CaptureStore`,
a process-wide `StateFlow`, so the UI and the service stay in sync across activity recreation. A
session is capped at 30 minutes to bound file size and encoder uptime.

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

# Capture sizing, formatting, naming, and config tests
./gradlew :core:capture:testDebugUnitTest

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
| `FOREGROUND_SERVICE` | Keep the capture session alive while you use other apps |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Required foreground service type for screen capture (Android 14+) |
| `POST_NOTIFICATIONS` | Show the ongoing recording notification (requested at first recording, Android 13+) |

All discovery permissions are normal permissions and are granted at install time. Screen capture is
authorized by the Android system consent dialog, not a manifest permission; the app can never start
capturing without that explicit approval, and the system's own stop control remains available.
`usesCleartextTraffic` is enabled because Roku ECP is a local HTTP API on port 8060. No internet
server is contacted.

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

## Troubleshooting capture

- **The consent dialog does not appear:** unlock the phone and keep CastBridge in the foreground
  while tapping *Start recording*. Android will not show a projection prompt for a background app.
- **"Screen capture could not be started":** some devices restrict the media-projection foreground
  service (battery savers, device-admin policies, or a work profile). Check the notification
  permission and try again from the main user profile.
- **The recording is black:** protected content (banking apps, DRM video) is blanked by the OS. That
  is expected and is not bypassed by CastBridge.
- **No file appears:** recordings are stored in the app-specific
  `Android/data/dev.castbridge.app/files/Movies/captures/` directory. Uninstalling the app removes
  them. The Completed card shows the exact file name, duration, resolution, and size.
- **Recording stops early:** a session is limited to 30 minutes, and Android stops projection if the
  consent is revoked from the system UI or the quick-settings tile.

## Testing

Unit tests cover:

- SSDP reply parsing, header normalisation, Roku identification, endpoint/serial extraction.
- SSDP request format (`MAN: "ssdp:discover"`, `ST: roku:ecp`).
- Roku mDNS fallback matching: `_display._tcp` always, `_airplay._tcp` only when it names Roku.
- Roku device-info XML parsing, missing fields, malformed XML, and DOCTYPE/XXE rejection.
- Chromecast TXT parsing and name derivation.
- Unified registry merge, sorting, two-miss removal, reappearance, and per-protocol isolation.
- Repository parallel scans, incremental publication, issue reporting/recovery, and cancellation.
- Capture size scaling, even-dimension rounding, bitrate clamping, and frame-rate selection.
- Capture config validation, timestamped file naming, and locale-independent stat formatting.

Discovery transports and the encoder need a real device or a virtual network with multicast support;
there is no emulator shortcut for SSDP/mDNS visibility or hardware H.264 encoding.

## Privacy and safety

- No DRM circumvention and no attempt to bypass OS capture restrictions.
- No data leaves the local network in this milestone.
- Recordings stay in app-specific storage on the phone; nothing is uploaded.
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

Milestone 3 adds the low-latency streaming transport on top of the encoder surface:

- Reuse the H.264 `MediaCodec` output as a live stream instead of muxing it to a file.
- Evaluate WebRTC for Chromecast (custom Web Receiver) and an HLS or progressive-HTTP path for Roku.
- Keep the receiver side minimal and local: no account, no cloud relay, no DRM circumvention.

Before committing to a transport, validate receiver compatibility on real hardware: Chromecast
requires a registered Web Receiver application for custom streams, while Roku needs a playback
channel that can sustain low-latency H.264. Roku TVs must also allow mobile control
(*Settings → System → Advanced system settings → Control by mobile apps → Network access*).
