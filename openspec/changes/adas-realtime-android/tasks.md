## 1. Project scaffold & app shell

- [x] 1.1 Create the Kotlin Android project (min/target SDK for Android 14+, Gradle, module layout)
- [x] 1.2 Add dependencies: CameraX, OpenCV for Android, Play Services Location, sensors
- [x] 1.3 Implement runtime permissions flow (camera, location) with denial handling
- [x] 1.4 Implement the typed foreground service for active driving mode + keep-screen-on
- [x] 1.5 Add the first-run safety disclaimer screen with required acknowledgement gate

## 2. Camera capture pipeline (adas-perception)

- [x] 2.1 Wire CameraX preview + `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST`
- [x] 2.2 Configure capture resolution/FPS as tunable params against the latency budget
- [x] 2.3 Convert YUV_420_888 frames to the model input format with low-latency reuse
- [ ] 2.4 Verify backpressure: under load only the latest frame is processed, others dropped (needs on-device run)

## 3. Device identification & QNN runtime (realtime-inference)

- [x] 3.1 Read `ro.board.platform` / `ro.soc.model` / HTP arch on-device; detect Exynos
- [x] 3.2 Implement acceleration-path selection: QNN → ONNX-RT QNN-EP → LiteRT GPU → CPU
- [x] 3.3 Load the per-device HTP context binary by SoC id (v69 S22+ / v81 S26 Ultra)
- [x] 3.4 Expose the active acceleration path + a reduced-performance indicator at runtime
- [x] 3.5 Implement the realtime frame scheduler (drop-stale, ≥15 FPS sustained floor)
- [x] 3.6 Add thermal-aware degradation (reduce resolution/cadence under throttle)

## 4. Model toolchain & quantization (realtime-inference)
<!-- Scripts authored in tools/ ; execution requires the Qualcomm QNN SDK + the phones. -->

- [ ] 4.1 Export YOLO11n → ONNX with the chosen input resolution and COCO classes (script: tools/export_yolo11n.py)
- [ ] 4.2 Build the QNN INT8 post-training quantization with representative road calibration data (script: tools/quantize_qnn.sh)
- [ ] 4.3 Validate quantized detection accuracy vs FP baseline; enforce the accuracy gate (script: tools/validate_accuracy.py)
- [ ] 4.4 Generate the S22+ (v69/taro) HTP context binary; benchmark with qnn-net-run (script: tools/gen_context_binary.sh)
- [ ] 4.5 Read S26 Ultra soc_id/dsp_arch on-device and generate the v81 context binary (script: tools/gen_context_binary.sh)
- [ ] 4.6 Export & INT8-quantize UFLDv2 lane model (CULane-validated) to a co-resident context binary (script: tools/export_ufldv2.py)
- [ ] 4.7 Run the 10-min sustained thermal loop with both models co-resident; record post-throttle FPS (script: tools/benchmark_sustained.sh)

## 5. Perception engine (adas-perception)

- [x] 5.1 Implement object detection inference + NMS + confidence-threshold suppression
- [x] 5.2 Implement UFLDv2 lane inference → ego-lane left/right geometry; report unavailable when unreliable
- [x] 5.3 Implement OpenCV classical-CV lane fallback for when the lane NPU model is unavailable
- [x] 5.4 Implement monocular distance (pinhole + ground-plane / known-width) with camera intrinsics
- [x] 5.5 Implement TTC from lead-vehicle bbox-scale change with Kalman/temporal filtering
- [x] 5.6 Define and publish the timestamped per-frame perception contract (detections + lanes + distance/TTC)

## 6. Speed-context service (speed-context)

- [x] 6.1 Acquire GPS speed via FusedLocationProvider with per-sample accuracy
- [x] 6.2 Implement low-pass smoothing and the standstill clamp
- [x] 6.3 Implement IMU dead-reckoning to bridge bounded GPS dropouts
- [x] 6.4 Implement the `VALID / DEAD_RECKONED / INVALID` validity + freshness contract
- [x] 6.5 Expose the gating API (smoothed speed + validity) — no raw-GPS access for consumers

## 7. ADAS warning evaluators

- [x] 7.1 FCW: two-stage TTC thresholds, speed gating + latency margin, ego-path relevance filtering (collision-warning)
- [x] 7.2 LDW: departure detection, activation-speed gate + hysteresis, lane-availability gate, best-effort intent suppression (lane-departure-warning)
- [x] 7.3 Headway: THW estimation, tailgating threshold with dwell + hysteresis, low-speed gating (headway-monitoring)
- [x] 7.4 TSR: speed-limit recognition + persistence/expiry, over-speed warning with tolerance, best-effort stop/light cues (traffic-sign-recognition)
- [x] 7.5 Wire all four evaluators to the perception contract + speed-context; make each independently toggleable

## 8. Driver HMI (driver-alert-hmi)

- [x] 8.1 Transparent SurfaceView overlay: bounding boxes, lane geometry, warning indicators, in-sync with latest frame
- [x] 8.2 Multi-modal alerts: urgency-scaled visual + audible (ToneGenerator/SoundPool) + haptic, with mute setting
- [x] 8.3 GLSurfaceView HUD mirror mode (simplified high-contrast layout)
- [x] 8.4 Degraded-mode indicators: speed lost/dead-reckoned, thermal throttle, fallback path, lanes unavailable
- [x] 8.5 Settings/about screen with persistent access to the safety disclaimer

## 9. Integration, benchmark & validation
<!-- Requires the physical S22+ / S26 Ultra units + Qualcomm QNN SDK. -->

- [ ] 9.1 End-to-end on S22+: measure glass-to-warning latency against the ≤~100 ms budget
- [ ] 9.2 Validate each warning's activation/clear behavior against its spec scenarios (road or replayed footage)
- [ ] 9.3 Validate degraded-mode behavior: GPS dropout (tunnel), thermal throttle, non-Snapdragon fallback
- [ ] 9.4 Deploy & benchmark on S26 Ultra; make the deploy decision on post-throttle sustained FPS
- [ ] 9.5 Resolve the YOLO11n AGPL-3.0 licensing decision (enterprise license vs detector swap) before distribution
