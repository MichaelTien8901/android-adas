## Why

On-device validation with the `LANEPAINT` ground-truth harness proved the ego-RIGHT
lane error is a **UFLDv2 model limitation, not a post-processing bug**: at the mid row
the real ego-right marking sits at ~0.67–0.68 but UFLDv2 predicts ~0.70–0.79 (often
with *no* paint near it), and at the near row the right slot locks onto the *next lane
over* (~0.83). `R_raw == R_out` in every frame — so the fragile decode is upstream of
all our fitting. The left slot matches paint every frame; the right slot does not.

We have stacked **six** post-processing layers (temporal EMA, median+moving-average,
marking-snap, bird's-eye fit, IRLS robust quad, Kalman tracker, left+width gate) to
patch a detector that is looking at the wrong line. UFLDv2 is row-anchor + TuSimple-
trained (sparse highway, ≤4 lanes); the literature notes row-anchor methods have
"significantly lower localization accuracy for side lanes." We are over-trusting it.

This change sets up an **objective bake-off** of alternative ego-lane perception
approaches, each spiked on its own branch and scored on the same benchmark, so the
model decision is data-driven rather than another patch.

## What Changes

- Define a **shared, objective evaluation harness** (already built): the `LANEPAINT`
  per-row independent paint detection, formalized into a per-run **score** = mean
  lateral deviation of the predicted ego-left / ego-right boundaries from the nearest
  detected lane paint, plus lane-width consistency, run on the replay feed via the
  ONNX-Runtime/CPU path (no QNN needed to evaluate).
- Define a **model-selection seam** so a candidate lane model can be dropped in behind
  a setting and scored without disturbing the shipped UFLDv2 path.
- Enumerate the **candidate options**, each implemented + tested on its own branch:
  - `eval/twinlitenet-drivable-area` — drivable-area + lane **segmentation**
    (TwinLiteNet+/YOLOP): ego lane derived from the road mask, sidestepping slot
    assignment. **Most promising.**
  - `eval/clrernet-lane` — CLRerNet anchor-based lane lines (higher line accuracy).
  - Baseline: **UFLDv2 + tracker + gate** (this branch's parent) — the number to beat.
- Produce a **comparison report** (paint-deviation, width consistency, latency, and
  visual frames) and a recommendation.

## Capabilities

### Modified Capabilities
- `adas-perception`: adds an evaluation contract — a lane model is scored by objective
  paint-deviation on the replay feed before adoption; the lane-geometry source becomes
  pluggable for evaluation.

### Explicitly Unchanged
- The shipped pipeline default stays UFLDv2 until a candidate wins the bake-off. No
  ADAS warning logic, TSR, FCW, or speed features change.

## Impact

- **Eval-only infrastructure**: the paint-deviation scorer (built), a model-selection
  toggle, and per-candidate ONNX assets fetched via `tools/` export scripts (same
  pattern as `fetch_lane.sh`). Candidates run on ONNX-Runtime/CPU for evaluation; the
  winner later gets the QNN/HTP context-binary treatment (tasks 4.x of
  `adas-realtime-android`).
- **No new dependency on any one model** until the bake-off concludes.
- Branches are throwaway-friendly: a losing candidate's branch is abandoned, not merged.
