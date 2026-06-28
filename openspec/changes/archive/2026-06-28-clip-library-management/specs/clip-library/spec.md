## ADDED Requirements

### Requirement: On-device clip library

The system SHALL provide an on-device screen that lists all recorded dashcam clips, reachable
from the app UI. The list SHALL show, per clip, the capture date/time, duration, and file
size, and SHALL be ordered newest-first by default.

#### Scenario: Open the library

- **WHEN** the user opens the clip library from the app
- **THEN** every recorded clip is listed newest-first with its capture datetime, duration, and
  size

#### Scenario: Empty library

- **WHEN** the user opens the clip library and no clips exist
- **THEN** the screen SHALL show an empty state rather than an error

#### Scenario: List reflects new recordings

- **WHEN** a new clip is recorded and the user opens (or refreshes) the library
- **THEN** the new clip appears in the list

### Requirement: Search and filter clips

The system SHALL let the user narrow the clip list by date — at least by month and by day —
and SHALL support sorting (newest/oldest). Filtering and sorting SHALL operate on the
date-organized storage so results match the on-disk day/month grouping.

#### Scenario: Filter by day

- **WHEN** the user filters the library to a specific day
- **THEN** only clips captured on that day are shown

#### Scenario: Filter by month

- **WHEN** the user filters the library to a specific month
- **THEN** only clips captured in that month are shown

#### Scenario: No matches

- **WHEN** a filter matches no clips
- **THEN** the screen SHALL show an empty result rather than an error

### Requirement: Delete clips

The system SHALL let the user delete a single clip, a multi-selection of clips, or all clips
within a chosen day, after a confirmation step. Deletion SHALL remove the underlying file from
storage and update the retention accounting. The clip currently being recorded SHALL NOT be
deletable from the library.

#### Scenario: Delete a single clip

- **WHEN** the user deletes one clip and confirms
- **THEN** that clip's file is removed from storage and it disappears from the list

#### Scenario: Delete multiple selected clips

- **WHEN** the user selects several clips, chooses delete, and confirms
- **THEN** all selected clips are removed from storage and the list updates

#### Scenario: Delete a day

- **WHEN** the user deletes all clips for a chosen day and confirms
- **THEN** every clip captured that day is removed and the day's folder is no longer shown

#### Scenario: Confirmation required

- **WHEN** the user triggers any delete
- **THEN** the deletion SHALL proceed only after an explicit confirmation

#### Scenario: Active clip cannot be deleted

- **WHEN** a recording is in progress and that clip appears in the library
- **THEN** the in-progress clip SHALL NOT be deletable

### Requirement: Play and stop a recorded clip

The system SHALL let the user play a recorded clip immediately for review in an in-app player,
and SHALL provide a control to stop playback. This playback is independent of the ADAS
replay-source selection and SHALL NOT change the replay source or the driving session.

#### Scenario: Play a clip immediately

- **WHEN** the user taps "play" on a clip in the library
- **THEN** the clip begins playing immediately in the in-app player

#### Scenario: Stop playback

- **WHEN** a clip is playing and the user stops it
- **THEN** playback halts and the user returns to the library

#### Scenario: Play does not affect replay

- **WHEN** the user plays a clip for review
- **THEN** the ADAS replay source and any driving session are unchanged

### Requirement: Set a recorded clip as the ADAS replay source

The system SHALL let the user set any clip in the library as the source for the ADAS replay
feed. Setting the source SHALL only record the selection — it SHALL NOT start playback, change
live driving behavior, or auto-start a session. When a session is later started in replay mode
through the existing replay flow, the perception pipeline SHALL be fed from the selected clip.

#### Scenario: Set the replay source

- **WHEN** the user chooses "set as replay source" on a clip in the library
- **THEN** that clip is recorded as the replay source and nothing else starts or changes

#### Scenario: Replay uses the selected clip

- **WHEN** a clip has been set as the replay source and the user starts a session in replay
  mode through the existing replay flow
- **THEN** the perception/warning pipeline is driven by frames decoded from the selected clip

### Requirement: Pushed replay clip lives in a USB-visible folder

The system SHALL read the pushed replay clip from a single, USB/MTP-visible, app-owned folder,
so a replay clip can be dropped in over USB (or `adb push`) without targeting hidden
app-private storage. When no library clip is selected as the replay source, replay mode SHALL
use the pushed clip from that folder. The previous app-private `replay.mp4` SHALL be moved into
this folder by the one-time migration so an existing replay clip is not lost.

#### Scenario: Pushed clip in a USB-visible folder

- **WHEN** a replay clip is placed in the documented USB-visible replay folder and no library
  clip is selected
- **THEN** starting replay mode feeds the pipeline from that pushed clip

#### Scenario: Selection overrides the pushed clip

- **WHEN** a library clip is selected as the replay source
- **THEN** replay mode uses the selected clip instead of the pushed clip

#### Scenario: Original replay clip is migrated

- **WHEN** a `replay.mp4` existed in the old app-private location at upgrade
- **THEN** the one-time migration moves it into the USB-visible replay folder and replay uses it
  from there

### Requirement: Pushed replay clips are manageable in the library

The clip library SHALL also list the clip(s) in the USB-visible replay folder, marked as the
replay source, and SHALL let the user play and delete them. Deleting a pushed replay clip SHALL
remove it from that folder.

#### Scenario: Replay clip listed and labelled

- **WHEN** a clip is present in the replay folder and the library is opened
- **THEN** it appears in the list marked as the replay source

#### Scenario: Play a pushed replay clip

- **WHEN** the user plays a pushed replay clip from the library
- **THEN** it plays in the in-app player like any other clip

#### Scenario: Delete a pushed replay clip

- **WHEN** the user deletes a pushed replay clip and confirms
- **THEN** the file is removed from the replay folder and it disappears from the list

### Requirement: Export and share clips

The system SHALL let the user share/export a clip (or a multi-selection) to other apps via the
platform share mechanism, and clips SHALL be stored so they are directly accessible as files
when the device is connected to a computer over USB (see the `dashcam-recording` storage
requirements).

#### Scenario: Share a clip to another app

- **WHEN** the user chooses "share" on a clip
- **THEN** the system offers the platform share sheet and the receiving app can read the clip

#### Scenario: Clips visible over USB

- **WHEN** the device is connected to a computer over USB and browsed as media storage
- **THEN** the recorded clips appear as ordinary files in their date-organized folders without
  any developer tooling
