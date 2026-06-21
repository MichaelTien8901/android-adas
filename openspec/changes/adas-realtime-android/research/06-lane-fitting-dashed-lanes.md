# 06 — Lane fitting & dashed-lane robustness

How to turn the UFLDv2 row-anchor output into clean, stable ego-lane curves —
especially for **dashed boundaries**, the hardest case. Scoped to our pipeline
(TuSimple UFLDv2 → on-device decode in `LaneDetector.kt`), with options ranked by
effort vs. payoff.

## Problem statement

Our lane path is: UFLDv2 predicts, per **row anchor**, a column distribution +
existence; we decode it to points (soft-argmax → ego-lanes-only → existence/​band
gate → temporal EMA per row) and fit `x = a·y² + b·y + c` by least squares with one
median-residual outlier pass (`LaneDetector.polyfitSmooth`).

A **dashed** boundary breaks this in two ways:

1. **Gaps → sparse, low-confidence rows.** Between dashes there is no paint, so the
   per-row column is a guess; existence is borderline. These rows survive as
   **outliers** that drag an ordinary least-squares fit.
2. **Cross-dataset domain gap.** The model is **TuSimple**-trained; run on other
   footage (our phone, the demo clip) it generalises imperfectly, and the dashed
   side is where that shows first. Post-processing that "fixes broken lane edges
   resulting from fragmentation" is exactly the documented remedy — RONELD reports
   *up to a two-fold accuracy increase on cross-dataset validation* doing this on
   top of a frozen CNN. [RONELD](https://arxiv.org/pdf/2010.09548)

> Plain least-squares is the wrong tool when a meaningful fraction of points are
> outliers — RANSAC and robust/weighted reweighting are the standard fixes.
> [RANSAC line fitting](https://medium.com/@fkastantin/ransac-line-fitting-outliers-rejection-4d5bfeea41a9),
> [fitPolynomialRANSAC (MATLAB)](https://www.mathworks.com/help/vision/ref/fitpolynomialransac.html)

## Options

### A. Confidence-weighted least squares  ★ recommended first
Weight each point in the fit by its **existence probability** (and/or the
soft-argmax peak). Dash-gap rows have low confidence → near-zero weight → they stop
pulling the curve, while dash-center rows dominate. This is precisely the
mechanism behind learned lane fitting: *"weights are set as the confidence of each
detected lane point … higher-confidence points are assigned higher weights"* in a
weighted-least-squares fit. [Differentiable LS fitting](https://arxiv.org/pdf/1902.00293)
RONELD likewise uses **weighted** least-squares regression to reconstruct
fragmented (dashed) edges. [RONELD](https://arxiv.org/pdf/2010.09548)
- **Cost:** ~10 lines — we already have the existence values; add per-point weights
  to `fitQuadratic`'s normal equations.
- **Risk:** none new. Strictly generalises the current unweighted fit.

### B. RANSAC / iteratively-reweighted robust fit  ★ recommended first
Replace the single median-residual pass with **RANSAC**: repeatedly fit from a
minimal random sample, score by inlier count, keep the best, then refit on inliers.
RANSAC *"minimises the influence of outliers by excluding them from the model
fitting process"* and is the standard for lane-point curve fitting from a 1st- to
3rd-degree polynomial.
[A Robust Lane Detection & Departure Warning System](https://arxiv.org/pdf/1504.07590),
[ransac (MATLAB)](https://www.mathworks.com/help/vision/ref/ransac.html)
A cheaper cousin is **IRLS** (iteratively-reweighted least squares: fit → weight by
closeness to the model → refit), which is what "robust fitting" usually means in
lane work and composes directly with option A.
- **Cost:** moderate (a bounded RANSAC loop, ~30 lines, no allocations in the hot
  path). IRLS is cheaper (2–3 reweight iterations).
- **Risk:** fixed iteration budget keeps latency bounded; needs a sane inlier
  threshold (we already use `max(0.03, 2.5·median residual)` as a starting point).

### C. Curve model: line vs quadratic vs cubic vs spline
We fit a **quadratic**. For gentle highway curves a quadratic is fine; for sharper
curves a **cubic** "provides smoother results than a line" and is the common
RANSAC lane model. [Robust LDW system](https://arxiv.org/pdf/1504.07590)
Splines/cubic interpolation are used specifically to *"get a continuous edge"*
across the plain (gap) areas of non-centre dashed lines. [Robust LDW system](https://arxiv.org/pdf/1504.07590)
- **Recommendation:** keep quadratic as default; only move to cubic if real curves
  under-fit (cubic is more prone to wiggle on sparse dashed support, so pair it
  with A/B). Splines add complexity with little gain over a good quadratic here.

### D. Temporal tracking — Kalman on the curve coefficients  ★ next
Track the fit **across frames**, not just per-row (our current EMA). The standard
formulation makes the Kalman **state = the polynomial coefficients** `[a, b, c]ᵀ`
of the lane curve; the filter *"generates smoothed measurements, a prediction for
the next frame, and an uncertainty estimate."*
[Method for lane detection (US10115026)](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/10115026),
[Preprocessing methods for lane detection & tracking](https://arxiv.org/pdf/2104.04755)
For a dashed lane this is powerful: when a frame's evidence is weak (all gaps), the
**prediction** carries the lane until paint reappears, killing flicker. RONELD's
temporal step similarly tracks preceding frames to "hypothesise true active lanes."
[RONELD](https://arxiv.org/pdf/2010.09548)
- **Cost:** a 3-state (or 6-state with velocities) KF per lane — small. Supersedes
  the per-row EMA with a model-level filter.
- **Risk:** needs gating so a bad frame doesn't poison the track (use the fit's
  inlier count / existence as the measurement covariance).

### E. Couple the two boundaries — lane-width / parallelism prior  ★ high-leverage
On a highway one boundary is usually **solid** (well-fit) and the other **dashed**
(noisy). Fit the confident lane, then constrain the noisy one to be **parallel at a
fixed lane width** — solve only its lateral offset instead of a free curve. Lane
geometry is a recognised prior: lane lines are *"parallel and spaced according to
local regulations"* (US freeway ≈ 3.6 m), and width/parallelism is computed from
the perpendicular distance between boundaries.
[Lane-marker detection (US10867189)](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/10867189),
[Online extrinsic calibration with a lane-width prior](https://arxiv.org/pdf/2008.03722)
- **Cost:** moderate; couples the two `decodeLane` results (do it in `detect`).
- **Payoff:** the **biggest** single win for the solid+dashed case the user hit.
- **Caveat:** boundaries are parallel in the **road plane**, not the image — couple
  in a bird's-eye (IPM) space, or share curvature `(a,b)` and solve only offset `c`
  as a good image-space approximation.

### F. Marking-snap (hybrid classical feature step)
Before fitting, **snap** each predicted point to the nearest bright lane-paint pixel
in a small horizontal window (a thin OpenCV step — brightness/colour peak, optional
IPM). Pulls points onto real paint and discards gap guesses, tightening alignment
to the actual markings. This is the only option that adds back a *classical feature
extraction* stage on the neural path (see the user's earlier question; today only
`ClassicalLaneFallback` does feature extraction).
- **Cost:** moderate (per-point pixel search); **risk:** reintroduces classical-CV
  brittleness (shadows, worn paint) — use it as a *refinement*, never a gate.

### G. Fix the source — finetune / swap the model
The deepest fix is the domain gap itself: **finetune UFLDv2 on representative
footage** (or our phone clips), or train the **CULane** variant (denser, more
varied than TuSimple). Post-processing (A–F) compensates for a frozen cross-dataset
model; retraining removes the need. [RONELD motivation](https://arxiv.org/pdf/2010.09548)
- **Cost:** high (data + training + re-quantise + re-export the context binary).
- **When:** if A–E plateau, or before any production deployment.

## Recommendation (layered)

1. **Now (cheap, robust):** A + B — **confidence-weighted, RANSAC/IRLS** quadratic
   fit. Self-contained, directly targets the dash-gap outliers, no new failure
   modes. Keep the quadratic (C) as default.
2. **Next (stability):** D — a **Kalman filter on `[a,b,c]`** per lane, replacing
   the per-row EMA, so a weak (all-gap) frame is bridged by prediction.
3. **High-leverage for solid+dashed:** E — **lane-width/parallel coupling**, fitting
   the dashed side off the solid side's curvature.
4. **Optional polish:** F (marking-snap) for tighter paint alignment.
5. **Deep fix / pre-production:** G — finetune or move to CULane to close the
   cross-dataset gap at the source.

## Where this lands in the code
- `LaneDetector.fitQuadratic` → add per-point **weights** (A) and an outer
  **RANSAC/IRLS** loop (B); weights come from the existence tensor already read in
  `decodeLane`/`existenceConfidence`.
- `LaneDetector.detect` → couple `left`/`right` for the **parallel prior** (E) and
  host the per-lane **Kalman** state (D), replacing the per-row `smoothLeft/Right`
  EMA arrays.
- Curve order (C) is the `fitQuadratic` degree; marking-snap (F) would be a new
  pre-fit pass over the source bitmap; (G) is a `tools/` retrain, not app code.

## Sources
- [RONELD: Robust Neural Network Output Enhancement for Active Lane Detection](https://arxiv.org/pdf/2010.09548)
- [End-to-end Lane Detection through Differentiable Least-Squares Fitting](https://arxiv.org/pdf/1902.00293)
- [A Robust Lane Detection and Departure Warning System](https://arxiv.org/pdf/1504.07590)
- [RANSAC Line Fitting & Outlier Rejection](https://medium.com/@fkastantin/ransac-line-fitting-outliers-rejection-4d5bfeea41a9)
- [fitPolynomialRANSAC (MATLAB)](https://www.mathworks.com/help/vision/ref/fitpolynomialransac.html) · [ransac (MATLAB)](https://www.mathworks.com/help/vision/ref/ransac.html)
- [Method for lane detection — Kalman on parabola coefficients (US10115026)](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/10115026)
- [Preprocessing Methods of Lane Detection and Tracking for Autonomous Driving](https://arxiv.org/pdf/2104.04755)
- [Systems and methods for lane-marker detection (US10867189)](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/10867189)
- [Online Extrinsic Camera Calibration … with a Lane Width Prior](https://arxiv.org/pdf/2008.03722)
