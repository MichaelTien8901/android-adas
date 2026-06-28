## Why

The app records dashcam clips on-device (`dashcam-recording`) but offers **no way to
manage them**: clips can only be enumerated internally and pulled via `adb`. There is no
on-device UI to browse, search, delete, or do anything with a recording. Separately, the
replay feed — the tool that drives perception/LDW validation from recorded footage — is
hardwired to a single `replay.mp4` that must be `adb push`-ed into app-private storage;
a driver cannot pick one of their own recordings to replay.

This change gives the driver a **clip library** on the device — find, review, delete, and
replay recordings — and stores clips where they are **easy to find and export over USB**
when the phone is plugged into a computer, instead of buried in app-private
`Android/data/…` that modern file managers and MTP hide.

## What Changes

- **Clip library screen** (new Activity reachable from the driving/settings UI): lists all
  recordings newest-first with capture datetime, duration, and size.
- **Search / filter**: find clips by date (day / month) and other facets (e.g. duration,
  size). Backed by the date-organized storage layout below.
- **Delete**: remove a single clip or a multi-selection, with a confirm step; updates the
  retention accounting. Bulk "delete all" / "delete a day" supported.
- **Replay a recorded clip**: select any clip in the library as the replay source. Starting a
  session in replay mode then feeds that clip through the perception pipeline. This is an
  **additional** source — the existing `adb push replay.mp4` workflow is **retained** as the
  fallback used when no library clip is selected, so the dev/validation path is unchanged.
- **Export / share**: surface a clip (or selection) to other apps / file managers via a
  share action, and — more importantly — store clips on **shared, MTP/USB-visible storage**
  so they appear as ordinary files when the phone is mounted on a computer.
- **Date-organized storage layout** (**BREAKING** to the on-disk path): clips are written
  into `…/<YYYY>/<MM>/<YYYY-MM-DD>/` subfolders (year → month → that day's clips together)
  instead of one flat `dashcam/` directory, so a day's footage is a single folder to browse
  or copy off. Filenames keep the sortable capture-datetime convention. Retention
  (size-capped circular deletion) operates across the hierarchy and prunes empty folders.
- **Recording target moves** from app-private `getExternalFilesDir(null)/dashcam/` to the
  MTP-visible shared location. Existing flat clips are migrated or remain readable by the
  library (decided in design).

## Capabilities

### New Capabilities
- `clip-library`: On-device management of recorded clips — browse, search/filter, review
  metadata, delete (single/multi/bulk), select a clip as the replay source, and
  export/share. Defines the management UI surface and operations contract.

### Modified Capabilities
- `dashcam-recording`: The "Clips are retrievable" / known-location requirement and the
  size-capped retention requirement change — clips now live in a **date-organized hierarchy
  on MTP/USB-visible shared storage** rather than a single flat app-private directory, and
  retention walks that hierarchy (and prunes empty date folders) instead of one folder.

## Impact

- **Code**
  - `recording/RecordingStore.kt` — directory layout (date subfolders), `newClipFile`/
    `clips()`/`planEviction`/`enforce` updated to walk the hierarchy; storage moves to a
    shared/MTP-visible location.
  - `recording/DashcamRecorder.kt` — writes into the dated path via the updated store.
  - `replay/ReplaySource.kt` — read the **selected** library clip when one is chosen,
    otherwise fall back to the pushed `replay.mp4` (retained); resolve the path from the
    library selection or the existing default.
  - `service/DrivingService.kt` — `attachPreview()` passes the selected replay clip to
    `ReplaySource`; expose clip-management operations to the UI layer.
  - New `ui/` Activity (+ layout/adapter) for the clip library; entry point from
    `DrivingActivity` / `SettingsActivity`.
  - `Prefs` — store the selected replay clip; possibly storage-location/search prefs.
- **Storage & platform**
  - Shared, MTP/USB-visible storage (e.g. a public `Movies/`-class location via MediaStore,
    or `MANAGE_EXTERNAL_STORAGE` — chosen in design). May add an `AndroidManifest`
    permission and/or a `FileProvider` for the share action.
  - On-disk path change for recordings (**BREAKING**); needs a migration/back-compat note.
- **Specs**: new `clip-library` spec; delta to `dashcam-recording` (storage layout +
  retention). No change to the `adas-perception` replay-feed requirement (the feed contract
  is unchanged; only its source selection moves from hardcoded to library-driven).
