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

- [x] 3.1 Convert `twinlite.onnx` → DLC (`qairt-converter`, QAIRT 2.47) — input `images`
      1×3×360×640, outputs `da`,`ll`. Graph name `twinlite`. Done in the `qnnbuild`
      ubuntu:22.04 container (Python 3.10 venv `/root/qnnvenv`); host 3.12 can't load the
      converter (libc++.so.1). ConvTranspose/BatchNorm merge warnings are benign.
- [x] 3.2 INT8 quantize: 64 real road frames (`build/dashcam_test.mp4`) preprocessed to
      TwinLite input (640×360 RGB /255 NCHW, `tools/gen_twinlite_calib.py` →
      `build/qnn/twinlite_calib/`); `qairt-quantizer --use_per_channel_quantization` →
      `twinlite_q.dlc` (697 KB). Per-tensor INT8 ranges healthy (ll min/max −10.7/14.6,
      scale 0.099) — argmax preserved; on-device re-score (3.4) confirms no regression.
- [x] 3.3 `qnn-context-binary-generator` (config `ext_twinlite.json`→`dev_twinlite.json`,
      `dsp_arch:v81`, NO soc_id) → `twinlite_v81.bin` (1.5 MB) + `twinlite_v69.bin`
      (1.6 MB, soc_id 36). Bundled in `assets/models/`. QnnModelRunner picks per-arch.
- [x] 3.4 On-device (S26, replay): `EngineFactory: twinlite -> QNN_HTP` ✓ — the seg
      graph loads on the NPU (the upsample-op risk did NOT block conversion).
      **Bug found + fixed:** the QNN path returns unnamed `out0`/`out1` in graph order
      `[da, ll]`, but `TwinLiteLaneDetector.pick("ll", 0)` fell back to index 0 = `da`;
      decoding the drivable-area mask as lane-lines made every centre pixel "road" →
      `avail=0%` (no lanes). Fixed: positional fallback for `ll` = index **1** (ORT path
      still binds by name). After fix:
      - Mid ego-right **0.65–0.68**, on the real paint (0.67–0.68) — matches the CPU
        baseline; INT8 did NOT regress the lane head.
      - Jitter `meanBottomDx` ~0.0015 (CPU 0.0013); `avail=100%`; clean overlay on a frame.
      - Latency **~176 ms avg** (159–202) on QNN_HTP vs **~205 ms** CPU — modest (~14%)
        gain, limited by the segmentation upsample (175 MB DDR spill in graph finalize);
        the larger win is CPU offload. Pipeline 5 fps (replay-bound).

- [x] 3.5 Right-line dropout on transitions (user-reported "right lane line sometimes
      disappears"): the right boundary is the DASHED marking; when its `ll` pixels are
      lost/occluded longer than the tracker's coast budget (a passing car, lane split,
      faded paint) the right track went stale and the line vanished. Pre-existing eval
      residual (2.4), NOT a QNN/INT8 regression. **Safe fix (keeps the right boundary
      measurement-driven so right-side LDW still works):**
      - `TwinLiteLaneDetector.isLane` now tests a ±`DILATE_R`(=4)-row vertical window
        (bridges dashed gaps / INT8-thinned dashes at the SAME column → no x bias).
      - `LaneTracker.TRACK_COAST_MAX` 6 → 12 (~2.4s hold through a gap).
      Rejected the width-prior alternative (synthesize right = left + width): it would
      defeat right-side lane-departure warning. Re-test (64 frames, replay): avail
      100% (was 99%), **0 right-boundary dropouts**, mid R steady 0.64–0.68 on real
      paint (accuracy unchanged), full corridor holds with an adjacent car present.

## 4. Decide

- [x] 4.1 **Verdict: WIN.** TwinLite+Kalman beats UFLDv2 on the right-lane failure
      (mid ego-right on real paint vs UFLDv2's drift onto the next lane), and the
      QNN/HTP path is now built + validated on the S26 (`twinlite -> QNN_HTP`, INT8 no
      regression, ~176 ms vs ~205 ms CPU, right boundary holds through transitions).
      Next: a follow-up change to flip the default `laneModel` to `twinlite` and decide
      whether to retire the UFLDv2 path (or keep it as a fallback).
