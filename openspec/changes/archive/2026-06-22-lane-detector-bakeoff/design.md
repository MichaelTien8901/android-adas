## Context

`LANEPAINT` (independent per-row paint detection) gave us objective ground truth: the
ego-RIGHT error is in UFLDv2's decode (`R_raw == R_out`), not our fitting. Rather than
add a seventh patch, we evaluate replacement detectors on the same benchmark. The app
already runs the lane model on **ONNX-Runtime/CPU** when no QNN binary is present (it's
how UFLDv2 runs in the debug build today), so a candidate ONNX can be evaluated on the
device with **no QNN conversion** — conversion is deferred to the winner only.

## Goals / Non-Goals

**Goals**
- An apples-to-apples, objective score per candidate on the replay feed.
- Each candidate isolated on its own branch with its own openspec change, so spikes are
  independent and a loser is abandoned cleanly.
- A pluggable lane-source seam so candidates don't disturb the shipped UFLDv2 path.

**Non-Goals**
- No QNN/HTP conversion during evaluation (CPU/ONNX-RT is enough to score accuracy).
- No retraining unless a candidate's *architecture* wins and only its weights need work.
- Not shipping a new default in this change — that's a follow-up once a winner is clear.

## Decisions

### D1 — Objective metric: paint-deviation + width consistency
**Decision**: score each model per frame as the mean lateral distance from the predicted
ego-left and ego-right boundary (sampled at near/mid/far) to the **nearest detected lane
paint** in that row, plus the stability of the implied lane width. Aggregate over a
fixed replay window. Lower deviation + stable, plausible width = better. The left
boundary (which every method gets right) is the calibration check that paint detection
is sound; the right boundary is the discriminator. *Rationale*: this is the only signal
that measures *correctness against the road*, unlike the stability/parallelism metrics
that fooled us earlier. *Caveat (logged, not hidden)*: "nearest paint" can match an
adjacent line, so the score is read together with the visual frames and the width-
consistency term, never alone.

### D2 — Evaluate on ONNX-Runtime/CPU; defer QNN to the winner
**Decision**: candidates ship as `assets/models/<name>.onnx` and run via the existing
`EngineFactory` → `OrtModelRunner` fallback. *Rationale*: accuracy is architecture-
bound, not runtime-bound; CPU is plenty to score it on the looping replay clip, and it
removes the QNN-conversion cost from every dead-end spike.

### D3 — Pluggable lane source behind a setting
**Decision**: add a lane-model selector (e.g. `laneModel` pref: `ufldv2` | `twinlite` |
`clrernet`) that picks the decoder/runner; UFLDv2 stays the default. Each candidate
implements the same `LaneGeometry` output contract so the warnings/overlay are
unchanged. *Rationale*: isolates the experiment; lets us A/B on one device build.

### D4 — One branch + one openspec change per candidate
**Decision**: `eval/twinlitenet-drivable-area` and `eval/clrernet-lane` branch off this
bake-off base (which carries the harness + the UFLDv2+tracker baseline). Each has its
own proposal/design/tasks and is scored independently. *Rationale*: the user asked for
individual branches and individual testing; keeps losers isolated and abandonable.

### D5 — Candidate set and rationale
- **TwinLiteNet+ / YOLOP (drivable-area + lane segmentation)** — *primary*. Reframes
  ego-lane from "classify which of 4 line-slots is ego" (the exact thing UFLDv2 gets
  wrong) to "segment the drivable road region"; ego boundaries are the mask edges.
  Embedded-designed, INT8/FP16-robust, and feeds the drivable-area overlay we already
  draw.
- **CLRerNet** — higher anchor-line accuracy + LaneIoU confidence than UFLDv2, ONNX
  available; still line-based so slot assignment remains a risk, but worth a number.
- **UFLDv2 + tracker + gate (baseline)** — the current best; the bar each must clear.

## Risks / Trade-offs
- **Segmentation gives a region, not parametric lines** — deriving clean ego boundaries
  from a mask needs edge-tracing/fitting; budget for that in the TwinLite spike.
- **Paint detection is itself imperfect** (dashed gaps, glare) — mitigated by the
  width-consistency term and reading scores with the frames (D1 caveat).
- **CPU latency** on the eval build is slow (~3–4 fps) — fine for accuracy scoring, not
  representative of NPU speed; latency is recorded but not the deciding factor.

## Open Questions
- Best public weights/ONNX for TwinLiteNet+ vs YOLOPv2 (BDD100K-trained); pick in-spike.
- Whether the winner needs finetuning on our footage or ships zero-shot.

## Migration / Rollout
1. Land the harness + selector on this base branch.
2. Spike each candidate on its branch; record the score + frames in its openspec change.
3. Compare; the winner gets a follow-up change for QNN/HTP + default flip. Losers' branches are abandoned.
