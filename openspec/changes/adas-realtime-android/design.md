## Context

This change builds a standalone Android driver-assistance app that turns a
dash-mounted Snapdragon Galaxy phone into a realtime ADAS aid. The motivation and
capability set are in `proposal.md`; per-capability requirements are in `specs/`; the
sourced research grounding every decision is in `research/` (`research/README.md`
indexes the five documents).

**Current state**: greenfield — no existing app, no existing specs. A device-tier
reference already exists in `doc/project_draft` (S22+ dev, S26 Ultra deploy; QNN/HTP
config matrix).

**Hard constraints discovered in research (binding on this design):**
- A standalone phone app **cannot read vehicle/CAN speed**; only Android Automotive OS
  exposes it. Speed is GPS/IMU-derived only (`research/05`).
- **Android Auto forbids custom camera/CV apps** — the only viable form factor is a
  standalone foreground app on a mounted phone (`research/04`).
- The NPU path (Qualcomm Hexagon/QNN) requires Snapdragon; **Exynos SKUs have no
  Hexagon** and must use a GPU/CPU fallback (`research/03`).
- Both 8 Gen 1 and 8 Elite Gen 5 **throttle hard under sustained load**; performance
  must be judged on post-throttle sustained FPS, not cold peak (`research/03`).
- Ultralytics **YOLO11n is AGPL-3.0** — a licensing decision is required before any
  distribution (`research/02`).

**Stakeholders**: the driver (primary user, safety-critical HMI), the developer
(on-device build/benchmark loop), and — implicitly — anyone relying on the warnings,
which is why the safety-disclaimer and degraded-mode requirements are first-class.

## Goals / Non-Goals

**Goals:**
- A realtime (≥15 FPS sustained, post-throttle) on-device perception pipeline running
  on the Hexagon NPU, validated on S22+ and deployable on S26 Ultra from one model.
- The four ADAS functions (FCW, LDW, headway, TSR/over-speed) driven off a single
  shared perception result, each independently speed-gated.
- A GPS-derived speed-context layer with an explicit `VALID/DEAD_RECKONED/INVALID`
  contract that every speed-dependent warning consumes.
- A driver HMI that overlays perception, raises urgency-scaled multi-modal alerts, and
  honestly signals degraded capability.
- A clean device-parameterization story: one ONNX export + one quantized graph,
  re-contexted per device; no code changes per target.

**Non-Goals:**
- No vehicle/CAN integration, no Android Auto / Android Automotive build, no autonomous
  control or actuation (warnings only — never braking/steering).
- No certification to ISO/Euro NCAP/FMVSS; standards are used as design references, not
  compliance targets.
- No cloud inference, no always-on connectivity requirement, no fleet/telematics
  backend in this change.
- No multi-camera / radar / LiDAR fusion; monocular forward camera only.
- Not optimizing for non-Snapdragon devices beyond a functional fallback.

## Decisions

### D1 — Standalone foreground app (not Android Auto / AAOS)
Android Auto's supported app categories exclude custom camera/CV apps, and AAOS ships
in the car, not on a phone. **Decision**: a standalone Kotlin app running a typed
foreground service in active driving mode, screen kept on, dashcam-style mount.
*Alternatives considered*: Android Auto projection (blocked by policy), AAOS app
(wrong hardware target). *Rationale*: it is the only form factor that can both access
the camera for CV and run our pipeline.

### D2 — QNN/Hexagon NPU as primary, layered fallbacks
**Decision**: primary inference path is Qualcomm AI Engine Direct (QNN) on the Hexagon
HTP with a pre-built INT8 context binary per SoC. Fallbacks, in order: ONNX Runtime
with QNN-EP (the path the Ultralytics exporter uses), then LiteRT GPU delegate, then
CPU. The runtime picks the path at startup from `ro.board.platform` / SoC id.
*Alternatives*: LiteRT+NNAPI as primary — rejected because NNAPI is deprecated in
Android 15+ and yields worse NPU utilization than QNN. *Rationale*: QNN gives the best
sustained FPS on the exact target chips (`research/03`).

### D3 — One model, per-device context binaries
**Decision**: YOLO11n → ONNX → QNN → INT8 post-training quantization → per-arch HTP
context binary (`v69` for S22+, `v81` for S26 Ultra). The build target stays
`aarch64-android`; only the context binary is device-specific, driven by a
`TARGET_DEVICE` switch (per `doc/project_draft`). SoC id / `dsp_arch` for the Elite
Gen 5 are **read on-device** before the first S26 build. *Rationale*: avoids
re-quantizing per device and keeps a single validated graph.

### D4 — Detection backbone: YOLO11n
**Decision**: YOLO11n as the detector — COCO already covers car/truck/bus/person/
bicycle/traffic-light/stop-sign, ~2.6M params, ~1–2 ms INT8 on Hexagon (`research/02`),
leaving budget for lanes + post-processing. *Alternatives*: YOLOv8n (similar, older),
RT-DETR (heavier), MobileNet-SSD/NanoDet (lighter, lower accuracy). *Open licensing
risk*: AGPL-3.0 → see D9 / Risks.

### D5 — Perception: NPU lane model (UFLDv2) for v1
**Decision**: lane detection uses **Ultra-Fast-Lane-Detection v2 (UFLDv2)** as a second
NPU model from v1, exported through the same ONNX → QNN → INT8 → HTP context-binary
pipeline as the detector (D3) and validated on CULane. OpenCV classical CV
(perspective transform / sliding window) is retained only as a CPU fallback when the
lane NPU model is unavailable. Distance via monocular geometry (pinhole + ground-plane
/ known-width) with Kalman filtering; **no monocular depth nets** (MiDaS/Depth-Anything
are not realtime on phone NPUs). TTC from bounding-box scale change. *Rationale*: UFLDv2
gives robust lane geometry in conditions where classical CV fails (worn markings, glare,
curves), and the realtime budget tolerates a second small NPU model since YOLO11n leaves
headroom (`research/02`/`03`). *Trade-off*: two models share the HTP and the per-device
context-binary build — the sustained-FPS benchmark (D2/D8) must now cover both models
co-resident.

### D6 — Shared perception contract → independent warning evaluators
**Decision**: the perception layer publishes one timestamped result per frame
(detections + lane geometry + lead distance/TTC). Each of the four warning functions is
an independent evaluator subscribing to that contract plus speed-context. *Rationale*:
decouples the heavy CV pipeline from lightweight per-function logic, lets each function
be tested in isolation, and matches the one-spec-per-function structure.

### D7 — Speed-context as a gating service with a validity contract
**Decision**: a dedicated speed-context module owns GPS acquisition
(FusedLocationProvider), low-pass smoothing, standstill clamp, IMU dead-reckoning
through dropouts, and emits `(speed, validity ∈ {VALID, DEAD_RECKONED, INVALID},
freshness)`. **No warning reads raw GPS directly.** Each warning applies its own
activation-speed gate + hysteresis on top. *Rationale*: centralizes the single most
fragile signal so its weaknesses are mitigated once, consistently (`research/05`).

### D8 — Threading model and latency budget
**Decision**: three logical lanes — (1) CameraX `ImageAnalysis` with
`STRATEGY_KEEP_ONLY_LATEST`, (2) an inference/perception thread, (3) a render/overlay
thread (transparent SurfaceView; GLSurfaceView for the HUD mirror). Frames are dropped
under backpressure, never queued; the overlay never lags more than one inference cycle.
Target glass-to-warning budget ≤ ~100 ms nominal. *Rationale*: keeps the pipeline
non-blocking and the displayed state fresh (`research/04`).

### D9 — Licensing posture (decision required, see Open Questions)
**Decision (provisional)**: treat YOLO11n as a development/PoC backbone under AGPL-3.0;
before any distribution, either (a) obtain an Ultralytics Enterprise license, or (b)
swap to an Apache/BSD-licensed detector (e.g. NanoDet/MobileNet-SSD) retrained on
BDD100K. This is flagged as an Open Question to resolve before release, not before PoC.

## Risks / Trade-offs

- **Sustained thermal throttling on 8 Gen 1 (S22+)** → Mitigation: judge perf on a
  10-min sustained NPU loop (per `doc/project_draft`), adapt input resolution/cadence
  under throttle (D2/realtime-inference spec), and keep S26 Ultra as the deploy target
  for headroom.
- **GPS speed is coarse, lagged, and drops out in tunnels** → Mitigation: smoothing +
  hysteresis + standstill clamp + bounded IMU dead-reckoning + explicit INVALID gating
  and degraded-mode HMI (D7, speed-context & driver-alert-hmi specs).
- **Monocular distance/TTC is geometry-approximate** → Mitigation: temporal Kalman
  filtering, ego-path relevance filtering for FCW, conservative latency-margin
  thresholds; positioned as assistance, not precision ranging.
- **False positives erode driver trust** → Mitigation: confidence gating, dwell times,
  intent suppression for LDW, ego-path filtering for FCW (per-warning specs).
- **AGPL-3.0 on YOLO11n** → Mitigation: D9 licensing decision before distribution.
- **Exynos / non-Snapdragon device** → Mitigation: runtime SoC detection + LiteRT/GPU
  fallback with a reduced-performance indicator (realtime-inference spec).
- **Wrong SoC id / dsp_arch on S26 causes silent CPU fallback or refused build** →
  Mitigation: read and pin `ro.board.platform` / SoC id / HTP arch on the actual unit
  before the first context-binary build (`doc/project_draft`).
- **Safety/liability of a non-certified aid** → Mitigation: acknowledged safety
  disclaimer, warnings-only (no actuation), honest degraded-mode signaling.

## Migration Plan

Greenfield rollout (no existing system to migrate):
1. Scaffold the Kotlin app: CameraX preview + `ImageAnalysis`, foreground service,
   permissions, transparent overlay.
2. Stand up the QNN runtime on the **S22+** (`v69`/`taro`, confirmed): YOLO11n → ONNX →
   INT8 → context binary; validate quantized accuracy vs FP baseline.
3. Wire the perception contract and the overlay; confirm ≥15 FPS sustained on S22+ via
   the 10-min thermal loop.
4. Add speed-context (GPS + IMU dead-reckoning + validity contract).
5. Implement the four warning evaluators on top of the shared contract + speed-context.
6. Build HMI alerts (visual/audible/haptic), HUD mirror, degraded indicators, disclaimer.
7. Read S26 Ultra SoC id / HTP arch on-device, build the `v81` context binary, re-run
   the sustained benchmark; make the deploy call on post-throttle FPS.
*Rollback*: each warning function is independently toggleable; the app degrades to a
detection-overlay-only mode if any downstream layer is unstable.

## Open Questions

- **Licensing (D9)**: Enterprise YOLO license vs swap to an Apache/BSD detector before
  release? Resolve before distribution.
- **S26 Ultra QNN params**: exact `soc_id` and `dsp_arch` for 8 Elite Gen 5 — must be
  read on the physical unit; `doc/project_draft` marks them TBD.
- **Calibration UX**: how does the driver set camera mounting height/angle for the
  ground-plane distance model — guided one-time calibration vs assumed defaults?
- **Intent inference for LDW**: with no turn-signal access, which best-effort cues are
  reliable enough to suppress warnings without masking real departures?
