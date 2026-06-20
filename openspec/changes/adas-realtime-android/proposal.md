## Why

Aftermarket ADAS hardware is expensive and car-specific, yet modern Snapdragon phones
already carry a camera and an NPU capable of running realtime road perception. This
change builds a standalone Android app that turns a dash-mounted Galaxy S22+ / S26
Ultra into a realtime driver-assistance aid (collision, lane, headway, and sign
warnings) — no vehicle integration required. Research backing every decision lives in
`research/` (see `research/README.md`).

## What Changes

- Add a **standalone foreground-service Android app** that captures the forward road
  via CameraX and runs a realtime perception pipeline on the Hexagon NPU.
- Add an **on-device inference runtime** built on the YOLO11n → ONNX → QNN → INT8 →
  HTP-context-binary pipeline, parameterized per device tier (S22+ `v69` dev target,
  S26 Ultra `v81` deploy target) with a LiteRT/GPU fallback for non-Snapdragon units.
- Add the **four ADAS warning functions**: Forward Collision Warning (TTC-based), Lane
  Departure Warning, Headway/Tailgating monitoring, and Traffic Sign Recognition with
  over-speed alerting.
- Add a **driver-alert HMI**: transparent overlay (bounding boxes, lane lines,
  warnings) plus audible and haptic alerts, with a heads-up (mirrored) display mode.
- Add a **GPS-based speed-context layer** that gates and scales every speed-dependent
  warning, with smoothing/hysteresis, IMU dead-reckoning through GPS dropouts, and an
  explicit degraded mode. **CONSTRAINT**: a phone app cannot read vehicle/CAN speed,
  and Android Auto forbids custom camera/CV apps — hence the standalone form factor.
- Ship the **cited research documents** (already drafted in `research/`) as the
  knowledge base grounding the specs and design.
- The app presents as a **driver-assistance aid, not a certified/autonomous ADAS**,
  with explicit in-app safety disclaimers.

## Capabilities

### New Capabilities
- `realtime-inference`: On-device NPU inference runtime — model export/quantization
  pipeline, per-device HTP context binaries (S22+/S26 Ultra), fallback delegates,
  frame scheduling, and the realtime FPS/latency budget.
- `adas-perception`: Camera capture and the perception pipeline that produces
  detections, lane geometry, and distance/TTC estimates consumed by the warnings.
- `collision-warning`: Forward Collision Warning — TTC computation, speed-scaled
  thresholds, and warning escalation.
- `lane-departure-warning`: Lane line detection, departure criteria, activation-speed
  gating, and turn-signal/intent suppression.
- `headway-monitoring`: Following-distance / time-headway estimation from the monocular
  camera and tailgating alerts.
- `traffic-sign-recognition`: Speed-limit (and stop/traffic-light) recognition with
  over-speed warning logic and tolerance margins.
- `speed-context`: GPS-derived speed acquisition, smoothing/standstill handling, IMU
  dead-reckoning, and the `VALID/DEAD_RECKONED/INVALID` validity states that gate all
  speed-dependent warnings.
- `driver-alert-hmi`: Overlay rendering, audible/haptic alerts, HUD mirror mode,
  degraded-mode indicators, and the safety disclaimer.

### Modified Capabilities
- None — `openspec/specs/` is empty; this is a greenfield project.

## Impact

- **New Android app project** (Kotlin): CameraX, foreground service, SurfaceView/OpenGL
  overlay, OpenCV for Android, FusedLocationProvider, IMU sensors.
- **ML toolchain**: Ultralytics YOLO11n (note AGPL-3.0 license), ONNX, Qualcomm AI
  Engine Direct (QNN) SDK / AI Hub, INT8 quantization; LiteRT + ONNX Runtime QNN-EP as
  fallbacks.
- **Device targets**: Galaxy S22+ (SD 8 Gen 1, HTP v69, dev) and S26 Ultra (SD 8 Elite
  Gen 5, HTP ~v81, deploy) — SoC id / dsp_arch for the Elite Gen 5 to be read on-device.
- **No vehicle/CAN integration**; speed and motion context are sensor-derived only.
- **Safety/legal**: driver-assistance disclaimer; not a certified ADAS; coarser-than-
  vehicle speed signal documented as a known limitation.
