## Context

Real driving with the shipped app surfaced two lane-quality failures our single-frame
UFLDv2 pipeline can't fully fix in post-processing:
- **Zig-zag**: per-row argmax noise makes lane lines jitter frame-to-frame even after
  the local median+moving-average smoothing in `LaneDetector.smoothLane()`.
- **Adjacent-lane mixing**: on multi-lane roads, UFLDv2's per-row independence lets the
  ego-lane slot lock onto the *neighbouring* lane's markings for a band of rows, so the
  fitted curve kinks toward the wrong lane.

We researched how production ADAS handles this and studied comma.ai **openpilot** in
depth (`research/01-openpilot-reuse-analysis.md`). Two findings frame this design:

1. **openpilot can't replace us.** It is MIT-licensed and portable in principle, but it
   does **no traffic-sign / speed-limit / stop-sign recognition** (documented
   limitations) — all features we ship — and its model needs every frame reprojected
   into comma's fixed camera intrinsics plus an auto-calibrated extrinsic frame, with a
   recurrent/stateful graph that is the riskiest part to convert to QNN/HTP.
2. **openpilot's stability is intrinsic to the model**: a recurrent GRU state (carries
   lane shape across frames, fills gaps) plus **per-point uncertainty** the planner
   down-weights, plus a `desire` input that *commands* lane changes so multi-lane
   geometry never "confuses" it.

**Decision (with the user): borrow the ideas, not the model.** Reproduce openpilot's
three stability *mechanisms* as on-device classical surrogates layered on our existing
UFLDv2 output, changing only the lane path and keeping every other feature untouched.

**Current state**: `LaneDetector.kt` already decodes ego-left/ego-right slots, does
soft-argmax + per-row temporal EMA, optional marking-snap, optional bird's-eye fit, and
local median+average smoothing. There is no curve-level temporal model and no
cross-lane consistency check. Task 5.8 of `adas-realtime-android` (dashed-lane
robustness) is the open task this change implements.

## Goals / Non-Goals

**Goals:**
- A **curve-level temporal track** of the ego lane (Kalman on `[a, b, c]`, optional
  clothoid curvature-rate state) — the classical surrogate for openpilot's recurrence —
  that smooths jitter and predicts through dashed gaps and brief dropouts.
- **Outlier gating** on each measurement so an adjacent-lane-mixed frame is rejected or
  down-weighted instead of corrupting the track.
- **Confidence weighting**: UFLDv2 existence/soft-argmax confidence flows into the fit
  (point weights) and the tracker (measurement covariance).
- A **left/right consistency guard** (lane width + parallelism) that directly attacks
  adjacent-lane mixing.
- Ship behind an **off-by-default experimental toggle**, validated on the replay feed.

**Non-Goals:**
- **Not porting the openpilot model**, not adding camera-intrinsics reprojection, not
  adding extrinsic auto-calibration, not adding a `desire`/planner/MPC layer.
- **No change** to TSR, FCW, headway, speed-context, HMI, or the YOLO11n detection path.
- No new model, no native code, no ABI/page-alignment/QNN-pipeline change.
- Not a steering/path output — this remains warnings-only lane geometry for LDW + the
  drivable-area overlay.

## Decisions

### D1 — Borrow the mechanisms, not the model
**Decision**: implement three classical surrogates mapped 1:1 onto openpilot's stability
sources, rather than porting `supercombo`. *Mapping*: GRU recurrence → Kalman/clothoid
coefficient track; per-point uncertainty → confidence-weighted fit + measurement
covariance; `desire`/multi-lane robustness → outlier gating + width/parallelism guard.
*Alternatives considered*: (a) **port supercombo** — rejected for this change: needs
camera reprojection to comma intrinsics, extrinsic auto-calibration, recurrent-graph
QNN conversion risk, and still wouldn't cover TSR/speed/stop (kept as an option for a
future change/eval spike); (b) **deeper post-processing of raw points** — rejected: the
zig-zag and mixing are temporal/structural, not per-frame smoothable. *Rationale*: gets
~the production stability win at near-zero runtime cost and zero new model risk.

### D2 — Track curve coefficients, not raw points
**Decision**: the Kalman state is the ego-lane polynomial in the fitting frame
(BEV when `birdEyeLaneFit` is on, image-space otherwise): `x = a·y² + b·y + c`, state
`[a, b, c]` (+ optional curvature-rate `a'` for a clothoid model). The per-frame
weighted fit (existing IRLS/`robustQuad`) produces the **measurement**; the filter
produces the **output** curve that LDW and the overlay consume. *Alternatives*:
per-row Kalman (56 independent filters) — rejected: doesn't enforce a coherent curve and
can't gate adjacent-lane mixing as a whole. *Rationale*: a 3–4 state filter is the
smallest object that captures "lane shape," matches what openpilot's net outputs, and is
sub-millisecond to update.

### D3 — Constant-velocity-in-coefficient process model with adaptive Q
**Decision**: model coefficients as slowly varying (random-walk / near-constant), with
process noise `Q` scaled up with ego speed and yaw-rate proxy (faster geometry change at
speed / in curves) so the filter isn't sluggish on real maneuvers. Measurement noise `R`
is derived from the per-frame fit confidence (D4). *Rationale*: lane shape is highly
autocorrelated frame-to-frame at video rate; a near-constant model is the right prior and
is what makes gap-prediction credible.

### D4 — Confidence as measurement covariance, not a hard gate alone
**Decision**: UFLDv2 existence × soft-argmax sharpness becomes (a) per-point weight in
the fit and (b) the scalar that inflates `R` for the update. Low-confidence frames still
update the track, just weakly. *Rationale*: mirrors openpilot's per-point std — degrade
gracefully rather than flip between "trust fully" / "ignore."

### D5 — Outlier gating via normalized innovation (chi-square) + jump guard
**Decision**: reject/clip a measurement when its innovation (measured vs predicted
coefficients) exceeds a chi-square gate, OR when implied lateral position jumps more than
a plausible per-frame bound (the adjacent-lane-mixing signature). A rejected frame →
**predict-only** (track coasts on the process model). Consecutive rejections are capped
(D6). *Rationale*: this is the surrogate for openpilot never getting "confused" by
multi-lane geometry — bad measurements simply don't enter the state.

### D6 — Bounded gap prediction with explicit validity window
**Decision**: the track may coast (predict-only) through dashed gaps / dropouts /
rejected frames for at most `TRACK_COAST_MAX` frames (≈ a small fraction of a second of
video; tuned on replay). Beyond that the track is marked **stale** and lane geometry is
reported unavailable (LDW already gates on lane-availability). Prediction covariance grows
each coasted frame so a stale track can't masquerade as confident. *Rationale*: "virtual
lane lines" are valuable but must not invent geometry indefinitely — openpilot's
uncertainty grows through gaps too.

### D7 — Left/right coupling: lane-width + parallelism consistency guard
**Decision**: maintain both ego-boundary tracks and reject a measurement on one side when
it implies an implausible lane width or a strongly non-parallel pair versus the other
(confident) side; optionally share curvature `a` between sides (the existing BEV
`couple()` already does a form of this). *Rationale*: directly targets adjacent-lane
mixing — the wrong-lane snap shows up as a width/parallelism violation before it shows up
anywhere else.

### D8 — Strictly additive, behind an off-by-default toggle
**Decision**: add `laneStabilityTracker` to `Prefs` (key `lane_stability_tracker`,
default `false`), a `SettingsActivity` switch + `strings.xml` hint (mirroring
`laneMarkingSnap` / `birdEyeLaneFit`), and gate the new `LaneTracker` stage in
`LaneDetector.detect()` on it. When off, behaviour is byte-for-byte today's pipeline.
*Rationale*: same rollout pattern as the last two lane experiments; lets us A/B on the
replay feed and on-device before changing any default.

## Risks / Trade-offs

- **Over-smoothing / lag on real maneuvers** — a too-stiff `Q` makes the track ignore
  genuine lane changes. *Mitigation*: adaptive `Q` (D3), innovation gate sized to admit
  real maneuvers, replay validation on lane-change clips.
- **Gating a *correct* surprising measurement** (e.g. real sharp curve) as an outlier.
  *Mitigation*: speed/curvature-scaled gate, bounded coast (D6) so a persistent new
  geometry is re-acquired quickly when the old track goes stale.
- **Clothoid adds a state with little data to constrain it** — may be unobservable at low
  curvature. *Mitigation*: make the curvature-rate term optional (D2); start with the
  3-state quadratic, add clothoid only if replay shows benefit.
- **Tuning burden** (`Q`, `R`, gate thresholds, coast length) — *Mitigation*: derive `R`
  from existing confidence, expose constants as `companion` consts like the rest of
  `LaneDetector`, tune once on the replay feed.

## Open Questions

- Track in **BEV or image space** when `birdEyeLaneFit` is off? (Lean: track in whatever
  frame the fit is done, resample back as today.)
- **3-state quadratic vs 4-state clothoid** as the shipped default — decide from replay
  results.
- Should the **drivable-area overlay** also consume the tracked (smoothed) curve, or keep
  raw for responsiveness? (Lean: tracked, for visual stability.)
- Does this **fully implement task 5.8**, or do we still want the RANSAC pre-fit as a
  separate measurement-cleaning stage in front of the tracker?

## Migration / Rollout

1. Land `LaneTracker.kt` + tests, toggle off by default — no behaviour change shipped.
2. Validate on the replay feed (zig-zag clip + multi-lane clip + lane-change clip),
   compare tracked vs current.
3. On-device A/B on S22+ / S26 Ultra.
4. Only after that, consider flipping the default (separate, small change).
