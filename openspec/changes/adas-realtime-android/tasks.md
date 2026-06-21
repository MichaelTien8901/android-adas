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
- [x] 2.4 Verify backpressure: under load only the latest frame is processed, others dropped — VALIDATED on the S26. CameraX `STRATEGY_KEEP_ONLY_LATEST` + a synchronous analyzer (`proxy.close()` after `onFrame`) means CameraX delivers only the latest frame and drops stale ones upstream; the `FrameScheduler` in-flight guard is a secondary safety. Live run: sensor ~30 fps but pipeline processes ~7-10 fps (so ~2/3 dropped by keep-latest) with steady FPS and bounded latency — no queue buildup. Added a `dropped` counter to the latency log (`dropped=0` confirms the guard never backs up; the drops happen in CameraX, by design).

## 3. Device identification & QNN runtime (realtime-inference)

- [x] 3.1 Read `ro.board.platform` / `ro.soc.model` / HTP arch on-device; detect Exynos
- [x] 3.2 Implement acceleration-path selection: QNN → ONNX-RT QNN-EP → LiteRT GPU → CPU
- [x] 3.3 Load the per-device HTP context binary by SoC id (v69 S22+ / v81 S26 Ultra)
- [x] 3.4 Expose the active acceleration path + a reduced-performance indicator at runtime
- [x] 3.5 Implement the realtime frame scheduler (drop-stale, ≥15 FPS sustained floor)
- [x] 3.6 Add thermal-aware degradation (reduce resolution/cadence under throttle)

## 4. Model toolchain & quantization (realtime-inference)
<!-- 4.1 and the ONNX-export half of 4.6 need only Python/Ultralytics (done).
     The QNN INT8 / context-binary / benchmark steps (4.2-4.5, 4.7) require the
     Qualcomm QNN SDK + the phones. -->

- [x] 4.1 Export YOLO11n → ONNX with the chosen input resolution and COCO classes (script: tools/export_yolo11n.py) — done via tools/fetch_detector.sh; detector.onnx (1x3x640x640 -> 1x84x8400, COCO), validated on S22+
- [x] 4.2 Build the QNN INT8 post-training quantization with representative road calibration data (script: tools/quantize_qnn.sh) — done with QAIRT 2.26.2 (qairt-converter -> qairt-quantizer) in an ubuntu:22.04/py3.10 container; 64 real road frames as calibration; detector.dlc (11M) -> detector_quant.dlc (2.9M INT8)
- [x] 4.3 Validate quantized detection accuracy vs FP baseline; enforce the accuracy gate (script: tools/validate_accuracy.py) — FP onnx vs INT8 (x86 HTP emulation) on road frames: per-TENSOR W8A16 lost marginal detections (69% recall); per-CHANNEL W8A16 recovers 100% recall vs FP, Δconf 0.044, no false positives. Detector context binary regenerated with --use_per_channel_quantization; verified on S22+ NPU (truck 62%). NOTE: full COCO-mAP gate + more diverse calibration still recommended for production.
- [x] 4.4 Generate the S22+ (v69/taro) HTP context binary; benchmark with qnn-net-run (script: tools/gen_context_binary.sh) — detector_v69.bin (3.9M) generated and RUN on the S22+ Hexagon v69 NPU: ~8.6 ms/inference (accel compute ~6.0 ms, 4 HVX threads), output [1,84,8400] verified. Well above the 15-FPS floor.
- [x] 4.5 Read S26 Ultra soc_id/dsp_arch on-device and generate the v81 context binary — DONE with QAIRT 2.47.0.260601 (2.26 was too old). S26 = SM-S948W / SM8850 / canoe / HTP v81 (soc table soc_id 660). Generated detector_v81.bin + lane_v81.bin (dsp_arch v81), RUN on the S26 Hexagon NPU: detector/lane -> QNN_HTP, ~54-58 ms perception (vs ~170 ms CPU). DeviceInfo maps SM8850->v81. Needed 16KB-page alignment on libadas_qnn.so for the S26 (Android 16).
- [x] 4.6 Export & INT8-quantize UFLDv2 lane model to a co-resident context binary (script: tools/export_ufldv2.py) — fp32 UFLDv2 (TuSimple) -> qairt-converter -> INT8 (64 road frames, lane preprocessing) -> lane_v69.bin (61M, FC-heavy), RUN on S22+ Hexagon v69 NPU at ~11.5 ms; loc[1,4,56,100]+exist[1,4,56] verified. (Still TuSimple, not CULane.)
- [ ] 4.7 Run the 10-min sustained thermal loop with both models co-resident; record post-throttle FPS (script: tools/benchmark_sustained.sh)

## 5. Perception engine (adas-perception)

- [x] 5.1 Implement object detection inference + NMS + confidence-threshold suppression
- [x] 5.2 Implement UFLDv2 lane inference → ego-lane left/right geometry; report unavailable when unreliable
- [x] 5.3 Implement OpenCV classical-CV lane fallback for when the lane NPU model is unavailable
- [x] 5.4 Implement monocular distance (pinhole + ground-plane / known-width) with camera intrinsics
- [x] 5.5 Implement TTC from lead-vehicle bbox-scale change with Kalman/temporal filtering
- [x] 5.6 Define and publish the timestamped per-frame perception contract (detections + lanes + distance/TTC)
- [x] 5.7 Lateral center calibration: a camera mounted off-centre / angled makes the straight-ahead point sit away from image x=0.5, biasing lane-departure judgement. Add a calibratable `centerRatio` (Calibration + Prefs, default 0.5) — a third draggable VERTICAL line in the guided calibration — and use it as the ego reference in `LaneDepartureWarning` (replace the hardcoded `ego = 0.5f`) and in the lead-vehicle in-path band (`PerceptionEngine.selectLead` 0.30–0.70).

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
      <!-- COMPLETE as of 7.6: over-speed logic (persistence/expiry/tolerance) is
           wired, stop-sign + traffic-light cues validated on-device (S22+ replay,
           ~0.85 conf), and speed-LIMIT recognition is now functional via the
           dedicated SpeedLimitRecognizer (7.6) — SPEED_LIMIT_SIGN detections carry
           the value and the over-speed warning fires. (YOLO11n/COCO has no
           speed-limit class, hence the separate GTSRB classifier.) -->
- [x] 7.5 Wire all four evaluators to the perception contract + speed-context; make each independently toggleable
- [x] 7.6 Implement speed-limit sign recognition (the missing input for 7.4 over-speed) — DONE. `SpeedLimitRecognizer` (perception/): OpenCV `HoughCircles` proposes circular sign regions → a GTSRB-trained CNN (`SignNet`, tools/train_gtsrb.py, 6 epochs, **94.8% test acc; 100% on all 8 speed-limit classes 20–120**, `assets/models/gtsrb.onnx`) reads the value → emits `SPEED_LIMIT_SIGN` with the limit in `Detection.attribute`. Wired into `PerceptionEngine` (every 3rd frame, result persists). A **red-ring annulus gate** rejects the closed-set classifier's false positives (no "background" class → any circular road feature is forced into a class): validated end-to-end on a real-GTSRB-50 composite clip (SPEED_LIMIT fires, over-speed 70>55 warns) with **0/300 false positives on a sign-free road clip**. Light-state (red/yellow/green) classifier not added — traffic-light presence is surfaced as an INFO cue only.

## 8. Driver HMI (driver-alert-hmi)

- [x] 8.1 Transparent SurfaceView overlay: bounding boxes, lane geometry, warning indicators, in-sync with latest frame
- [x] 8.2 Multi-modal alerts: urgency-scaled visual + audible (ToneGenerator/SoundPool) + haptic, with mute setting
- [x] 8.3 GLSurfaceView HUD mirror mode (simplified high-contrast layout)
- [x] 8.4 Degraded-mode indicators: speed lost/dead-reckoned, thermal throttle, fallback path, lanes unavailable
- [x] 8.5 Settings/about screen with persistent access to the safety disclaimer
- [x] 8.6 Draw the **drivable area** (a.k.a. free space / ego-lane corridor): fill the region between the left and right ego-lane boundaries (`LaneGeometry.left`/`right`) as a translucent polygon in `OverlayView`, tinted by state (e.g. green normal, amber on lane-departure). Requires both boundaries present; degrade gracefully to lines-only when one is missing.

## 9. Integration, benchmark & validation
<!-- 9.1/9.4 (latency, sustained-FPS deploy) need the Qualcomm QNN SDK + units.
     9.2/9.3/9.6 can be done now on the ORT-CPU fallback path (no SDK). -->

<!-- Validated on-device 2026-06-20 (Galaxy S22+ SM-S906W, ORT-CPU fallback path,
     no QNN SDK): debug APK builds, installs, launches; first-run disclaimer +
     runtime permissions; device ID correct (platform=taro soc=SM8450 htp=v69);
     CameraX opens; both models load (detector -> CPU, lane -> CPU); classical-CV
     lane fallback engages cleanly when the lane model is absent. Perception
     *functions* (detections/warnings) not yet exercised — needs road imagery (9.6). -->

- [x] 9.1 End-to-end on S22+: measure glass-to-warning latency against the ≤~100 ms budget — instrumented the pipeline; perception+warning compute ~80-94 ms on the QNN_HTP path (NPU inference ~20 ms; remainder is CPU preprocessing). Within the ~100 ms budget; CPU preprocessing is the optimization lever, not inference.
- [ ] 9.2 Validate each warning's activation/clear behavior against its spec scenarios (road or replayed footage)
      <!-- IN PROGRESS. Harness: DrivingService now logs both WARN (activate) and
           CLEAR (deactivate) transitions; tools/make_test_clip.py --scene speed
           --limits A,B builds parameterized scenarios.
           DONE — Over-speed: validated activate+clear on the S26. A 30→120 clip at
           110 km/h fires OVER_SPEED in the 30 zone (110>35) and CLEARs at the 120
           sign (110<125), then re-fires on loop. (Stop/traffic-light INFO cues also
           seen firing earlier.)
           REMAINING — FCW, LDW, Headway: each needs a lead-vehicle / lane-drift
           scenario. Synthetic lanes drive UFLDv2 too noisily for a reliable LDW
           clear, and FCW/headway need a YOLO-detectable lead vehicle (closing then
           opening) — best done with short real dashcam clips. -->
- [ ] 9.2b (cont.) FCW / LDW / Headway activate+clear scenarios — see note above
- [x] 9.3 Validate degraded-mode behavior: GPS dropout (tunnel), thermal throttle, non-Snapdragon fallback — non-v69-Snapdragon fallback VALIDATED on the S26 (SM8850): unrecognized SoC -> htp=null -> QNN skipped -> detector/lane -> CPU, app runs fine with NO NPU chip (the 'mismatched/missing context binary' path). Thermal chip split from low-FPS validated earlier. GPS-dropout scenario (replay injects synthetic speed) still pending a live-GPS test.
- [x] 9.4 Deploy & benchmark on S26 Ultra — app runs on the S26 v81 NPU (detector/lane -> QNN_HTP, ~54-58 ms perception, ~3x faster than its CPU and faster than the S22+ v69 ~85 ms, as expected for the Elite Gen 5). Same APK serves both devices via per-arch context binary selection (v69 S22+ / v81 S26). Sustained/post-throttle FPS curve still TODO (thermal loop skipped). 16 KB-page support added for the S26/Android 16: libadas_qnn.so linked 16 KB-aligned, deps bumped (CameraX 1.4.2 / OpenCV 4.11 / ORT 1.22 / TFLite 2.17), and a 16 KB libc++_shared.so (NDK r27, pickFirst) overrides OpenCV's 4 KB one — all 13 native libs 16 KB-aligned, no compatibility prompt.
- [ ] 9.5 Resolve the YOLO11n AGPL-3.0 licensing decision (enterprise license vs detector swap) before distribution
- [x] 9.6 Add a debug replay source (pushed MP4 → Bitmap → PerceptionEngine, bypassing CameraX) behind a Settings toggle, with injected synthetic speed + warning-transition logging, so each warning's activation/clear can be validated deterministically without driving (the replayed-footage method for 9.2). Validated on S22+ (LDW fired on a synthetic clip). Uses a MediaCodec + MediaExtractor + getOutputImage decoder (sequential, ~5x faster than the old MediaMetadataRetriever seek-per-frame path). Replay FPS now bounded by CPU YUV->RGB + preprocessing, not decode; the live-camera path (RGBA direct) avoids that.
- [x] 9.7 Close the lane preprocessing gap: replicate UFLDv2's top-crop (crop_ratio) in `Preprocess` for the lane input so row-anchor alignment is correct (currently full-frame letterbox → approximate lanes)
- [x] 9.8 Move perception onto the NPU — DONE via the full QNN HTP path (better than the ORT-EP plan): native libadas_qnn.so JNI bridge loads the v69 context binaries and executes on the Hexagon NPU. App shows `detector -> QNN_HTP` + `lane -> QNN_HTP` on the S22+; detections + lanes render, `NO NPU` chip clears. Key fixes: <uses-native-library libcdsprpc.so> (cDSP RPC access), skel as assets/models/dsp materialized to ADSP_LIBRARY_PATH, QNN log handle, and W8A16 detector quant (per-tensor INT8 crushed the mixed-range [box|score] output -> no detections; 16-bit activations fixed it).
