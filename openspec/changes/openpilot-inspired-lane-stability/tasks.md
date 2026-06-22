# Tasks — openpilot-inspired-lane-stability

Implements task 5.8 of `adas-realtime-android` (dashed-lane robustness) as a focused
change. Borrows openpilot's stability mechanisms as classical surrogates; ports no model.
See `proposal.md`, `design.md` (D1–D8), `specs/`, and `research/01-openpilot-reuse-analysis.md`.

## 1. Research & decision (done)

- [x] 1.1 Research openpilot reusability (license, model I/O, camera/calibration needs)
      and replaceability (does it cover TSR/speed/stop?) — documented in
      `research/01-openpilot-reuse-analysis.md`.
- [x] 1.2 Decision recorded: borrow the mechanisms, not the model; keep all TSR/speed/stop
      features (proposal "Explicitly Unchanged", design D1).

## 2. LaneTracker core (adas-perception)

- [ ] 2.1 Add `perception/LaneTracker.kt`: a Kalman filter over ego-lane curve
      coefficients `[a, b, c]` (quadratic `x = a·y² + b·y + c`) in the active fitting
      frame (BEV when `birdEyeLaneFit` on, else image space). One tracker instance per
      ego boundary (left, right). (design D2)
- [ ] 2.2 Process model: near-constant (random-walk) coefficients with adaptive process
      noise `Q` scaled by ego speed and a yaw/curvature proxy; expose `Q` base + scale as
      `companion` consts. (design D3)
- [ ] 2.3 Measurement model: take the existing per-frame weighted fit (`robustQuad`/IRLS)
      as the measurement; derive measurement noise `R` from UFLDv2 existence ×
      soft-argmax confidence (confidence → covariance). (design D4)
- [ ] 2.4 Feed per-point UFLDv2 confidence into the fit as point weights (if not already),
      so faint rows contribute less to the measurement. (design D4)

## 3. Gating, gap prediction, consistency (adas-perception)

- [ ] 3.1 Measurement gating: reject/clip an update failing a chi-square normalized-
      innovation test OR exceeding a per-frame lateral-jump bound; rejected → predict-only
      coast. (design D5)
- [ ] 3.2 Bounded gap prediction: coast at most `TRACK_COAST_MAX` frames; grow prediction
      covariance each coasted frame; mark track stale past budget and report the lane
      unavailable. (design D6)
- [ ] 3.3 Left/right consistency guard: reject/down-weight a one-side measurement that
      implies implausible lane width or strong non-parallelism vs the confident side;
      optionally share curvature `a`. (design D7)

## 4. Wiring & settings (adas-perception, driver-alert-hmi)

- [ ] 4.1 Wire `LaneTracker` into `LaneDetector.detect()` after the per-frame fit, gated
      on the new toggle; when off, pipeline is byte-for-byte unchanged. (design D8)
- [ ] 4.2 Publish per-lane confidence / track-validity (measured vs predicted) on the lane
      geometry so LDW can honour the coast window. (spec: adas-perception output)
- [ ] 4.3 Add `laneStabilityTracker` to `Prefs` (key `lane_stability_tracker`, default
      `false`); add `SettingsActivity` switch + `strings.xml` label/hint, mirroring
      `laneMarkingSnap` / `birdEyeLaneFit`. Thread through `DrivingService` →
      `PerceptionEngine` → `LaneDetector`.
- [ ] 4.4 Decide whether the drivable-area overlay consumes the tracked curve (design open
      question) and wire accordingly.

## 5. Tests & validation

- [ ] 5.1 Unit tests (FakeRunner / `laneOutputs` harness, like `WarningLogicTest`):
      jitter suppression, outlier rejection of an adjacent-lane-mixed frame, gap
      prediction within budget, stale-after-budget → unavailable, width/parallelism guard.
- [ ] 5.2 Replay-feed validation: run zig-zag clip, multi-lane clip, and a real
      lane-change clip; compare tracked vs current; confirm no over-smoothing/lag on the
      genuine lane change. (design D3/D5 risk)
- [ ] 5.3 On-device A/B on S22+ and S26 Ultra; confirm sub-ms tracker cost against the
      ~33 ms frame budget.
- [ ] 5.4 Decide shipped default model: 3-state quadratic vs 4-state clothoid (design open
      question) from replay results; only then consider a separate change to flip the
      default on.

## 6. Docs

- [ ] 6.1 Mark task 5.8 of `adas-realtime-android` as implemented-by this change (or fold
      its RANSAC pre-fit note in if 5.2 shows it's needed).
- [ ] 6.2 `openspec validate openpilot-inspired-lane-stability --strict` clean; archive on
      completion.
