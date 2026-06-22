## Context

The live camera is driven by `CameraController` (CameraX) which binds two use cases to the
activity lifecycle: a `Preview` (to the UI surface) and an `ImageAnalysis`
(`STRATEGY_KEEP_ONLY_LATEST`, RGBA_8888) whose frames feed perception via a `FrameSink`.
`DrivingService` owns the session; perception now runs on a dedicated worker thread, and we
just optimized FPS — so the recording path must **not** add load to the perception/encode
critical path. Replay mode uses a separate `ReplaySource` (no CameraX).

Recording must be bounded (size-capped circular buffer of short, datetime-named clips) and
opt-in via Settings, with a live on/off button.

## Goals / Non-Goals

**Goals:**
- Capture the live camera to disk without degrading perception FPS or warnings.
- Time-segmented clips, each filename-stamped with capture datetime.
- Hard total-size cap enforced by deleting oldest clips first; never fill the device.
- Clips retrievable off-device for training.
- Opt-in: Settings options + a live record button; default off.

**Non-Goals:**
- Audio capture (video-only for v1 — avoids mic permission + privacy scope).
- In-app playback / gallery browser (retrieval is via the file directory).
- Recording in replay mode.
- Per-clip metadata sidecars (GPS/speed/labels) — noted as a future extension.

## Decisions

### D1 — Capture via CameraX `VideoCapture`, not MediaCodec on analysis frames
Bind a third use case (`VideoCapture`/`Recorder`) alongside `Preview` + `ImageAnalysis`.
- **Why:** the hardware encoder runs on separate silicon, off the CPU/NPU perception path,
  so it preserves the FPS work. Encoding the RGBA `ImageAnalysis` frames with `MediaCodec`
  instead would re-process every frame on the CPU/GPU and compete with perception — exactly
  what we just optimized away.
- **Alternative considered:** `MediaCodec` from the analysis stream — rejected for the load
  reason; only revisit if 3-concurrent-use-case binding proves unsupported on target devices.

### D2 — App-driven segment rollover
CameraX `Recorder` records one continuous file until stopped, so the app drives rollover: at
the configured max duration, finalize the current `Recording` and immediately start the next.
- **Why:** gives exact control over per-file duration and naming, independent of CameraX
  version capabilities. The brief stop/start gap is acceptable for dashcam use.
- **Alternative:** a single long file — rejected: violates the per-file duration requirement
  and makes circular retention coarse.

### D3 — Storage in app-specific external dir
Write clips to `context.getExternalFilesDir("dashcam")` (e.g. `Android/data/<pkg>/files/
dashcam/`).
- **Why:** scoped-storage friendly — **no runtime storage permission**, and the dir is
  pullable via `adb pull` / file manager for the training corpus.
- **Alternative:** `MediaStore` (gallery-visible) — rejected for v1: more plumbing and
  permission surface for no training benefit. Revisit if drivers want gallery access.

### D4 — Retention manager (delete-oldest with hysteresis)
A `RecordingStore` component enforces the cap: on each segment finalize (and at session
start) it sums the dir size and, if over the cap (or device free space below a floor),
deletes oldest clips first — ordered by the datetime in the filename, falling back to
`lastModified`. It deletes down to a low-water mark (e.g. ~90% of cap), not exactly the cap,
to avoid thrashing every segment. The in-progress clip is never deleted.
- **Why:** bounded storage, minimal delete churn, deterministic eldest-first eviction.

### D5 — Lifecycle, threading, and failure isolation
Recording is bound in `CameraController` next to `ImageAnalysis` and controlled by
`DrivingService` + the overlay toggle. Rollover timing and retention run on a background
executor (not the perception worker, not the main thread). Recorder/storage errors are caught
and surfaced (status flag + log); they never crash or stall the session. With `dashcamEnabled
= false` no `VideoCapture` use case is bound.

### D6 — Filenames
`dashcam_yyyy-MM-dd_HHmmss.mp4` (local time, zero-padded, sortable). If two segments would
collide (clock unchanged / sub-second rollover), append a short monotonic suffix.

## Risks / Trade-offs

- **3 concurrent CameraX use cases unsupported on some hardware** → query CameraX
  `isConcurrentCameraModeSupported` / catch bind failure; if unsupported, disable recording
  gracefully and surface a message (perception keeps running). Target S26 supports it.
- **Encoder + thermal load alongside the NPU** → hardware encoder is separate silicon, but
  sustained record adds heat; the existing thermal governor already throttles perception, and
  recording can be turned off. Monitor in on-device testing.
- **Flash wear / delete churn from constant rotation** → hysteresis low-water mark (D4) and
  batch deletes reduce write/delete frequency.
- **Datetime collisions / unset clock** → monotonic suffix (D6); sort also falls back to
  `lastModified`.
- **Storage truly full mid-write** → retention runs before/around rollover; if space can't be
  secured, stop recording rather than fail the session (per spec).

## Migration Plan

Additive and opt-in. Ship with `dashcamEnabled = false`; no behavior change until a user
enables it. Rollback = disable the setting (no `VideoCapture` bound) or revert the feature
commits. No data migration; existing clips are just files under the dashcam dir.

## Open Questions

- Default caps: 3 min/segment and 2048 MB total — confirm against typical device free space.
- Record resolution/bitrate: match the camera (1280×720) or a lower training-friendly preset?
- Future: per-clip metadata sidecar (timestamp/GPS/speed/ego-lane) to make clips directly
  trainable — in scope later, out for v1.
- Should recording auto-start when `dashcamEnabled` is true, or always require the button?
  (Proposed: auto-start when enabled, button overrides within the session.)
