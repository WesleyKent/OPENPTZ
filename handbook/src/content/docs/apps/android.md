---
title: Android app
description: OPENPTZ Android monitor with Tail 2, VISCA over IP, USB UVC, RTSP and GPU exposure assists. arm64-v8a only.
---

The Android app lives in `Apps/Android/`. The current Android transition build
is branded **OPENPTZ** and focuses on live monitoring rather than media
management. It supports Tail 2 over LAN and brand-independent USB Video Class
inputs. It is **not on Google Play yet**.

## OPENPTZ connections

- **Tail 2:** enter the LAN IP, then select RTSP, NDI or USB UVC preview. REST
  v1 handles camera settings; Sony-compatible VISCA over IP handles PTZ,
  recording and Tail 2 AI tracking.
- **USB Video:** select any device exposing a UVC interface, including composite
  webcams and HDMI/SDI capture cards. No OBSBOT-specific VID/PID is required.
  The source can be video-only or paired with an independent VISCA IP/UDP port.
- **Exposure assists:** every input reaches the same Vulkan/GLES producer
  surface, so LUT, false color, peaking, zebra and scopes work on UVC exactly as
  they do on RTSP.

Android requests the Camera permission before its per-device USB permission.
UVC opening prefers advertised MJPEG 1080p/720p modes, falls back through YUYV
and other advertised sizes, follows Activity pause/resume, and reports hot
unplug as a connection failure instead of keeping a frozen frame.

## How Swift reaches Android

Business logic stays in `OpenPocketViewCore`. Android follows the OpenZCine
pattern, not Skip/SKIE:

1. Portable Swift core (no SwiftUI, UIKit, or Android imports).
2. `OpenPocketCineAndroidFacade` — session + hand-written JNI (`@_cdecl`).
3. Gradle `:app:stageSwiftCore` (`just android-core`) builds
   `aarch64-unknown-linux-android29` and stages `libOpenPocketCineAndroid.so`.
   **arm64-v8a only.** Toolchain pin: Swift **6.3.3**.
4. Kotlin `core-api` wraps JNI. Kotlin does not pack protocol bytes.

Build recipes: [Setup](../guides/setup/). The living JNI/I/O notes:
[`ANDROID.md`](https://github.com/erik-sutton95/OpenPocketCine/blob/main/ANDROID.md).

## Operator surface

Chrome, assists, capture, Operator Setup, and media are meant to match iOS.
The gimbal stick and zoom chip sit together as a cluster in the
trailing-bottom of the picture, same as iOS. Stick pan stays
picture-relative. Stick triple-tap 180 inverts pan at the end of the
rotation (like Mimo). Extra-mirror live view when that 180 lands and
Selfie Flip is off; Flip on skips extra-mirror. The last picture stays
for a couple of frames before that X-flip so the feed does not swap in
place. Invert is the rotate-180 button at settle, not joystick 180.
Reconnect-at-180 seeds TT180 from settled attitude (a 0° stub does not
lock front). D-Log2 cannot zoom while rolling — the chip grays and
tap/pinch toast; idle still hops to D-Log off 1×.
Long-press LUT for the same exposure compensation as iOS (−3…+3 at ½ stop,
input-referred before the cube). Next/prev with LUT on keeps the grade on
the same GLES host (ExoPlayer writes an OES surface; LUT / PEAK / FALSE /
ZEBRA grade in `LiveFeedEffectsSession` like live — TextureView is only the
window). Auto on a clip reads `com.dji.camera.ColorGammaSxS`
from the original take like iOS — not the LRF/XRF sidecar (Rec.709 even
for log). Shot color is stored with the cached clip. A **Proxy** tag means
only the 720p sidecar is on the phone. Storage **Full Resolution Caching**
matches iOS. Share/save is the original camera file — LUT bake
(and Bake exposure) is iOS only.
Exceptions (Frame.io, MetalFX, iOS 26 Liquid Glass, …) are listed in
[`docs/PARITY.md`](https://github.com/erik-sutton95/OpenPocketCine/blob/main/docs/PARITY.md).

Live picture: Vulkan when the device can init it; GLES fallback. HUD liquid
glass is Kyant on API 33+ / ≥4 GB; older or low-RAM devices stay on solid frost.
Present path matches iOS `FeedPresentPolicy` (skip duplicate timestamps, keep
the last frame on freeze, one live-enable write at a time).

Wi-Fi passwords stay in Keystore, not saved-camera JSON. Pairing and live view
need a **physical** Android phone.

## What not to copy from OpenZCine Android

Nikon PTP-IP, AccessorySetupKit, and OCR SSID scanner. OPENPTZ's UVC input is
its own libuvc-to-monitor path, not OpenZCine's camera-specific USB path.
