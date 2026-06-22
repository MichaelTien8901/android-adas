## Why

The camera feed is consumed only for live perception and then discarded. Persisting it
as a dashcam recording gives two things we don't have today: (a) incident footage a driver
can keep, and (b) a corpus of real on-road video to retrain the lane / detection models on.
Recording must be bounded — it cannot fill the device — so it runs as a size-capped circular
buffer of short, datetime-named clips.

## What Changes

- Add a **record on/off** control in the driving overlay (live toggle) so the driver can
  start/stop capture mid-session.
- Add a **Settings** section (`make this a setting option`) so recording can be enabled by
  default and configured. New `Prefs`:
  - `dashcamEnabled` — master on/off (default off).
  - `dashcamSegmentMinutes` — max duration per file (default e.g. 3 min).
  - `dashcamMaxStorageMb` — total storage cap for the clip directory (default e.g. 2048 MB).
- **Segmented recording**: write the feed to discrete files, each rolled over at the
  configured max duration. **Filename encodes the capture datetime** (e.g.
  `dashcam_2026-06-22_124530.mp4`) so clips sort and are identifiable.
- **Circular storage (looping)**: before/while writing, enforce the total-size cap by
  deleting the **oldest** clips first when the cap (or free disk) would be exceeded.
- **Retrieval**: clips live in a known, pullable directory so they can be retrieved later
  for training.

This is additive and opt-in — with `dashcamEnabled = false` the perception pipeline is
unchanged (**not** a breaking change).

## Capabilities

### New Capabilities
- `dashcam-recording`: capture control (on/off via Settings + live button), time-segmented
  file writing with datetime-stamped names, size-capped circular retention (delete-oldest),
  and a retrievable storage location for the clips.

### Modified Capabilities
<!-- None — recording is additive and does not change existing perception/warning requirements. -->

## Impact

- **Camera pipeline**: recording needs the camera stream that perception already owns. A
  recorder component is added — either a CameraX `VideoCapture` use case bound alongside the
  existing `ImageAnalysis`, or encoding the analysis frames via `MediaCodec`. (Trade-off
  resolved in design.md.) Touches `CameraController` / `DrivingService` lifecycle so capture
  starts/stops with the session and the toggle.
- **Settings / Prefs**: 3 new options + a Settings UI section (consistent with the existing
  `Prefs` + `SettingsActivity` pattern).
- **Driving overlay**: a record button + recording-state indicator.
- **Storage**: a new clip directory + a rotation/retention manager (segment rollover,
  oldest-first deletion against the size cap). Scoped-storage / app-specific-dir handling and
  any runtime permissions.
- **Replay**: unaffected — recording is for the live camera path; replay mode does not record.
