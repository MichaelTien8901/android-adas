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
Qualcomm SDK or NPU build required.

The detector & lane model blobs are **not** in git (large, separately licensed) —
you regenerate them with the fetch scripts. The small speed-limit classifier
(`gtsrb.onnx`, ~5 MB) **is** committed, so it works out of the box.

### 0. Host toolchain (one-time setup)
- **JDK 17** — set `JAVA_HOME` to a JDK 17 install.
- **Android SDK** with **platform 34** (`compileSdk`/`targetSdk` = 34, `minSdk` =
  31) and **NDK `26.3.11579264`**. Install via Android Studio's SDK Manager or
  `sdkmanager "platforms;android-34" "ndk;26.3.11579264" "platform-tools"`, then
  point `local.properties` at it: `sdk.dir=/path/to/Android/Sdk`.
- **`adb`** (from `platform-tools`) on `PATH`.
- **`python3`** + network for the model scripts — each creates its own venv under
  `build/.export-venv`, so nothing is installed system-wide.
- *(NPU path only)* the **Qualcomm AI Runtime (QAIRT/QNN) SDK** — see
  [`tools/README_qnn.md`](tools/README_qnn.md). Skip for the CPU/ORT build.

### 1. Fetch the perception models
```bash
# exported into app/src/main/assets/models/
tools/fetch_detector.sh          # YOLO11n  -> detector.onnx (~11 MB, stock Ultralytics)
tools/fetch_lane.sh              # UFLDv2   -> lane.onnx (~130 MB, INT8-dynamic, TuSimple res18)
# gtsrb.onnx (speed-limit classifier) is already in the repo — retrain only if you want (see "Training" below)
```

### 2. Build the debug APK
```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
```

### 3. Install on a device (S22+ / S26 Ultra)
```bash
adb devices                                              # confirm the phone is listed
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
**One APK serves both phones** — the inference path is chosen at runtime, no
per-device build:
- **CPU / ONNX-Runtime** (no QNN SDK): runs on any arm64 device, on the CPU —
  verify `EngineFactory: detector -> CPU` (confirmed on the S26). The Hexagon NPU
  path needs the QNN build below; ORT's QNN-EP and the native HTP bridge both
  require the bundled QNN runtime libs, which this build doesn't ship.
- **NPU / Hexagon HTP** (QNN SDK present): the APK bundles per-arch context
  binaries and selects one per device — **S22+ → `detector_v69.bin`**,
  **S26 Ultra → `detector_v81.bin`**. Verify `detector -> QNN_HTP`,
  `lane -> QNN_HTP`. See §"NPU build" below.

On first launch: accept the safety disclaimer, grant camera + location
permissions, and (optionally) run the guided horizon calibration in Settings.

> **"Flashing" note:** this is an ordinary app — you **install an APK** with
> `adb install`, not flash a firmware image. No fastboot, bootloader unlock, or
> recovery needed; the phone just needs USB debugging enabled.

What each script does: sets up the venv, downloads the source weights, exports to
ONNX **matching the in-app decoders** (`ObjectDetector.kt` expects `[1,84,8400]`;
`LaneDetector.kt` expects `loc [1,4,56,100]` + `exist [1,4,56]`), and validates the
model loads **from bytes** the way `OrtModelRunner` does. `fetch_lane.sh` wraps
UFLDv2's native 4-tensor output into the app's 2-tensor contract, bakes in
ImageNet normalization, and INT8-quantizes (UFLDv2's FC head is ~96M params —
385 MB fp32 would OOM the app). Re-running either script is idempotent.

**Without the models** the app still builds and launches camera-only with a
"models missing" notice; lanes fall back to classical OpenCV CV when `lane.onnx`
is absent. To verify which path is live, check logcat: `detector -> CPU` for this
fallback build (`-> QNN_HTP` once you add the QNN build below).

### Training the speed-limit classifier (GTSRB)
The speed-limit recognizer (task 7.6) proposes circular sign regions with OpenCV
and reads the value with a GTSRB-trained CNN. A trained `gtsrb.onnx` is **already
committed**, so training is optional — only re-run it to regenerate or improve the
model:
```bash
# uses the venv a fetch script created; needs torch + torchvision
build/.export-venv/bin/python tools/train_gtsrb.py --epochs 6 \
    --out app/src/main/assets/models/gtsrb.onnx
```
Downloads GTSRB to `build/gtsrb/`, trains `SignNet` (3 conv blocks), and exports
`1×3×48×48 → 43` ONNX. 6 epochs ≈ **94.8% test accuracy, 100% on the 8 speed-limit
classes** (20–120 km/h); CPU training is ~5 min/epoch. To validate end-to-end
without driving, generate a replay clip and push it (see
[`tools/make_test_clip.py`](tools/make_test_clip.py)):
```bash
build/.export-venv/bin/python tools/make_test_clip.py --scene speed --out build/test_speed.mp4
adb push build/test_speed.mp4 /sdcard/Android/media/com.adasedge.app/replay/replay.mp4
# then enable Settings ▸ "Replay test video" and start driving mode
```

### NPU build (S22+ v69 / S26 Ultra v81)
The CPU/ORT build above runs everywhere. To put detection + lanes on the Hexagon
NPU, build the per-device HTP context binaries and the native bridge — full steps
(calibration data, gotchas, device matrix) in
[`tools/README_qnn.md`](tools/README_qnn.md). In short:
```bash
export QNN_SDK_ROOT=~/qairt/2.47.0.260601   # QAIRT 2.47+ (the S26's v81 needs ≥ 2.47)
tools/vendor_qnn_sources.sh                 # vendor SampleApp sources + v69/v81 libs/skels

# Quantize once, then build a context binary PER DEVICE (TARGET_DEVICE picks the HTP arch):
TARGET_DEVICE=s22plus  tools/quantize_qnn.sh     build/detector.onnx detector
TARGET_DEVICE=s22plus  tools/gen_context_binary.sh detector   # -> detector_v69.bin  (S22+)
TARGET_DEVICE=s26ultra tools/gen_context_binary.sh detector   # -> detector_v81.bin  (S26 Ultra)
# repeat the two gen_context_binary lines for `lane`

JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug   # compiles libadas_qnn.so when QNN_SDK_ROOT is set
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
The **same APK** bundles every `*_v69.bin` / `*_v81.bin` and reports
`detector/lane -> QNN_HTP` on both the S22+ (v69) and the S26 Ultra (v81),
selecting the matching binary at runtime. (The S26 runs Android 16 / 16 KB memory
pages — the build links all native libs 16 KB-aligned.)

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

## Recorded clips & replay source (USB-visible storage)

Both live on **shared, USB/MTP-visible storage** — connect the phone to a computer
and browse **Internal storage**; no `adb` required.

**Recorded dashcam clips** → `Movies/ADASEdge/<YYYY>/<MM>/<YYYY-MM-DD>/`
```
Internal storage/Movies/ADASEdge/2026/06/2026-06-28/dashcam_2026-06-28_124530.mp4
```
A top-level `Movies/` folder, organized by date (one folder per day); each clip is
named by capture datetime so it sorts chronologically. Manage them in-app via
**Settings ▸ Recorded clips** (browse, filter by day, play, share, delete, or set
as the replay source), or just copy them off over USB. Size-capped retention
deletes the oldest clips first to stay under the configured cap.

**Replay source clips** → `Android/media/<pkg>/replay/`
```
Internal storage/Android/media/com.adasedge.app/replay/replay.mp4
```
Drop a clip here to use it as the replay source for validation without driving,
then enable **Settings ▸ Replay test video**. `replay.mp4` is used if present,
otherwise the newest `*.mp4` in the folder. These clips also appear in the in-app
library (play / delete). `Android/media/...` is app-owned yet USB-visible — unlike
the old hidden `Android/data/...` path, which earlier builds used.

## Status
Application code, model toolchain, and the on-device NPU path are implemented and
validated: detection + lanes run on the Hexagon HTP on both the **S22+ (v69)** and
the **S26 Ultra (v81, Android 16 / 16 KB pages)** from one APK, and all four
warnings — FCW, LDW, headway, and traffic-sign/over-speed (speed-limit
recognition + spoken alerts) — fire on replayed footage. Remaining open items are
the systematic per-warning spec validation and the sustained-FPS thermal
benchmark — see `openspec/changes/adas-realtime-android/tasks.md` (group 9) and
`tools/README_qnn.md`.

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
