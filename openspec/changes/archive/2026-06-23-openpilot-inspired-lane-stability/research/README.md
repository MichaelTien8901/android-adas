# openpilot-inspired-lane-stability — Research

Research backing the `openpilot-inspired-lane-stability` change. See also the deep-dive on
openpilot's architecture in the `adas-realtime-android` change,
`research/06-lane-fitting-dashed-lanes.md` ("openpilot, in depth"), and the dashed-lane
fitting options (Kalman, RANSAC, lane-coupling) documented there.

| Doc | Topic | Key takeaway |
|---|---|---|
| [01-openpilot-reuse-analysis.md](01-openpilot-reuse-analysis.md) | Can we reuse openpilot? Can it replace our stack? | MIT-licensed and portable **but not a swap** (camera-intrinsics reprojection + recurrent-graph QNN conversion); **can't replace** our TSR/speed-limit/stop-sign features. Decision: **borrow the mechanisms** (Kalman track ↔ recurrence, confidence weighting ↔ per-point uncertainty, gating+width guard ↔ `desire`), not the model. |
