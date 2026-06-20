# QNN / Hexagon integration & model pipeline

This app runs YOLO11n (detection) + UFLDv2 (lanes) on the Qualcomm Hexagon NPU
via pre-built HTP **context binaries**, with ONNX-Runtime/LiteRT fallbacks. The
Kotlin app builds and runs **without** the QNN SDK (the native path is optional
and falls back); to get the NPU path you must build the models and the native
bridge against the Qualcomm AI Engine Direct (QNN) SDK.

## 0. Prerequisites
- Qualcomm AI Engine Direct (QNN) SDK → set `QNN_SDK_ROOT`.
- `pip install ultralytics onnx onnxsim`
- A connected S22+ / S26 unit over `adb`.
- Calibration frames (representative road imagery) for INT8 quantization.

## 1. Confirm device identity (run once per unit)
```bash
adb shell getprop ro.board.platform   # S22+: taro ; S26: RECORD this
adb shell getprop ro.soc.model        # S22+: SM8450
adb shell getprop ro.board.platform | grep -qi exynos && echo "EXYNOS -> no QNN path"
```
Put the S26 `soc_id` / platform / HTP arch into `tools/target.env` **and**
`DeviceInfo.kt` (`socId()` / `htpArchFor()`) — both are marked TBD for Elite Gen 5.

## 2. Export → quantize → context binary (per model, per device)
```bash
# detector
python tools/export_yolo11n.py --imgsz 640 --out build/detector.onnx          # task 4.1
QNN_SDK_ROOT=$QNN_SDK_ROOT TARGET_DEVICE=s22plus tools/quantize_qnn.sh build/detector.onnx detector   # 4.2
python tools/validate_accuracy.py --fp build/detector.onnx --int8 detector --data data/val --max-drop 0.03  # 4.3
TARGET_DEVICE=s22plus tools/gen_context_binary.sh detector                     # 4.4 (v69)
TARGET_DEVICE=s26ultra tools/gen_context_binary.sh detector                    # 4.5 (v81)

# lane model (same pipeline) — task 4.6
python tools/export_ufldv2.py --ckpt culane_res18.pth --out build/lane.onnx
TARGET_DEVICE=s22plus tools/quantize_qnn.sh build/lane.onnx lane
TARGET_DEVICE=s22plus tools/gen_context_binary.sh lane
TARGET_DEVICE=s26ultra tools/gen_context_binary.sh lane
```
Outputs land in `app/src/main/assets/models/<base>_<arch>.bin` and are bundled as
uncompressed assets (see `noCompress` in build.gradle). The app picks the binary
matching the device's HTP arch at runtime (`QnnModelRunner`).

## 3. Sustained thermal benchmark (decisive — research/03)
```bash
TARGET_DEVICE=s22plus tools/benchmark_sustained.sh detector 20000   # tasks 4.7 / 9.4
```
Record cold FPS, FPS@10min, throttle-knee. **Deploy decision uses post-throttle
sustained FPS (must stay ≥ 15), not the cold peak.** Run with both models present.

## 4. Native bridge (`libadas_qnn.so`)
`app/src/main/cpp/adas_qnn.cpp` is a reference JNI skeleton implementing the four
`QnnNative` entrypoints (loadContext/run/outputShapes/release). Implement the
TODOs against your SDK's `qnn-sample-app` (retrieve-context + execute), then:
1. Enable the CMake block noted in `app/src/main/cpp/CMakeLists.txt`.
2. Copy `libQnnHtp.so`, `libQnnHtpV69Stub.so` / `libQnnHtpV81Stub.so`, and the
   matching Hexagon skel `.so` into `app/src/main/jniLibs/arm64-v8a/`.
3. Rebuild — `QnnNative.available` becomes true and the QNN HTP path activates;
   otherwise the app cleanly falls back to ONNX-Runtime/LiteRT.

## Fallback paths (no SDK / non-Snapdragon)
- Bundle `detector.onnx` / `lane.onnx` at `assets/models/` → ONNX-Runtime path
  (QNN-EP on Snapdragon, CPU otherwise).
- Or `detector.tflite` / `lane.tflite` → LiteRT GPU/CPU.
- Exynos units have no Hexagon → automatically use the LiteRT/CPU fallback.
