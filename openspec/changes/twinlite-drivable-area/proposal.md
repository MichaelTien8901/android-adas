## Why

The `lane-detector-bakeoff` proved UFLDv2's ego-RIGHT slot mis-detects on multi-lane
roads at the model level (`R_raw == R_out`; right slot lands on the next-lane paint).
This branch spikes the primary alternative: **TwinLiteNet**, a lightweight multi-task
**segmentation** model. Instead of classifying "which of 4 line-slots is the ego
boundary" (the thing UFLDv2 gets wrong), the ego boundaries are read from a dense
lane-line / drivable-area mask — there is no slot to assign, so that failure mode can't
occur.

## What Changes

- Add `assets/models/twinlite.onnx` (1.8 MB; input `1×3×360×640`, outputs `da` and `ll`
  2-class masks) fetched from the public TwinLiteNet ONNX release.
- Add `Preprocess.toSegInput` (full-frame resize to 640×360, RGB, /255, NCHW — matches
  the model's training preprocessing; 16:9 frames map without distortion).
- Add `TwinLiteLaneDetector`: per row, the nearest lane-line (`ll`) pixel each side of
  the ego centre is the ego-left / ego-right boundary; falls back to the drivable-area
  (`da`) corridor edge where `ll` is blank. Emits the same `LaneGeometry` contract.
- Add a `laneModel` pref (`ufldv2` default | `twinlite`) and branch the lane path in
  `PerceptionEngine`; runs on the existing ONNX-Runtime/CPU seam (no QNN needed to eval).

## Capabilities

### Modified Capabilities
- `adas-perception`: adds TwinLiteNet as a selectable ego-lane source for evaluation;
  default UFLDv2 path unchanged.

## Impact

- Eval-only: behind `laneModel=twinlite`; default stays UFLDv2. No warning/TSR/FCW change.
- Scored on the replay paint-deviation benchmark (`LANEPAINT`) vs the UFLDv2+tracker
  baseline. If it wins, a follow-up change does QNN/HTP + default flip; if not, this
  branch is abandoned.
