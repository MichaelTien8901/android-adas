## MODIFIED Requirements

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
