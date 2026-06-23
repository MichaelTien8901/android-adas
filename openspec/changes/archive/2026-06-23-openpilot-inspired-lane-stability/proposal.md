## Why

Real-world driving exposed two persistent lane-quality problems our single-frame
UFLDv2 pipeline cannot fully solve in post-processing: **frame-to-frame zig-zag**
(noisy, jittery lane lines) and **adjacent-lane mixing** (the ego-lane slot snapping
onto the neighbouring lane's markings on multi-lane roads). We researched how
production ADAS — specifically comma.ai's **openpilot** — avoids these, and confirmed
openpilot is **MIT-licensed** and technically portable, but **cannot replace our
system**: it does **no traffic-sign, speed-limit, or stop-sign recognition** (all
shipped features here), it requires reprojecting every frame into comma's fixed camera
intrinsics + an auto-calibrated extrinsic frame, and its recurrent/stateful graph is
the highest-risk part to convert to QNN/HTP. See `research/01-openpilot-reuse-analysis.md`.

The decision (recorded with the user) is **borrow the ideas, not the model**:
reproduce openpilot's *sources* of stability — a recurrent temporal state and
per-point uncertainty — as **on-device classical surrogates** on top of our existing
UFLDv2 output. This keeps every current ADAS feature intact and avoids the camera
re-projection / model-conversion risk.

## What Changes

- Add a **Kalman/clothoid lane-coefficient tracker**: instead of smoothing raw per-row
  points, track the ego-lane curve coefficients `[a, b, c]` (quadratic, optionally a
  clothoid curvature-rate term) through a Kalman filter. This is the classical
  equivalent of openpilot's recurrent GRU state — it carries lane shape across frames,
  smooths jitter, and **predicts through dashed gaps and brief dropouts** ("virtual
  lane lines").
- Add **outlier gating** on the measurement update: per-frame fits whose coefficients
  or residuals jump implausibly (the adjacent-lane-mixing signature) are rejected or
  down-weighted before they corrupt the track — the surrogate for openpilot's
  per-point uncertainty and lane-change `desire` input.
- Add **confidence weighting** end-to-end: feed UFLDv2 existence/soft-argmax
  confidence into the fit as point weights and into the tracker as measurement
  covariance, so faint/ambiguous rows contribute less.
- Add a **lane-width / parallelism consistency guard** (couples left/right tracks):
  reject a measurement that implies an implausible lane width or non-parallel pair,
  the direct fix for adjacent-lane mixing.
- Add a **settings toggle** (`Lane stability tracker`, experimental, default off)
  mirroring the existing `laneMarkingSnap` / `birdEyeLaneFit` pattern, plus replay-feed
  validation.

## Capabilities

### New Capabilities
- None. This change adds no new ADAS function; it improves the quality of an existing
  one.

### Modified Capabilities
- `lane-departure-warning`: lane geometry consumed by LDW is now temporally tracked
  (Kalman/clothoid) with outlier gating, gap prediction, and a width/parallelism guard
  — improving stability and reducing adjacent-lane false departures. Adds requirements
  for temporal continuity, measurement gating, and gap-prediction validity windows.
- `adas-perception`: the lane-geometry output contract gains a per-lane **confidence /
  track-validity** signal and a defined behaviour for predicted-vs-measured frames.

### Explicitly Unchanged
- `traffic-sign-recognition`, `collision-warning`, `headway-monitoring`,
  `speed-context`, `realtime-inference`, `driver-alert-hmi` — **no change**. openpilot
  does not provide these; they remain our implementation. This change does **not** port
  the openpilot model, does not add camera-intrinsics reprojection, and does not touch
  the object-detection (YOLO11n) path.

## Impact

- **Code**: new `LaneTracker.kt` (Kalman/clothoid + gating) in `perception/`, wired
  into `LaneDetector.detect()` after the per-frame fit and behind the new toggle;
  `Prefs` + `SettingsActivity` + `strings.xml` for the toggle; unit tests in
  `WarningLogicTest`-style harness for gating/prediction logic.
- **No new models, no new native libs, no ABI/page-alignment impact** — pure Kotlin on
  top of existing UFLDv2 output. No change to the QNN/HTP pipeline or device targets.
- **Performance**: a 3-state (or 4-state clothoid) Kalman update per lane per frame is
  sub-millisecond; negligible against the ~33 ms frame budget.
- **Risk**: low — fully behind an off-by-default toggle, validated on the replay feed
  before any on-device default change. Supersedes/implements task 5.8 of
  `adas-realtime-android` (dashed-lane robustness).
