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

## 2. On-device evaluation (replay, S26 CPU)

- [x] 2.1 Ran replay with `laneModel=twinlite`; captured `LANEPAINT` + frames.
- [x] 2.2 Result vs UFLDv2+tracker baseline:
      - **MID row (where UFLDv2 failed): TwinLite WINS.** TwinLite ego-right = 0.65–0.68,
        landing ON the real ego-right paint (0.67–0.68). UFLDv2 was 0.70–0.79 — drifted
        onto the next lane. The segmentation lane head finds the correct marking where
        UFLDv2's slot can't. **Core hypothesis validated.**
      - **NEAR row: TwinLite WORSE (decoder bug).** Right jumps to 0.77–1.00 (often 1.00 =
        frame edge): when `ll` has a dashed gap at the bottom, the `da`-corridor fallback
        runs to the road edge. UFLDv2 near was a stable-but-too-far ~0.82.
      - **Jitter** higher (0.066 vs 0.003 — no temporal smoothing yet).
      - **Latency** lower: ~205 ms CPU vs ~250 ms (1.8 MB model).
- [ ] 2.3 Decoder refinement (next): drop/cap the wide `da` fallback; cap the `ll` search
      to a plausible half-lane (kill the R→1.00 / next-lane snaps); use a left-boundary +
      width prior for the right at near; add temporal smoothing. Re-score.

## 3. Decide

- [ ] 3.1 Verdict vs baseline; if win → follow-up change for QNN/HTP context binary +
      default flip; if loss → abandon this branch with the score recorded.
