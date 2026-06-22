# Tasks — dashcam-recording

Branch `feature/dashcam-recording`. Implements the spec: configurable, segmented, size-capped
dashcam recording on the live camera path. Ordered by dependency.

## 1. Settings + Prefs (the "setting option")

- [x] 1.1 Add `Prefs`: `dashcamEnabled` (Bool, default false), `dashcamSegmentMinutes`
      (Int, default 3), `dashcamMaxStorageMb` (Int, default 2048), with keys + getters/setters.
- [x] 1.2 Add a "Recording" section to `SettingsActivity` + layout: enable toggle and
      segment-minutes / max-storage-MB inputs (EditText number fields), with hint strings.
- [x] 1.3 String resources for the new labels/hints.

## 2. Storage / retention (RecordingStore)

- [x] 2.1 `RecordingStore`: resolve the clip dir (`getExternalFilesDir("dashcam")`), create it,
      expose the path for retrieval.
- [x] 2.2 Filename helper: `dashcam_yyyy-MM-dd_HHmmss.mp4` (sortable, monotonic suffix on
      collision) and a parser/sorter (by name datetime, fallback `lastModified`).
- [x] 2.3 Retention: sum dir size; when over `dashcamMaxStorageMb` (or device free space below
      a floor), delete oldest-first down to a low-water mark (~90% of cap); never delete the
      active clip. Unit-test the eviction ordering + low-water hysteresis (pure JVM).

## 3. Recorder (CameraX VideoCapture)

- [x] 3.1 Add CameraX video dependency (`androidx.camera:camera-video`).
- [x] 3.2 `DashcamRecorder`: build a `Recorder`/`VideoCapture`; start/stop a `Recording` to a
      datetime-named file in the clip dir; expose `isRecording`.
- [x] 3.3 Segment rollover: at `dashcamSegmentMinutes`, finalize the current `Recording` and
      immediately start the next (background executor timer); call `RecordingStore` retention
      on each finalize and at start.
- [x] 3.4 Failure isolation: catch encoder/storage errors, surface a status flag, never crash
      the session; if free space can't be secured, stop recording.

## 4. Camera + session wiring

- [x] 4.1 `CameraController`: optionally bind `VideoCapture` as a 3rd use case (Preview +
      ImageAnalysis + VideoCapture) only when recording is enabled; handle unsupported-binding
      by disabling recording gracefully (`isConcurrent... ` / catch bind failure).
- [x] 4.2 `DrivingService`: own the `DashcamRecorder` lifecycle — auto-start when
      `dashcamEnabled` on the live (non-replay) path; stop + finalize on session teardown.
      Ensure replay mode never records.

## 5. Driving UI control

- [x] 5.1 Add a record on/off button to the driving overlay; wire to start/stop within the
      session (overrides the Settings default for the session).
- [x] 5.2 Recording-state indicator (e.g. red dot / "REC") reflecting `isRecording`.

## 6. Verify on device

- [x] 6.1 Built + installed (S26); enabled recording, live camera, 1-min segments. Clips
      appeared at `/sdcard/Android/data/com.adasedge.app/files/dashcam/` as
      `dashcam_2026-06-22_HHMMSS.mp4` and rolled over at ~60s (134918 → 135008 → 135108 →
      135208). Record button shown in the overlay.
- [x] 6.2 Circular retention confirmed: with a 256 MB cap, on the rollover that crossed the
      cap `RecordingStore` logged "deleted 2 oldest clip(s) to stay under 256MB"; the two
      oldest were removed, total dropped to 174 MB, and the in-progress clip was untouched.
- [x] 6.3 No perception regression: detector + twinlite ran on QNN_HTP concurrently with
      recording at 65–101 ms / 10–11 fps, no crash. Replay path does not record (verified the
      live path only; replay returns before the recorder). Pulled a finalized clip via adb —
      valid `ISO Media, MP4 v2`, 90 MB. (Device restored to replay mode after the test.)
