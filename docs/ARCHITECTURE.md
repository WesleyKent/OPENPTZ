# Architecture

OpenPocketCine is a shared Swift business/protocol core with native platform shells.

| Layer | Path | Purpose |
| --- | --- | --- |
| **Shared core** | `Sources/OpenPocketViewCore/` | DUML, commands, status, LUTs, layout policy. **Portable** Foundation. |
| **iOS app** | `ios/OpenPocketCine/` | SwiftUI **shell**, CoreBluetooth, NEHotspotConfiguration, sockets, VideoToolbox/Metal. Teardown: [live-session](live-session.md). |
| **Android app** | `Apps/Android/app/` | Compose **shell**. Live picture and HUD I/O: [`ANDROID.md`](../ANDROID.md). Operator-visible behavior: [parity](PARITY.md). Teardown: [live-session](live-session.md). |
| **Android facade** | `Sources/OpenPocketCineAndroidFacade/` | Swift session and JNI boundary |
| **Tests** | `Tests/OpenPocketViewCoreTests/` | Swift Testing suite for the portable core |

HUD glyphs that both shells share are vendored Lucide SVGs (`OpcIcon` on iOS and Android).
Regenerate Android VectorDrawables with `python3 scripts/vendor-lucide-icons.py`. Do not add a JS
runtime. Custom keepers: zebra stripes, the Frame.io F mark, and the battery outline pill.
SF Symbols / Material stay only on controls this catalog has not replaced yet.

The iOS Xcode project is generated: `cd ios && xcodegen generate`.

## Connection spine

### OPENPTZ Android PTZ and USB-video paths

The Android transition build has brand-neutral network-control and video-input
paths that do not use the Pocket spine:

1. The operator selects the Tail 2 profile or the generic USB Video profile.
2. Tail 2 uses official REST v1 for camera settings and Sony-compatible VISCA
   over IP (UDP 52381) for low-latency PTZ, record and AI commands. The VISCA
   transport owns the 8-byte network envelope, sequence reset, ACK/completion
   matching and camera error replies.
3. Tail 2 REST selects the preview output. RTSP is decoded by Media3 into the existing
   Vulkan/GLES producer surface. The standard NDI Android SDK can discover Tail
   2 but cannot decode its HX3 stream, so the Android shell resolves Tail 2 NDI
   requests to RTSP and keeps false-color, zebra, peaking and scopes on the same
   GPU path. Native Tail 2 NDI remains an Advanced-SDK + MediaCodec seam.
4. USB Video enumerates every attached device that exposes USB Video Class
   interface `0x0E`, including composite webcams and HDMI/SDI capture cards. It
   asks Android for per-device permission, negotiates advertised MJPEG/YUYV
   modes with fallbacks, and writes libuvc output directly into the GLES OES
   producer `Surface` (the Vulkan producer is a MediaCodec `PRIVATE`
   `ImageReader`). LUT, false color, peaking, zebra and scopes therefore remain
   the existing GPU pipeline and do not require a second implementation.
5. Generic USB Video may be video-only or paired with an independent VISCA over
   IP address/port. USB hot-unplug fails the live session cleanly and returns the
   operator to the connection surface.

`NdiReceiverBackend` owns the NDI request independently from the screen. It
starts native discovery only after the Vulkan window exists, matches the Tail 2
by IP/name, reconnects with the receiver runtime, and reports the first frame
and raster size back to the monitor session. The proprietary runtime stays in
`.local/` and is never committed.

The legacy spine below remains present only for the iOS shell and the dormant
Android Pocket implementation during the transition.

1. BLE scan and pair (GATT FFF0).
2. Read camera Wi-Fi credentials.
3. Join SoftAP `192.168.2.1`. On-path only after DHCP `192.168.2.2…254`
   (`CameraSoftAP.isAssociatedIPv4`).
4. UDP DUML to `192.168.2.1:9004` on an **ephemeral local port**. Camera 9004 is
   the remote only. Bind and ACK details: [live-session](live-session.md).
5. Enable live view **enable-once** after path + display are ready. Arm pktType
   `0x02` ingest on that write. Recover policy: [watchdog](feed-watchdog.md).
6. Pocket 4 / 4 Pro: HEVC 720p. Nano: AVC/H.264 High 720p. Decoder setup and
   NAL latch: [live-session](live-session.md).

### Policy in Swift, I/O in the shells

Business/protocol logic lives in `Sources/OpenPocketViewCore/` (the Swift-for-Android
SDK). Both apps must call the same state machines:

| Policy | Core type | Shell I/O |
| --- | --- | --- |
| SoftAP addressing, path-ready, handshake rebind, foreground recover | `CameraSoftAP` | iOS `WiFiJoiner` / `NEHotspotConfiguration`; Android `CameraApJoiner` / `WifiNetworkSpecifier` |
| Stall, GOP-reset grace, AF-C grace, enable-once, rebuild ladder | `FeedWatchdog` | iOS `CameraSession.applyFeedWatchdog`; Android JNI `feedWatchdogCreate/Tick` — not a second Kotlin clone |
| Present hygiene (skip-dup, freeze ≠ flush, drawable gate, one enable) | `FeedPresentPolicy` | iOS `CIFeedView` / `PlaybackFeedSession`; Android `LiveFeedEffectsSession` (Kotlin lockstep + tests) |
| Clip shot color (`ColorGammaSxS`) | `ClipColorProfile` | iOS `ClipColorProfileIO`; Android `ClipColorProfile.kt` (Kotlin lockstep). Original take only — LRF/XRF is Rec.709 even for log. Shells read the `moov` tail (2 MiB Range when the 4K file is not cached) and store it in the media cache `color.json`. |
| Gimbal cluster (stick + zoom + reserved controls) | `GimbalCluster` | iOS `LiveMonitorLayout` / portrait chrome; Android `GimbalCluster.kt` lockstep. |
| Screen-relative gimbal stick | `GimbalStickMapping` (invert pan on rotate-180 at settle, not joystick 180; extra-mirror = TT180 && Selfie Flip off; MIRROR assist XORs) | iOS `updateGimbalStick`; Android JNI `gimbalStickEncode` + Kotlin fallback |
| Drop storm, bounded reconnect | `SessionRecovery` | platform BLE rescan + SoftAP rejoin |
| Link score → 0–4 bars | `CameraLinkHealth` + `LinkSignalBars` | top-bar FPS chip (delivery health, not RSSI) |
| Camera SET mailbox, retransmit, settle | `CameraSetMailbox` | iOS `fireCamera`; Android JNI |

Platform shells own sockets, BLE, SoftAP join, permissions, lifecycle, rendering,
storage, and UI. Do not import SwiftUI, UIKit, Android, or Compose into the core.

See [`live-session.md`](live-session.md), [`feed-watchdog.md`](feed-watchdog.md),
[`PARITY.md`](PARITY.md), [`PERFORMANCE.md`](PERFORMANCE.md), [`UX.md`](UX.md),
and [`ANDROID.md`](../ANDROID.md).

See the [protocol handbook](https://openpocketcine.app/docs/) for wire-level detail
(Markdown source in `handbook/src/content/docs/`; stub at [`protocol-notes.md`](protocol-notes.md)).
