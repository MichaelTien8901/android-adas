# Speed Source & Warning Gating

How the ADAS app obtains vehicle speed, why it can only use **GPS speed** (not the
vehicle's CAN bus), and how every speed-dependent warning must be *gated* around
that coarser signal. This is a load-bearing constraint: it shapes the warning
thresholds, the smoothing/hysteresis logic, and the safety disclaimers.

Related: [01-adas-functions.md](01-adas-functions.md) (the warnings being gated),
[04-app-architecture-libraries.md](04-app-architecture-libraries.md) (sensors &
form-factor constraints).

---

## 1. What "gating" means

Most ADAS warnings should **not** fire at every speed. *Gating* is the logic that
enables, suppresses, or re-scales a warning based on how fast the car is currently
moving. The system therefore needs a continuous answer to "how fast am I going
right now?" before it decides whether — and how urgently — to warn.

Concretely, each of the four functions in this app depends on speed:

| Function | How speed gates it |
|---|---|
| **Lane Departure Warning (LDW)** | Active only above an activation speed (commonly **~60 km/h / 37 mph**, per ISO 17361 the standard is validated from 60–180 km/h). Below it — parking, stop-and-go, turning — lane crossings are normal, so LDW must stay silent to avoid nuisance. |
| **Forward Collision Warning (FCW)** | The warning distance scales with speed. For a fixed Time-To-Collision, a car at 100 km/h must be warned much earlier (more meters) than at 30 km/h. Below a floor speed (~5–10 km/h) FCW is usually suppressed. |
| **Headway / Tailgating** | The "safe following distance" *is* a function of speed: the 2-second rule means more meters at higher speed. With no speed you cannot compute a headway threshold at all. |
| **Traffic Sign Recognition over-speed** | The whole point is comparing **current speed** to the detected limit and warning on exceedance (plus a tolerance margin). |

So speed is not optional context — it is a primary input to three of the four
warning decisions and the entire premise of the fourth.

---

## 2. Where speed *should* come from — and why we can't have it

A production in-car ADAS reads speed off the vehicle's **CAN bus** (Controller Area
Network), the internal network the car's own modules use. That signal is fast
(~50 updates/second), low-latency, exact, and available everywhere the car drives —
tunnels included.

**A standalone third-party phone app cannot access the CAN bus.** Android exposes no
API for a normal app to read vehicle data. The only environment that can is
**Android Automotive OS (AAOS)** — the OS *built into* the car's head unit — via
`CarPropertyManager` (e.g. `PERF_VEHICLE_SPEED`). Our target is the opposite: a
**mounted phone running a foreground app** (see doc 04 — Android Auto itself also
forbids custom camera/CV apps). A mounted phone has no electrical or software path
to the car's network.

That leaves exactly one usable speed source on the phone:

> **GPS-derived speed**, obtained from the fused location provider
> (`FusedLocationProviderClient`), which reports a `speed` (m/s) on each location
> update, computed from successive position fixes / Doppler.

Everything the app does with speed must therefore be built on top of GPS speed and
must tolerate its weaknesses.

---

## 3. GPS speed vs CAN speed — the tradeoffs

| Property | Vehicle / CAN speed (**unavailable**) | GPS speed (**what we get**) |
|---|---|---|
| Update rate | ~50 Hz | ~1 Hz typical (configurable, but limited by fix cadence) |
| Latency | Near-instant | ~0.5–1.5 s lag (position must change to be measured) |
| Accuracy when locked | Exact (wheel-speed derived) | ±1–2 km/h with good sky view; degrades in poor geometry |
| At standstill | Reads exactly 0 | **Jitters** — often reports 2–3 km/h when parked |
| Low speed (<5 km/h) | Accurate | Unreliable — noise dominates the small position deltas |
| Tunnels / parking garages / urban canyons | Always works | **Drops out** — no satellite view, speed goes stale/absent |
| Hard braking / rapid change | Tracks instantly | Lags behind the real value during transients |

The headline problems for ADAS are the **1 Hz / lagged updates**, the **dropout in
tunnels and dense cities**, and the **jitter near zero**. Each forces a specific
piece of mitigation logic.

---

## 4. Mitigations — how the app makes GPS speed usable

### 4.1 Smoothing + hysteresis around activation thresholds
GPS speed jitter near a gating boundary (e.g. LDW's 60 km/h on/off line) would
otherwise flicker the warning system on and off. Mitigate with:
- A short **low-pass / moving-average filter** on the speed signal.
- **Hysteresis**: enable LDW at, say, 62 km/h but only disable it below 57 km/h, so
  small oscillations around 60 don't toggle state.
- A minimum **dwell time** before changing activation state.

### 4.2 Dead-reckoning through GPS dropouts (tunnels)
When fixes stop arriving, fuse the last known GPS speed with the phone's **IMU
(accelerometer/gyroscope)** to estimate speed for a short window (seconds to tens of
seconds). This is short-horizon dead reckoning — accuracy decays with time, so it is
a bridge, not a replacement.

### 4.3 Explicit "speed unknown" degraded mode
If GPS is unavailable beyond the dead-reckoning window, the app must **degrade
gracefully and visibly**:
- Suppress speed-gated warnings (LDW activation, over-speed) rather than firing them
  on stale data.
- Keep purely visual perception (object boxes, lane overlay) running — those don't
  need speed.
- Surface a clear "speed signal lost" indicator so the driver knows the assist level
  dropped.

### 4.4 Standstill handling
Clamp speeds below a floor (~3 km/h) to "stopped" so parked-car jitter doesn't read
as motion and trigger headway/over-speed logic.

### 4.5 Latency-aware thresholds
Because GPS speed lags real speed during acceleration/braking, FCW/headway thresholds
should carry a small safety margin (warn slightly earlier) to absorb the lag rather
than assume the reported speed is current.

---

## 5. Recommended speed-source design

```
        ┌─────────────────────┐
        │ FusedLocationProvider│  ~1 Hz, speed (m/s) + accuracy
        └──────────┬──────────┘
                   │  raw GPS speed
                   ▼
        ┌─────────────────────┐      IMU (accel/gyro) ───┐
        │  Speed estimator     │◄─────────────────────────┘ dead-reckon on dropout
        │  • low-pass filter    │
        │  • standstill clamp    │
        │  • staleness/validity  │
        └──────────┬──────────┘
                   │  smoothed speed + validity flag
                   ▼
        ┌─────────────────────┐
        │  Warning gating layer │  per-function activation + threshold scaling
        │  (LDW / FCW / headway │  hysteresis, latency margin
        │   / over-speed)        │  → if speed invalid: degrade & notify
        └─────────────────────┘
```

The estimator emits **two** things every cycle: a smoothed speed value *and* a
**validity flag** (`VALID` / `DEAD_RECKONED` / `INVALID`). Every gating decision
reads both — never the raw GPS number directly.

---

## 6. Why this matters beyond engineering

The speed signal being fundamentally coarser than a real car's is one of the core
reasons this product is a **driver-assistance aid, not a safety-certified ADAS**:
- It cannot meet the timing/availability assumptions baked into standards like ISO
  17361 (LDW) or NHTSA FMVSS 127 (FCW/AEB), which presume a vehicle-grade speed
  source.
- Warnings can be late or absent exactly where the GPS is weak (tunnels, garages,
  dense urban canyons) — situations the driver must not rely on the app to cover.

This must be stated plainly in the in-app disclaimer and reinforced by the degraded-
mode UI, so the driver's mental model matches the system's true capability.

---

## 7. Spec implications (carry into proposal / specs)

- **`realtime-inference` or a dedicated `speed-context` capability**: a requirement
  defining the speed source (GPS via FusedLocationProvider), the smoothing/standstill
  rules, the IMU dead-reckoning fallback, and the `VALID/DEAD_RECKONED/INVALID`
  validity states.
- **`driver-alert-hmi`**: requirements for the "speed signal lost / degraded" indicator
  and for suppressing speed-gated warnings when speed is invalid.
- **Each warning capability** (LDW, FCW, headway, over-speed): each spec must state its
  activation-speed gate, threshold-vs-speed scaling, and hysteresis behavior explicitly.
- **Disclaimer requirement**: the GPS-only limitation is named in the user-facing
  safety disclaimer.

---

## References

- Android `FusedLocationProviderClient` — speed in location updates:
  https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient
- Android `Location.getSpeed()` / speed accuracy:
  https://developer.android.com/reference/android/location/Location
- Android Automotive `CarPropertyManager` (`PERF_VEHICLE_SPEED`) — the vehicle-speed
  API available only to AAOS, not to phone apps:
  https://developer.android.com/reference/android/car/hardware/property/CarPropertyManager
- ISO 17361 (LDW) activation-speed range context — see
  [01-adas-functions.md](01-adas-functions.md).
- Android Auto custom-app restrictions / no CAN access on phone — see
  [04-app-architecture-libraries.md](04-app-architecture-libraries.md).
