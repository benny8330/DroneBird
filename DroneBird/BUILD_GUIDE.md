# DroneBird — Build & Discovery Guide

A landscape Flappy-Bird-style game piloted by a **DJI Mavic Air 2 Remote
Controller (RC-N1)** read as a **raw USB CDC/VCOM serial device** — no DJI Mobile
SDK, no DJI Fly, no `com.dji:*` dependency, no App Key, no developer.dji.com
account. A full touchscreen fallback kicks in whenever no controller is present.

> **Read this first.** The RC-N1 serial protocol is undocumented. This project is
> built *discovery-first*: the Diagnostic screen is the feature that must work
> before anything else, and every uncertain byte offset / USB ID lives as a named
> constant in `usb/DjiProtocol.kt` with a `// VERIFY VIA DIAGNOSTIC` note. You
> confirm the real values on your own device, then flight control is trustworthy.

---

## 1. Prerequisites

- **Android Studio** Hedgehog (2023.1) or newer.
- **JDK 17** (bundled with recent Android Studio).
- **Android SDK** API 33+ installed (project compiles against API 34, `minSdk 26`).
- An Android phone/tablet that supports **USB host / OTG**.
- A **data-capable USB-C OTG cable** (charge-only cables will not enumerate the
  controller).
- **No DJI developer account, App Key, or DJI SDK is required.**

The first time you open the project, Android Studio will sync Gradle and (if the
wrapper JAR is absent) offer to generate the Gradle wrapper — accept it. From the
command line you can instead run `gradle wrapper` once in the project root to
create `gradlew`.

---

## 2. Find your controller's USB IDs

The defaults in `usb/DjiProtocol.kt` (`VENDOR_ID = 0x2CA3`, `PRODUCT_ID = -1`
meaning "any product for that vendor") match the common DJI VCOM identity, but
**confirm yours**:

- **On a PC:** power on the RC-N1, plug it into the computer, and look for a
  device named **"DJI USB VCOM For Protocol"**.
  - Windows: *Device Manager → Ports / USB*, open *Properties → Details →
    Hardware Ids* and read `VID_xxxx` / `PID_xxxx`.
  - Linux/macOS: `lsusb` / `system_profiler SPUSBDataType` and read the
    Vendor/Product ID.
- **On Android:** install any "USB Device Info" app, or just use **DroneBird's
  Diagnostic screen** — it prints the matched device's VID/PID/product string.

Paste the real values into **both**:
1. `app/src/main/java/com/dronebird/usb/DjiProtocol.kt` → `VENDOR_ID`, `PRODUCT_ID`.
2. `app/src/main/res/xml/device_filter.xml` → `vendor-id` / `product-id`
   (**decimal**; `0x2CA3 == 11427`).

---

## 3. Discovery workflow (do this before expecting flight control)

1. Build and install the app (Part 4/5).
2. Open **DroneBird → DIAGNOSTIC / CALIBRATE**.
3. Power on the RC-N1 and connect it to the phone with a **data-capable USB-C OTG
   cable** (bottom USB-C port on the controller).
4. Accept the **USB permission dialog** when it appears.
5. Confirm the top of the screen shows **CONNECTED** and your device's
   VID/PID/product string.
6. **Move each stick and the dial** one at a time and watch:
   - the **latest raw frame (hex)** line — note which byte columns change;
   - the five **candidate axis bars** — see which bar responds to which stick.
7. If a stick moves a *different* byte than the one currently mapped, open
   `DjiProtocol.kt` and change that axis's `OFFSET_*` constant (and, if the value
   looks 8-bit rather than 11-bit, adjust `VALUE_BYTES` / `VALUE_MASK` /
   `LITTLE_ENDIAN`). Rebuild and re-check.
8. **Calibrate** each axis: pick the axis with the radio buttons, then for each
   of its extremes press **MIN**, **CENTRE**, **MAX** while holding the stick at
   that position. Values persist to `SharedPreferences`; `FrameParser` uses them
   to normalise to `-1f..+1f` (sticks) and `0f..1f` (dial).
9. Only once the bars track the sticks cleanly and calibration is done is the
   in-game flight control reliable.

> If the hex line never updates, the serial line parameters are probably wrong —
> see Troubleshooting.

---

## 4. Build the APK

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

---

## 5. Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

…or just press **Run ▶** in Android Studio with the device connected.

---

## 6. Controls

| Source (decoded)                | In-game action                                             |
|---------------------------------|------------------------------------------------------------|
| Left stick — Y                  | Drone vertical flight (primary)                            |
| Left stick — X                  | Speed boost (right) / brake (left): world scroll ±20%      |
| Top control dial                | Fine global game-speed / difficulty trim, applied live     |
| Right stick — quick X flick     | **Dodge**: 0.4 s invincibility through one obstacle; 5 s cooldown |
| Right stick — Y                 | Cosmetic drone tilt only (no mechanic)                     |

No controller **buttons** are read — the physical buttons (Fn, Pause/RTH,
shutter, record, mode switch) are not exposed in a documented way, so every
discrete controller action comes from stick gestures and the dial only.

**Touch fallback** (auto-engages when no controller is detected, or if it drops
mid-game):
- Tap anywhere (right zone) = upward impulse (classic Flappy); hold = sustained thrust.
- Left virtual joystick = vertical + speed boost/brake (mirrors the left stick).
- Swipe right on the right half = dodge.
- Pause button, top-right corner.

When the controller (re)connects mid-game the touch HUD gives way to controller
input with a toast — *"RC CONTROLLER CONNECTED — Switching to RC mode."* — and the
reverse on disconnect. A green **RC CONNECTED** badge shows top-left while on the
controller.

---

## 7. Troubleshooting

| Symptom | Likely cause / fix |
|---------|--------------------|
| Device not listed at all | OTG unsupported on the phone, or a **charge-only** cable. Use a data-capable OTG cable; test the cable with another USB device. |
| Permission dialog never appears | Re-plug the controller; confirm the `USB_DEVICE_ATTACHED` intent-filter + `device_filter.xml` are intact; grant permission when prompted. |
| **No bytes** in the Diagnostic hex line | Wrong serial params. In `DjiProtocol.kt` try a different `BAUD_RATE`; if the device is not true CDC, set `USE_CDC_DRIVER`/framing accordingly and re-test. Confirm the controller is **powered on**. |
| Bytes appear but no axis bar moves | Wrong `OFFSET_*`. Watch which hex column changes with each stick and set the offset to that index. |
| Values jumpy / drifting | Run **calibration** (MIN/CENTRE/MAX per axis). |
| Axis moves the wrong way | Swap the captured MIN and MAX for that axis during calibration. |
| Worked before, now dead | Controller firmware may have changed the framing — **re-run discovery** (offsets + calibration). |

---

## 8. Project layout

```
DroneBird/
├── settings.gradle, build.gradle, gradle.properties
├── BUILD_GUIDE.md
└── app/
    ├── build.gradle, proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/dronebird/
        │   ├── MainActivity / ControllerDetectionActivity / DiagnosticActivity
        │   ├── GameActivity / GameView / GameEngine / ObstacleManager
        │   ├── ScoreManager / SpriteRenderer / SoundManager
        │   ├── usb/  (UsbSerialController, DjiProtocol, FrameParser, ControllerFrame)
        │   └── input/ (InputRouter, GestureDetector, GameInput)
        └── res/ (layout, values, xml/device_filter.xml)
```

---

## 9. Future work (Phase 2 — intentionally NOT in this MVP)

Shield power-up · slow-motion · ×2 score multiplier · parallax multi-layer sky ·
looping motor-hum audio · screenshot-to-gallery · on-screen power-up buttons
beyond Pause.

---

## 10. File summary

| File | Lines | Purpose |
|------|------:|---------|
| settings.gradle | 23 | Modules + repositories (google, mavenCentral, JitPack). No DJI maven. |
| build.gradle (root) | 11 | AGP 8.5.2 + Kotlin 1.9.24 plugin versions. |
| gradle.properties | 5 | AndroidX + JVM settings. |
| app/build.gradle | 49 | App module: SDKs, viewBinding, usb-serial + coroutines deps. |
| app/proguard-rules.pro | 2 | Keep usb-serial driver classes. |
| AndroidManifest.xml | 56 | USB-host feature, activities, USB attach filter, landscape lock. |
| usb/ControllerFrame.kt | 47 | Axis enum + normalised stick/dial sample. |
| usb/DjiProtocol.kt | 149 | **CONFIG**: VID/PID, serial params, offsets, framing — all `VERIFY VIA DIAGNOSTIC`. |
| usb/FrameParser.kt | 206 | Raw bytes → ControllerFrame; calibration store + normalisation. |
| usb/UsbSerialController.kt | 356 | Device find, permission, open, coroutine read loop, state flow. |
| input/GameInput.kt | 48 | Input contract the engine consumes. |
| input/GestureDetector.kt | 63 | Stick-flick → discrete dodge event, debounced. |
| input/InputRouter.kt | 213 | Controller vs touch auto-switch; virtual joystick + swipe. |
| DiagnosticActivity.kt | 158 | Byte inspector: hex view, axis bars, calibration flow. |
| ControllerDetectionActivity.kt | 100 | Live USB connect/disconnect UI. |
| MainActivity.kt | 32 | Entry menu → play / detection / diagnostic. |
| GameEngine.kt | 233 | State machine, physics, scoring, dodge cooldown. |
| ObstacleManager.kt | 99 | Pipe spawn/move/recycle + AABB collision. |
| ScoreManager.kt | 43 | Current + high score (SharedPreferences). |
| SpriteRenderer.kt | 270 | Path-based drone, pipes, HUD, overlays, button rects. |
| GameView.kt | 197 | SurfaceView + 60 FPS loop; touch routing; audio cues. |
| GameActivity.kt | 90 | Landscape host; wires USB + input + sound. |
| SoundManager.kt | 99 | Programmatic blip + crash via AudioTrack (no assets). |
| res/layout/activity_main.xml | 46 | Menu layout. |
| res/layout/activity_detection.xml | 59 | Detection layout. |
| res/layout/activity_diagnostic.xml | 184 | Diagnostic layout (hex, bars, calibration). |
| res/values/strings.xml | 50 | All user-facing strings. |
| res/values/colors.xml | 31 | All colours. |
| res/values/styles.xml | 45 | Diagnostic row/button styles. |
| res/values/themes.xml | 10 | App theme. |
| res/xml/device_filter.xml | 15 | USB attach filter (placeholder VID/PID). |

*Line counts are approximate and will drift as you edit the `VERIFY VIA
DIAGNOSTIC` constants.*
