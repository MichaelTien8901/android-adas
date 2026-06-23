## MODIFIED Requirements

### Requirement: Lane geometry extraction
The system SHALL extract ego-lane geometry (left and right lane boundaries) from the
forward scene when lane markings are visible, producing lane-line positions usable by
the lane-departure logic. When lane markings are not reliably detectable, the system
SHALL report lanes as unavailable rather than emitting spurious geometry.

When the lane-stability tracker is enabled, the system SHALL additionally maintain a
**temporal track of each ego-lane boundary at the curve-coefficient level** (not raw
per-row points), so that the published geometry is continuous across frames and robust
to per-frame argmax noise. Each published lane boundary SHALL carry a **confidence /
track-validity** indication distinguishing a measured frame from a predicted (coasted)
one. When the tracker is disabled, behaviour is unchanged from the existing per-frame
pipeline.

#### Scenario: Lanes detected
- **WHEN** clear lane markings are present in the scene
- **THEN** the system outputs left and right ego-lane boundary geometry for the current frame

#### Scenario: Lanes not detectable
- **WHEN** lane markings are absent, occluded, or too faint to detect reliably
- **THEN** the system reports lane geometry as unavailable for that frame

#### Scenario: Tracker enabled, noisy single frame
- **WHEN** the tracker is enabled and a single frame's fitted coefficients deviate
  implausibly from the established track (e.g. the slot snapped onto an adjacent lane)
- **THEN** the published geometry follows the temporal track rather than the noisy
  single-frame fit, and the offending measurement is gated out or down-weighted

## ADDED Requirements

### Requirement: Temporal lane-coefficient tracking
When enabled, the system SHALL track ego-lane curve coefficients through a recursive
temporal filter (Kalman, optionally with a clothoid curvature-rate state). The per-frame
weighted curve fit SHALL be treated as a measurement whose noise is derived from the
detector's per-point confidence, and the filter output SHALL be the lane geometry
published downstream. The process model SHALL allow coefficients to vary with ego speed
and curvature so the track follows genuine maneuvers without lag.

#### Scenario: Steady lane, jitter suppressed
- **WHEN** consecutive frames produce noisy but consistent lane fits
- **THEN** the tracked output is smooth frame-to-frame without the per-frame zig-zag

#### Scenario: Genuine lane change
- **WHEN** the vehicle performs a real lane change producing a sustained lateral shift
- **THEN** the track follows the maneuver within a bounded number of frames rather than
  treating it as an outlier indefinitely

### Requirement: Measurement gating and bounded gap prediction
The system SHALL gate measurement updates by a normalized-innovation (chi-square) test
and a per-frame lateral-jump bound; a rejected measurement SHALL cause the track to
predict-only (coast) on its process model. Coasting SHALL be bounded to a configured
maximum number of frames, after which the track SHALL be marked stale and the affected
lane reported unavailable. Prediction uncertainty SHALL grow with each coasted frame so a
stale track cannot be reported as confident.

#### Scenario: Dashed-line gap
- **WHEN** the lane marking is briefly absent (dashed gap or short dropout) within the
  coast budget
- **THEN** the system predicts lane geometry through the gap and resumes measuring when
  markings reappear

#### Scenario: Coast budget exceeded
- **WHEN** measurements are rejected or absent for longer than the coast budget
- **THEN** the track is marked stale and the lane is reported unavailable rather than
  emitting invented geometry

### Requirement: Left/right lane consistency guard
The system SHALL cross-check the two ego-lane boundary tracks for plausible lane width
and approximate parallelism, and SHALL reject or down-weight a measurement on one side
that violates consistency with the other (confident) side.

#### Scenario: Adjacent-lane mixing rejected
- **WHEN** one boundary's measurement implies an implausible lane width or a strongly
  non-parallel pair (the adjacent-lane-mixing signature)
- **THEN** that measurement is gated out and the affected boundary follows its track or
  the consistent side
