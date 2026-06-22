# openpilot-inspired-lane-stability

Borrow openpilot's temporal-stability ideas (recurrent state, per-point uncertainty) as on-device classical surrogates — Kalman/clothoid lane-coefficient tracking with outlier gating and confidence weighting — without porting the supercombo model. Keeps all existing TSR/speed-limit/stop-sign features.
