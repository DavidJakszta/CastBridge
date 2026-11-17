# CastBridge

A universal Android casting app for Chromecast and Roku. This repository implements
**Milestones 1–4**: reliable local-network discovery of Google Cast and Roku devices in one unified,
selectable device list, screen capture with a hardware H.264 encoder, a tokenised local HLS stream,
and playback on both a Chromecast and a Roku TV — without an account, a registration, a backend, or
an internet connection.

## Current status

| Milestone | Status |
| --- | --- |
| 1. Project, architecture, unified device discovery | ✅ Implemented |
| 2. Screen / app-window capture and encoder pipeline | ✅ Implemented (local MP4) |
| 3. Low-latency streaming transport | ✅ Implemented (local HLS + HTTP server) |
| 4. Chromecast and Roku receiver apps | ✅ Implemented (Cast v2 + Roku dev channel) |

The app streams the encoded screen as HLS from a small HTTP server on the phone. The server binds
only to the phone's Wi-Fi address, serves only a per-session token path, and stops with the stream.
A Chromecast plays it through the built-in Default Media Receiver over the local Cast v2 protocol,
and a Roku TV plays it through a side-loaded developer channel launched with ECP. Neither path needs
a developer account or an internet connection.

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
- Streams the same encoder output as a live HLS playlist over a local HTTP server, with a random
  per-session token, byte-range support, and 1–2 second segments.
- Plays the live stream on a **Chromecast** by launching the built-in Default Media Receiver
  (`CC1AD845`) over Cast v2 on port 8009 — no Play Services, no Cast SDK, no registration.
- Plays the live stream on a **Roku TV** through a small side-loaded SceneGraph channel, launched
  with an ECP deep link in Limited mode — no Roku developer account.
- Shows a six-digit **pairing code** on both the phone and the TV so you can confirm the TV is
  playing *your* stream.
- Detects when another phone takes over the Chromecast, and stops the session in the app.
- Switches between TVs directly: picking another TV while casting stops the previous one first, so
  only one screen plays at a time.
- Fills the TV with the phone screen: the picture is scaled to cover the screen and centre-cropped,
  so a portrait phone does not leave large black bars on a landscape TV.
- Shows the stream link in the app with a one-tap **Copy link** action and live segment/size stats.
- On Android 14+ the consent dialog offers the system's **single app** or **entire screen** capture
  mode.
- Runs capture and streaming in a media-projection foreground service, so the session continues
  while you switch apps, with a notification that stops it.
- Material 3 Compose UI with light/dark themes, filters, empty states, and animated radar.

No runtime permissions are required for discovery. The notification permission is requested only
when you start a recording or stream. No backend, account, analytics, or cloud service is used.

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
    ├── :core:streaming (pure Kotlin)
    │     HlsStreamingSession ── token URL, server lifecycle, state
    │     HlsSegmenter ───────── keyframe-aligned segment cutting
    │     MpegTsMuxer ────────── PAT/PMT/PES/PCR transport stream
    │     HlsMediaStore ──────── live playlist window + retention
    │     HlsHttpServer ──────── tokenised local HTTP with byte ranges
    │
    ├── :core:casting (Android library)
    │     CastingController ─── device-independent playback + monitoring
    │     CastV2Client ───────── minimal Cast v2 over TLS (port 8009)
    │     RokuEcpController ─── ECP launch + active-app checks (Limited mode)
    │     PairingCode ───────── six-digit confirmation code from the token
    │
    └── :core:model (pure Kotlin)
          CastDevice, ChromecastDevice, RokuDevice
          DeviceDiscovery, DeviceProbe, DiscoveryState
```

- **MVVM:** `DeviceListViewModel` combines repository state, selection, filters, probe state, capture
  state, and streaming state into one immutable UI state. The Compose screen is stateless and
  receives callbacks.
- **Common device interface:** `CastDevice` lets the UI and future connection layer treat Chromecast
  and Roku uniformly. Protocol-specific data stays in `ChromecastDevice` / `RokuDevice`.
- **Discovery transports stay separate:** each implements `DeviceDiscovery.scan(timeoutMillis)`.
  Adding another TV platform means adding one implementation and one protocol entry.
- **The repository owns merging:** transports never mutate each other's state. The repository runs
  one bounded scan per transport in parallel, merges by stable device ID, tracks misses, and exposes
  a single `StateFlow<DiscoveryState>`.
- **Manual DI:** `AppContainer` is intentionally small. It can be replaced by Hilt/Koin without
  changing feature code.

### Why HLS instead of WebRTC?

WebRTC would need a registered custom Web Receiver, static HTTPS hosting, and an internet round trip
before the TV can show anything, so it cannot work on a LAN without internet. HLS is playable by the
Chromecast's built-in Default Media Receiver (`CC1AD845`) and by a side-loaded Roku channel, needs no
registration, and works entirely on the local network. The trade-off is a few seconds of latency,
which this project accepts.

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
recording session is capped at 30 minutes to bound file size and encoder uptime.

## How live streaming works

1. **Stream mode reuses the encoder.** `ScreenCaptureService` creates an `HlsStreamingSession`
   instead of an MP4 output file. `H264ScreenRecorder` normalises every MediaCodec buffer to Annex-B
   and forwards it to the session on the encoder thread, so the same hardware path feeds both
   recording and streaming.
2. **Codec configuration is carried forward.** The encoder's `csd-0`/`csd-1` buffers (or a
   `BUFFER_FLAG_CODEC_CONFIG` buffer) provide SPS/PPS. They are inserted before every keyframe so
   each segment is independently decodable.
3. **Samples are cut into segments at keyframes.** `HlsSegmenter` only closes a segment on a
   keyframe and when the target duration has elapsed. The default target is 1.5 seconds with a
   two-second keyframe interval, which yields roughly two-second segments and a 3–6 second
   glass-to-glass delay.
4. **`MpegTsMuxer` writes MPEG-TS.** Every segment starts with a PAT and PMT, uses H.264 stream type
   `0x1B`, carries a PCR and random-access indicator on the first packet of each keyframe, and
   maintains per-PID continuity counters. Timestamps are rebased so the first PTS is zero. A silent
   AAC-LC track (stream type `0x0F`, PID `0x0101`) is muxed alongside the video: the Chromecast
   Default Media Receiver rejects a video-only transport stream. The audio frames are generated once
   by the platform AAC encoder and then looped, so no second encoder runs during the session.
5. **`HlsMediaStore` builds the live playlist.** It advertises a four-segment window, keeps eight
   segments for players that are one request behind, advances `EXT-X-MEDIA-SEQUENCE`, and writes
   millisecond `EXTINF` values plus `EXT-X-INDEPENDENT-SEGMENTS`.
6. **`HlsHttpServer` serves the token path.** It binds to the phone's Wi-Fi IPv4 address only and
   answers only `/s/<32-hex-token>/…`, with correct playlist/segment content types, `HEAD`, and byte
   ranges (including suffix ranges). Everything else is a 404.
7. **The UI publishes the link.** `StreamingStore` moves through `Preparing`, `Live`, `Stopping`,
   `Stopped`, and `Failed`. The live state contains the playlist URL and live segment/byte counters;
   the URL can be copied to the clipboard. `Live` is only published after the first complete segment
   exists, so a TV never sees an empty playlist.
8. **The server stops with the session.** Closing the stream writes `EXT-X-ENDLIST`, closes the
   server socket, and releases the encoder and projection.

The URL has the shape
`http://<phone-wifi-ip>:<random-port>/s/<token>/index.m3u8`. The port is chosen by the OS, the token
is 128 random bits generated per session, and neither is reused across sessions. The phone's IP can
change between sessions, so always copy the current link from the app.

Streaming carries video plus a silent AAC track. No microphone, system audio, or app audio is
captured. The encoder is capped at two hours per session.

## How casting works

Both TVs play the *same* tokenised HLS URL. The app never uploads anything: the TV fetches the
playlist and segments directly from the phone.

### Chromecast

1. `CastV2Client` opens a TLS connection to the Chromecast on port 8009. The Cast protocol frames
   each message as a 4-byte big-endian length followed by a protobuf envelope; the client hand-rolls
   the small part of the protobuf schema it needs and sends JSON payloads. There is no Play Services
   dependency and no Cast SDK.
2. It sends `CONNECT` on the virtual-connection namespace, then `LAUNCH` for the built-in **Default
   Media Receiver** (`CC1AD845`). That receiver is already on the device, so nothing is registered or
   uploaded.
3. It sends `LOAD` on the media namespace with the HLS URL. The receiver fetches the playlist and
   segments straight from the phone. A receiver that lost its previous stream can get stuck and keep
   answering `LOAD` with `LOAD_FAILED` even though the stream is fine, so a rejected `LOAD` ends the
   receiver session (`STOP`), launches a fresh one, and tries again — up to three rounds. The app then
   tracks the receiver session that actually started playing.
4. While playing, the client polls `RECEIVER_STATUS` and `MEDIA_STATUS`. If the receiver session ID
   changes, another phone launched an app on the same Chromecast: the app reports that the
   Chromecast was taken over and stops. If the receiver reports `IDLE` with `CANCELLED`, playback was
   stopped on the TV and the app reports that too.

The Cast device presents a self-signed TLS certificate. The client accepts it because the
connection is local and the Cast v2 handshake does not use the certificate for authentication; the
unguessable stream token is what protects the stream.

### Roku

1. The TV runs a tiny SceneGraph developer channel (`roku/CastBridgeChannel`) with a `Video` node.
2. `RokuEcpController` launches it with an ECP deep link:
   `POST http://<tv>:8060/launch/dev?contentId=<url-encoded-hls-url>&mediaType=movie`.
3. `main.brs` passes the deep-link arguments into the scene as a field. The scene builds a
   `ContentNode` with `url` and `streamFormat = "hls"` and assigns it to the `Video` node. (A `Video`
   node has no `url` field of its own.)
4. Stopping sends a second deep link with `contentId=castbridge-stop`, which stops the video.
5. The channel is relaunched from scratch on every ECP launch. If the playlist is not ready yet, the
   channel retries up to three times instead of showing a permanent error.

Only the read-only and launch parts of ECP are used: `query/device-info`, `query/active-app`, and
`launch`. CastBridge never sends keypress, power, volume, or input-switch commands, so the TV can
stay in Roku's **Limited** control mode.

The channel is a developer channel, not a store channel. Developer mode is free and needs no Roku
account or developer registration. See [Install the Roku channel](#install-the-roku-channel).

### Switching TVs

Only one TV plays at a time. While casting, selecting another TV shows a **Switch to …** button next
to **Stop casting**. Switching ends the session on the old TV before the new one is contacted, so the
previous screen does not keep playing. A failure or stop notice belongs to the TV that produced it,
so it is cleared as soon as another TV is selected instead of offering a retry on the wrong device.

### Pairing code

The six-digit code is `SHA-256(stream token) mod 1_000_000`, shown in the app and on the TV. It is
appended to the URL as `?pairing=NNNNNN`, which the HLS server ignores when routing. The code is a
**visual confirmation**, not authentication: knowing it does not grant control. Control is
protected by the per-session stream token, which is 128 random bits and changes on every stream.

### Framing on the TV

The phone screen is usually portrait while the TV is landscape. Roku always preserves the video's
aspect ratio inside the `Video` node, so the channel sizes the node to the stream's aspect ratio and
moves the overflow off-screen: the picture **covers** the TV and is **centre-cropped**, with no
black bars and no distortion. The app passes the stream size as `w`/`h` query parameters, and the
channel falls back to a portrait assumption for older builds.

### Install the Roku channel

The channel is a developer (side-loaded) channel. This is free and needs no Roku account or
developer registration.

1. On the Roku, enable developer mode: press **Home ×3, Up ×2, Right, Left, Right, Left, Right**.
   Accept the prompt and set a developer password. The TV reboots.
2. Note the TV's IP address (**Settings → Network → About**) and make sure the phone and the TV are
   on the same Wi-Fi.
3. Build and install the channel from a computer on the same network:

   ```powershell
   # Windows PowerShell
   .\roku\build-channel.ps1
   .\roku\install-channel.ps1 -DeviceIp 192.168.0.189 -Password <developer-password>
   ```

   ```bash
   # macOS / Linux
   cd roku/CastBridgeChannel && zip -r ../CastBridgeChannel.zip . && cd ..
   curl --digest -u rokudev:<developer-password> -F "mysubmit=Install" \
     -F "archive=@CastBridgeChannel.zip" http://192.168.0.189/plugin_install
   ```

4. Allow mobile control on the TV: **Settings → System → Advanced system settings → Control by
   mobile apps → Network access**. CastBridge only uses the read-only/launch ECP commands, so the TV
   can stay in **Limited** mode.
5. Open CastBridge on the phone, start a stream, pick the Roku, and tap **Play on …**.

Re-run `install-channel.ps1` whenever `roku/CastBridgeChannel` changes.

## Requirements

- Android Studio Ladybug or newer (or a JDK 17 + Android SDK command-line setup)
- JDK 17
- Android SDK Platform 35 and Build-Tools 35.0.0
- Android 8.0 (API 26) or newer
- For Roku: a TV with **developer mode** enabled and the CastBridge developer channel installed
  (see [Install the Roku channel](#install-the-roku-channel)). No Roku account is required.
- For Chromecast: a Cast device on the same Wi-Fi. No Cast developer account or custom receiver is
  required; the built-in Default Media Receiver is used.

## Build and test

```bash
# Debug APK
./gradlew :app:assembleDebug

# JVM unit tests (SSDP/ECP/TXT parsers, registry, scan orchestration)
./gradlew :core:discovery:testDebugUnitTest

# Capture sizing, formatting, naming, and config tests
./gradlew :core:capture:testDebugUnitTest

# MPEG-TS muxer, segmenter, playlist, HTTP server, and live-session tests
./gradlew :core:streaming:test

# Cast v2 framing, pairing code, and Roku ECP tests
./gradlew :core:casting:testDebugUnitTest

# All checks
./gradlew build
```

On Windows use `gradlew.bat`. The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

## Permissions

| Permission | Why |
| --- | --- |
| `INTERNET` | Roku ECP HTTP requests; local HLS server and sockets |
| `ACCESS_NETWORK_STATE` | Network availability |
| `ACCESS_WIFI_STATE` | Multicast lock |
| `CHANGE_WIFI_MULTICAST_STATE` | Receive SSDP traffic on Android |
| `FOREGROUND_SERVICE` | Keep the capture/stream session alive while you use other apps |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Required foreground service type for screen capture (Android 14+) |
| `POST_NOTIFICATIONS` | Show the ongoing recording/stream notification (requested at first session, Android 13+) |

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

## Troubleshooting streaming

- **"Could not start the live stream":** the phone must be connected to Wi-Fi. The server binds to
  the Wi-Fi IPv4 address; a phone on mobile data only cannot serve a LAN URL.
- **The link does not open on the TV or laptop:** confirm both are on the same subnet and that the
  router does not isolate clients (guest networks often do). The URL is valid only while the
  stream is live; stopping the stream closes the port.
- **The link returns 404:** copy the full URL from the app, including the 32-character token after
  `/s/`. A link from an earlier session will not work because the token and port change.
- **Playback stalls or rebuffers:** HLS keeps a four-segment window. If the TV is slow to start,
  keep the stream running for a few seconds; the playlist fills as segments are produced.
- **The picture is black:** protected content (banking apps, DRM video) is blanked by the OS. This is
  expected and is not bypassed by CastBridge.
- **The stream stops when the phone locks:** keep the phone awake while streaming. Android may stop
  projection when the screen is turned off or the consent is revoked from the system UI.
- **No audio:** the stream carries a silent AAC track, not the phone's audio. Capturing app or system
  audio is not part of this milestone.

## Troubleshooting casting

### Chromecast

- **The TV shows "Default Media Receiver" but then an error:** confirm the stream is live and
  playable from a laptop on the same Wi-Fi. The receiver fetches the playlist and the first segment
  directly from the phone; if the phone's server is not reachable, playback fails.
- **The picture never starts:** the receiver needs a moment to fetch the first segment. Keep the
  stream running; the app publishes `Live` only after the first complete segment exists.
- **"Another device took over the Chromecast":** someone launched another app on the same Chromecast
  from a different phone. CastBridge stops instead of fighting for the device. Start the cast again
  from your phone to take it back.
- **The Chromecast is not listed:** mDNS (`_googlecast._tcp`) must reach the phone. Guest networks
  and client isolation block it. The Chromecast and phone must be on the same subnet.
- **The stream is rejected (`LOAD_FAILED` / `idleReason: ERROR`):** the Default Media Receiver
  requires an audio track and accepts H.264 only up to level 4.2. CastBridge muxes a silent AAC track
  and asks the encoder for the lowest sufficient level; if you changed the encoder, verify both.
- **A previously working stream is suddenly refused:** a receiver that was interrupted can stay
  stuck and reject every `LOAD` for a minute or more. CastBridge handles this by restarting the
  receiver app (end the session, launch it again) and loading the stream on the fresh session. If
  even that fails three times, the TV itself is the problem — unplug it or check that the phone's
  stream is still reachable from a laptop.

### Roku

- **"The CastBridge channel is not installed on this Roku":** install the developer channel (see
  [Install the Roku channel](#install-the-roku-channel)). A store channel is not used.
- **"The Roku refused the request":** enable **Control by mobile apps → Network access** on the TV.
- **The TV flashes the home screen when you start casting:** a developer channel is relaunched from
  scratch on every ECP launch. This is normal for a side-loaded channel and does not happen with a
  store-published channel.
- **The picture shows only the pairing code:** the channel is connected but the first segment is not
  ready yet. It retries up to three times and then shows a message. Keep the stream running and tap
  **Play on …** again.
- **The picture is cropped at the top and bottom:** that is the centre-crop framing used to fill a
  landscape TV with a portrait phone screen. Nothing is distorted; the edges of the phone UI are
  simply outside the TV.
- **The Roku is not listed:** confirm the TV is on the same network and "Control by mobile apps" is
  enabled. The app discovers Roku over SSDP (`roku:ecp`) and verifies it with
  `http://<tv>:8060/query/device-info`.

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
- Annex-B/AVCC normalisation, PAT/PMT/PES/PCR layout, CRC, continuity counters, and PTS rebasing.
- Keyframe-only segment cuts, sequence naming, SPS/PPS repetition, and final partial segments.
- Playlist window, media-sequence advance, retention, target duration, and `EXT-X-ENDLIST`.
- Token enforcement, content types, `HEAD`, byte ranges, suffix ranges, and error responses.
- An end-to-end loopback session that serves a real playlist and MPEG-TS segment over HTTP.
- Cast v2 framing and message codec, the pairing-code derivation, and Roku ECP launch/URL encoding.
- Cast v2 load recovery: a rejected `LOAD` restarts the receiver, a stale media status from the
  rejected session is ignored, and recovery gives up after three rounds.

Discovery transports and the encoder need a real device or a virtual network with multicast support;
there is no emulator shortcut for SSDP/mDNS visibility or hardware H.264 encoding. Verify the stream
itself from a laptop on the same Wi-Fi with `curl`, `Invoke-WebRequest`, or VLC before involving a TV.

## Privacy and safety

- No DRM circumvention and no attempt to bypass OS capture restrictions.
- No data leaves the local network. The stream is served only on the phone's Wi-Fi address.
- The HLS URL contains a random per-session token; paths without it return 404. The token is not a
  substitute for network isolation, so do not stream on a network you do not trust.
- **Only your phone controls the TV.** Playback authority comes from the unguessable per-session
  stream token, not from the pairing code. Another phone on the LAN cannot guess the token, and the
  Chromecast reports a receiver-session change if another sender takes over, so CastBridge stops
  instead of fighting for the device. The pairing code is a visual confirmation only.
- **Roku stays in Limited control mode.** CastBridge sends only `query/device-info`,
  `query/active-app`, and `launch`. It never sends keypress, power, volume, or input-switch commands.
  `query/apps` is not used, so no app inventory is read.
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

## Next steps

Milestone 4 is complete: both receivers play the live HLS stream with no account, registration,
backend, or internet connection.

Possible follow-ups:

- Capture the phone's own app or system audio (with Android's audio-capture consent) instead of the
  silent AAC track.
- Lower latency with shorter keyframe intervals, or an LL-HLS-style part playlist.
- Remember the last TV and offer a one-tap "cast to last TV".
- Publish the Roku channel to the store (requires a Roku developer account, unlike the free
  developer-mode install used here).
- Add a Chromecast custom receiver only if a future feature genuinely needs one (it requires a Cast
  developer account and HTTPS hosting, so it is deliberately avoided).

Before shipping any receiver change, validate it on real hardware. Roku TVs must allow mobile
control (*Settings → System → Advanced system settings → Control by mobile apps → Network access*).
