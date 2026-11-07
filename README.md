# Hand Mouse for XREAL One Pro

**Control a cursor on an external desktop display with your bare hands, using the XREAL One Pro's Eye camera.**

Hand Mouse turns the RGB "Eye" camera built into the [XREAL One Pro](https://www.xreal.com/) glasses into a hands-free pointing device. It streams the camera over USB, runs on-device hand tracking (Google MediaPipe), and moves a cursor — and injects real taps, drags, scrolls and media controls — onto your phone's external desktop display. Pinch to click. Hold to drag. Wave your palm to skip a video. Say "back" to go back.

> [!IMPORTANT]
> **Not affiliated with XREAL.** This is an independent, unofficial hobby project. "XREAL" and "Nreal" are trademarks of their respective owner and are used here only to describe hardware compatibility. This project is not endorsed by or sponsored by XREAL. Use at your own risk — you are interfacing with your own hardware.

---

## Table of contents

- [What you need](#what-you-need)
- [How it works](#how-it-works)
- [Building from source](#building-from-source)
- [Installing on your phone](#installing-on-your-phone)
- [Permissions the app needs](#permissions-the-app-needs)
- [Controls — gestures & voice](#controls--gestures--voice)
- [First-run setup](#first-run-setup)
- [Troubleshooting & QA](#troubleshooting--qa)
- [Project layout](#project-layout)
- [Provenance & licensing](#provenance--licensing)

---

## What you need

**Hardware**

- **XREAL One Pro** glasses (the model with the RGB Eye camera).
- A phone that supports an external desktop display, such as a **Samsung phone with DeX**. **Pixel 10 Pro support is experimental** and requires Android's desktop mode on a wired external display; physical-device validation is still required. A plain mirrored phone screen has no separate desktop display for the cursor overlay.
- A **USB-C** connection between the glasses and the phone.

**Software / to build**

- **Android 14 or newer** on the phone (`minSdk = 34`, `targetSdk = 35`). Pixel desktop mode requires a compatible Android version/build with desktop mode enabled.
- **arm64-v8a** device (the app ships `arm64-v8a` only).
- To build: **JDK 17** and the **Android SDK (API 35)**. The included Gradle wrapper handles the rest.

On Pixel, the app currently relies on Android reporting the connected display in the
`DISPLAY_CATEGORY_PRESENTATION` category. Pixel 10 Pro compatibility, USB camera capture and
gesture injection on the external display have not yet been verified on hardware. If the
display is not detected, collect the Android display and application logs before changing the
selection logic; selecting an arbitrary secondary display could direct gestures to the wrong screen.

---

## How it works

The app runs entirely in one process, organized around **two services**:

- **`EyeCaptureService`** — a foreground service that owns the capture pipeline. It performs the Kotlin USB handshake that turns on the Eye camera, reads the video stream, decodes it, and runs hand tracking.
- **`HandMouseAccessibilityService`** — an [AccessibilityService](https://developer.android.com/guide/topics/ui/accessibility/service) that owns the on-screen cursor overlay and injects the actual taps/gestures onto the external desktop display. It also runs a watchdog that revives capture if it dies.

End to end: the glasses are activated over a pure-Kotlin USB control channel (`WaitPilotReady → GetUsbConfigAll → SetUsbConfigAll(uvc0=1) → GetCameraStatus`), which re-enumerates the device and exposes its UVC video interface. Frames are read over a bulk endpoint, decoded (MJPEG or HEVC), converted to RGBA (downscaled to 768×576 via libyuv), and fed to MediaPipe HandLandmarker (LIVE_STREAM / GPU). The resulting 21 hand landmarks drive a cursor pipeline (relative "trackpad" mapping → One-Euro smoothing → radial dead-zone) and a set of gesture detectors, which become real touch events via `dispatchGesture`.

```
XREAL One Pro (USB-C)
   │  control channel (interrupt HID)           bulk UVC endpoint
   ▼                                                 ▼
GlassesConnection ──activate uvc0──► re-enumerate ──► UvcCameraHelper (UVC-Stream thread)
 (glasses/*: pure-Kotlin protocol)                    │ access units
                                                      ▼
                               ┌── MJPEG ── MjpegDecoder ──► Bitmap ──┐
                               └── HEVC  ── HevcDecoder  ──► YUV Image │
                                                     │ FrameConverter  │
                                                     ▼ (libyuv 768×576)│
                                                   RGBA ───────────────┤
                                                                       ▼
                                          HandTracker (MediaPipe LIVE_STREAM/GPU)
                                                     │ 21 landmarks
                                                     ▼
                 CursorPipeline (RelativeCursorMapper → OneEuroFilter → DeadZone)
                  │                    │                       │
                  ▼                    ▼                       ▼
           CursorOverlay      ClickDragStateMachine     gesture detectors
          (external display)               │             (fist / palm / thumb / V)
                                      ▼                       │
                               GestureInjector ◄──────────────┘
                           (dispatchGesture on the external displayId)
```

| Layer | Package | Key classes |
|---|---|---|
| Glasses control | `glasses/` | `GlassesConnection`, `GlassesTransport`, `GlassesCommands`, `KotlinGlassesProtocol`, `UsbConfigCodec` |
| Capture / decode | `capture/` | `UvcCameraHelper`, `MjpegDecoder`, `HevcDecoder`, `FrameAssembler` |
| Hand tracking | `tracking/` | `HandTracker`, `FrameConverter`, `CursorPipeline`, `RelativeCursorMapper`, `OneEuroFilter`, gesture detectors |
| Input injection | `input/` | `GestureInjector`, `MediaGestureController`, `VoiceCommandController`, `AppLauncher` |
| Cursor overlay | `overlay/` | `CursorOverlay`, `CursorView`, `DexDisplayMonitor`, `DexDisplaySelector` |
| Services | `service/` | `EyeCaptureService`, `HandMouseAccessibilityService` |

---

## Building from source

```bash
git clone https://github.com/nudou350/Xreal-tools.git
cd Xreal-tools/xreal-hand-mouse

# Point JAVA_HOME at a JDK 17. Android Studio's bundled JBR works well, e.g. on Windows:
#   $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'

# Build the debug APK:
./gradlew assembleDebug          # Windows: .\gradlew.bat assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

Run the unit tests with:

```bash
./gradlew test
```

> A `release` build is also configured. It runs a `verifyNoSpikeNativeLibsInRelease` check that fails the build if the debug-only USB-capture native library (`libioctltap.so`, used for reverse-engineering only) ever leaks into a release APK — release artifacts are guaranteed free of it.

---

## Installing on your phone

There is no prebuilt release yet, so install the APK you built. With USB debugging enabled on the phone:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or over Wi-Fi (pair once, then):

```bash
adb connect <DEVICE-IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Quick setup via adb

If you have adb handy, you can grant **most** permissions from your computer instead of tapping through every settings screen. Run these once after installing (the commands are copy-paste safe on both bash and PowerShell):

```bash
# Runtime permissions
adb shell pm grant com.raphael.handmouse android.permission.CAMERA
adb shell pm grant com.raphael.handmouse android.permission.RECORD_AUDIO
adb shell pm grant com.raphael.handmouse android.permission.POST_NOTIFICATIONS

# Exempt from battery optimization (Doze whitelist) — helps against background kills
adb shell dumpsys deviceidle whitelist +com.raphael.handmouse

# Enable the accessibility service (this is what draws the cursor and injects taps)
adb shell settings put secure enabled_accessibility_services com.raphael.handmouse/com.raphael.handmouse.service.HandMouseAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

> [!WARNING]
> The accessibility command **replaces** the list of currently-enabled accessibility services. If you already use others (e.g. TalkBack), re-enable them in Settings afterwards, or append this one to the existing value with a `:` separator.

**What adb can't do:** the **two USB permission prompts** cannot be pre-granted — Android's per-device USB permission has no adb equivalent, so you must accept both on the phone when you connect the glasses (accept them quickly; the handshake times out). Samsung's deeper anti-kill toggles ("apps that never sleep") also aren't fully exposed to adb and stay manual.

Then open **Hand Mouse** on the phone, connect the glasses, and accept the two USB prompts.

---

## Permissions the app needs

These are Android-level requirements and don't change with the UI. The setup wizard walks you through them in order — or grant most of them at once with [Quick setup via adb](#quick-setup-via-adb).

| Permission | Required? | Why |
|---|---|---|
| **Camera** | Yes | Not used to record — it's Android's precondition for granting USB access to a video-class device. Without it the Eye-camera USB grant fails. |
| **USB device access** (2 prompts) | Yes | One prompt authorizes the control/HID channel; a **second** prompt appears because activating the camera **re-enumerates** the device and Android treats it as new. Accept both quickly (see troubleshooting). |
| **Notifications** | Yes (Android 13+) | Powers "glasses disconnected — tap to reconnect" and the capture watchdog alerts. |
| **Ignore battery optimizations** | Strongly recommended | The load-bearing anti-kill step so Samsung's One UI doesn't kill capture during long sessions. |
| **Accessibility service** | Yes | Enabled manually in Settings. **Without it there is no cursor and no clicks** — capture would run with nothing consuming it. |
| **Microphone** | Optional | Only for voice commands. Denying it just disables voice; everything else works. |

There is also an **"anti-kill" checklist** (Samsung only) that deep-links you to: set the app's battery to *Unrestricted*, add it to "apps that never sleep", and review device battery settings. These vary by One UI version and are best-effort.

---

## Controls — gestures & voice

> These mirror the in-app **Gestures** and **Voice commands** reference cards.

**Gestures**

| Gesture | Action |
|---|---|
| **Move your hand** (tracked at the index-finger knuckle) | Moves the cursor in **relative / trackpad mode** (gain scales with speed for precision). Moving your hand out of view and back does **not** teleport the cursor. |
| **Pinch** (thumb + index) and release in < 400 ms | **Click** at the pinch position. |
| **Pinch and hold** ≥ 400 ms | **Drag** — starts at the pinch anchor, follows your hand, ends when you release. |
| **Closed fist** | Suppresses accidental clicks. **Hold 2 s → recenter** the cursor on screen. |
| **Thumbs-up, held 1 s** | **Toggle cursor mute** — hides the cursor and ignores all gestures except thumbs-up to turn it back on. Persists even with your hand out of view. |
| **"V" sign, held 500 ms** | Opens **voice listening** (cursor turns yellow; you can lower your hand while speaking). |
| **Open palm** (5 fingers, hand roughly still) | Enters **media mode** — cursor freezes, ready for a swipe. |
| Open palm + swipe **right / left** | **Skip forward / back** in a video (double-tap at 75% / 25% width). |
| Open palm + swipe **up / down** | **Volume up / down** (shows the native volume slider). |

**Voice commands** (say the "V" gesture first; both English and Portuguese are always accepted)

| Say | Action |
|---|---|
| "back" / "voltar" | Back |
| "home" / "close" / "início" / "fechar" | Home |
| "recents" / "recentes" | Recent apps |
| "send" / "enviar" | Press Enter in the focused field |
| "clear" / "apagar tudo" | Clear the focused field |
| "open \<app\>" / "abrir \<app\>" | Launch an app by name on the external display |
| "write \<text\>" / "escrever \<texto\>" | Dictate text into the focused field |

Cursor feedback: **yellow** = listening, **green flash** = command ran, **red flash** = error / no match.

---

## First-run setup

The app's home screen is a stack of cards. You only do this setup **once** — after you grant access and connect the glasses one time, the app remembers and reconnects automatically (the **SETUP** card collapses to a **✓ Complete** checkmark). Work through the cards top to bottom:

**1. SETUP**

1. Tap **"1. Grant permissions"** and accept each prompt — camera, notifications, and the battery-optimization exemption (microphone is optional; it only enables voice commands). You may have to tap it **several times (3–4)** until it stops asking — each tap surfaces the next permission.
2. Connect the glasses over USB-C, then tap **"2. Connect glasses & start capture"**. Accept **both USB permission prompts as quickly as possible** — the handshake times out if you're slow. (A second prompt appears because activating the camera re-enumerates the device.)

Once the camera is streaming, this card shows **✓ Complete** and collapses. You won't need to repeat it unless you reinstall the app.

> [!NOTE]
> **Known issue:** right after this first-time setup and granting all permissions, you may need to **unplug and replug the glasses once or twice** before capture starts reliably. A fix is on the way.

**2. CURSOR ON EXTERNAL DISPLAY**

Enable the accessibility service — this is what actually draws the cursor and injects taps, and Android requires enabling it by hand:

1. Tap **"Open Accessibility Settings"**.
2. Find **"Hand Mouse"** in the list.
3. Enable it and confirm.

**3. SAMSUNG ANTI-KILL** (Samsung only, recommended)

So the app isn't killed in the background during long sessions, tap through the three shortcuts: set the app's battery to **Unrestricted**, add it to **apps that never sleep**, and review your **device battery settings**. These deep-link into One UI and vary by version.

**4. GESTURES & VOICE COMMANDS**

These two cards are in-app cheat sheets listing everything you can do — the same reference as [Controls](#controls--gestures--voice) below. The voice-commands card follows the language you pick in the voice selector.

> Prefer the command line? [Quick setup via adb](#quick-setup-via-adb) grants everything grantable in one go — only the two USB prompts still have to be accepted on the phone.

---

## Troubleshooting & QA

Most failure modes are handled automatically (soft/full stream restarts, format demotion, USB reconnection). This section explains what you might see and what, if anything, to do.

### "No XREAL glasses on USB" / the service won't start
The connected-device foreground service can only start **once the USB permission is granted** — merely being plugged in isn't enough. **Fix:** connect the cable, tap start capture, and **accept the USB prompt**. The app requests the grant *before* starting the service, so you no longer need to unplug/replug repeatedly.

### The handshake stalls — a countdown appears ("accept the USB prompt now — N s")
Activating the camera re-enumerates the device and triggers a **second** USB prompt; taking too long blows the handshake timeouts (`WaitPilotReady` caps at 10 s). **Fix:** accept both prompts immediately. If you miss the window, replug the cable or reopen the app.

### Stuck on "Searching for UVC interface…", then ERROR after ~1 minute
The UVC video interface never appeared after re-enumeration (bad cable/port, or the firmware reported the camera on without exposing the interface). After 30 retries it errors out. **Fix:** replug the cable (auto-resumes) or reopen the app.

### The stream freezes for a second or two
The Eye camera intermittently stops delivering bytes. The app detects the stall and does a **soft restart** (~1.5 s, camera + decoder only) if the stream was healthy, or a full restart if it died young. **Nothing to do** — it recovers on its own. Brief hiccups (< 2 s) are tolerated without any restart.

### "Camera didn't start after 6 attempts — retrying in 15 s"
A bad post-connection window exhausted the restart budget. This is **not** a dead end: a full cycle is rescheduled with a 15 s backoff and the camera reliably comes back. You can also unplug/replug the glasses. (Three consecutive stillborn streams escalate to a forced USB re-enumeration automatically.)

### "Glasses disconnected — tap to reconnect"
The cable actually dropped. (The brief detach *during* the handshake's re-enumeration is normal and won't alarm — there's a 5 s grace period.) **Fix:** reconnect the cable (the service re-arms itself) or tap the notification. A background presence poll every 10 s re-arms the pipeline even if the reconnect broadcast is missed.

### "Capture stopped running — tap to reopen the app"
The capture process was killed by the system and couldn't restart in the background (Android 14 restricts background microphone foreground-services). **Fix:** tap the notification to reopen. **Prevention:** complete the anti-kill checklist (battery *Unrestricted* + "never sleeping apps") and the battery-optimization exemption.

### No cursor on the external display
Check, in order: (1) the **accessibility service is enabled**; (2) an **external desktop display exists** (the overlay does not draw on a plain mirrored phone screen); (3) a newly-created display may reject windows briefly — the app retries 8× over ~2 s; (4) the cursor may be **muted** (thumbs-up 1 s to re-enable); (5) the cursor **fades after 2 s idle** by design — move your hand.

### The cursor is frozen but tracking is alive
You're in **media mode** (an open, still palm freezes the cursor for swipes — intentional). **Fix:** curl any finger (or bring your thumb toward your index) to exit; the cursor re-anchors without a jump.

### Tracking gets choppy in a long session (warm phone)
Deliberate **thermal throttling**: 60 fps normally, dropping to 40 or 24 fps as the device heats up. **Fix:** let it cool — the frame rate recovers by itself.

### A voice command does nothing
Causes: microphone denied in the wizard; capture started *before* the mic was granted (grant it with the app open); or the on-device language model is missing (it falls back to the default recognizer automatically). **Fix:** grant the mic via the wizard with the app open, and check the selected voice language.

### Reinstalling the app "breaks" the connection
Reinstalling **revokes granted USB permissions**, and the watchdog correctly won't start capture without them. **Fix:** open the app and redo the two USB grants (replugging helps trigger the prompts).

---

## Project layout

```
xreal-hand-mouse/     The Android app (the main project)
  app/src/main/kotlin/com/raphael/handmouse/
    capture/  glasses/  tracking/  input/  overlay/  service/  util/
  app/src/main/cpp/    ioctltap.c — debug-only USB-capture interposer (never in release)
dex-spike/            A minimal DeX cursor spike (early prototype)
docs/                 Design specs and implementation plans
```

---

## Provenance & licensing

This project is licensed under the **[Apache License 2.0](LICENSE)**.

- The **glasses control protocol** is reimplemented in pure Kotlin from this project's **own black-box USB capture on its own hardware** — an interoperability clean-room. The proprietary XREAL native libraries (`libota-lib.so`, `libnr_glasses_api.so`) and their Java shim are **not** part of this project.
- Portions of the capture code are **adapted from** the MIT-licensed [Aloim reverse-engineering project](https://github.com/Aloim/Xreal-One-Pro-Eye-RGB-Camera-feed-on-Android).
- Hand tracking uses Google's **MediaPipe HandLandmarker** model, licensed Apache-2.0.

See the [NOTICE](NOTICE) file for full third-party attributions.
