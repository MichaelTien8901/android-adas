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
- [x] 2.3 Decoder refinement + Kalman: dropped the wide `da` fallback, capped the `ll`
      search to a half-lane (`MAX_HALF`), and applied the existing `LaneTracker` (Kalman
      coeffs + chi-square/jump gate) to the TwinLite output. Result on replay:
      - **Jitter 0.066 → 0.0013 (~40×)** — gross `R→1.00` outliers gone (capped + gated).
      - Mid ego-right = 0.66–0.69, **on the real paint (0.67–0.68)**.
      - Visually a clean, smooth corridor; both boundaries converge correctly; right line
        no longer snaps to the adjacent car / frame edge.
      - Latency still ~205 ms CPU (1.8 MB model). 100% availability.
      - Minor residual: near-field right ~0.80–0.84 (a touch wide, but steady not noisy);
        a left+width prior could tighten it further.
- [x] 2.4 Scene A/B (replay sweep, 12 frames): TwinLite+Kalman is clean and correct on
      straight + curve + multi-lane-with-passing-car sections; right line no longer snaps
      to adjacent lanes/cars; mid ego-right stays on real paint (0.67–0.69). One residual:
      occasional sparse/short right boundary on transitions (incomplete, not wrong).
      Near-field "wide" reading is geometrically consistent (a width prior is a no-op),
      so the cap+Kalman already resolved the real outliers. **Verdict: TwinLite+Kalman
      beats UFLDv2 on the right-lane failure.**

## 3. QNN/HTP follow-up (get it off CPU onto the NPU)

- [ ] 3.1 Convert `twinlite.onnx` → DLC (`qairt-converter`, QAIRT 2.47) — input `images`
      1×3×360×640, outputs `da`,`ll`.
- [ ] 3.2 INT8 quantize with representative road frames (TwinLite preprocessing: 640×360,
      RGB, /255) → `twinlite_quant.dlc`; validate seg-mask accuracy vs FP.
- [ ] 3.3 Generate context binaries `twinlite_v69.bin` (S22+) / `twinlite_v81.bin` (S26),
      bundle in assets; QnnModelRunner picks them up (multi-output `da`/`ll`).
- [ ] 3.4 On-device: confirm `twinlite -> QNN_HTP` and the latency drop vs ~205 ms CPU;
      re-score paint-deviation to confirm INT8 didn't regress the lane head.

## 3. Decide

- [ ] 3.1 Verdict vs baseline; if win → follow-up change for QNN/HTP context binary +
      default flip; if loss → abandon this branch with the score recorded.
