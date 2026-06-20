## ADDED Requirements

### Requirement: Time-headway estimation
The system SHALL compute the time-headway (THW) to the lead vehicle as the estimated
following distance divided by the ego speed, using the perception-layer distance
estimate and valid speed context. THW SHALL be temporally smoothed before being used
for warnings.

#### Scenario: Headway computed
- **WHEN** a lead vehicle is detected and valid speed context is available
- **THEN** the system computes a smoothed time-headway value in seconds

#### Scenario: No lead vehicle
- **WHEN** no lead vehicle is detected in the ego path
- **THEN** no headway value is produced and no tailgating warning is active

### Requirement: Tailgating warning
The system SHALL warn the driver when time-headway falls below a configured safe
threshold (defaulting toward the two-second rule) for a sustained period, and SHALL
clear the warning when headway returns above the threshold plus a hysteresis margin.

#### Scenario: Following too closely
- **WHEN** the smoothed time-headway stays below the safe threshold for the configured dwell time
- **THEN** the system issues a tailgating warning

#### Scenario: Headway restored
- **WHEN** the time-headway rises above the threshold plus hysteresis margin
- **THEN** the tailgating warning is cleared

#### Scenario: Transient dip ignored
- **WHEN** the time-headway dips below threshold only momentarily (below the dwell time)
- **THEN** no tailgating warning is issued

### Requirement: Speed-gated activation
The system SHALL only evaluate tailgating warnings when valid speed context is
available and ego speed is above a minimum activation speed, so that stop-and-go and
standstill traffic do not generate nuisance warnings.

#### Scenario: Low-speed / stop-and-go
- **WHEN** the ego speed is below the headway-monitoring activation speed
- **THEN** tailgating warnings are not issued

#### Scenario: Speed context invalid
- **WHEN** speed context is reported as INVALID
- **THEN** headway-based warnings are suppressed and the degraded state is reflected in the HMI
