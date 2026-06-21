# ADAS Realtime Android — Research Documents

Cited research backing the `adas-realtime-android` change. All claims are
sourced inline. Target devices: **Galaxy S22+** (dev, SD 8 Gen 1, Hexagon v69)
and **Galaxy S26 Ultra** (deploy, SD 8 Elite Gen 5, Hexagon ~v81). See also
`doc/project_draft` for the device-tier / QNN-config matrix.

| Doc | Topic | Key takeaway |
|---|---|---|
| [01-adas-functions.md](01-adas-functions.md) | The 4 ADAS functions (FCW, LDW, Headway/Tailgating, TSR/TLR) + standards | TTC/THW thresholds, Euro NCAP / NHTSA FMVSS 127 / ISO 15623·17361·11270·22839, HMI & false-positive design |
| [02-perception-models.md](02-perception-models.md) | CV models: detection, lanes, signs, distance/TTC | YOLO11n detector backbone; classical-CV→UFLDv2 lanes; geometry-based distance + bbox-scale TTC over monocular depth nets; AGPL license note |
| [03-android-realtime-inference.md](03-android-realtime-inference.md) | On-device inference stack | QNN/Hexagon primary; LiteRT NNAPI/GPU & ONNX-Runtime QNN-EP fallbacks; INT8 PTQ; decide on post-throttle sustained FPS; Exynos risk |
| [04-app-architecture-libraries.md](04-app-architecture-libraries.md) | Android app architecture & libraries | Standalone foreground-service app, CameraX ImageAnalysis (keep-latest), SurfaceView/OpenGL overlay, OpenCV; Android Auto forbids custom CV apps; no CAN/vehicle-speed access on phone |
| [05-speed-source-and-warning-gating.md](05-speed-source-and-warning-gating.md) | Speed source & warning gating | Phone can't read CAN/vehicle speed → **GPS speed only**; how every speed-dependent warning is gated, smoothing/hysteresis, IMU dead-reckoning through tunnels, `VALID/DEAD_RECKONED/INVALID` validity states, degraded mode |
| [06-lane-fitting-dashed-lanes.md](06-lane-fitting-dashed-lanes.md) | Lane fitting & dashed-lane robustness | Clean ego-lane curves from UFLDv2 row anchors: **confidence-weighted + RANSAC/IRLS** fit, **Kalman on `[a,b,c]`** temporal track, **lane-width/parallel** coupling (solid anchors dashed), marking-snap, or finetune to close the TuSimple cross-dataset gap (RONELD) |
