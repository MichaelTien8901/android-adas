# Tasks — lane-detector-bakeoff

Base branch `eval/lane-detector-bakeoff` (carries the paint-deviation harness + the
UFLDv2+tracker baseline). Candidate spikes branch off this. See `design.md` (D1–D5).

## Outcome — CLOSED (2026-06-22)

Bake-off concluded: **TwinLiteNet + Kalman won** over UFLDv2 (mid ego-right on the real
paint where UFLDv2 drifted onto the next lane). The winner was implemented, QNN/HTP-converted
for the S26 NPU, on-device verified, made the default lane detector, and merged — see the
archived change `2026-06-22-twinlite-drivable-area`. The `clrernet` candidate (3.2) and the
UFLDv2 finetune (3.3) were **not pursued** — TwinLite met the bar, so further candidates
weren't needed. Closing this change.

## 1. Evidence & methodology (done)

- [x] 1.1 Prove the right-lane error is model-level via `LANEPAINT` ground truth
      (R_raw == R_out; right slot lands on next-lane paint). Documented in
      `proposal.md` / research of `openpilot-inspired-lane-stability`.
- [x] 1.2 Objective metric defined (D1): paint-deviation of ego-left/right + width
      consistency, scored on the replay feed.

## 2. Shared eval harness (this base branch)

- [x] 2.1 Per-row independent paint detection + predicted L/R logging (`LANEPAINT`,
      `LANEDBG`) — already built in PerceptionEngine/DrivingService.
- [x] 2.2 Reduce `LANEPAINT` to a single per-run **score**: mean |predicted − nearest
      paint| for left & right at near/mid/far + width-consistency, aggregated over a
      fixed replay window; log one SCORE line per window for direct model comparison.
- [x] 2.3 Add a `laneModel` selector seam (pref + EngineFactory/decoder dispatch),
      default `ufldv2` — so a candidate plugs in without touching the shipped path. (D3)
- [x] 2.4 Record the **baseline** score (UFLDv2 + tracker + gate) on a fixed replay
      window as the number to beat.

## 3. Candidate spikes (each on its own branch + openspec change)

- [x] 3.1 `eval/twinlitenet-drivable-area` (primary): fetch/export TwinLiteNet+ (or
      YOLOPv2) to ONNX → assets; write the seg decoder (drivable-area mask → ego
      boundaries; lane-line head for precision) → `LaneGeometry`; score on replay;
      record frames + score in its openspec change.
- [ ] 3.2 (NOT PURSUED — winner found) `eval/clrernet-lane`: fetch/export CLRerNet ONNX → assets; write the anchor
      decoder → `LaneGeometry`; score on replay; record.
- [ ] 3.3 (NOT PURSUED — optional) finetune/alt-dataset UFLDv2 only if a quick win looks likely.

## 4. Compare & decide

- [x] 4.1 Comparison table: paint-deviation (L/R, near/mid/far), width consistency,
      right-on-correct-paint %, CPU latency, and 3–4 representative frames per model.
- [x] 4.2 Recommend a winner; open a follow-up change for its QNN/HTP context binary +
      default flip (tasks 4.x of `adas-realtime-android`). Abandon losing branches.

## 5. Docs

- [x] 5.1 `openspec validate lane-detector-bakeoff --strict` clean; keep each
      candidate's score in its own change so the decision is auditable.
