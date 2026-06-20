## ADDED Requirements

### Requirement: Forward collision warning based on TTC
The system SHALL warn the driver of an imminent forward collision when the estimated
Time-To-Collision (TTC) to a lead object in the ego path falls below configured
thresholds. The system SHALL provide a two-stage warning: an advisory (cautionary)
stage at a longer TTC and an imminent (urgent) stage at a shorter TTC.

#### Scenario: Advisory warning
- **WHEN** the TTC to a lead vehicle in the ego path falls below the advisory threshold but above the imminent threshold
- **THEN** the system issues a cautionary forward-collision alert

#### Scenario: Imminent warning escalation
- **WHEN** the TTC falls below the imminent threshold
- **THEN** the system escalates to an urgent forward-collision alert (stronger visual/audible/haptic cue)

#### Scenario: Clearing the hazard
- **WHEN** the lead object leaves the ego path or TTC rises back above the advisory threshold
- **THEN** the warning is cleared

### Requirement: Speed-gated activation
The system SHALL only evaluate forward collision warnings when valid speed context is
available and the ego speed is above a minimum activation speed. Warning timing SHALL
account for speed-signal latency by applying a safety margin.

#### Scenario: Below minimum speed
- **WHEN** the ego speed is below the FCW minimum activation speed
- **THEN** forward collision warnings are not issued

#### Scenario: Speed context invalid
- **WHEN** speed context is reported as INVALID
- **THEN** TTC-threshold warnings that depend on speed are suppressed and the degraded state is reflected in the HMI

### Requirement: Ego-path relevance filtering
The system SHALL issue collision warnings only for objects assessed to be in the ego
vehicle's path, using lane geometry and/or object lateral position, to limit false
positives from adjacent-lane and roadside objects.

#### Scenario: Adjacent-lane vehicle ignored
- **WHEN** a detected vehicle is in an adjacent lane and not closing within the ego path
- **THEN** no forward-collision warning is issued for that vehicle

#### Scenario: Cross-traffic / roadside object
- **WHEN** a detected object is off the ego path (parked car, roadside sign)
- **THEN** it does not trigger a forward-collision warning
