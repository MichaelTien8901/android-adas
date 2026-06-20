## ADDED Requirements

### Requirement: GPS-derived speed acquisition
The system SHALL obtain ego speed from the device's fused location provider, since a
phone app cannot access the vehicle CAN bus. The system SHALL NOT depend on any
vehicle/CAN speed source. Each speed sample SHALL carry the source GPS accuracy.

#### Scenario: Speed from location updates
- **WHEN** location updates are available with a valid speed field
- **THEN** the system uses the GPS-derived speed as the ego speed source

#### Scenario: No vehicle integration
- **WHEN** the app runs on a mounted phone (not Android Automotive OS)
- **THEN** the system relies solely on GPS/IMU and never attempts to read vehicle/CAN speed

### Requirement: Speed smoothing and standstill handling
The system SHALL low-pass filter the raw GPS speed to reduce jitter and SHALL clamp
speeds below a standstill floor to zero so that parked-vehicle GPS jitter is not
interpreted as motion.

#### Scenario: Jitter smoothing
- **WHEN** raw GPS speed fluctuates between successive 1 Hz samples
- **THEN** the published speed is a smoothed value rather than the raw per-sample value

#### Scenario: Standstill clamp
- **WHEN** the smoothed speed is below the standstill floor
- **THEN** the published speed is reported as zero / stopped

### Requirement: Dead-reckoning through GPS dropouts
The system SHALL bridge short GPS dropouts (tunnels, garages, urban canyons) by
estimating speed from the last valid GPS speed fused with IMU data, for a bounded
dead-reckoning window after which speed is declared invalid.

#### Scenario: Tunnel dropout within window
- **WHEN** GPS fixes stop arriving but the dead-reckoning window has not elapsed
- **THEN** the system continues to publish an IMU-bridged speed marked as dead-reckoned

#### Scenario: Dropout beyond window
- **WHEN** GPS remains unavailable past the dead-reckoning window
- **THEN** the published speed is marked INVALID

### Requirement: Speed validity contract
The system SHALL publish, with every speed value, a validity state of `VALID`,
`DEAD_RECKONED`, or `INVALID`. All speed-dependent warnings SHALL read this validity
state and SHALL NOT consume raw GPS speed directly.

#### Scenario: Validity exposed to consumers
- **WHEN** any warning function requests ego speed
- **THEN** it receives both the smoothed speed value and its validity state

#### Scenario: Invalid speed gates warnings
- **WHEN** the validity state is INVALID
- **THEN** speed-dependent warnings are suppressed and the degraded state is signaled to the HMI

### Requirement: Latency-aware output
The system SHALL document and account for GPS speed lag, exposing the data freshness
so warning logic can apply latency safety margins during rapid acceleration or braking.

#### Scenario: Stale-but-valid sample
- **WHEN** the most recent valid speed sample is older than a freshness threshold but within the valid window
- **THEN** the published speed carries a freshness indicator consumers can use to widen safety margins
