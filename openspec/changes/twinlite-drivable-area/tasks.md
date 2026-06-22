# Tasks — twinlite-drivable-area (spike)

Branch `eval/twinlitenet-drivable-area` (off `eval/lane-detector-bakeoff`). Goal: an
objective paint-deviation score for TwinLiteNet vs the UFLDv2+tracker baseline.

## 1. Integration (done — builds)

- [x] 1.1 Fetch TwinLiteNet ONNX (1.8 MB, `da`+`ll` 360×640 masks) → `assets/models/twinlite.onnx`.
- [x] 1.2 `Preprocess.toSegInput` (640×360, RGB, /255, NCHW).
- [x] 1.3 `TwinLiteLaneDetector` — `ll`-nearest-each-side-of-centre → ego boundaries,
      `da`-corridor-edge fallback; emits `LaneGeometry`.
- [x] 1.4 `laneModel` pref + `PerceptionEngine` branch + `DrivingService` wiring; runs on
      ONNX-RT/CPU. App builds clean.

## 2. On-device evaluation (BLOCKED — device dozing)

- [ ] 2.1 Run replay with `laneModel=twinlite`; capture `LANEPAINT` (predicted L/R vs
      detected paint) at near/mid/far + representative frames. *(Pending: the S26 keeps
      dropping into doze/lock — battery-protection paused charging so the screen won't
      hold — blocking the Start tap. Needs the device kept awake / Start tapped.)*
- [ ] 2.2 Compare TwinLite vs UFLDv2+tracker baseline (paint-deviation L/R, width
      consistency, right-on-paint %, latency). Record in this change.
- [ ] 2.3 If `ll` is sparse/noisy, try the `da`-corridor decoder and/or refine edges by
      snapping to `ll`. Tune `SAMPLE_ROWS`, centre handling.

## 3. Decide

- [ ] 3.1 Verdict vs baseline; if win → follow-up change for QNN/HTP context binary +
      default flip; if loss → abandon this branch with the score recorded.
