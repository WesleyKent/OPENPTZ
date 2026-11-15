# OPENPTZ

OPENPTZ is an open-source Android field monitor and remote-control app for PTZ
cameras. The current target is OBSBOT Tail 2, with independent video and control
paths so the monitoring tools can also be used with generic cameras and capture
cards.

> This project is under active development. Network and USB behavior varies by
> camera, Android device, cable, and firmware. Verify recording and PTZ commands
> on the camera before relying on them in production.

## What is implemented

- OBSBOT Tail 2 REST control integration, including record and AI work-mode paths
- Low-latency RTSP preview with latency recovery
- Optional native NDI receiver integration
- Generic USB Video Class input for UVC cameras and HDMI/SDI capture cards
- VISCA over IP control, usable independently from the selected video input
- GPU monitoring assists: LUT preview, false color, zebras, focus peaking,
  framing guides, histogram, waveform, vectorscope, and RGB parade
- Android connection flow for Tail 2, network video, USB video, and optional
  VISCA control

Media-management features are not part of the current OPENPTZ product scope.

## Connection model

| Video | Control | Intended use |
| --- | --- | --- |
| RTSP | Tail 2 REST | Tail 2 network monitoring and camera control |
| NDI | Tail 2 REST | Tail 2 NDI monitoring when a compatible NDI runtime is present |
| USB UVC | Tail 2 REST | Direct USB video with Tail 2 network control |
| USB UVC | VISCA over IP | Generic PTZ camera or capture-card workflow |
| USB UVC | None | Universal field monitor for cameras and capture cards |

## Android requirements

- Android 10 / API 29 or later
- `arm64-v8a`
- USB host support for UVC input
- JDK 17
- Android SDK 36 and Build Tools 36.0.0
- Android NDK 28.2.13676358
- CMake 3.22.1

## Build

```bash
cd Apps/Android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The installable debug APK is generated at:

```text
Apps/Android/app/build/outputs/apk/debug/app-debug.apk
```

### NDI SDK

The proprietary NDI SDK is not redistributed by this repository. After accepting
the NDI SDK license, place the official Android SDK at:

```text
.local/NDI SDK for Android/
```

The Gradle build detects `lib/arm64-v8a/libndi.so` automatically. Without the
local SDK, OPENPTZ still builds with RTSP and USB UVC support, while NDI is
reported as unavailable at runtime.

## Project layout

| Path | Purpose |
| --- | --- |
| `Apps/Android/` | Active OPENPTZ Android application |
| `Apps/Android/app/src/main/kotlin/.../tail2/` | Tail 2 REST and preview integration |
| `Apps/Android/app/src/main/kotlin/.../ndi/` | Native NDI receiver bridge |
| `Apps/Android/app/src/main/kotlin/.../uvc/` | Generic USB UVC video input |
| `Apps/Android/app/src/main/kotlin/.../visca/` | VISCA over IP transport and commands |
| `Apps/Android/app/src/main/kotlin/.../assists/` | Monitoring and exposure assists |
| `Sources/`, `ios/`, `site/`, `handbook/` | Upstream OpenPocketCine sources retained for attribution and reference |

## Current verification

- Android debug APK builds successfully with SDK 36
- 416 JVM unit tests pass
- APK packaging includes the ARM64 UVC runtime and, when installed locally, the
  official NDI runtime
- Tail 2, NDI, UVC, and VISCA still require hardware testing for each supported
  Android device and camera firmware combination

## Licensing and attribution

OPENPTZ is distributed under the Apache License 2.0. See [LICENSE](LICENSE),
[NOTICE](NOTICE), and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

This project is derived from
[OpenPocketCine](https://github.com/erik-sutton95/OpenPocketCine) by Erik Sutton.
The active Android product direction, Tail 2 integration, VISCA over IP, UVC
input, and OPENPTZ branding are maintained in this derivative project.

UVC support uses
[UVCAndroid](https://github.com/shiyinghan/UVCAndroid), itself based on the
UVCCamera project, under Apache License 2.0.

OBSBOT, Tail 2, NDI, Android, and other product names belong to their respective
owners. This project is not endorsed by or affiliated with those vendors.

## Security and repository hygiene

Do not commit camera credentials, packet captures, private signing keys, the NDI
SDK, local Android SDKs, or proprietary vendor material. The repository ignore
rules exclude `.local/`, build outputs, signing material, and capture files.
