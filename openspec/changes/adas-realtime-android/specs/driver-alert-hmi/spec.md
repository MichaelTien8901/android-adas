## ADDED Requirements

### Requirement: Realtime perception overlay
The system SHALL render a transparent overlay aligned to the live camera preview
showing detected object bounding boxes, ego-lane geometry, and active warning
indicators. The overlay SHALL update in realtime and SHALL NOT lag the camera by more
than one inference cycle.

#### Scenario: Overlay reflects current perception
- **WHEN** a new perception result is published
- **THEN** the overlay redraws bounding boxes, lane lines, and warning state for that result

#### Scenario: Overlay stays in sync
- **WHEN** inference falls behind the camera frame rate
- **THEN** the overlay tracks the latest processed frame without accumulating lag

### Requirement: Multi-modal warning alerts
The system SHALL present warnings using visual, audible, and haptic modalities, with
urgency-scaled cues (advisory vs imminent). Audible/haptic alert behavior SHALL be
user-configurable (e.g. mute).

#### Scenario: Imminent collision alert
- **WHEN** an imminent forward-collision warning is active
- **THEN** the system shows an urgent visual cue, plays an audible alert, and triggers a haptic cue

#### Scenario: User mutes audio
- **WHEN** the user disables audible alerts in settings
- **THEN** warnings are presented visually (and haptically) without sound

### Requirement: Heads-up (HUD) mirror mode
The system SHALL provide a HUD mode that mirrors the warning display for reflection off
the windshield, presenting a simplified high-contrast warning view suitable for glance
reading.

#### Scenario: HUD mode enabled
- **WHEN** the user enables HUD mode
- **THEN** the display switches to a mirrored, simplified high-contrast warning layout

### Requirement: Degraded-mode and status indicators
The system SHALL clearly indicate when its capability is reduced — including speed
signal lost/dead-reckoned, thermal throttling, fallback inference path, or lanes
unavailable — so the driver's expectation matches actual capability.

#### Scenario: Speed signal lost
- **WHEN** speed context becomes INVALID
- **THEN** the HMI shows a "speed signal lost" indicator and reflects that speed-gated warnings are suppressed

#### Scenario: Reduced-performance path
- **WHEN** the runtime is using the non-NPU fallback or is thermally throttled
- **THEN** the HMI shows a reduced-performance indicator

### Requirement: Foreground operation and screen-on
The system SHALL run as a foreground service while in active driving mode and SHALL
keep the screen on, so perception and warnings continue without the device sleeping.

#### Scenario: Active driving keeps screen on
- **WHEN** the app is in active driving mode
- **THEN** a foreground service runs and the screen is kept on for the duration

### Requirement: Safety disclaimer
The system SHALL present a driver-assistance safety disclaimer that the app is an
assistance aid, not a certified or autonomous ADAS, and that warnings may be late or
absent (e.g. where GPS is weak). The user SHALL acknowledge the disclaimer before first
use of active driving mode.

#### Scenario: First-run acknowledgement
- **WHEN** the user starts active driving mode for the first time
- **THEN** the disclaimer is shown and must be acknowledged before perception begins

#### Scenario: Disclaimer accessible later
- **WHEN** the user opens settings/about
- **THEN** the safety disclaimer remains available for review
