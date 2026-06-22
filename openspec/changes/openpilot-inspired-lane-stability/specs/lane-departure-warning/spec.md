## MODIFIED Requirements

### Requirement: Lane-availability gating
The system SHALL only issue lane-departure warnings when lane geometry is available
from the perception layer. When lanes are unavailable, the function SHALL be inactive
rather than warning on guessed geometry.

When the lane-stability tracker is enabled, lane geometry MAY be supplied by a predicted
(coasted) track through brief marking gaps and SHALL still be considered available during
that bounded prediction window. Once the track is marked stale (coast budget exceeded),
lane geometry SHALL be treated as unavailable and the warning SHALL go inactive.

#### Scenario: Lanes unavailable
- **WHEN** the perception layer reports lane geometry as unavailable
- **THEN** no lane-departure warning is issued for that period

#### Scenario: Predicted geometry within coast window
- **WHEN** the tracker is enabled and lane markings are briefly absent but the track is
  still within its coast budget
- **THEN** lane-departure evaluation continues on the predicted geometry

#### Scenario: Track gone stale
- **WHEN** the track exceeds its coast budget and is marked stale
- **THEN** lane geometry is treated as unavailable and lane-departure warnings go inactive

## ADDED Requirements

### Requirement: Stability against adjacent-lane mixing
On multi-lane roads, the system SHALL NOT issue a lane-departure warning that is caused
solely by the ego-lane boundary momentarily snapping onto an adjacent lane's markings.
The temporal track and left/right consistency guard SHALL absorb such single-frame
mis-associations before they reach the departure logic.

#### Scenario: Neighbour-lane snap on a multi-lane road
- **WHEN** the detector momentarily associates the ego boundary with an adjacent lane's
  markings while the vehicle stays centred
- **THEN** no lane-departure warning is issued, because the mixed measurement is gated
  out of the track
