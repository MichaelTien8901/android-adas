## 1. Storage foundation (MediaStore date layout)

- [x] 1.1 Add a `ClipRepository` that queries the MediaStore `Video` collection for app-owned
      clips under `Movies/ADASEdge/`, returning `Clip` (uri, name, sizeBytes, captureMillis,
      durationMs, relativePath/day)
- [x] 1.2 Add a date-path helper that maps a capture timestamp to
      `Movies/ADASEdge/<YYYY>/<MM>/<YYYY-MM-DD>/` and a clip name `dashcam_<YYYY-MM-DD_HHmmss>.mp4`
- [x] 1.3 Implement `ClipRepository.newClipOutput(nowMillis)` → `MediaStoreOutputOptions` in the
      dated folder (CameraX manages the `IS_PENDING` create/finalize)
- [x] 1.4 Implement `ClipRepository.delete(uris)` and `deleteDay(day)` over owned entries

## 2. Recorder migration

- [x] 2.1 Switch `DashcamRecorder` from `FileOutputOptions` to `MediaStoreOutputOptions` using
      `ClipRepository.newClipOutput`
- [x] 2.2 Finalize each segment's MediaStore entry on rollover/stop (CameraX clears `IS_PENDING`
      on finalize); rollover logic unchanged — verify no-gap rollover on device (see 7.2)
- [ ] 2.3 Add a feature-flag fallback to the legacy app-private `dashcam/` file path if
      MediaStore output is unavailable on a device (rollback path) — DEFERRED; a MediaStore
      failure is already contained (recorder logs + disables recording, no crash)

## 3. Retention over the hierarchy

- [x] 3.1 Rework `RecordingStore` to source clips from `ClipRepository`; keep `planEviction`
      pure (now keyed by content URI, ordered by capture time) and update its unit tests
- [x] 3.2 `enforce()` deletes oldest-by-capture across all date folders until under the
      low-water cap; the active clip is excluded (pending in MediaStore + newest-first ordering)
- [x] 3.3 `freeSpaceBytes()` uses a `StatFs` check on the shared volume; recorder stops (not
      crash) when space cannot be secured
- [x] 3.4 Empty date folders do not affect size accounting (MediaStore accounts per entry, so
      empty folders are inherently ignored)

## 4. Replay source selection (fallback retained)

- [x] 4.1 Add `Prefs.selectedReplayClip` (nullable content-URI string) with get/set/clear
- [x] 4.2 Update `ReplaySource` to decode the selected clip URI via
      `MediaExtractor.setDataSource(context, uri, null)`, falling back to the pushed
      `replay.mp4` file path when no selection is set
- [x] 4.3 Pass the resolved replay source through `DrivingService.attachPreview()`
- [x] 4.4 Replay source resolution verified on device: a selected library clip drives replay
      (`REPLAY MODE: <id>`), and a deleted/unreadable selection falls back via `canRead`
- [x] 4.5 Pushed-replay folder moved to the USB-visible app media dir `Android/media/<pkg>/replay/`
      (via `getExternalMediaDirs()`); `ReplaySource` reads `replay.mp4` there (else newest `*.mp4`);
      replay hint string + push path updated. Device-verified: an adb-pushed clip in that folder
      drives replay (`REPLAY MODE: replay.mp4`), no media permission needed
- [x] 4.6 Original app-private `replay.mp4` migrated into the new folder (synchronous same-volume
      rename in `attachPreview`, idempotent). Device-verified: 90 MB `replay.mp4` moved out of
      `…/files/` into `Android/media/<pkg>/replay/` and replay fed from it
- [x] 4.7 Surface pushed replay clip(s) in the clip library (File-backed `Clip`, pinned + labelled
      "Replay source (pushed)"); Play + Delete only (`deleteClips` routes file vs MediaStore).
      Device-verified: clip listed + labelled, `file://` Play → ClipPlayerActivity, Delete removed
      it from the folder + list

## 5. Clip library UI

- [x] 5.1 Create `ui/ClipLibraryActivity` + layout + RecyclerView adapter listing clips
      newest-first with capture datetime, duration, size; empty state
- [x] 5.2 Add an entry point to the library from `SettingsActivity`
- [x] 5.3 Add search/filter by day (and month via the dated paths) plus newest/oldest sort,
      backed by `ClipRepository`; empty-result state
- [x] 5.4 Add per-item Delete and multi-select bulk delete and "delete a day", each with a
      confirm dialog; the in-progress clip is pending and not listed, so it can't be deleted
- [x] 5.5 Add per-item "Play" → immediate in-app playback with a stop control (a
      `ClipPlayerActivity` using `VideoView` + `MediaController`; independent of ADAS replay,
      does not change the replay source or any session)
- [x] 5.6 Add per-item "Set as replay source" → set `Prefs.selectedReplayClip` only (reuse the
      existing replay flow; no auto-start, no new replay options)
- [x] 5.7 Add per-item / multi-select Share via `ACTION_SEND` with the content URI(s)
- [x] 5.8 Load video thumbnails lazily (placeholder on failure)

## 6. Legacy clip migration

- [x] 6.1 One-time, idempotent (Prefs-guarded) background import of existing
      `files/dashcam/*.mp4` into the MediaStore date layout, deriving date from filename
      (fallback `lastModified`), deleting the source on success
- [x] 6.2 Import-and-forget: set the migration-done flag only when all legacy clips import; after
      that the library reads only the MediaStore layout (legacy dir no longer surfaced)

## 7. Manifest, validation & docs

- [x] 7.1 `AndroidManifest`/Gradle updated (RecyclerView dep added; no broad storage permission;
      register the new activities; `MediaStoreOutputOptions` supported on minSdk 31)
- [x] 7.2 Device validation on the S26: recording wrote `Movies/ADASEdge/2026/06/2026-06-28/`
      (USB-visible shared path); library listed clips newest-first with datetime/duration/size +
      thumbnails; play/stop, set-replay, and delete (file + MediaStore) all verified
- [x] 7.3 Retention verified on device: a 10 MB cap evicted the oldest clip
      (`retention: deleted 1 oldest clip(s)`) while the active (pending) clip was protected;
      legacy import-and-forget ran (flag set, `files/dashcam/` drained)
- [x] 7.4 Update docs/comments referencing the old flat `dashcam/` path (recorder/store docs,
      replay hint, specs)
