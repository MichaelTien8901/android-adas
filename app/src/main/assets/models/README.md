# Model assets

The runtime loads, by priority, whichever of these exist (see EngineFactory):

| File | Path used by | Notes |
|---|---|---|
| `detector_v69.bin` / `detector_v81.bin` | QNN HTP (S22+ / S26 Ultra) | per-device context binaries |
| `lane_v69.bin` / `lane_v81.bin` | QNN HTP | UFLDv2 lanes |
| `detector.onnx` / `lane.onnx` | ONNX-Runtime (QNN-EP / CPU) | fallback |
| `detector.tflite` / `lane.tflite` | LiteRT (GPU / CPU) | fallback |

Build them with `tools/` — see [`tools/README_qnn.md`](../../../../../tools/README_qnn.md).
The app builds and launches without any of these (camera-only + "models missing"
notice); add at least one detector variant to enable perception.

**Quick start (no QNN SDK / no device needed):** from the repo root run
`tools/fetch_detector.sh` (→ `detector.onnx`) and `tools/fetch_lane.sh`
(→ `lane.onnx`) to export decoder-compatible models here. The app then runs
perception on the ONNX-Runtime path (CPU everywhere, QNN-EP on Snapdragon). See
the **Quickstart** in the [root README](../../../../../README.md) for the full
rebuild (models → `./gradlew assembleDebug` → `adb install`). The
`.onnx`/`.bin`/`.tflite` blobs are git-ignored — regenerate them, don't commit
them. (YOLO11n is AGPL-3.0 — task 9.5.)
