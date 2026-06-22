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

- [ ] 6.1 Build + install (S26); enable recording, drive the live camera; confirm clips appear
      in the dashcam dir with datetime names and roll over at the segment limit.
- [ ] 6.2 Confirm circular retention: drive past the size cap, verify oldest clips are deleted
      and total stays under the cap; active clip never deleted.
- [ ] 6.3 Confirm no perception regression (FPS / lane / detection unchanged with recording on)
      and that replay mode does not record. Pull a clip via `adb` to confirm retrievability.
