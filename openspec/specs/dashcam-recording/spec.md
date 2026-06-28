# dashcam-recording Specification

## Purpose
TBD - created by archiving change dashcam-recording. Update Purpose after archive.
## Requirements
### Requirement: Recording is a configurable setting

The system SHALL expose dashcam recording as user settings: a master enable toggle, a
maximum per-file duration in minutes, and a maximum total storage size in megabytes. Defaults
SHALL be: recording disabled, 3 minutes per file, 2048 MB total. When recording is disabled
the live perception pipeline SHALL behave exactly as before (no capture, no added cost).

#### Scenario: Recording disabled by default

- **WHEN** the app is installed and a driving session starts with no settings changed
- **THEN** no video is recorded and no clip files are created

#### Scenario: Enabling recording in Settings

- **WHEN** the user enables the dashcam toggle in Settings and starts a driving session
- **THEN** recording begins automatically for that session

#### Scenario: Configurable segment and storage limits

- **WHEN** the user sets the per-file duration and total storage cap in Settings
- **THEN** the recorder SHALL use those values for segment rollover and retention

#### Scenario: Auto-start on drive start is configurable

- **WHEN** dashcam is enabled and the "start recording on drive start" option is ON
- **THEN** recording SHALL begin automatically when a driving session starts

#### Scenario: Manual start when auto-start is off

- **WHEN** dashcam is enabled but the "start recording on drive start" option is OFF
- **THEN** recording SHALL NOT begin automatically; the record button starts it on demand

### Requirement: Live record on/off control

The driving overlay SHALL provide a record on/off button that starts and stops capture during
an active session, independent of the default set in Settings, and SHALL show whether
recording is currently active.

#### Scenario: Toggle recording mid-session

- **WHEN** the user taps the record button while driving
- **THEN** recording starts (or stops) within the current session and the indicator reflects
  the new state

#### Scenario: Recording indicator visible

- **WHEN** recording is active
- **THEN** the overlay SHALL display a visible recording indicator

### Requirement: Time-segmented files with datetime filenames

The system SHALL write the camera feed to discrete video files, rolling over to a new file
when the current file reaches the configured maximum duration. Each filename SHALL encode the
capture start datetime so files are chronologically sortable and identifiable
(e.g. `dashcam_2026-06-22_124530.mp4`).

#### Scenario: Segment rollover at the duration cap

- **WHEN** an active recording reaches the configured maximum per-file duration
- **THEN** the current file SHALL be finalized and a new file SHALL be started with no gap in
  capture

#### Scenario: Filename encodes capture datetime

- **WHEN** a new clip file is created
- **THEN** its name SHALL contain the capture start date and time in a sortable format

### Requirement: Size-capped circular retention

The system SHALL keep the total size of all recorded clips at or below the configured storage
cap by deleting the **oldest** clips first, evaluated across the entire date-organized
hierarchy (not a single flat folder). It SHALL also avoid exhausting device free space.
Retention SHALL never delete the clip currently being written. Date folders left empty by
deletion MAY be ignored or removed and SHALL NOT affect the size accounting.

#### Scenario: Oldest clips deleted when the cap is exceeded

- **WHEN** adding or growing a clip would push the total size of all clips above the configured
  size cap
- **THEN** the system SHALL delete the oldest clips, eldest first, across the whole hierarchy,
  until the total is within the cap

#### Scenario: Low device free space

- **WHEN** device free space falls below a safe minimum during recording
- **THEN** the system SHALL delete oldest clips to recover space, and if space still cannot be
  secured SHALL stop recording rather than fail the session

#### Scenario: Active clip is protected

- **WHEN** retention runs while a clip is being written
- **THEN** the in-progress clip SHALL NOT be deleted

#### Scenario: Empty date folders do not break retention

- **WHEN** deleting the last clip in a day leaves that day's folder empty
- **THEN** retention SHALL continue correctly and the empty folder SHALL NOT be counted toward
  the storage cap

### Requirement: Clips are retrievable

Recorded clips SHALL be stored on shared, MTP/USB-visible storage in a documented
date-organized hierarchy — `<YYYY>/<MM>/<YYYY-MM-DD>/` — so that a given day's footage is a
single folder and all clips can be browsed, exported, or copied off the device when it is
connected to a computer over USB, without any developer tooling. Filenames SHALL keep encoding
the capture datetime so clips remain chronologically sortable and a clip's date is recoverable
from its name as well as its folder. Storage SHALL NOT require the user to grant broad
"all files" access.

#### Scenario: Clips in a date-organized location

- **WHEN** recordings exist
- **THEN** they SHALL reside under the documented clip root in `<YYYY>/<MM>/<YYYY-MM-DD>/`
  subfolders, each clip named by its capture datetime

#### Scenario: Clips found over USB

- **WHEN** the device is connected to a computer over USB and browsed as media storage
- **THEN** the clips appear as ordinary files in their date folders, with no `adb` or other
  developer tooling required

#### Scenario: A day's clips are grouped

- **WHEN** clips were captured on the same calendar day
- **THEN** they SHALL all reside in that day's single `<YYYY-MM-DD>/` folder

### Requirement: Recording does not disrupt perception

Recording SHALL run on the live camera path only and SHALL NOT degrade ADAS perception or
warnings. Recording SHALL be inactive in replay mode. A recorder failure SHALL NOT crash or
stall the driving session.

#### Scenario: Replay mode does not record

- **WHEN** the session is running in replay mode
- **THEN** no recording occurs regardless of the dashcam setting

#### Scenario: Recorder failure is contained

- **WHEN** the recorder fails (e.g. encoder error or storage error)
- **THEN** the perception/warning pipeline SHALL continue and the failure SHALL be surfaced
  without crashing the session

