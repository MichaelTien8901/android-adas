## ADDED Requirements

### Requirement: Lane departure detection
The system SHALL warn the driver when the vehicle is departing its ego lane without
intent, based on the vehicle's position/trajectory relative to the detected lane
boundaries. A departure SHALL be declared when the projected lateral position crosses
a configured distance-to-line threshold.

#### Scenario: Drift across lane line
- **WHEN** the vehicle approaches or crosses a detected lane boundary beyond the departure threshold
- **THEN** the system issues a lane-departure warning indicating the side of departure

#### Scenario: Lane re-centered
- **WHEN** the vehicle returns within the lane boundaries
- **THEN** the lane-departure warning is cleared

### Requirement: Intent-based suppression
The system SHALL suppress lane-departure warnings when the departure is intentional.
Because a phone app cannot read the vehicle turn-signal state, the system SHALL infer
intent from available cues (e.g. detected turn-signal status if available, deliberate
steering geometry) and SHALL document that turn-signal-based suppression is best-effort.

#### Scenario: Intentional lane change
- **WHEN** the system infers an intentional lane change from available intent cues
- **THEN** the lane-departure warning is suppressed for that maneuver

#### Scenario: No intent signal available
- **WHEN** no reliable intent cue is available
- **THEN** the warning behaves as a plain departure warning, and the app documents the lack of turn-signal access

### Requirement: Speed-gated activation
The system SHALL only evaluate lane-departure warnings when valid speed context is
available and the ego speed is above the lane-departure activation speed (defaulting to
a highway-range threshold). Activation SHALL use hysteresis to avoid flicker around the
threshold.

#### Scenario: Below activation speed
- **WHEN** the ego speed is below the lane-departure activation speed
- **THEN** lane-departure warnings are not issued

#### Scenario: Hysteresis around threshold
- **WHEN** the GPS speed oscillates around the activation threshold
- **THEN** the activation state changes only after crossing the upper/lower hysteresis bounds, not on every oscillation

### Requirement: Lane-availability gating
The system SHALL only issue lane-departure warnings when lane geometry is available
from the perception layer. When lanes are unavailable, the function SHALL be inactive
rather than warning on guessed geometry.

#### Scenario: Lanes unavailable
- **WHEN** the perception layer reports lane geometry as unavailable
- **THEN** no lane-departure warning is issued for that period
