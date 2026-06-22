# openpilot reuse analysis — can we reuse it, and can it replace what we built?

This document records the research behind the `openpilot-inspired-lane-stability`
change: whether comma.ai's openpilot can be ported into this Android app, whether it
could replace our existing perception/warning stack, and why we chose to **borrow the
ideas, not the model**. Companion deep-dive on openpilot's architecture lives in the
`adas-realtime-android` change at `research/06-lane-fitting-dashed-lanes.md`
("openpilot, in depth").

## Question 1 — Is openpilot legally reusable? **Yes.**

openpilot (incl. the trained driving model) is **MIT-licensed**; comma.ai explicitly
open-sources the model and ships it as **ONNX**, which is exactly the format our
QNN/QAIRT toolchain converts from. Reuse only requires attribution.
- [openpilot LICENSE (MIT)](https://github.com/commaai/openpilot/blob/master/LICENSE)
- [supercombo.onnx community mirror](https://github.com/MTammvee/openpilot-supercombo-model)
- [modeld model README (I/O)](https://github.com/commaai/openpilot/blob/master/selfdrive/modeld/models/README.md)

## Question 2 — Is it technically portable to our app? **Yes, but it's a project, not a swap.**

Four frictions, in order of risk:

1. **Camera intrinsics must match comma's camera.** The model is trained on comma's
   fixed road camera (specific focal length / FOV). Input is two consecutive frames
   reprojected + stacked into YUV420 `(N, 12, 128, 256)`. An arbitrary phone camera has a
   different FOV, so every frame must be **reprojected into comma's camera model** before
   inference; wrong intrinsics → wrong lane geometry.
2. **Extrinsic calibration (roll/pitch/yaw) is a required input** and openpilot earns it
   by auto-calibrating over miles of driving; we'd have to feed our horizon/center
   calibration in as those angles.
3. **Recurrent / stateful graph.** The model feeds a hidden feature state back each step.
   Converting recurrent/stateful ONNX to QNN/HTP is the **highest-risk** step (most likely
   to hit unsupported ops) and needs a conversion spike to de-risk.
4. **Its output is for *control*, not warnings** — path + lane lines + lead with
   uncertainty, designed to feed MPC steering. We'd consume only lane lines + lead, using
   ~a third of the model.

Sources: [modeld README](https://github.com/commaai/openpilot/blob/master/selfdrive/modeld/models/README.md),
[Level 2 AD on a single device (arXiv 2206.08176)](https://arxiv.org/pdf/2206.08176),
[How openpilot works in 2021](https://blog.comma.ai/openpilot-in-2021/).

## Question 3 — Can openpilot replace all we built? **No.**

openpilot explicitly does **not** detect **traffic signs, speed-limit signs, or stop
signs** — documented limitations. Those are features we ship (speed-limit recognition,
over-speed alert, stop-sign announce; GTSRB sign classifier). So even a successful port
replaces only our **lane + lead** perception; TSR, speed-limit/over-speed, stop-sign, and
GPS speed-context logic stay ours regardless.
- [openpilot LIMITATIONS.md](https://github.com/commaai/openpilot/blob/master/docs/LIMITATIONS.md)

| Our feature | openpilot equivalent? | Verdict |
|---|---|---|
| Lane detection / LDW (UFLDv2) | ✅ lane lines (recurrent, w/ uncertainty) | openpilot is **better** at stability |
| Forward collision / lead | ✅ lead with uncertainty | comparable / better |
| Traffic-sign & speed-limit (GTSRB) | ❌ not detected | **must keep ours** |
| Stop-sign announce | ❌ not detected | **must keep ours** |
| GPS speed-context + gating | ❌ uses CAN/vehicle speed | **must keep ours** |
| Drivable-area overlay | ✅ path/lane output | ours suffices |

## Decision — borrow the ideas, not the model

openpilot's robustness is **intrinsic to a recurrent, uncertainty-aware, end-to-end model
trained on huge data**; it can't be bolted onto our frozen single-frame UFLDv2, and
porting it carries camera-reprojection + recurrent-conversion risk while still not
covering our sign/speed features. So we reproduce its three stability *mechanisms* as
on-device classical surrogates:

| openpilot mechanism | our classical surrogate |
|---|---|
| GRU recurrent hidden state (temporal fusion, gap-filling) | **Kalman/clothoid track** on lane coefficients with bounded gap prediction |
| Per-point uncertainty (planner down-weights) | **confidence-weighted fit** + confidence-derived measurement covariance |
| `desire` input → never confused by multi-lane geometry | **outlier gating** + **lane-width/parallelism guard** |

This delivers most of the production stability win at near-zero runtime cost, with no new
model, no native code, and no risk to the shipped features. A full openpilot **port/eval
spike** (convert ONNX→QNN, reproject our camera, A/B on the replay feed) remains a viable
*future, separate* change if the classical surrogate proves insufficient — but it would
augment, never replace, the sign/speed stack.

## Sources

- [openpilot repo + LICENSE (MIT)](https://github.com/commaai/openpilot/blob/master/LICENSE)
- [openpilot LIMITATIONS.md](https://github.com/commaai/openpilot/blob/master/docs/LIMITATIONS.md)
- [modeld model README — inputs/outputs](https://github.com/commaai/openpilot/blob/master/selfdrive/modeld/models/README.md)
- [supercombo.onnx community mirror](https://github.com/MTammvee/openpilot-supercombo-model)
- [Level 2 Autonomous Driving on a Single Device (arXiv 2206.08176)](https://arxiv.org/pdf/2206.08176)
- [How openpilot works in 2021](https://blog.comma.ai/openpilot-in-2021/)
- [Decoding comma.ai openpilot — the driving model](https://medium.com/@chengyao.shen/decoding-comma-ai-openpilot-the-driving-model-a1ad3b4a3612)
