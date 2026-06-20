# QNN / Hexagon integration & model pipeline

This app runs YOLO11n (detection) + UFLDv2 (lanes) on the Qualcomm Hexagon NPU
via pre-built HTP **context binaries**, with ONNX-Runtime/LiteRT fallbacks. The
Kotlin app builds and runs **without** the QNN SDK (the native path is optional
and falls back); to get the NPU path you must build the models and the native
bridge against the Qualcomm AI Engine Direct (QNN) SDK.

## 0. Prerequisites
- Qualcomm AI Engine Direct (QNN) SDK → set `QNN_SDK_ROOT` (see §0a).
- `pip install ultralytics onnx onnxsim`
- A connected S22+ / S26 unit over `adb`.
- Calibration frames (representative road imagery) for INT8 quantization.

## 0a. Getting the QNN SDK
The SDK is now the **Qualcomm AI Runtime (QAIRT) SDK** (current name for / superset
of the AI Engine Direct "QNN" SDK; bundles the host converters/quantizer,
`qnn-net-run`, `qnn-context-binary-generator`, and the HTP backend libs). It is a
**free community download** gated behind a Qualcomm account + license acceptance.

**Use the ZIP from the Software Center — you do NOT need QPM3 / `qpm-cli` / the
`.qik`.** The `.qik` is a proprietary container that only `qpm-cli` can open; the
plain ZIP avoids that entirely.

1. Create a free **Qualcomm ID**, sign in, and accept the license at
   https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk
2. Open the **Qualcomm Software Center**, pick a QAIRT/QNN version, and download the
   **`.zip`** (e.g. `v2.26.2.240911.zip`). (The `.qik` + QPM3 route is the legacy
   alternative and needs `qpm-cli`; skip it.)
3. Unzip — it expands to `qairt/<version>/` — and move it somewhere stable:
   ```bash
   unzip v2.26.2.240911.zip -d ~/        # -> ~/qairt/<version>/
   export QNN_SDK_ROOT=~/qairt/2.26.2.240911
   source "$QNN_SDK_ROOT/bin/envsetup.sh"     # sets PATH + QNN libs
   ```
4. Verify (host tools + the S22+ V69 HTP libs are present):
   ```bash
   ls "$QNN_SDK_ROOT/bin/x86_64-linux-clang/qairt-converter"      # host converter
   ls "$QNN_SDK_ROOT/lib/aarch64-android/libQnnHtpV69Stub.so"     # S22+ stub
   ls "$QNN_SDK_ROOT/lib/hexagon-v69/libQnnHtpV69Skel.so"         # S22+ skel
   ```

**Host requirement:** the SDK targets **Ubuntu 22.04 / Python 3.10 (x86-64)**. The
device-side binaries + HTP libs run anywhere, but the **host Python converters**
(`qairt-converter` / `qnn-onnx-converter`) may reject newer Pythons. On Ubuntu
24.04 / Python 3.12, run the conversion steps in a `python3.10` venv or a
`ubuntu:22.04` container; the `.so` / context-binary outputs are identical.

Alternatives: [quic/ai-engine-direct-helper](https://github.com/quic/ai-engine-direct-helper)
(open-source helper with clearer examples), and **Qualcomm AI Hub** (cloud service
that compiles/profiles on real Snapdragon devices and returns a prebuilt context
binary — skips the local toolchain).

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
