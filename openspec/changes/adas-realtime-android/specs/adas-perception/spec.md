## ADDED Requirements

### Requirement: Forward road camera capture
The system SHALL capture the forward road scene from the device camera using a
realtime analysis pipeline that delivers frames to the perception engine with a
keep-only-latest backpressure strategy. Capture resolution and frame rate SHALL be
configurable to balance accuracy against the inference latency budget.

#### Scenario: Continuous frame delivery
- **WHEN** the app is in active driving mode
- **THEN** the camera delivers frames continuously to the analyzer
- **AND** when the analyzer is busy, only the most recent frame is retained and older frames are dropped

#### Scenario: Camera permission denied
- **WHEN** camera permission is not granted
- **THEN** the app does not enter active driving mode and prompts the user to grant camera access

### Requirement: Object detection of road agents
The system SHALL detect driving-relevant objects in each processed frame — at minimum
vehicles (car, truck, bus), pedestrians, and cyclists — producing labeled bounding
boxes with confidence scores. Detections below a configurable confidence threshold
SHALL be suppressed.

#### Scenario: Vehicle ahead detected
- **WHEN** a vehicle is visible in the forward scene above the confidence threshold
- **THEN** the system emits a detection with class, bounding box, and confidence

#### Scenario: Low-confidence suppression
- **WHEN** a candidate detection's confidence is below the configured threshold
- **THEN** the detection is suppressed and not passed to downstream warning logic

### Requirement: Lane geometry extraction
The system SHALL extract ego-lane geometry (left and right lane boundaries) from the
forward scene when lane markings are visible, producing lane-line positions usable by
the lane-departure logic. When lane markings are not reliably detectable, the system
SHALL report lanes as unavailable rather than emitting spurious geometry.

#### Scenario: Lanes detected
- **WHEN** clear lane markings are present in the scene
- **THEN** the system outputs left and right ego-lane boundary geometry for the current frame

#### Scenario: Lanes not detectable
- **WHEN** lane markings are absent, occluded, or too faint to detect reliably
- **THEN** the system reports lane geometry as unavailable for that frame

### Requirement: Per-target distance and TTC estimation
The system SHALL estimate distance to the lead vehicle using monocular geometry
(camera intrinsics, ground-plane assumption, and/or known-object-width scaling) and
SHALL estimate Time-To-Collision from the change in lead-vehicle bounding-box scale
over time. Estimates SHALL be temporally filtered to reduce per-frame noise.

#### Scenario: Lead vehicle distance estimated
- **WHEN** a lead vehicle is detected in the ego lane
- **THEN** the system outputs a filtered distance estimate and a TTC estimate

#### Scenario: Noisy single-frame estimate
- **WHEN** a per-frame distance estimate spikes relative to the temporal trend
- **THEN** the filtered output suppresses the spike rather than passing it to warning logic

### Requirement: Perception output contract
The system SHALL publish a per-frame perception result containing detections, lane
geometry, and lead-vehicle distance/TTC, timestamped and consumed by the warning
capabilities. Downstream warnings SHALL operate only on this published contract.

#### Scenario: Frame result published
- **WHEN** a frame finishes processing
- **THEN** a timestamped perception result with detections, lanes, and distance/TTC is published to consumers
