# Tasks — lane-detector-bakeoff

Base branch `eval/lane-detector-bakeoff` (carries the paint-deviation harness + the
UFLDv2+tracker baseline). Candidate spikes branch off this. See `design.md` (D1–D5).

## 1. Evidence & methodology (done)

- [x] 1.1 Prove the right-lane error is model-level via `LANEPAINT` ground truth
      (R_raw == R_out; right slot lands on next-lane paint). Documented in
      `proposal.md` / research of `openpilot-inspired-lane-stability`.
- [x] 1.2 Objective metric defined (D1): paint-deviation of ego-left/right + width
      consistency, scored on the replay feed.

## 2. Shared eval harness (this base branch)

- [x] 2.1 Per-row independent paint detection + predicted L/R logging (`LANEPAINT`,
      `LANEDBG`) — already built in PerceptionEngine/DrivingService.
- [ ] 2.2 Reduce `LANEPAINT` to a single per-run **score**: mean |predicted − nearest
      paint| for left & right at near/mid/far + width-consistency, aggregated over a
      fixed replay window; log one SCORE line per window for direct model comparison.
- [ ] 2.3 Add a `laneModel` selector seam (pref + EngineFactory/decoder dispatch),
      default `ufldv2` — so a candidate plugs in without touching the shipped path. (D3)
- [ ] 2.4 Record the **baseline** score (UFLDv2 + tracker + gate) on a fixed replay
      window as the number to beat.

## 3. Candidate spikes (each on its own branch + openspec change)

- [ ] 3.1 `eval/twinlitenet-drivable-area` (primary): fetch/export TwinLiteNet+ (or
      YOLOPv2) to ONNX → assets; write the seg decoder (drivable-area mask → ego
      boundaries; lane-line head for precision) → `LaneGeometry`; score on replay;
      record frames + score in its openspec change.
- [ ] 3.2 `eval/clrernet-lane`: fetch/export CLRerNet ONNX → assets; write the anchor
      decoder → `LaneGeometry`; score on replay; record.
- [ ] 3.3 (optional) finetune/alt-dataset UFLDv2 only if a quick win looks likely.

## 4. Compare & decide

- [ ] 4.1 Comparison table: paint-deviation (L/R, near/mid/far), width consistency,
      right-on-correct-paint %, CPU latency, and 3–4 representative frames per model.
- [ ] 4.2 Recommend a winner; open a follow-up change for its QNN/HTP context binary +
      default flip (tasks 4.x of `adas-realtime-android`). Abandon losing branches.

## 5. Docs

- [ ] 5.1 `openspec validate lane-detector-bakeoff --strict` clean; keep each
      candidate's score in its own change so the decision is auditable.
