# QNN / Hexagon integration & model pipeline

This app runs YOLO11n (detection) + UFLDv2 (lanes) on the Qualcomm Hexagon NPU
via pre-built HTP **context binaries**, with ONNX-Runtime/LiteRT fallbacks. The
Kotlin app builds and runs **without** the QNN SDK (the native path is optional
and falls back); to get the NPU path you must build the models and the native
bridge against the Qualcomm AI Engine Direct (QNN) SDK.

## 0. Prerequisites
- Qualcomm AI Runtime (QAIRT/QNN) SDK → set `QNN_SDK_ROOT` (see §0a).
  **Validated with `QAIRT 2.47.0.260601`** (`~/qairt/2.47.0.260601`).
- `pip install ultralytics onnx onnxsim`
- A connected device over `adb`.
- Calibration frames (representative road imagery) for INT8 quantization.

### Device / HTP target matrix (confirmed on-device)
| Device | model | SoC | platform | HTP arch | min QAIRT |
|---|---|---|---|---|---|
| Galaxy S22+ | SM-S906W | SM8450 | `taro` | **v69** | 2.26+ |
| Galaxy S26 Ultra | SM-S948W | SM8850 (8 Elite Gen 5) | `canoe` | **v81** | **2.47+** |

> **SDK version matters:** QAIRT **2.26.2 only ships Hexagon v66–v75**, so it
> CANNOT build the S26's **v81** binary — use **≥ 2.47** (which adds v79/v81). One
> SDK builds both arches; the app ships `detector_<arch>.bin` per device.

> **16 KB pages (S26 / Android 16):** the S26 uses 16 KB memory pages, so every
> bundled `.so` must be 16 KB-aligned (`libadas_qnn.so` is linked with
> `-Wl,-z,max-page-size=16384`; the 2.47 QNN libs already are; OpenCV's 4 KB
> `libc++_shared.so` is overridden by an NDK r27+ one — see tools/vendor_qnn_sources.sh).

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
2. Open the **Qualcomm Software Center**, pick **2.47.0.260601** (or newer — needed
   for the S26's v81), and download the **`.zip`**. (The `.qik` + QPM3 route is the
   legacy alternative and needs `qpm-cli`; skip it.)
3. Unzip — it expands to `qairt/<version>/` — and move it somewhere stable:
   ```bash
   unzip 2.47.0.260601.zip -d ~/        # -> ~/qairt/2.47.0.260601/
   export QNN_SDK_ROOT=~/qairt/2.47.0.260601
   source "$QNN_SDK_ROOT/bin/envsetup.sh"     # sets PATH + QNN libs
   ```
4. Verify (host tools + the S22+ v69 AND S26 v81 HTP libs are present):
   ```bash
   ls "$QNN_SDK_ROOT/bin/x86_64-linux-clang/qairt-converter"            # host converter
   ls "$QNN_SDK_ROOT/lib/aarch64-android/libQnnHtpV69Stub.so"           # S22+ stub
   ls "$QNN_SDK_ROOT/lib/aarch64-android/libQnnHtpV81Stub.so"           # S26 stub (2.47+)
   ls "$QNN_SDK_ROOT/lib/hexagon-v69/unsigned/libQnnHtpV69Skel.so"      # S22+ skel
   ls "$QNN_SDK_ROOT/lib/hexagon-v81/unsigned/libQnnHtpV81Skel.so"      # S26 skel (2.47+)
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
Confirmed on-device and pinned in `DeviceInfo.kt` / `tools/target.env`:
S22+ = `taro` / SM8450 / **v69**; S26 Ultra = `canoe` / SM8850 / **v81**.

## 2. Export → quantize → context binary (per model, per device)
The host converters need **Python 3.10** (QAIRT 2.47), so run them in a
`ubuntu:22.04` container with `$QNN_SDK_ROOT` + the repo mounted (see how this was
actually built). Key steps (validated):
```bash
# 0) one-time: venv + SDK python deps + onnx in the 22.04 container
python3 $QNN_SDK_ROOT/bin/check-python-dependency   # in a venv; then: pip install onnx==1.12.0

# 1) detector: onnx -> dlc -> INT8 (W8A16, per-channel) -> v69 + v81 context binaries
qairt-converter --input_network detector.onnx --output_path detector.dlc
qairt-quantizer  --input_dlc detector.dlc --input_list calib/input_list.txt \
                 --act_bitwidth 16 --use_per_channel_quantization --output_dlc detector_q.dlc   # 4.3: per-channel = 100% recall vs FP
# per-arch context binary; config = {"graphs":[{"graph_names":["detector"]}],"devices":[{"dsp_arch":"v69"}]}
qnn-context-binary-generator --backend libQnnHtp.so --model libQnnModelDlc.so \
  --dlc_path detector_q.dlc --config_file htp_v69.json --binary_file detector_v69   # 4.4
# v81: same DLC, config devices[].dsp_arch="v81" (NO soc_id — the C++ soc table rejects 660)   # 4.5

# 2) lane (UFLDv2): same flow, INT8 on the FC head; graph name "lane_fp32"           # 4.6
```
Calibration = ~64 real road frames preprocessed like the app (detector: letterbox
640 NCHW [0,1]; lane: horizon-crop + stretch to 800x320). INT8 detector outputs
mix box coords (0–640) and class scores (0–1), so per-tensor INT8 crushes scores →
use **`--act_bitwidth 16`** (and **per-channel** weights). For v81, pass only
`dsp_arch` in the device config (`soc_id` 660 isn't in the backend soc table).

Outputs → `app/src/main/assets/models/<base>_<arch>.bin`, bundled uncompressed
(`noCompress`). The app picks the binary matching the device's HTP arch at runtime
(`QnnModelRunner` → `detector_v69.bin` on S22+, `detector_v81.bin` on S26).

## 3. Sustained thermal benchmark (decisive — research/03)
```bash
TARGET_DEVICE=s22plus tools/benchmark_sustained.sh detector 20000   # tasks 4.7 / 9.4
```
Record cold FPS, FPS@10min, throttle-knee. **Deploy decision uses post-throttle
sustained FPS (must stay ≥ 15), not the cold peak.** Run with both models present.

## 4. Native bridge (`libadas_qnn.so`) — implemented
`app/src/main/cpp/adas_qnn.cpp` is a full JNI bridge over the vendored QNN
SampleApp helpers (`IOTensor` for INT8 quant/dequant, `DynamicLoadUtil`): it loads
a context binary (systemContext graph meta → `contextCreateFromBinary` →
`graphRetrieve`) and executes float-in/float-out. To enable the NPU path:
1. `export QNN_SDK_ROOT=~/qairt/2.47.0.260601` then **`tools/vendor_qnn_sources.sh`** —
   copies the Qualcomm-licensed SampleApp sources, the arm64 runtime libs
   (`libQnnHtp`/`libQnnSystem` + `V69`/`V81` stubs) into `jniLibs/`, the v69/v81
   Hexagon skels into `assets/models/dsp/`, and a 16 KB `libc++_shared.so`. (These
   are git-ignored.)
2. Place the `*_v69.bin` / `*_v81.bin` context binaries in `assets/models/`.
3. Build with `QNN_SDK_ROOT` set — Gradle compiles `libadas_qnn.so` (16 KB-aligned)
   only when the SDK + vendored sources are present. On a Snapdragon device
   `EngineFactory` reports `detector/lane -> QNN_HTP`; otherwise it falls back to
   ONNX-Runtime / LiteRT / CPU.

Gotchas (all handled in the app): the HTP stub needs **`libcdsprpc.so`** (declared
via `<uses-native-library>` in the manifest — a public vendor lib); the Hexagon
skel must be on **`ADSP_LIBRARY_PATH`** (materialized from `assets/models/dsp/`);
`deviceCreate`/`backendCreate` need a real **QNN log handle**; and the lib must be
**16 KB-aligned** for Android 16 devices (S26).

## Fallback paths (no SDK / non-Snapdragon)
- Bundle `detector.onnx` / `lane.onnx` at `assets/models/` → ONNX-Runtime path
  (QNN-EP on Snapdragon, CPU otherwise).
- Or `detector.tflite` / `lane.tflite` → LiteRT GPU/CPU.
- Exynos units have no Hexagon → automatically use the LiteRT/CPU fallback.
