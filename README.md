# ADAS Edge — Realtime Android Driver-Assistance

A standalone Android app that turns a dash-mounted Snapdragon phone (Galaxy
**S22+** dev / **S26 Ultra** deploy) into a realtime driver-assistance aid:
forward-collision, lane-departure, tailgating/headway, and traffic-sign/over-speed
warnings, with on-device NPU perception.

> **Driver-assistance aid — NOT a certified or autonomous ADAS.** Warnings only;
> never controls the vehicle. See the in-app disclaimer and `research/01`.

## Architecture

```
CameraX (keep-latest) ─▶ PerceptionEngine ─▶ PerceptionResult ─┬▶ WarningManager ─▶ AlertController
   forward road           YOLO11n  + UFLDv2     (shared contract) │   FCW / LDW        audible/haptic
                          distance/TTC                            │   Headway / TSR
SpeedContext (GPS+IMU) ───────────────────────────────────────── ┘        │
   VALID/DEAD_RECKONED/INVALID                                      OverlayView (boxes/lanes/HUD/status)
```
- **Inference** (`inference/`): QNN Hexagon HTP primary → ONNX-Runtime QNN-EP →
  LiteRT GPU → CPU, chosen per device. Per-device HTP context binaries (v69/v81).
- **Perception** (`perception/`): YOLO11n detection, UFLDv2 lanes (OpenCV fallback),
  monocular geometry distance + bbox-scale TTC.
- **Speed** (`speed/`): GPS-only speed with smoothing, standstill clamp, IMU
  dead-reckoning, and a validity contract gating every speed-dependent warning.
- **Warnings** (`warnings/`): four independent evaluators on the shared contract.
- **HMI** (`ui/`, `alert/`): realtime overlay, urgency-scaled alerts, HUD mirror,
  degraded-mode indicators, safety disclaimer, foreground service.

The design rationale, specs, and cited research live in
`openspec/changes/adas-realtime-android/` (`research/README.md` indexes the
research; `design.md` records decisions D1–D9).

## Quickstart — rebuild from scratch

Produces a runnable debug APK with working detection + lane perception on the
**ONNX-Runtime fallback path** (CPU on any device, QNN-EP on Snapdragon). No
Qualcomm SDK or NPU build required. Model blobs are **not** in git — you
regenerate them with the two fetch scripts below.

**Prerequisites:** JDK 17, Android SDK (or Android Studio), `python3` + network.
The fetch scripts create their own venv under `build/.export-venv` — nothing is
installed system-wide.

```bash
# 1. Fetch + export the perception models into app/src/main/assets/models/
tools/fetch_detector.sh          # YOLO11n  -> detector.onnx (~11 MB, stock Ultralytics)
tools/fetch_lane.sh              # UFLDv2   -> lane.onnx (~93 MB, INT8, TuSimple res18)

# 2. Build the app
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk

# 3. Install + run on a device (arm64; Snapdragon Galaxy for the NPU path)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

What each script does: sets up the venv, downloads the source weights, exports to
ONNX **matching the in-app decoders** (`ObjectDetector.kt` expects `[1,84,8400]`;
`LaneDetector.kt` expects `loc [1,4,56,100]` + `exist [1,4,56]`), and validates the
model loads **from bytes** the way `OrtModelRunner` does. `fetch_lane.sh` wraps
UFLDv2's native 4-tensor output into the app's 2-tensor contract, bakes in
ImageNet normalization, and INT8-quantizes (UFLDv2's FC head is ~96M params —
385 MB fp32 would OOM the app). Re-running either script is idempotent.

**Without the models** the app still builds and launches camera-only with a
"models missing" notice; lanes fall back to classical OpenCV CV when `lane.onnx`
is absent. To verify which path is live, check logcat: `detector -> CPU`
(or `-> ORT_QNN` on Snapdragon).

### Notes & caveats
- **Checkpoint trust / source:** `fetch_lane.sh` downloads the UFLDv2 TuSimple
  ResNet18 weights from the authors' Google Drive (link in their repo README) and
  `torch.load`s them (pickle). Review before running in a sensitive environment.
- **Lane preprocessing gap:** UFLDv2 crops the top of the frame before resizing;
  the app's `Preprocess` letterboxes the full frame, so row-anchor alignment is
  approximate. Fine for a PoC — replicate the crop in `Preprocess` to tighten it.
- **Production NPU path** (per-device QNN HTP context binaries, INT8, sustained
  thermal benchmark) is separate and SDK-gated — see
  [`tools/README_qnn.md`](tools/README_qnn.md).
- The AI Hub INT8 YOLO model is **not** a drop-in for the ORT decoder (different
  output layout); use it only on the QNN path. `fetch_detector.sh` uses the stock
  Ultralytics export the decoder was written against.

## Status
Application code + model toolchain are implemented. On-device model build and the
sustained-FPS deploy benchmark require the Qualcomm QNN SDK and the physical
phones — see the remaining unchecked items in
`openspec/changes/adas-realtime-android/tasks.md` (group 9) and `tools/README_qnn.md`.

## License & attribution
This project is licensed under **AGPL-3.0** — see [`LICENSE`](LICENSE). It uses
Ultralytics YOLO11 (AGPL-3.0), so the project as a whole is AGPL-3.0 to stay
compatible. Third-party components, their licenses, and the relicensing path are
listed in [`NOTICE`](NOTICE).

Model weights and the Qualcomm QNN SDK are **not** bundled — they're fetched or
provided at build time (`tools/`), each under its own license.

**Before distribution** (design D9 / task 9.5): resolve the YOLO11n AGPL-3.0
obligation — obtain an Ultralytics Enterprise license, or swap to a permissive
detector (NanoDet/MobileNet-SSD) retrained on BDD100K, after which the project
could be relicensed permissively.
