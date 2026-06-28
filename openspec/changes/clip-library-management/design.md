## Context

Dashcam recording (`dashcam-recording`) writes flat `.mp4` files into app-private external
storage at `getExternalFilesDir(null)/dashcam/`, named `dashcam_<YYYY-MM-DD_HHmmss>.mp4`.
`RecordingStore` owns that directory and enforces a size-capped circular buffer via a pure,
unit-tested `planEviction`. `DashcamRecorder` uses CameraX `VideoCapture<Recorder>` and asks
the store for the next file. The replay validation path (`ReplaySource`) decodes a single
hardcoded `replay.mp4` from the same app-private dir via `MediaExtractor` + `MediaCodec`,
started from `DrivingService.attachPreview()`.

Constraints: `minSdk = 31`, `targetSdk = 34` — full scoped storage, no legacy
`WRITE_EXTERNAL_STORAGE`. App is sideloaded onto the test device (S26), not Play-distributed.
Today there is **no clip-management UI** and clips are only retrievable via `adb pull` from
`Android/data/…`, which Android 11+ hides from MTP and most desktop file managers.

## Goals / Non-Goals

**Goals:**
- An on-device clip library: browse, search/filter by date, view metadata, delete
  (single/multi/bulk), select a clip as the replay source, and share/export.
- Store clips on **shared, MTP/USB-visible storage** organized by date
  (`<YYYY>/<MM>/<YYYY-MM-DD>/`) so a day's footage is one folder to copy off a mounted phone.
- Keep the existing `adb push replay.mp4` workflow working unchanged as the fallback source.
- Preserve the size-capped circular-retention guarantees over the new layout.

**Non-Goals:**
- Cloud upload/sync, in-app playback/scrubbing UI, editing/trimming, or transcoding.
- Changing the perception/LDW pipeline or the replay frame contract.
- Multi-user / per-profile libraries.

## Decisions

### D1 — Shared storage via MediaStore (`Movies/ADASEdge/…`), not app-private or all-files

Recordings move to the public **MediaStore** `Video` collection with
`RELATIVE_PATH = Movies/ADASEdge/<YYYY>/<MM>/<YYYY-MM-DD>/`. CameraX writes directly through
`MediaStoreOutputOptions`, so the recorder keeps using `VideoCapture<Recorder>`.

- **Why:** Files land under `Movies/` and are visible over MTP/USB with **no broad storage
  permission** (scoped-storage compliant on minSdk 31). The app owns the entries it inserts,
  so it can delete them without `RecoverableSecurityException`. MediaStore content URIs are
  directly shareable via `ACTION_SEND` — **no `FileProvider` needed**.
- **Alternatives considered:**
  - *Keep `getExternalFilesDir`* — rejected: `Android/data/…` is hidden from MTP/file
    managers on Android 11+, defeating the "find/export over USB" goal.
  - *`MANAGE_EXTERNAL_STORAGE` + top-level `/sdcard/ADASEdge/`* — simplest File I/O and a
    top-level folder, but requires the heavyweight "All files access" grant and is
    Play-restricted. Viable for a sideload but rejected to avoid the scary permission and to
    stay future-proof; the MediaStore path already satisfies USB visibility.

### D2 — Date hierarchy via nested `RELATIVE_PATH`

The `<YYYY>/<MM>/<YYYY-MM-DD>/` nesting is expressed in `RELATIVE_PATH`; nested relative paths
create real directories (API 29+) that appear over MTP. Filenames keep the existing
`dashcam_<YYYY-MM-DD_HHmmss>.mp4` convention, so clips remain sortable within a day and a
clip's date is recoverable from either its path or its name. A "day folder" is just the set of
entries sharing one `RELATIVE_PATH`; "delete a day" = delete all entries with that path.
Physical empty date dirs may linger after deletion (cosmetic, ignored).

### D3 — `RecordingStore` becomes MediaStore-backed; eviction stays pure

`RecordingStore` is reworked over a thin `ClipRepository` that queries MediaStore
(`_ID`, `DISPLAY_NAME`, `SIZE`, `DATE_TAKEN`/`DATE_ADDED`, `RELATIVE_PATH`, `DURATION`).
The retention math stays the existing **pure `planEviction`** operating on a
`List<ClipInfo>` (now keyed by content `Uri` instead of filename) so it remains unit-testable
off-device. `enforce()` queries app-owned clips, sums `SIZE`, deletes oldest by capture time,
and **never deletes the in-progress clip** (the active `Uri`). Free-space check uses
`StatFs` on the external volume.

### D4 — Replay source selection, fallback retained

Add `Prefs.selectedReplayClip` (nullable content `Uri` string). `ReplaySource` resolves its
input as: **selected library clip URI** → else the **pushed replay clip** in a USB-visible
folder. The pushed-clip folder moves from hidden app-private `getExternalFilesDir/replay.mp4`
to the app's external **media** dir `Android/media/<pkg>/replay/` (via `getExternalMediaDirs()`)
— `Android/media` is MTP/USB-visible (unlike `Android/data`) **and** app-owned, so a clip
dropped in over USB is read by direct file path with no media permission and no MediaStore scan.
`ReplaySource` picks `replay.mp4` there, else the newest `*.mp4` in the folder. The one-time
migration moves the old app-private `replay.mp4` into this folder.
`MediaExtractor.setDataSource(context, uri, null)` handles the selected content URI; the file
path branch reads the pushed clip. `DrivingService.attachPreview()`
passes the resolved source. Selecting "Replay this clip" in the library **only sets the pref**
(the replay source) and reuses the existing ADAS replay path/options — the user then starts
replay through the existing flow (Settings replay toggle → start driving). No auto-start and no
new replay options.

### D5 — Clip library UI

New `ui/ClipLibraryActivity` (RecyclerView) backed by `ClipRepository`. Entry point added to
`DrivingActivity` and/or `SettingsActivity`. Per-item actions: **Play/Stop** (immediate playback
in an in-app `ClipPlayerActivity` using `VideoView` + `MediaController`, so play and stop live in
our UI — independent of ADAS replay), **Set as replay source** (sets `Prefs.selectedReplayClip`
only), **Share** (`ACTION_SEND` with the content URI), **Delete** (confirm). Toolbar:
multi-select mode for bulk delete, and search/filter by **month** and **day** plus sort
(newest-first default). Metadata shown: capture datetime, duration, size. Thumbnails via
`MediaStore` video thumbnails (best-effort; placeholder on failure).

### D6 — Legacy clip migration (best-effort, non-blocking)

On first launch after update, a one-time background pass imports existing
`Android/data/<pkg>/files/dashcam/*.mp4` into the new MediaStore layout, deriving the date
path from the filename datetime (fallback `lastModified`), then removes the source file on
success. Migration is **import-and-forget**: best-effort and idempotent, the `Prefs` "done"
flag is set only when the pass imports every legacy file, so a partial failure leaves the
remaining files in place and retries on the next launch. Once done, the legacy dir is no longer
read — the library reads only the MediaStore layout. The pushed-replay clip is migrated
separately and idempotently: each session start moves a lingering app-private `replay.mp4` into
`Android/media/<pkg>/replay/` (a no-op once moved), so it needs no flag.

## Risks / Trade-offs

- **MediaStore rewrite of a today-simple File path** → keep `planEviction` pure and put all
  MediaStore I/O behind `ClipRepository`; verify on-device (the existing unit tests cover the
  eviction math unchanged).
- **CameraX → `MediaStoreOutputOptions` behavior change** (URI-based finalize, segment
  rollover) → validate segment rollover + the "active clip protected" path on device before
  wiring retention to it.
- **Retention now does a MediaStore query per segment** instead of a `listFiles()` →
  negligible at clip counts here; query is indexed by date.
- **Empty physical date folders linger** after deletes → cosmetic only; not pruned.
- **Migration partial failure** → the "done" flag is set only when every legacy file imports,
  so remaining files stay in place and the pass retries next launch (idempotent, no data loss).
- **Share/export of large clips** → use `ACTION_SEND` with the content URI (no copy); the
  receiving app streams it.

## Migration Plan

1. Land `ClipRepository` (MediaStore) + reworked `RecordingStore`/`DashcamRecorder` writing to
   `Movies/ADASEdge/<date>/`.
2. Add replay-source selection (`Prefs` + `ReplaySource` + `DrivingService`), `replay.mp4`
   fallback intact.
3. Add `ClipLibraryActivity` and its entry point.
4. Run the one-time legacy import (import-and-forget) on first post-update launch.
5. **Rollback:** the recorder can fall back to the app-private `getExternalFilesDir` path
   behind a flag if a device rejects the MediaStore output; the library still reads it.

## Open Questions

- Public folder name under `Movies/` — `ADASEdge` assumed; confirm branding.

Resolved during apply: "Replay this clip" only sets the replay source (reuse existing replay
flow, no auto-start); thumbnails load lazily; legacy migration is import-and-forget.
