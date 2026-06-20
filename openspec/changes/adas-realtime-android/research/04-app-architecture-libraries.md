# 04 — App Architecture & Libraries (Realtime ADAS on Android Phone)

> **Scope.** This document covers the Android application architecture and the supporting library
> stack for a realtime Advanced Driver-Assistance System (ADAS) that runs *on a phone*
> (Samsung Galaxy S22+ / S26 Ultra), mounted in a car, capturing the road through the phone
> camera and surfacing driver warnings (forward-collision, lane-departure, etc.).
> The target is a **standalone foreground Android app**, not an OEM-embedded system. Every key
> technical claim is cited inline.

---

## 1. Camera Capture Pipeline

### 1.1 CameraX vs Camera2

CameraX is the Jetpack camera library built on top of Camera2; it abstracts the
device-specific quirks of Camera2 behind lifecycle-aware **use cases** (`Preview`,
`ImageCapture`, `ImageAnalysis`, `VideoCapture`), while still giving access to the
underlying Camera2 controls through `Camera2Interop` when needed
([Android — CameraX overview](https://developer.android.com/media/camera/camerax)).
For an ADAS app the decisive use case is **`ImageAnalysis`**, which delivers CPU-accessible
buffers to an analyzer callback — exactly what an inference engine needs
([Android — Image analysis](https://developer.android.com/media/camera/camerax/analyze)).

**Recommendation: use CameraX `ImageAnalysis`** as the primary frame source. It removes a large
amount of per-device boilerplate, and because Galaxy flagships have well-behaved camera HALs,
the loss of fine-grained Camera2 control is rarely a problem. Drop to `Camera2Interop` only if
you must pin exposure/AE/focus for a fixed dash mount, or request a specific high-FPS sensor mode.

| Aspect | Camera2 | CameraX `ImageAnalysis` |
|---|---|---|
| API level / boilerplate | Verbose, device-specific | Concise, lifecycle-aware ([docs](https://developer.android.com/media/camera/camerax)) |
| Backpressure handling | Manual `ImageReader` queue mgmt | Built-in strategies ([docs](https://developer.android.com/media/camera/camerax/analyze)) |
| Output format control | Full | `YUV_420_888` (default) or `RGBA_8888` ([docs](https://developer.android.com/media/camera/camerax/analyze)) |
| Fine sensor control (AE/AF lock, FPS range) | Native | Via `Camera2Interop` |

### 1.2 Frame format — YUV_420_888

`ImageAnalysis` delivers an `ImageProxy` whose default format is **`YUV_420_888`**, a compact
planar YUV layout; `RGBA_8888` is also selectable via `setOutputImageFormat()`, in which case
CameraX performs the YUV→RGBA conversion internally
([Android — Image analysis](https://developer.android.com/media/camera/camerax/analyze)).

For a vision pipeline, **prefer `YUV_420_888`**: it is what the sensor produces natively (no extra
conversion), the Y (luma) plane alone is often enough for edge/lane work, and most inference
engines (TFLite, ONNX Runtime, NCNN) accept a YUV or single-channel input and can do the
color conversion / normalization on GPU. If the model needs RGB, doing the YUV→RGB conversion
on the GPU (RenderScript replacement / GLES / a TFLite GPU-delegate preprocessing op) avoids
a costly CPU copy.

> **Critical lifecycle rule:** the analyzer **must call `ImageProxy.close()`** when done with each
> frame to return the buffer to CameraX — and must *not* call `close()` on the wrapped
> `android.media.Image` directly
> ([Android — Image analysis](https://developer.android.com/media/camera/camerax/analyze)).
> Failing to close stalls the pipeline almost immediately.

### 1.3 Backpressure — keep-only-latest

`ImageAnalysis` exposes two backpressure strategies
([Android — Image analysis](https://developer.android.com/media/camera/camerax/analyze)):

- **`STRATEGY_KEEP_ONLY_LATEST`** (default, *non-blocking*): caches only the most recent frame in a
  single-frame buffer. If a new frame arrives while the analyzer is still busy, the previous
  un-analyzed frame is **dropped** and overwritten. This keeps other use cases (e.g. `Preview`)
  running smoothly.
- **`STRATEGY_BLOCK_PRODUCER`** (*blocking*): queues frames (`setImageQueueDepth()`), dropping only
  when the queue is full; can block the whole camera device, including preview.

**For ADAS, use `STRATEGY_KEEP_ONLY_LATEST`.** Driver safety depends on *fresh* perception, not on
processing *every* frame. A stale queued frame describes a road position the car has already
passed; dropping it and analyzing the newest frame minimizes glass-to-warning latency. The
official guidance also notes that when the analyzer completes within one frame interval
(e.g. <16 ms at 60 fps), either strategy yields a smooth experience — but the moment inference
overruns the frame budget, keep-only-latest degrades gracefully by dropping frames rather than
backing up the camera ([Android — Image analysis](https://developer.android.com/media/camera/camerax/analyze)).

### 1.4 Resolution & frame-rate choices

Set **either** target resolution **or** target aspect ratio (not both)
([Android — Image analysis](https://developer.android.com/media/camera/camerax/analyze)). For ADAS:

| Parameter | Recommended | Rationale |
|---|---|---|
| Analysis resolution | **1280×720** (down to 640×360 for small models) | Detection nets rarely need >720p; smaller = faster inference & less heat |
| Target FPS | **30 fps** (15–30 acceptable) | 30 fps ⇒ 33 ms/frame budget; matches typical detector throughput on mobile GPU/NPU |
| Aspect ratio | 16:9 | Matches sensor + minimizes crop of the road scene |

A separate, higher-resolution `Preview`/`VideoCapture` surface can run concurrently for the HMI
display and optional dashcam recording while `ImageAnalysis` runs at a lower CV resolution.

---

## 2. Realtime Processing Architecture

### 2.1 Threading model

A non-blocking ADAS pipeline separates concerns across threads so no single stage stalls the rest:

| Thread / executor | Responsibility | Notes |
|---|---|---|
| **Camera thread** (HAL-driven) | Produces frames into `ImageAnalysis` | Managed by CameraX |
| **Analyzer / inference thread** | Pre-process + run the model on each `ImageProxy` | A dedicated single-thread `Executor` passed to `setAnalyzer()` ([docs](https://developer.android.com/media/camera/camerax/analyze)) |
| **Render / UI thread** | Draw overlay (boxes, lanes, warnings) | Overlay on a `SurfaceView`/`GLSurfaceView` renderer thread, decoupled from the main UI thread (§3) |
| **Sensor thread** | GPS / IMU callbacks; speed gating (§5) | Lightweight; posts state to a shared model |

The analyzer executor should be a **dedicated background thread**, never the main thread, because
each `ImageProxy` callback runs synchronously and blocks the next delivery until it returns
([Android — Image analysis](https://developer.android.com/media/camera/camerax/analyze)). Inference
itself should be dispatched to the **GPU delegate or NNAPI/NPU** so the CPU thread mostly marshals
buffers. Results are published to the render and alert subsystems via a lock-free latest-value
holder (e.g. an `AtomicReference` to an immutable "perception result"), not a growing queue.

### 2.2 Frame dropping (intentional)

Frame dropping is a **feature**, not a failure mode, here. `STRATEGY_KEEP_ONLY_LATEST` guarantees
the analyzer always works on the freshest frame and silently discards frames produced while it is
busy ([Android — Image analysis](https://developer.android.com/media/camera/camerax/analyze)).
The render thread similarly draws the *latest* perception result on every vsync regardless of how
many camera frames were dropped between detections, so the overlay stays smooth even when the
detector runs at, say, 20 fps against a 30 fps camera.

### 2.3 Latency budget

The relevant metric is **end-to-end "glass-to-warning" latency**: sensor exposure → buffer →
preprocess → inference → post-process (tracking / time-to-collision) → alert render + sound.
Published vision-based collision/pedestrian-alert systems report end-to-end latency in the
**~58–64 ms** range for ~75% of samples, with a **maximum computational latency budget of ~100 ms**
and an average computational latency around 56 ms
([Vision-based Pedestrian Alert Safety System, arXiv 2019](https://arxiv.org/pdf/1907.05284)).

A practical target budget for the phone app:

| Stage | Target |
|---|---|
| Capture + buffer delivery | ≤ 20–35 ms (1–2 frames at 30 fps) |
| Preprocess (YUV→tensor, resize, normalize) | ≤ 5–10 ms (GPU) |
| Inference (detection + lane) | ≤ 30–50 ms (GPU/NPU) |
| Post-process + tracking + TTC | ≤ 5–10 ms |
| Alert render + tone/haptic dispatch | ≤ 10 ms |
| **End-to-end** | **≤ ~100 ms** (aligns with the cited ≤100 ms computational ceiling) |

Keeping the whole loop **non-blocking** — dedicated executors, GPU offload, latest-value handoffs,
mandatory `ImageProxy.close()` — is what makes the ≤100 ms target achievable on a phone.

---

## 3. Overlay / HMI Rendering

### 3.1 Canvas/SurfaceView vs OpenGL ES

The overlay (bounding boxes, lane polylines, warning banners) is light 2-D vector drawing that
updates every frame, ideally on a surface decoupled from the main UI thread.

- **`SurfaceView`** gives a dedicated surface that **SurfaceFlinger composites directly to the
  screen**, avoiding the extra offscreen-compositing step of a normal `View`, and is the
  recommended companion when rendering alongside the Camera API or an OpenGL ES context
  ([AOSP — SurfaceView and GLSurfaceView](https://source.android.com/docs/core/graphics/arch-sv-glsv)).
  You drive it with `lockCanvas()` / `unlockCanvasAndPost()` and ordinary `Canvas` draw calls —
  ample for boxes, text and lane lines.
- **`GLSurfaceView`** is a `SurfaceView` subclass that manages an EGL display and renders OpenGL ES
  on a **dedicated render thread**, decoupling rendering performance from the UI thread
  ([AOSP — SurfaceView and GLSurfaceView](https://source.android.com/docs/core/graphics/arch-sv-glsv)).

**Recommendation:** a transparent **`SurfaceView` overlay with `Canvas`** stacked over the camera
preview is sufficient and simplest for box/lane/banner HMI. Move to **`GLSurfaceView`/OpenGL ES**
only if you (a) also do GPU preprocessing/warping in the same GL context, (b) render heavy
semi-transparent fills or many primitives per frame, or (c) need the HUD mirror-warp below.

### 3.2 HUD reflection (windshield projection) mode

A common in-car pattern is to reflect the phone screen off the windshield as a poor-man's
heads-up display. The image must be **horizontally mirrored** so it reads correctly in the
reflection. This is trivially a horizontal flip of the render surface — either a `Canvas`
`scale(-1f, 1f)` transform, or a flipped projection matrix / texture coordinates in OpenGL ES.
A GL pipeline is the cleaner choice here because the same fragment stage can also dim the
background to black (HUDs need maximum contrast against a dark field) and apply a mild keystone
warp; OpenGL ES rendering on a `GLSurfaceView` is designed for exactly this kind of
per-frame transformed compositing
([AOSP — SurfaceView and GLSurfaceView](https://source.android.com/docs/core/graphics/arch-sv-glsv)).

### 3.3 Audible alerts & haptics

Warnings must be **multimodal** (visual + audio + haptic) because the driver's eyes are on the road.

- **Audio:** use **`SoundPool`** for short, pre-loaded, **low-latency** alert clips — it is the
  recommended API for low-latency sound effects and avoids the startup latency of `MediaPlayer`
  ([SoundPool low-latency discussion](https://github.com/adrianstevens/Xamarin-Plugins/issues/38)).
  `ToneGenerator` is a lightweight alternative for synthesized beeps when you don't want to bundle
  audio assets. Route alerts to a dedicated audio attribute so they cut through music/navigation.
- **Haptics:** trigger vibration via **`VibratorManager`** (Android 12+), which can list and drive
  each actuator and play predefined `VibrationEffect` primitives such as `CLICK`/`TICK`
  ([Android — Haptics APIs](https://developer.android.com/develop/ui/views/haptics/haptics-apis);
  [VibratorManager overview](https://yggr.medium.com/exploring-android-12-vibratormanager-new-vibration-primitives-e862c95fe938)).
  On a windshield mount the phone may not be touching the driver, so haptics are a *secondary*
  channel — audio + visual are primary.

---

## 4. Supporting Libraries

### 4.1 OpenCV for Android

OpenCV is the workhorse for classical image preprocessing and geometry. The OpenCV team
distributes an official **Android Archive (AAR) on Maven Central** (since OpenCV 4.9.0), so it can
be added as a normal Gradle dependency with no manual SDK import, alongside the prebuilt
*OpenCV for Android SDK* and a from-source build option
([OpenCV — Android Development tutorial](https://docs.opencv.org/4.x/d5/df8/tutorial_dev_with_OCV_on_Android.html);
[OpenCV on Android](https://opencv.org/android/)).

Key uses for this app:

- **Preprocessing:** convert the `YUV_420_888` `ImageProxy` to an OpenCV `Mat`, then run
  grayscale/Canny edge detection, color thresholding, blurring, ROI cropping, etc.; results
  convert back to a `Bitmap` via `Utils.matToBitmap()`
  ([OpenCV Android tutorial](https://docs.opencv.org/4.x/d5/df8/tutorial_dev_with_OCV_on_Android.html)).
- **Perspective transform for lanes:** `Imgproc.getPerspectiveTransform(src, dst)` computes a 3×3
  homography from four road-plane points to a rectangle, and `Imgproc.warpPerspective()` produces a
  top-down **bird's-eye view** that makes lane lines parallel and curvature easy to fit — the
  standard advanced-lane-detection pipeline
  ([Automatic Addison — Real-Time Lane Detection with OpenCV](https://automaticaddison.com/the-ultimate-guide-to-real-time-lane-detection-using-opencv/)).
  The inverse homography maps detected lanes back into camera space for overlay drawing.

> **Performance note:** keep heavy OpenCV ops on the inference thread and on a downscaled frame;
> `warpPerspective` and Hough/contour passes are CPU-bound and can blow the latency budget at 720p.

### 4.2 MediaPipe (optional)

Google's **MediaPipe** (now part of *MediaPipe Solutions / LiteRT*) offers ready-made, GPU-accelerated
on-device perception graphs (object detection, segmentation) with a `CameraX`-friendly input path.
It is an *optional* accelerator for prototyping detectors quickly but is **not required** — a custom
TFLite/LiteRT or ONNX-Runtime model wired directly into the `ImageAnalysis` analyzer gives finer
control over the latency budget. Treat MediaPipe as a swap-in for the detection stage, not the
backbone of the architecture.

### 4.3 GPU image ops

YUV→RGB conversion, resize and normalization should run on the **GPU** to keep them off the
CPU/analyzer thread. Options: a **TFLite/LiteRT GPU delegate** that ingests the camera texture
directly, OpenGL ES shaders in the same `GLSurfaceView` context used for the overlay (§3), or
OpenCV's `imgproc` operating on small frames. GPU preprocessing is what frees the analyzer thread
to stay within the per-frame budget defined in §2.3.

---

## 5. Sensors & Context

### 5.1 GPS speed (FusedLocationProvider)

Device speed is read from the **Fused Location Provider**. `FusedLocationProviderClient` is the
entry point for continuous fixes via `requestLocationUpdates()` configured by a `LocationRequest`;
with **`PRIORITY_HIGH_ACCURACY`** and a short interval it streams realtime fixes
([Google — FusedLocationProviderClient](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient)).
Each delivered `Location` exposes **`getSpeed()`** (m/s, GPS-derived) which is the gating signal for
warnings. It requires `ACCESS_FINE_LOCATION`.

### 5.2 IMU / accelerometer

The device IMU (accelerometer, gyroscope, rotation vector) via `SensorManager` provides:
- **Camera pitch/roll** for runtime correction of the lane perspective transform when the mount
  shifts or the road grade changes.
- **Hard-braking / harsh-event detection** to corroborate a collision warning.
- **Dead-reckoning smoothing** of GPS speed between fixes (GPS updates ~1 Hz; IMU is high-rate).

### 5.3 Using speed to gate warnings

Speed context suppresses false alarms and tunes thresholds:
- **Suppress** lane-departure and forward-collision alerts below a minimum speed (e.g. parking lot,
  stop-and-go) to avoid nuisance warnings.
- **Scale time-to-collision thresholds** with speed — higher speed ⇒ earlier warning.
- Speed comes from GPS `getSpeed()`
  ([Google — FusedLocationProviderClient](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient)),
  optionally fused with IMU.

### 5.4 Limitation — no access to true vehicle speed

> **Important constraint.** A standalone phone app **cannot read the vehicle's own speedometer,
> wheel-speed, steering angle, brake, or other CAN-bus signals.** Those live on the car's internal
> network and are only exposed to in-vehicle software through **Android Automotive OS `CarPropertyManager`
> / the Vehicle HAL**, which is unavailable to a phone-side app
> ([Android — Build apps for cars / Automotive platform](https://developer.android.com/training/cars)).
> The app must therefore rely on **GPS-derived speed**, which has ~1 Hz update cadence, brief
> dropouts in tunnels/urban canyons, and lag — all of which the IMU fusion in §5.2 partially
> mitigates, but it is never as authoritative as the vehicle bus.

---

## 6. Deployment Form Factors

| Form factor | Camera/CV custom app? | Phone needed? | Verdict for this project |
|---|---|---|---|
| **Standalone phone app** (dash mount) | **Yes** — full camera + GPU access | n/a (is the phone) | **Target** |
| **Android Auto** (projection) | **No** | Yes | Not viable |
| **Android Automotive OS** (embedded) | Restricted to OEM/approved categories | No | Not the target (no phone) |

### 6.1 Android Auto does NOT allow custom camera / CV apps

Android Auto is a **projection** system: the phone renders to the car head-unit, and Google
restricts what can be projected to a small set of **driver-distraction-reviewed categories**.
The officially supported, all-developer categories are **media (audio), navigation, point of
interest, IoT, and weather**, built with the *Android for Cars App Library*; messaging, calling,
games and browsers are limited/lab categories
([Android — Build apps for cars](https://developer.android.com/training/cars)). There is **no camera
or computer-vision category**, and custom (non-templated) apps are limited to specific Google
partners ([Android Auto — Custom apps](https://developers.google.com/cars/design/android-auto/apps/archive/custom-apps)).
Android Auto only exposes the head-unit screen and audio — **it does not give a projected app access
to the phone camera or arbitrary surfaces** — so a live camera/CV ADAS app **cannot run as an
Android Auto app**. This is a hard platform constraint, not a configuration issue.

### 6.2 Android Automotive OS

Android Automotive OS (AAOS) is a **full operating system embedded in the vehicle**, self-sufficient
and with deep hardware access (climate, EV functions, and crucially the vehicle bus via
`CarPropertyManager`); it is distinct from phone-based Android Auto
([einfochips — AAOS vs Android Auto](https://www.einfochips.com/blog/android-automotive-os-vs-android-auto-understanding-the-worlds-first-native-os/);
[Android — Build apps for cars](https://developer.android.com/training/cars)). While AAOS *could*
theoretically host a richer ADAS app with real vehicle-speed data, it (a) runs on the car, not the
phone (so it doesn't use the phone camera in a removable-mount scenario), (b) is gated to the same
restricted app categories plus OEM approval, and (c) is unavailable on the Galaxy phones that are
this project's hardware.

### 6.3 Why a standalone foreground app is the realistic target

The phone's rear/forward camera, GPU/NPU, GPS and IMU are all freely accessible to an ordinary
**standalone Android app** — none of the camera/CV restrictions of Android Auto apply
([Android — Build apps for cars](https://developer.android.com/training/cars)), and CameraX +
TFLite + OpenCV all run unrestricted. A dashcam-style mounted app that the driver glances at (or
reflects onto the windshield, §3.2) is the **only deployment path that simultaneously gives camera
access, custom CV inference, and GPU rendering** on the target Galaxy hardware. Hence the
architecture in §§1–5 targets a **standalone foreground service-backed app**.

---

## 7. Operational Concerns

### 7.1 Keeping the screen on & staying alive (foreground service)

The capture+inference loop must survive while the app is in use and the screen must not dim:

- **Foreground service** — a long-running camera/CV workload must run as a **foreground service with
  a persistent notification**, and on **Android 14+** must declare a **foreground-service *type***
  (`camera`/`location`) and hold the matching permission
  ([Android — foreground service requirements](https://support.google.com/googleplay/android-developer/answer/13392821);
  [Android — Keep the screen on](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on)).
- **Keep screen on** — `FLAG_KEEP_SCREEN_ON` / `View.setKeepScreenOn(true)` can only be applied from
  an **Activity/View, never from a service**, so the visible ADAS Activity sets the flag while the
  service does the work ([Android — Keep the screen on](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on)).
  This is preferred over a wake lock because it is lifecycle-scoped and released automatically when
  the screen leaves.

### 7.2 Battery & heat (charging in a car mount)

Running camera + GPU/NPU + bright always-on display while **charging in a hot car** is a
thermal-throttling risk that directly threatens the latency budget. Mitigations:
- Analyze at reduced resolution/FPS (§1.4); offload to NPU/GPU rather than CPU.
- Drop FPS adaptively when `PowerManager.getThermalHeadroom()` / thermal status rises.
- Prefer a mount with airflow; avoid blocking the phone with direct sun. Charging supplies power but
  also adds heat, so the app should treat sustained high temperature as a first-class signal and
  degrade gracefully (lower FPS, dim overlay) rather than crash or stutter.

### 7.3 Camera mounting & calibration

CV accuracy depends on a **stable, calibrated** camera pose:
- Rigid mount; fixed phone orientation; lens aimed at the road horizon.
- **Calibration step:** capture the four road-plane reference points used by
  `getPerspectiveTransform` for the bird's-eye lane transform (§4.1)
  ([OpenCV lane detection](https://automaticaddison.com/the-ultimate-guide-to-real-time-lane-detection-using-opencv/)),
  plus intrinsic calibration (focal length / distortion) for distance/TTC estimates.
- Use the IMU (§5.2) to detect mount shifts and prompt re-calibration; lock camera exposure/focus
  via `Camera2Interop` so frames are photometrically stable.

### 7.4 Legal & safety disclaimers

> **Driver-assistance, not autonomy.** This app provides **supplementary driver-assistance
> warnings only**. It does **not** control the vehicle and is **not** an autonomous or self-driving
> system. The driver remains fully responsible for vehicle control and for watching the road at all
> times; warnings may be late, missed, or false. Because the app relies on phone-grade camera,
> GPS-only speed (no vehicle bus, §5.4), and a removable mount, its accuracy is inherently below
> OEM-integrated ADAS, and it must not be relied upon for safety-critical decisions.

Practical obligations: a **mandatory startup disclaimer/EULA** the user must accept; clear
documentation that it is an aid, not a replacement for attentive driving; compliance with local laws
on **windshield mounting / screen visibility while driving** and on dashcam recording/privacy;
and limitation-of-liability language in the EULA. These disclaimers are a hard requirement before
distribution, paralleling Google Play's emphasis that in-car apps minimize driver distraction
([Android Auto — Custom apps design / safety](https://developers.google.com/cars/design/android-auto/apps/archive/custom-apps)).

---

## Summary of Architectural Decisions

| Concern | Decision |
|---|---|
| Camera API | CameraX `ImageAnalysis` |
| Frame format | `YUV_420_888`, GPU preprocessing |
| Backpressure | `STRATEGY_KEEP_ONLY_LATEST` (drop stale frames) |
| Resolution / FPS | ~720p analysis @ ~30 fps |
| Threading | Dedicated analyzer executor + GL/Surface render thread; latest-value handoff |
| Latency target | ≤ ~100 ms glass-to-warning |
| Overlay | Transparent `SurfaceView`+`Canvas`; `GLSurfaceView`/GLES for HUD mirror |
| Alerts | `SoundPool`/`ToneGenerator` audio + `VibratorManager` haptics |
| CV libs | OpenCV AAR (preprocess + `warpPerspective` lanes); TFLite/LiteRT (or optional MediaPipe) detection |
| Sensors | FusedLocationProvider speed + IMU fusion; **no vehicle-bus speed** |
| Form factor | **Standalone foreground-service app** (Android Auto forbids custom camera/CV apps) |
| Ops | Foreground service (typed, Android 14+) + keep-screen-on Activity; thermal-adaptive; mount calibration; legal disclaimers |

---

## Sources

1. Android Developers — CameraX overview: https://developer.android.com/media/camera/camerax
2. Android Developers — Image analysis (ImageAnalysis, backpressure, YUV_420_888): https://developer.android.com/media/camera/camerax/analyze
3. Vision-based Pedestrian Alert Safety System (end-to-end ADAS latency), arXiv: https://arxiv.org/pdf/1907.05284
4. AOSP — SurfaceView and GLSurfaceView: https://source.android.com/docs/core/graphics/arch-sv-glsv
5. SoundPool low-latency audio (discussion): https://github.com/adrianstevens/Xamarin-Plugins/issues/38
6. Android Developers — Haptics APIs: https://developer.android.com/develop/ui/views/haptics/haptics-apis
7. Android 12 VibratorManager / vibration primitives: https://yggr.medium.com/exploring-android-12-vibratormanager-new-vibration-primitives-e862c95fe938
8. OpenCV — Android Development tutorial (AAR, Mat, matToBitmap): https://docs.opencv.org/4.x/d5/df8/tutorial_dev_with_OCV_on_Android.html
9. OpenCV on Android (distribution): https://opencv.org/android/
10. Automatic Addison — Real-Time Lane Detection with OpenCV (getPerspectiveTransform / bird's-eye): https://automaticaddison.com/the-ultimate-guide-to-real-time-lane-detection-using-opencv/
11. Google — FusedLocationProviderClient reference: https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient
12. Android Developers — Build apps for cars (supported categories, Automotive platform): https://developer.android.com/training/cars
13. Android Auto — Custom apps (partner-only, safety): https://developers.google.com/cars/design/android-auto/apps/archive/custom-apps
14. einfochips — Android Automotive OS vs Android Auto: https://www.einfochips.com/blog/android-automotive-os-vs-android-auto-understanding-the-worlds-first-native-os/
15. Android Developers — Keep the screen on: https://developer.android.com/develop/background-work/background-tasks/awake/screen-on
16. Google Play — Foreground service requirements (Android 14 typed FGS): https://support.google.com/googleplay/android-developer/answer/13392821
