## ADDED Requirements

### Requirement: Speed-limit sign recognition
The system SHALL detect and classify speed-limit signs in the forward scene and
maintain the current applicable speed limit. A recognized limit SHALL persist as the
active limit until superseded by a newer recognized sign, with stale limits expiring
after a configured distance/time without confirmation.

#### Scenario: Speed-limit sign recognized
- **WHEN** a speed-limit sign is detected and classified above the confidence threshold
- **THEN** the system updates the current applicable speed limit

#### Scenario: Stale limit expiry
- **WHEN** no speed-limit sign has been confirmed for the configured distance/time
- **THEN** the current limit is marked stale/unknown rather than retained indefinitely

### Requirement: Over-speed warning
The system SHALL warn the driver when the ego speed exceeds the current applicable
speed limit by more than a configurable tolerance margin, and SHALL clear the warning
when the speed returns within the limit-plus-tolerance.

#### Scenario: Exceeding the limit
- **WHEN** valid ego speed exceeds the current limit by more than the tolerance margin
- **THEN** the system issues an over-speed warning showing the limit and current speed

#### Scenario: Within tolerance
- **WHEN** the ego speed is at or below the limit plus tolerance
- **THEN** no over-speed warning is active

#### Scenario: Unknown limit
- **WHEN** no current speed limit is known (none recognized or stale)
- **THEN** no over-speed warning is issued

### Requirement: Other-sign and traffic-light recognition (best-effort)
The system SHALL, on a best-effort basis, recognize stop signs and traffic-light state
when present and surface them as informational cues, without treating them as
authoritative control inputs.

#### Scenario: Stop sign detected
- **WHEN** a stop sign is detected above the confidence threshold
- **THEN** the system surfaces a stop-sign informational cue

#### Scenario: Traffic light detected
- **WHEN** a traffic light is detected with a classifiable state
- **THEN** the system surfaces the light state as an informational cue only
