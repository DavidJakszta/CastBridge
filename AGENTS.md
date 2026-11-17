# AGENTS.md

Guidance for anyone (human or AI agent) working in this repository.

## Hardware testing: only two devices

**Never test on any TV or cast device other than these two:**

| Device | Type | Address | Notes |
| --- | --- | --- | --- |
| `3 Gachinhos` | Roku 50RP630 | `192.168.0.189` | Dev mode, password `abc123` |
| `Schlafzimmer` | Chromecast | `192.168.0.57` | |

The other devices that show up during discovery (`Cat vision` at `192.168.0.133`,
`50" TCL Roku TV` at `192.168.0.136`, and the Roku at `192.168.0.147`) belong to other people
or other rooms. Do **not** cast to them, launch channels on them, send ECP requests to them, or
use them for verification — not even for a "quick comparison" or to see whether newer firmware
behaves differently.

If `Schlafzimmer` or `3 Gachinhos` cannot reproduce a problem, say so and ask the owner of the
device before touching anything else.

## Phone

The only test phone is the attached Samsung `SM-A576B` (serial `RZGL7066YVZ`).

## Streaming facts worth knowing

- The live stream is produced by a `MediaProjection` virtual display feeding a surface-backed
  H.264 encoder, muxed into MPEG-TS by `MpegTsMuxer`, cut into HLS segments by `HlsSegmenter`,
  and served by `HlsHttpServer` from the phone.
- Chromecast playback uses the built-in Default Media Receiver (`CC1AD845`) over Cast v2 on
  TLS 8009. Roku playback uses the side-loaded dev channel launched by ECP deep link.
- No registration, account, backend, or internet connection is involved in either path.
