# ADAS Functions: Technical Reference for a Realtime Android ADAS App

This document is a cited engineering reference for four core ADAS warning functions targeted by the realtime Android ADAS app: Forward Collision Warning (FCW), Lane Departure Warning / Lane Keeping Assist (LDW/LKA), Tailgating / Headway monitoring, and Traffic Sign / Traffic Light Recognition (TSR). For each function it covers what it does, the perception inputs required, the core decision logic with concrete thresholds, and the governing safety standards and benchmarks (Euro NCAP, NHTSA, ISO, and UN Regulations). A final section covers warning HMI design and false-positive considerations.

Because the Android app is camera-first (a phone or tablet running on the windshield), special attention is paid to what can be derived from a **single monocular camera** plus the phone's GPS and IMU, rather than from automotive radar/lidar.

---

## 1. Forward Collision Warning (FCW)

### What it does
FCW continuously monitors the vehicle ahead in the host vehicle's path and warns the driver of an imminent rear-end collision so they can brake or steer. FCW is a **warning-only** function: it does not actuate the brakes. It is the alerting layer that typically precedes Automatic Emergency Braking (AEB), which actually applies the brakes when the driver does not respond. ISO 15623:2013 defines the Forward Vehicle Collision Warning System (FVCWS) as a system that warns the driver of a potential rear-end collision with vehicles ahead while operating at ordinary speed on roads with curve radii over 125 m ([ISO 15623:2013](https://www.iso.org/standard/56655.html)).

### Perception inputs
- **Camera (primary for this app):** detect and track the lead vehicle, estimate range and relative closing speed from scale change across frames.
- **Radar (in production cars):** direct range and range-rate; the app substitutes vision-based range-rate.
- **GPS / IMU:** host-vehicle speed and longitudinal acceleration; lane/path geometry for in-path determination on curves.

### Core logic: Time-To-Collision (TTC)
The central metric is **Time-To-Collision (TTC)** — the time until the host vehicle would contact the lead vehicle if both maintained their current relative motion:

```
TTC = range / relative_closing_speed     (when closing; undefined/∞ when opening)
```

A warning fires when TTC drops below a threshold. Because driver reaction plus braking takes time, advisory warnings are issued earlier than imminent ones. Human-factors research on FCW timing describes layered alerts — a visual advisory near **TTC ≈ 4 s** and a sustained ~80 dB auditory alert at **TTC ≈ 2 s** for an imminent collision ([Forward Collision Warning: Clues to Optimal Timing, PMC](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5026383/)). A commonly used imminent-collision TTC threshold in FCW/AEB systems is **approximately 2 s** ([same study](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5026383/)).

| Stage | Typical TTC threshold | Modality |
|-------|----------------------|----------|
| Advisory / early caution | ~2.5–4 s | Visual |
| Imminent warning | ~1.5–2 s | Audible + visual (+ haptic) |
| AEB intervention (production) | ~< 1.5 s / when crash unavoidable by driver alone | Braking |

### Relationship to AEB and standards
FCW is the precursor stage of AEB. **NHTSA FMVSS No. 127** (final rule, May 2024) mandates that all new light vehicles include FCW, AEB, and pedestrian AEB by **September 1, 2029** (small-volume/final-stage/alterers by Sept 1, 2030) ([Federal Register, FMVSS 127](https://www.federalregister.gov/documents/2024/05/09/2024-09054/federal-motor-vehicle-safety-standards-automatic-emergency-braking-systems-for-light-vehicles)). Key numeric requirements:

- AEB must **avoid contact with a lead vehicle at speeds up to 62 mph (~100 km/h)** ([NHTSA / Federal Register](https://www.federalregister.gov/documents/2024/05/09/2024-09054/federal-motor-vehicle-safety-standards-automatic-emergency-braking-systems-for-light-vehicles)).
- AEB must **brake automatically up to 90 mph** when a lead-vehicle collision is imminent, and avoid pedestrians at up to **40 mph** (mitigate up to **45 mph**) ([NHTSA FMVSS 127](https://www.federalregister.gov/documents/2024/05/09/2024-09054/federal-motor-vehicle-safety-standards-automatic-emergency-braking-systems-for-light-vehicles)).
- The **FCW visual signal** must fall within an ellipse extending 18° vertically and 10° horizontally of the driver's forward line of sight; the **audible signal** must be **15–30 dB above the masked threshold**, with entertainment audio muted or reduced to within 5 dB during the alert ([NHTSA final rule clarifications, Nov 2024](https://www.jjkeller.com/news/article/NHTSA-Final-Rule-Federal-Motor-Vehicle-Safety-Standards-Automatic-Emergency-Braking-Systems-for-Light-Vehicles_JJ131169-L-1736183985818)).

**Euro NCAP** tests the AEB/FCW combination in three Car-to-Car Rear scenarios — stationary lead (CCRs), moving lead (CCRm), and braking lead (CCRb) — incrementing test speed in 5 km/h steps. For FCW scoring, the test braking robot applies the brakes **1.2 s after the warning** to simulate driver reaction time, validating that the warning is early enough to be useful ([Euro NCAP AEB C2C Test Protocol](https://www.euroncap.com/media/79864/euro-ncap-aeb-c2c-test-protocol-v43.pdf)). Relevant ISO standards: **ISO 15623** (FVCWS performance/test) ([ISO 15623](https://www.iso.org/standard/56655.html)) and **ISO 22839:2013** for forward vehicle collision mitigation systems ([ISO 22839](https://standards.iteh.ai/catalog/standards/iso/161e434c-c7e1-419e-ac48-a95f4b3b3487/iso-22839-2013)). At the UN level, **UN Regulation No. 152** governs Advanced Emergency Braking Systems for light vehicles ([UNECE regulatory overview](https://www.otobrite.com/news/understanding-un-vehicle-safety-regulations-and-how-sensors-help-vehicles-comply/detail)).

---

## 2. Lane Departure Warning (LDW) and Lane Keeping Assist (LKA)

### What it does
LDW warns the driver when the vehicle begins to drift out of its lane without a turn signal; it is warning-only. LKA (LKAS) adds gentle corrective steering torque to nudge the vehicle back. Per **ISO 17361:2017**, an LDW system warns the driver of lane departure on highways and highway-like roads, may use optical/electromagnetic/GPS sensing, takes **no automatic action**, and leaves responsibility with the driver ([ISO 17361:2017](https://www.iso.org/standard/72349.html)). **ISO 11270:2014** specifies LKAS control strategy, minimum functionality, driver interface, diagnostics, and test procedures; LKAS supports safe lane-keeping but does **not** perform automated driving ([ISO 11270:2014](https://www.iso.org/standard/50347.html)).

### Perception inputs
- **Camera (primary):** detect lane markings, fit lane lines/polynomials, compute lateral offset and lateral velocity relative to the markings.
- **IMU:** yaw rate and lateral acceleration to stabilize estimates and predict trajectory.
- **GPS / map:** road type gating (highway vs. urban) and curvature priors.
- **Turn-signal state:** read from the vehicle (CAN/OBD) or inferred; used to suppress warnings on intentional maneuvers.

### Lane line detection and departure criteria
Lane lines are detected per frame (classical edge/color + Hough or polynomial fit, or a CNN segmentation model), transformed to a top-down/vehicle frame, and used to compute the **distance from the wheel/vehicle edge to the lane marking** and the **rate of departure (lateral velocity)**. A warning is issued as the vehicle approaches or crosses the marking.

ISO 17361 defines an **earliest and latest warning line** relative to the lane boundary and requires the system to issue the warning within **± 0.1 m of the warning threshold** when the rate of departure is **less than 0.8 m/s** ([ISO 17361 performance, ANSI/GlobalSpec summary](https://standards.globalspec.com/std/10160301/iso-17361)). Departure decisions therefore depend jointly on lateral position and lateral velocity: faster drift triggers the warning slightly earlier (predictive), slower drift triggers it nearer the line.

| Parameter | ISO 17361 reference value |
|-----------|---------------------------|
| Warning-line placement tolerance | ± 0.1 m from threshold |
| Max rate of departure for spec accuracy | < 0.8 m/s |
| Action taken | Warning only (no steering) |
| Operating domain | Highway / highway-like roads |

### Turn-signal suppression
When the driver activates the turn signal in the direction of departure, the lane crossing is intentional and the warning is **suppressed**. Likewise LKA must not apply corrective steering against an indicated maneuver: the controller receives a turn-signal suppression signal and inhibits lane-keep torque because the turn signal indicates intent to leave the lane ([turn-signal suppression logic, USPTO 11772648 / lane-keep patents](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/11772648)). Suppression also typically applies below a minimum speed and where no valid markings are detected.

### Standards and benchmarks
- **ISO 17361** (LDW) and **ISO 11270** (LKAS); a revised ISO/AWI 11270 is in development ([ISO/AWI 11270](https://www.iso.org/standard/92440.html)).
- **UN Regulation No. 130** is the UN regulation for Lane Departure Warning Systems ([UN R130, GlobalAutoRegs](https://globalautoregs.com/rules/160-lane-departure-warning-systems)).
- **UN Regulation No. 157 (ALKS)** governs automated lane keeping (Level 3), operating up to 130 km/h on roads where pedestrians/cyclists are prohibited — relevant as the boundary between assistance (this app's scope) and automation ([UN R157, UNECE](https://unece.org/transport/documents/2021/03/standards/un-regulation-no-157-automated-lane-keeping-systems-alks)).
- **Euro NCAP** assesses Lane Support Systems (LSS), including LDW and Emergency Lane Keeping, under its Safe Driving / Collision Avoidance protocols ([Euro NCAP AEB LSS VRU Test Protocol](https://www.euroncap.com/media/80156/euro-ncap-aeb-lss-vru-test-protocol-v451.pdf)).

---

## 3. Tailgating / Headway Monitoring

### What it does
Headway monitoring measures how closely the host vehicle follows the lead vehicle and warns when the gap is unsafe (tailgating). Unlike FCW, which fires on imminent closing speed (TTC), headway monitoring is about the **steady-state following gap** and is active even when both vehicles travel at the same speed.

### Time-Headway (THW) definition
**Time-Headway (THW)** is the time it would take the host vehicle to reach the lead vehicle's current position at the host's current speed:

```
THW = following_distance / host_speed     (seconds)
```

THW is the time-equivalent of the gap. A driver maintaining the **"two-second rule"** keeps THW ≥ 2 s; some authorities recommend a **three-second** gap for greater margin ([Two-second rule, Wikipedia](https://en.wikipedia.org/wiki/Two-second_rule)). Compliance is low in practice — one field study found only ~49% of drivers met the 2-second rule absent a roadside reminder, rising to ~58% when a sign was present ([Two-second rule field data](https://en.wikipedia.org/wiki/Two-second_rule)).

| THW band | Interpretation | Typical app action |
|----------|----------------|--------------------|
| ≥ 2.0 s | Safe following | No warning |
| 1.0–2.0 s | Close / caution | Soft visual indicator |
| < 1.0 s | Tailgating | Audible/visual warning |
| < 0.5 s | Severe tailgating | Escalated warning |

### Computing distance/headway from a monocular camera
With no radar, range to the lead vehicle is estimated from the **single camera image**. Two standard cues:

1. **Geometry / flat-ground model:** using the camera's intrinsic calibration, mounting height `h`, and the pixel row of the lead vehicle's contact point with the road, distance follows from the pinhole projection (the lower the vehicle's bounding-box bottom in the image, the closer it is). This requires calibration of focal length, pitch, and camera height.
2. **Object-scale / learned model:** a detector (e.g., a CNN) localizes the lead vehicle and a learned regressor maps bounding-box size/position to distance. A monocular-camera + deep-learning headway estimator has been demonstrated with RMSE around **0.045 s** of headway, confirming that camera-only THW is accurate enough for warning purposes ([Headway and Following Distance Estimation using a Monocular Camera, SciTePress](https://www.scitepress.org/Papers/2021/102533/102533.pdf)).

THW is then `estimated_distance / host_speed`, where host speed comes from GPS/IMU. The app should also fall back to a fixed **minimum safe distance** at very low speeds (where THW becomes noisy) to avoid nuisance alerts in stop-and-go traffic.

### Standards
There is no single ISO "tailgating" standard; headway logic is most often bundled into Adaptive Cruise Control and FCW specifications (ISO 15622 for ACC; ISO 15623 for forward collision warning ([ISO 15623](https://www.iso.org/standard/56655.html))). The 2-second/3-second following rule is a long-standing driver-education benchmark rather than a regulatory threshold ([Two-second rule](https://en.wikipedia.org/wiki/Two-second_rule)).

---

## 4. Traffic Sign Recognition (TSR) and Traffic Light Recognition

### What it does
TSR detects and classifies road signs — primarily **speed-limit signs** — and presents the current limit to the driver; combined with vehicle speed it can issue an **over-speed warning** and feed Intelligent Speed Assistance (ISA). Traffic Light Recognition (TLR) detects signal state (red/amber/green) to warn of red-light running or imminent stops. A TSR pipeline is two-stage: **detection** of candidate signs by shape/color, then **classification** of each region of interest to the exact sign type ([Real-time traffic sign recognition, PMC](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5338798/)).

### Perception inputs
- **Camera (primary):** sign/light detection and classification.
- **GPS + map data:** cross-check and supply limits where signs are absent or occluded; the most robust systems **fuse camera recognition with map data** ([Euro NCAP / ISA, ETSC](https://etsc.eu/euro-ncap-new-2026-protocols-target-distraction-impairment-and-speeding/)).
- **Vehicle speed (GPS/IMU/OBD):** compared against the recognized limit for over-speed logic.
- **Context signals** (e.g., wiper state) for conditional limits like "wet weather" sub-signs ([Euro NCAP TSR results, ETSC](https://etsc.eu/updated-euro-ncap-tests-reveal-advances-in-traffic-sign-recognition-technology/)).

### Over-speed warning logic
Maintain a `current_speed_limit` state, updated when a higher-confidence sign is recognized or a map limit applies (camera signs generally override map for temporary/variable limits). Then:

```
if host_speed > current_speed_limit + margin:
    issue over-speed warning   # margin handles speedometer tolerance, e.g. +3–5 km/h
```

A small tolerance margin (a few km/h) reduces nuisance alerts from minor speedometer drift. Production ISA gives a **subtle warning** when the vehicle exceeds the set/recognized limit, and the most advanced systems let the driver confirm the detected limit directly into a speed limiter or ACC ([speed-limit/ISA behavior, arXiv TSR survey](https://arxiv.org/pdf/0910.1295)).

### Standards and benchmarks
- **Intelligent Speed Assistance (ISA)** has been mandatory on new EU vehicle types since 2022; **Euro NCAP** rewards effective Speed Assistance Systems and is, from **2026**, moving verification of speed-limit accuracy **from the test track to real-world on-road driving**, reflecting that ISA effectiveness "depends entirely on the reliability of map data and camera recognition" ([Euro NCAP 2026 protocols, ETSC](https://etsc.eu/euro-ncap-new-2026-protocols-target-distraction-impairment-and-speeding/)).
- Euro NCAP's Safe Driving protocols (Vehicle Assistance, v1.1, Oct 2025) define the current scoring for speed assistance ([Euro NCAP Safe Driving Vehicle Assistance protocol](https://www.euroncap.com/media/91705/euro-ncap-protocol-safe-driving-vehicle-assistance-v11.pdf)).
- Earlier Euro NCAP testing showed systems already reading **conditional sub-signs** (e.g., rain limits linked to wiper status) when fusing map and camera ([Euro NCAP TSR advances, ETSC](https://etsc.eu/updated-euro-ncap-tests-reveal-advances-in-traffic-sign-recognition-technology/)).

For a phone-based app, the practical TSR design point is camera classification gated by map/GPS priors, with conservative confidence thresholds so that misread limits do not trigger false over-speed alerts.

---

## 5. Warning HMI and False-Positive Considerations

### How alerts are presented
ADAS warnings use three channels, often layered by severity:

- **Visual:** icons/banners in the driver's forward field of view. NHTSA FMVSS 127 constrains the FCW visual signal to within **18° vertical × 10° horizontal** of the forward line of sight ([NHTSA FCW signal requirements](https://www.jjkeller.com/news/article/NHTSA-Final-Rule-Federal-Motor-Vehicle-Safety-Standards-Automatic-Emergency-Braking-Systems-for-Light-Vehicles_JJ131169-L-1736183985818)).
- **Audible:** chimes/tones for imminent events; FMVSS 127 requires the FCW tone at **15–30 dB above the masked threshold** with media ducked during the alert ([NHTSA audible requirements](https://www.jjkeller.com/news/article/NHTSA-Final-Rule-Federal-Motor-Vehicle-Safety-Standards-Automatic-Emergency-Braking-Systems-for-Light-Vehicles_JJ131169-L-1736183985818)).
- **Haptic:** steering-wheel or seat vibration (and, on a phone, device vibration) — especially effective for LDW because it spatially maps to the drift direction.

A widely used pattern is **escalation**: a soft visual cue first (e.g., headway caution, lane proximity), then audible + visual for imminent events (FCW at TTC ≈ 2 s, lane crossing), reserving the most intrusive multimodal alert for the highest urgency.

### Why low false-positive rates matter
Warning quality is dominated by **driver trust**. Frequent false or premature alerts cause annoyance, are switched off, or trained to be ignored — degrading real-world benefit even when the underlying detector is accurate. This is precisely why standards push timing and reliability so hard: ISO 17361 bounds LDW timing to ± 0.1 m so warnings are neither early-nuisance nor late ([ISO 17361](https://standards.globalspec.com/std/10160301/iso-17361)); Euro NCAP's 2026 move to on-road ISA verification is justified explicitly because real-world reliability "is essential for driver acceptance" ([ETSC, Euro NCAP 2026](https://etsc.eu/euro-ncap-new-2026-protocols-target-distraction-impairment-and-speeding/)); and human-factors FCW research optimizes alert timing specifically to avoid both late and false-alarm-prone warnings ([FCW timing study, PMC](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5026383/)).

**Design implications for the Android app:**
- Apply **confidence/persistence gating** (require N consecutive frames before warning) and **tolerance margins** (speed-limit +3–5 km/h; THW hysteresis) to suppress nuisance alerts.
- **Suppress on intent** (turn signal for LDW) and on **low-confidence perception** (no lane lines, poor lighting, occlusion).
- Tune TTC/THW thresholds to be early enough to act on yet not chronically triggering — favoring **few, trustworthy, escalating** alerts over many marginal ones.

---

## Cited sources
- ISO 15623:2013 (FVCWS): https://www.iso.org/standard/56655.html
- ISO 22839:2013 (forward collision mitigation): https://standards.iteh.ai/catalog/standards/iso/161e434c-c7e1-419e-ac48-a95f4b3b3487/iso-22839-2013
- ISO 17361:2017 (LDW): https://www.iso.org/standard/72349.html and https://standards.globalspec.com/std/10160301/iso-17361
- ISO 11270:2014 (LKAS): https://www.iso.org/standard/50347.html ; ISO/AWI 11270: https://www.iso.org/standard/92440.html
- NHTSA FMVSS 127 final rule: https://www.federalregister.gov/documents/2024/05/09/2024-09054/federal-motor-vehicle-safety-standards-automatic-emergency-braking-systems-for-light-vehicles ; clarifications: https://www.jjkeller.com/news/article/NHTSA-Final-Rule-Federal-Motor-Vehicle-Safety-Standards-Automatic-Emergency-Braking-Systems-for-Light-Vehicles_JJ131169-L-1736183985818
- Euro NCAP AEB C2C Test Protocol v4.3: https://www.euroncap.com/media/79864/euro-ncap-aeb-c2c-test-protocol-v43.pdf ; LSS/VRU protocol: https://www.euroncap.com/media/80156/euro-ncap-aeb-lss-vru-test-protocol-v451.pdf ; Safe Driving Vehicle Assistance v1.1: https://www.euroncap.com/media/91705/euro-ncap-protocol-safe-driving-vehicle-assistance-v11.pdf
- Euro NCAP 2026 / ISA (ETSC): https://etsc.eu/euro-ncap-new-2026-protocols-target-distraction-impairment-and-speeding/ ; TSR advances (ETSC): https://etsc.eu/updated-euro-ncap-tests-reveal-advances-in-traffic-sign-recognition-technology/
- UN R130 (LDW): https://globalautoregs.com/rules/160-lane-departure-warning-systems ; UN R157 (ALKS): https://unece.org/transport/documents/2021/03/standards/un-regulation-no-157-automated-lane-keeping-systems-alks ; UN regs overview: https://www.otobrite.com/news/understanding-un-vehicle-safety-regulations-and-how-sensors-help-vehicles-comply/detail
- FCW timing (human factors): https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5026383/
- Two-second rule: https://en.wikipedia.org/wiki/Two-second_rule
- Monocular headway estimation: https://www.scitepress.org/Papers/2021/102533/102533.pdf
- Real-time TSR (deep learning): https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5338798/ ; TSR speed-limit survey: https://arxiv.org/pdf/0910.1295
- Turn-signal suppression (LKA logic): https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/11772648
