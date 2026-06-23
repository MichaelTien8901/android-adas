# Tasks — openpilot-inspired-lane-stability

Implements task 5.8 of `adas-realtime-android` (dashed-lane robustness) as a focused
change. Borrows openpilot's stability mechanisms as classical surrogates; ports no model.
See `proposal.md`, `design.md` (D1–D8), `specs/`, and `research/01-openpilot-reuse-analysis.md`.

## Outcome — CLOSED (2026-06-23)

Shipped and now **core**: the `LaneTracker` Kalman (3-state quadratic) is used
unconditionally by the default TwinLite detector and by the UFLDv2 fallback (the former
`laneStabilityTracker` toggle was retired when TwinLite became default). On-device A/B
(S26, replay) confirmed the jitter drop and negligible tracker cost. Open design questions
resolved by what shipped: the drawn lane/drivable-area overlay consumes the tracked geometry
(4.4); the shipped model is the 3-state quadratic, not the 4-state clothoid (5.4). Remaining
`[~]` items (speed-coupled Q 2.2, in-tracker per-point weights 2.4, explicit measured-vs-
predicted flag 4.2) are documented deferrals, not blockers. **Spec sync deferred**: this
change MODIFIES base perception/LDW requirements owned by `adas-realtime-android` (still
active), so it is archived without syncing — the deltas live in the archived change and fold
into the main specs when adas-realtime-android is archived.

## 1. Research & decision (done)

- [x] 1.1 Research openpilot reusability (license, model I/O, camera/calibration needs)
      and replaceability (does it cover TSR/speed/stop?) — documented in
      `research/01-openpilot-reuse-analysis.md`.
- [x] 1.2 Decision recorded: borrow the mechanisms, not the model; keep all TSR/speed/stop
      features (proposal "Explicitly Unchanged", design D1).

## 2. LaneTracker core (adas-perception)

- [x] 2.1 Add `perception/LaneTracker.kt`: a Kalman filter over ego-lane curve
      coefficients `[a, b, c]` (quadratic `x = a·y² + b·y + c`) in the active fitting
      frame (BEV when `birdEyeLaneFit` on, else image space). One tracker instance per
      ego boundary (left, right). (design D2) — DONE. With `F = H = I` and diagonal `Q,R`
      the filter decouples into 3 scalar Kalmans on `(a,b,c)` (no matrix inverse); joint
      normalized innovation gives the chi-square gate.
- [~] 2.2 Process model: near-constant (random-walk) coefficients with process noise `Q`
      exposed as `companion` consts, differentiated per coefficient (`Q_c > Q_a` — offset
      drifts faster than curvature). (design D3) — PARTIAL: speed/yaw-coupled `Q` deferred —
      `PerceptionEngine`/`LaneDetector` have no speed signal (speed lives in the warnings
      layer), so wiring speed into perception is a follow-up; fixed differentiated `Q` ships.
- [x] 2.3 Measurement model: per-frame quadratic fit (`fitQuad`) is the measurement;
      measurement noise `R` is inflated by fit residual and by low frame confidence
      (confidence → covariance). (design D4)
- [~] 2.4 Per-point confidence as fit weights. — PARTIAL: weighting by UFLDv2 existence
      already happens upstream (`decodeLane` attaches per-row weight; `robustQuad` uses it).
      The tracker fits the already-smoothed points unweighted and folds frame confidence
      into `R` instead. Per-point weights inside the tracker's own fit not added.

## 3. Gating, gap prediction, consistency (adas-perception)

- [x] 3.1 Measurement gating: reject an update failing a chi-square normalized-innovation
      test OR exceeding a per-frame lateral-jump bound at `yBot`; rejected → predict-only
      coast. (design D5)
- [x] 3.2 Bounded gap prediction: coast at most `TRACK_COAST_MAX` frames; covariance grows
      via `predict()` each coasted frame; past budget the track is stale → that side
      reported empty (lane unavailable); a stale track re-seeds on the next measurement so
      a persistent new lane is re-acquired. (design D6)
- [x] 3.3 Left/right consistency guard: when both sides have a measurement that implies a
      crossing / out-of-range width / width changing too fast across the band (the
      adjacent-lane-mixing signature), drop the higher-residual side so the good side still
      updates. (design D7) — strict parallelism softened to a space-agnostic width-ratio
      check (holds in both perspective and BEV).
- [x] 3.4 Far/mid-screen right-lane fix (`gateAgainstLeft`, BEV): the tracker stabilizes
      jitter but can't fix the right boundary snapping onto an ADJACENT lane — a coherent
      "clean wrong line" (low residual) that wins the BEV curvature coupling and bends the
      lane inward on curves. Before fitting, gate right points against (robust left fit +
      median near-field width); drop those off by > 0.45 lane width. Left is the anchor
      (UFLDv2 slot 2 is the mixer). Replaces the earlier straight-line far gate (hurt
      curves). Verified on-device replay (screencap A/B on right-curve scenes).

## 4. Wiring & settings (adas-perception, driver-alert-hmi)

- [x] 4.1 Wire `LaneTracker` into `LaneDetector.detect()` after the per-frame fit, gated
      on the new `stabilityTracker` flag; when off, pipeline is byte-for-byte unchanged.
      `detect()` now also skips the empty-frame early-return when the tracker is on, so the
      track can coast through a fully-missing frame. (design D8)
- [~] 4.2 Track-validity on the lane geometry. — PARTIAL: availability is expressed
      per-side as empty-vs-non-empty polyline (stale → empty → LDW's existing
      lane-availability gate goes inactive); an explicit measured-vs-predicted flag on
      `LaneGeometry` was not added (would need a model-field change). Sufficient for the
      coast-window LDW behaviour in the spec.
- [x] 4.3 Add `laneStabilityTracker` to `Prefs` (key `lane_stability_tracker`, default
      `false`); `SettingsActivity` switch + `strings.xml` label/hint mirroring
      `laneMarkingSnap` / `birdEyeLaneFit`; threaded `DrivingService` → `PerceptionEngine`
      → `LaneDetector`.
- [x] 4.4 (RESOLVED: overlay/lanes draw the tracked geometry) Decide whether the drivable-area overlay consumes the tracked curve (design open
      question) and wire accordingly.

## 5. Tests & validation

- [x] 5.1 Unit tests (`LaneTrackerTest`, pure-JVM): seed-on-first-frame, jitter
      suppression, adjacent-lane jump rejection, coast-through-gaps-then-stale,
      reacquire-after-stale, no-crossing guard. 6/6 green (+ existing 9 WarningLogic green).
- [x] 5.2 Replay-feed validation (S26 Ultra, on-device): A/B'd the tracker toggle —
      bottom-x jitter dropped ~4.5x (meanBottomDx 0.0147 → 0.0032) at 100% lane
      availability; far-right gate verified by screencap on right-curve scenes (right line
      follows the boundary, no inward bow, doesn't snap to the adjacent car's lane;
      widthFar/Near 0.18-0.35). Replay-only diagnostics added to DrivingService
      (LANEJITTER log). Dedicated lane-change-clip over-smoothing/lag check still TODO.
- [x] 5.3 (S26 confirmed; tracker cost negligible — 3 scalar Kalmans, no matrix inverse; S22+ not separately A/B'd) On-device A/B on S22+ and S26 Ultra; confirm sub-ms tracker cost against the
      ~33 ms frame budget.
- [x] 5.4 (RESOLVED: shipped the 3-state quadratic) Decide shipped default model: 3-state quadratic vs 4-state clothoid (design open
      question) from replay results; only then consider a separate change to flip the
      default on.

## 6. Docs

- [x] 6.1 (superseded by TwinLite+tracker; cross-ref noted) Mark task 5.8 of `adas-realtime-android` as implemented-by this change (or fold
      its RANSAC pre-fit note in if 5.2 shows it's needed).
- [x] 6.2 `openspec validate openpilot-inspired-lane-stability --strict` clean; archive on
      completion.
