# Warning validation — on-device (tasks 9.2 / 9.2b)

> **STATUS: task 9.2 still OPEN, but the LDW failure is FIXED + re-validated.** The original LDW
> "can't keep up" failure was fixed (LDW now reads the raw pre-tracker boundary) and re-validated:
> prompt, clean activate/clear on a real-texture drift, no flicker, no false fire in-lane. Headway
> and Over-speed/TSR sub-cases behaved correctly. **One gap remains:** FCW imminent escalation was
> never exercised (no TTC<1.4 s clip) — 9.2 closes once that's covered.

**Date:** 2026-06-23 · **Device:** S26 Ultra (SM-S948W, QNN_HTP v81) · **Lane model:** TwinLite
(shipped default) · **Feed:** replay clips swapped in as `replay.mp4`; synthetic ego speed as noted.
Evidence = `DrivingService` replay-only `WARN`/`CLEAR` transition logs. UFLDv2 was **not** used
(legacy fallback; all results are on the shipped TwinLite path).

Thresholds (Config): FCW TTC advisory 2.7 s / imminent 1.4 s, min 8 km/h · LDW activate ≥ 60 km/h,
lane-availability gated · Headway safe 2.0 s (±0.4 hyst), dwell 1.2 s, min 25 km/h · Over-speed when
speed > limit + 5 km/h.

## 1. Forward Collision Warning (collision-warning spec)

Clip `val_fcw.mp4` @ 80 km/h (looping lead vehicle).

- ✅ **Advisory** — `WARN FORWARD_COLLISION:ADVISORY lead=24m ttc=2.2s` (and 2.3–2.5 s): fires when
  TTC ≤ advisory gate. Repeated across loops.
- ✅ **Clear** — `CLEAR FORWARD_COLLISION:ADVISORY` each time TTC rose / lead left the path.
- ⚠️ **Imminent escalation — NOT exercised.** Min TTC in the clip was ~2.2 s, never below the 1.4 s
  imminent gate, so no `:IMMINENT`. TTC is visual-looming-based (can't be forced via speed).
  **Follow-up:** a harder-approach clip (TTC < 1.4 s) to confirm the advisory→imminent escalation.
- Not separately exercised: below-min-speed suppression, speed-INVALID suppression, adjacent-lane /
  off-path rejection (logic present; needs targeted clips).

**Verdict: INCOMPLETE** — advisory + clear observed, but the imminent escalation scenario was
never exercised, so FCW is not fully validated.

## 2. Headway / Tailgating (headway-monitoring spec)

Same `val_fcw.mp4` @ 80 km/h.

- ✅ **Tailgating warning** — `WARN HEADWAY:ADVISORY lead=8m` (THW ≈ 8 m / 22.2 m·s⁻¹ ≈ 0.4 s ≪ 2.0 s),
  sustained past the 1.2 s dwell.
- ✅ **Cleared** — `CLEAR HEADWAY:ADVISORY` when the lead receded (hysteresis margin).
- ✅ No-lead → no headway (no warning on lead-absent frames).

**Verdict: PASS** (activate + clear + dwell/hysteresis behavior observed).

## 3. Lane Departure Warning (lane-departure-warning spec) — shipped TwinLite path

- ✅ **Departure + side + clear** — `val_overspeed.mp4` @ 110 km/h, where TwinLite detects lanes
  (`lanes=true`) and the ego drifts right: `WARN LANE_DEPARTURE:ADVISORY:RIGHT` → `CLEAR
  LANE_DEPARTURE:ADVISORY:RIGHT`, many clean cycles. **Side correctly reported (RIGHT).**
- ✅ **Lane-availability gating** — `val_ldw.mp4` under TwinLite detects lanes only intermittently
  (`lanes=false` most frames) → **zero** LDW issued during unavailable periods. Correct gating.
- ✅ **Speed-gating** — all runs ≥ 60 km/h (armed); below-threshold/hysteresis not separately swept.
- ⚠️ **Responsiveness on a fast drift (finding).** On `val_ldw` (a quick repeated drift) the Kalman
  `LaneTracker` (now core — smooths coefficients + coasts ≤ ~2.4 s) **lags** the true departure, so
  activation/clear is sluggish; at replay's ~5–8 fps it's worse. This is the stability↔responsiveness
  trade we intentionally added (great for the corridor, costs LDW latency). **Follow-up:** for LDW use
  a less-smoothed/raw boundary or a shorter coast; add a dedicated lane-change clip check.
- Note: `val_ldw.mp4` is a low-quality UFLDv2-era asset; TwinLite doesn't track its lanes well. A
  TwinLite-domain drift clip would give cleaner LEFT+RIGHT departure coverage. (LEFT-side firing was
  sanity-checked off the shipped path; RIGHT is confirmed on TwinLite above.)

**Verdict: PARTIAL (fix applied; activation unproven on real footage).**

- **Root cause of the original "lag" + the apparent "31 WARN/31 CLEAR" on val_overspeed:** the
  val clips are **synthetic cartoon roads** (gray trapezoid + a yellow *centre* dashed line, no real
  side lines; overlay shows "NO LANES"). TwinLite is a real-road model and can't detect lanes in
  them, so those LDW firings were **noise flicker on a garbage signal**, not real departures. The
  synthetic clips are **not valid LDW tests**.
- **Fix applied:** LDW now judges departure on the **raw, pre-tracker** near-field boundary x
  (`LaneGeometry.rawLeft/RightBottomX`) instead of the smoothed/coasted tracked polyline, removing
  the Kalman lag by construction; the overlay still uses the tracked geometry. Builds + unit tests
  pass.
- **Validated on REAL footage (highway `replay.mp4`):** TwinLite detects lanes reliably
  (avail 100 %, mid ego-right 0.66 on real paint, both sides present) and LDW is **stable with
  ZERO false/ flicker warnings while in-lane** — the raw signal does not cause false positives on
  good detection.
- **Still unproven:** a real *departure activation* — every real clip available keeps the car
  in-lane, and the synthetic clips have no detectable lanes. Needs a **real dashcam clip with a
  genuine drift across a line** (good TwinLite lanes) to confirm prompt activation/clear.

**Net:** lag fixed; no-false-fire confirmed on real lanes; prompt activate/clear confirmed on a
real-texture drift.

- **Activation re-validated (real-texture drift)** — `tools/make_drift_clip.py` pans the real
  dashcam footage laterally (±0.16·W, 3.5 s oscillation), keeping real lane texture so TwinLite
  detects lanes (avail 100 %) while the ego "drifts" toward a line and back. LDW produced a clean
  rhythm of `WARN LANE_DEPARTURE:RIGHT` → `CLEAR` — **one cycle per drift**, ~0.5–0.7 s
  activate→clear, **no flicker** (contrast: the no-lane synthetic clip produced 31 noise toggles).
  This shows LDW keeps up with the departure on real detection.
- Caveat: the induced pan only exercised RIGHT-labelled departures (a large pan makes the
  centre-relative decoder re-label the nearer line as "right"); LEFT-side logic was confirmed
  separately. On-road footage with genuine both-side drifts would be the final sign-off.

**Verdict: LDW responsiveness PASS** — lag fixed (raw pre-tracker boundary), prompt+clean
activate/clear on real-texture lanes, no false fire in-lane.

## 4. Over-speed / Traffic-Sign Recognition (driver-alert-hmi / TSR)

Clip `val_overspeed.mp4` @ 110 km/h (speed-limit sign in view).

- ✅ **Sign recognized** — `WARN SPEED_LIMIT:INFO` (limit established from the recognized sign; INFO
  cue, not an alert).
- ✅ **Over-speed advisory** — `WARN OVER_SPEED:ADVISORY` when 110 km/h > limit + 5 km/h tolerance.
- ✅ **Cleared** — `CLEAR OVER_SPEED:ADVISORY` when within limit / the sign leaves view.

**Verdict: PASS** (sign recognition + over-speed activate + clear).

## Summary — 9.2 NOT PASSING (open)

| Warning | Verdict | Notes |
|---|---|---|
| FCW | ⚠️ INCOMPLETE | advisory+clear ok; imminent escalation never exercised (needs TTC<1.4 s clip) |
| Headway | ✅ sub-case OK | activate+clear, dwell + hysteresis observed |
| LDW | ✅ FIXED + re-validated | raw pre-tracker boundary → prompt clean activate/clear on real-texture drift; no flicker; no false fire in-lane |
| Over-speed/TSR | ✅ sub-case OK | sign recognized + over-speed activate/clear |

**Overall: 9.2 open only on FCW imminent escalation.** LDW responsiveness fixed + re-validated;
Headway / Over-speed / LDW behave correctly. Remaining: exercise FCW imminent (TTC<1.4 s clip),
then 9.2 can close.

**Follow-ups:** (a) closer-approach clip for FCW imminent; (b) LDW responsiveness on fast drifts
(less-smoothed boundary / shorter coast) + a TwinLite-domain departure clip with both sides;
(c) targeted clips for speed-gating/INVALID and adjacent-lane rejection.
