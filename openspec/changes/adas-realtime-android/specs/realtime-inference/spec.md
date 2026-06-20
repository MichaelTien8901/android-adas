## ADDED Requirements

### Requirement: On-device NPU inference runtime
The system SHALL run road-perception model inference fully on-device using the
Qualcomm Hexagon NPU as the primary accelerator. The runtime SHALL load a
pre-compiled HTP context binary matched to the running device's SoC and SHALL NOT
require network connectivity to perform inference.

#### Scenario: Inference runs on the Hexagon NPU
- **WHEN** the app starts on a supported Snapdragon device with a valid context binary
- **THEN** the runtime loads the QNN HTP backend and executes detection inference on the NPU
- **AND** no model inference traffic is sent over the network

#### Scenario: Offline operation
- **WHEN** the device has no network connectivity
- **THEN** the perception pipeline continues to run at full capability

### Requirement: Per-device model targeting
The system SHALL parameterize the model deployment per device tier so a single ONNX
export and quantized graph can be re-contexted per target without re-quantizing. The
runtime SHALL select the correct HTP architecture and SoC parameters for the running
device (S22+ → `v69`/`taro`; S26 Ultra → `v81`, SoC id/dsp_arch read on-device).

#### Scenario: S22+ development target
- **WHEN** the app runs on a Galaxy S22+ (SM8450, `ro.board.platform`=`taro`)
- **THEN** the runtime loads the `v69` HTP context binary

#### Scenario: S26 Ultra deployment target
- **WHEN** the app runs on a Galaxy S26 Ultra (Snapdragon 8 Elite Gen 5)
- **THEN** the runtime loads the `v81` HTP context binary built for that SoC id

#### Scenario: Mismatched or missing context binary
- **WHEN** no context binary matches the device SoC id / HTP arch
- **THEN** the runtime does not silently fall back to CPU without notice
- **AND** it activates the documented fallback path and surfaces a degraded-capability indicator

### Requirement: Quantized model pipeline
The system SHALL deploy an INT8 post-training-quantized model produced by the
documented pipeline (YOLO11n → ONNX → QNN → INT8 → HTP context binary). Quantization
calibration SHALL use representative road imagery, and the quantized model's detection
accuracy SHALL be validated against the float baseline before deployment.

#### Scenario: Quantized accuracy gate
- **WHEN** the INT8 model is built
- **THEN** its detection accuracy on the validation set is measured against the FP baseline
- **AND** the model is rejected if accuracy degradation exceeds the configured threshold

### Requirement: Non-Snapdragon fallback path
The system SHALL detect devices without a usable Hexagon NPU (e.g. Exynos SKUs) and
SHALL fall back to a LiteRT GPU/CPU or ONNX-Runtime delegate path rather than failing.
The active acceleration path SHALL be discoverable at runtime.

#### Scenario: Exynos / no-NPU device
- **WHEN** `ro.board.platform` indicates an Exynos/non-Snapdragon SoC
- **THEN** the runtime selects the LiteRT/GPU fallback delegate
- **AND** the app indicates that it is running in reduced-performance mode

### Requirement: Realtime frame scheduling and latency budget
The system SHALL process camera frames in realtime, dropping stale frames rather than
queuing them, and SHALL sustain a minimum end-to-end perception rate. The runtime
SHALL target at least 15 FPS sustained inference and SHALL keep glass-to-detection
latency within the configured budget under nominal conditions.

#### Scenario: Backpressure under load
- **WHEN** inference cannot keep pace with the camera frame rate
- **THEN** the runtime processes only the most recent frame and drops intermediate frames
- **AND** the displayed overlay never lags more than one inference cycle behind the camera

#### Scenario: Sustained-rate floor
- **WHEN** the pipeline runs continuously for a sustained period
- **THEN** the measured inference rate remains at or above the minimum FPS floor under nominal thermal conditions

### Requirement: Thermal-aware sustained operation
The system SHALL measure sustained (post-throttle) inference performance and SHALL
degrade gracefully under thermal throttling rather than dropping frames silently. The
deployment performance claim SHALL be based on post-throttle sustained FPS.

#### Scenario: Thermal throttling
- **WHEN** sustained load causes the SoC to throttle and inference FPS drops below the floor
- **THEN** the runtime reduces input resolution or inference cadence to maintain responsiveness
- **AND** the app surfaces a thermal/degraded indicator to the driver
