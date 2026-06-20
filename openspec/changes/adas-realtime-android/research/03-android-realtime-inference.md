# On-Device Realtime ML Inference Stack for Android ADAS (Qualcomm Snapdragon)

**Scope.** This document evaluates the realtime ML inference stack for an Android ADAS app running a YOLO11n-class detector on Qualcomm Snapdragon phones. Dev target: **Galaxy S22+ — Snapdragon 8 Gen 1 (SM8450), Hexagon HTP arch v69**. Deploy target: **Galaxy S26 Ultra — Snapdragon 8 Elite Gen 5 (SM8850-class), Hexagon HTP arch ~v81**. Every key claim is cited inline.

**TL;DR.** The lowest-latency, most-deterministic path is **Qualcomm AI Engine Direct (QNN) on the Hexagon NPU** via precompiled INT8 context binaries; on a Snapdragon 8 Elite Gen 5 a YOLO-class detector runs the Hexagon NPU at ~11 ms end-to-end vs ~53 ms on CPU ([Ultralytics](https://docs.ultralytics.com/integrations/qnn)). **LiteRT** (with the new Qualcomm AI Engine Direct accelerator) is the right fallback for portability and a simpler Android API, and **ONNX Runtime Mobile + QNN EP** is a strong middle path that the Ultralytics QNN exporter itself uses under the hood. The deploy decision must be made on **post-throttle sustained FPS**, not peak, because the 8 Elite Gen 5 throttles hard under sustained load ([Android Headlines](https://www.androidheadlines.com/2025/11/qualcomm-snapdragon-8-elite-gen-5-thermal-throttling-heat-hot-tests.html)).

---

## 1. Qualcomm AI Stack: AI Engine Direct (QNN), Hexagon HTP, context binaries

**Qualcomm AI Engine Direct (QNN / QAIRT)** is Qualcomm's low-level inference SDK. It exposes a unified C API with swappable backend libraries that target the Snapdragon **CPU**, **Adreno GPU**, and the **Hexagon Tensor Processor (HTP)** — the AI-accelerated NPU ([Qualcomm](https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk)). QNN distinguishes the **HTP** backend (Hexagon NPU *with* the fused tensor-accelerator architecture) from the legacy **cDSP** backend (Hexagon NPU without the fused accelerator) ([Qualcomm AI Engine Direct SDK](https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk)). The older **SNPE** (Snapdragon Neural Processing Engine) SDK is the previous-generation stack; new development should use AI Engine Direct / QAIRT, which supersedes SNPE.

### HTP architecture versions and SoC mapping

The HTP backend is versioned by **DSP/HTP architecture** (`htp_arch` / `dsp_arch`), and a context binary compiled for one arch will not load on a different one. The mapping used by the Ultralytics QNN exporter is the canonical reference ([Ultralytics QNN docs](https://docs.ultralytics.com/integrations/qnn)):

| HTP arch | Snapdragon platform | SoC ID |
|---|---|---|
| v68 | Snapdragon 888 | — |
| **v69** | **8 Gen 1 / 8+ Gen 1** | **SM8450** |
| v73 | 8 Gen 2, X Elite | SM8550 |
| v75 | 8 Gen 3 | SM8650 |
| v79 | 8 Elite | SM8750 |
| **v81** | **8 Elite Gen 5** | **SM8850-class** |

Sources: [Ultralytics QNN](https://docs.ultralytics.com/integrations/qnn); v79 toolchain support landed in LLVM/Clang in late 2024 ([cfe-commits](https://lists.llvm.org/pipermail/cfe-commits/Week-of-Mon-20241223/657260.html)); Hexagon version history is tracked on [Wikipedia: Qualcomm Hexagon](https://en.wikipedia.org/wiki/Qualcomm_Hexagon). The relevant consequence for this project: **the S22+ dev binary (v69) is not the same binary that ships to the S26 Ultra (v81)** — each device needs its own context binary (or runtime recompilation).

### `soc_id` / `dsp_arch` and context binaries

The QNN HTP backend is configured per device through two options that appear both in the raw QNN config and in the ONNX Runtime QNN EP: **`soc_model`** (the numeric SoC ID) and **`htp_arch`** (the HTP/DSP architecture number) ([ONNX Runtime QNN EP](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html)). Setting these correctly lets the compiler emit a binary tuned for the exact target.

A **context binary** is the precompiled, serialized QNN graph for a specific backend + arch. Generating it ahead of time (with `qnn-context-binary-generator`, or the equivalent ONNX Runtime `ep.context_enable` path) means the device does **not** recompile the graph at load time, which removes a major chunk of first-inference / model-load latency ([ONNX Runtime QNN EP](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html), [Ultralytics QNN](https://docs.ultralytics.com/integrations/qnn)). For an ADAS app this matters at app startup and avoids a cold-start stall in the perception pipeline.

### YOLO11n → ONNX → QNN → INT8 → context binary → benchmark pipeline

The Ultralytics QNN exporter implements this end-to-end and is the recommended pipeline ([Ultralytics QNN](https://docs.ultralytics.com/integrations/qnn), [Ultralytics export reference](https://docs.ultralytics.com/reference/utils/export/qnn)):

1. **Export to ONNX** — the `.pt` model is first converted to ONNX.
2. **Quantize** — quantized with the **ONNX Runtime QNN QDQ** flow to **INT8 weights + 16-bit activations** ("W8A16"), which the docs call the Hexagon NPU's recommended accuracy/performance balance.
3. **Calibrate** — a calibration dataset (default `coco8.yaml`) drives the activation-range statistics.
4. **Compile context binary** — the `onnxruntime-qnn` Execution Provider (which bundles the **QAIRT** libraries) compiles the quantized graph to a context binary **entirely on the host machine — no Qualcomm account or cloud upload required** ([Ultralytics QNN](https://docs.ultralytics.com/integrations/qnn)).
5. **Package** — output is a self-contained `*_qnn.onnx` embedding the precompiled context binary, targeted at an HTP arch via the `name`/arch parameter (e.g. `model.export(format="qnn", name="69")` for the S22+).
6. **Benchmark** — run on-device. Qualcomm's native CLI for this is **`qnn-net-run`** (executes a QNN graph + input tensors and reports timing); the Ultralytics flow benchmarks through the ORT QNN session.

Reported numbers for a YOLO-class detector at 640 px on **Snapdragon 8 Elite Gen 5**: **Hexagon NPU (QNN) ~11.3 ms**, Adreno GPU INT8 ~17.2 ms, CPU INT8 ~53.3 ms — roughly a 2–5× speedup over CPU ([Ultralytics QNN](https://docs.ultralytics.com/integrations/qnn)).

---

## 2. LiteRT (formerly TensorFlow Lite) + delegates

**LiteRT** is Google's rebranded TensorFlow Lite runtime and the standard portable on-device path on Android ([Google AI Edge LiteRT](https://developers.googleblog.com/litert-the-universal-framework-for-on-device-ai/)). Acceleration is via *delegates*:

- **NNAPI delegate — deprecated.** NNAPI (Android's old unified NN HAL, introduced in Android 8.1) was **deprecated in Android 15**; Google's stated reason is that its monolithic design couldn't keep pace with transformer/diffusion-era ML, and developers needed an *updatable* runtime ([Android NNAPI Migration Guide](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide)). It still functions on existing devices but should not anchor new development. Google's recommended replacements are **LiteRT in Google Play Services**, the **GPU delegate**, and **vendor SDKs such as Qualcomm QNN** for performance-critical paths ([Android Migration Guide](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide)).
- **GPU delegate** — runs on the Adreno GPU; broadly portable across vendors, good fallback when NPU isn't available, but slower than the NPU for INT8 vision models.
- **Qualcomm path (the important one)** — Google and Qualcomm shipped the **LiteRT Qualcomm AI Engine Direct accelerator** (HTP backend), which **replaces the older TFLite QNN delegate** and exposes the Hexagon NPU through a unified LiteRT API with an AOT (ahead-of-time) compile toolchain ([Google Developers Blog](https://developers.googleblog.com/unlocking-peak-performance-on-qualcomm-npu-with-litert/), [LiteRT Qualcomm NPU docs](https://developers.google.com/edge/litert/android/npu/qualcomm)). Supported SoCs include **8 Gen 1 (SM8450)**, 8 Gen 2, 8 Gen 3, and 8 Elite ([LiteRT Qualcomm NPU docs](https://developers.google.com/edge/litert/android/npu/qualcomm)). Google reports NPU inference up to **100× faster than CPU and ~10× faster than GPU** on 8 Elite Gen 5, with 50+ models under 5 ms ([Google Developers Blog](https://developers.googleblog.com/unlocking-peak-performance-on-qualcomm-npu-with-litert/)). It can pull pre-optimized models directly from **Qualcomm AI Hub**.

**When LiteRT is the right call vs raw QNN:** Choose LiteRT when you want **one Android-native API**, easy GPU/CPU fallback for non-Snapdragon or unsupported devices, and Play Services delivery — at the cost of slightly less control over the context binary and arch targeting. Choose **raw QNN** when you need maximum, deterministic NPU performance, explicit per-arch context binaries, and the tightest control over the Hexagon graph — which is exactly the ADAS realtime case.

---

## 3. ONNX Runtime Mobile + QNN Execution Provider

**ONNX Runtime Mobile** with the **QNN Execution Provider (EP)** is a third path, and notably it is the engine **underneath the Ultralytics QNN export** ([Ultralytics QNN](https://docs.ultralytics.com/integrations/qnn)). Key facts ([ONNX Runtime QNN EP](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html)):

- The **HTP backend only supports quantized models** — FP32 activations/weights must be quantized to 8-bit/16-bit integers first, via **QDQ** (Quantize-Dequantize) nodes. The EP supports `uint8`/`uint16`, with `qnn_preprocess_model()` as a preprocessing step.
- Device targeting uses **`soc_model`** (SoC ID) and **`htp_arch`** (HTP arch number).
- **Context-binary caching** is built in: `ep.context_enable=1` serializes the compiled QNN graph, `ep.context_embed_mode=1` embeds it in the ONNX model, and `ep.context_file_path` names the output. Cached binaries deploy to production without recompilation — the same load-latency win as `qnn-context-binary-generator`.

**Use ORT + QNN EP** when you already have an ONNX-centric toolchain, want cross-platform parity (the same EP runs QNN on Windows-on-Snapdragon and Android), and want context-binary control without writing against the raw QNN C API. It sits between "raw QNN" (max control) and "LiteRT" (max simplicity).

---

## 4. Quantization: INT8 PTQ vs QAT

**Why NPUs need INT8.** The Hexagon HTP backend is an integer accelerator — it **only runs quantized graphs**; floating-point models must be lowered to int8/int16 before they can execute on the NPU ([ONNX Runtime QNN EP](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html)). INT8 quantization also shrinks model size ~4× and dramatically raises throughput on battery-powered hardware, which is why the YOLO→QNN pipeline quantizes to **INT8 weights + 16-bit activations** as the default balance ([Ultralytics QNN](https://docs.ultralytics.com/integrations/qnn)).

**PTQ vs QAT.**

| | Post-Training Quantization (PTQ) | Quantization-Aware Training (QAT) |
|---|---|---|
| When applied | After training, on the frozen model | During / via fine-tuning |
| Effort | Low — needs only a small **calibration** set | High — requires retraining + labeled data |
| Accuracy | Good for most CNNs; can degrade on sensitive models | Almost always higher; recovers near-FP32 |
| Use when | First attempt; YOLO INT8 export default | PTQ accuracy drop is unacceptable |

PTQ and QAT differ only in *when* quantization is introduced — QAT during training, PTQ after ([SabrePC](https://www.sabrepc.com/blog/deep-learning-and-ai/what-is-quantization-aware-training-qat-vs-ptq)). Naive PTQ can hurt badly on sensitive models — a full-precision ResNet-50 dropped from ~75% to ~50% top-1 under direct INT8 in one study, while QAT recovers near-FP32 accuracy ([NVIDIA TensorRT QAT blog](https://developer.nvidia.com/blog/achieving-fp32-accuracy-for-int8-inference-using-quantization-aware-training-with-tensorrt/)).

**Calibration** is the critical PTQ step: the FP model is run over a small, representative dataset to measure per-tensor activation distributions, from which quantization scales are derived ([Integer Quantization for DL Inference, NVIDIA/arXiv](https://arxiv.org/pdf/2004.09602)). **Practical guidance for ADAS:** start with PTQ + a calibration set drawn from real driving frames (not COCO8) and validate mAP on a held-out driving set; escalate to QAT only if detection accuracy on small/distant objects regresses unacceptably. The W8A16 default already mitigates much of the activation-side accuracy loss.

---

## 5. Qualcomm AI Hub

**Qualcomm AI Hub** is a cloud service for compiling, profiling, and benchmarking models against **real Snapdragon devices hosted in the cloud** ([AI Hub Get Started](https://aihub.qualcomm.com/get-started)). It offers:

- **Models** — 150+ pre-optimized, validated models across vision/speech/audio/text ([AI Hub Get Started](https://aihub.qualcomm.com/get-started)).
- **Compile jobs** — take a **PyTorch or ONNX** model and compile to a target runtime (**TFLite, ONNX, or QNN**) for a chosen device ([AI Hub Get Started](https://aihub.qualcomm.com/get-started)).
- **Profile jobs** — run the compiled model on a **real cloud-hosted device**, returning **per-layer timing**, load + inference times, memory use, and which compute unit each layer ran on; a profile job runs inference **100 times** and benchmarks refresh every two weeks ([AI Hub Profiling FAQ](https://workbench.aihub.qualcomm.com/docs/hub/faq.html), [AI Hub Models repo](https://github.com/qualcomm/ai-hub-models)).
- **Quantization** — supports INT8 and other precisions in Workbench ([AI Hub Get Started](https://aihub.qualcomm.com/get-started)).
- **Device coverage** — 50+ devices including `Snapdragon 8 Elite` profiles selectable as `hub.Device(...)`.

**Value for this project:** AI Hub lets us profile the YOLO11n INT8 model on a real **Snapdragon 8 Gen 1** and a real **8 Elite Gen 5** *before* having physical S22+/S26 Ultra units, get per-layer latency to find NPU-unfriendly ops, and confirm the chosen export targets the Hexagon NPU rather than silently falling back to CPU/GPU.

---

## 6. Performance & thermal

**Realtime FPS targets.** A camera-based ADAS perception loop typically targets **15–30 FPS** (≈33–66 ms total budget per frame, inclusive of capture, pre/post-processing, and tracking). The measured Hexagon-NPU inference of ~11 ms for a YOLO-class detector on 8 Elite Gen 5 leaves comfortable headroom inside a 30 FPS budget; the same model at ~53 ms on CPU does **not** ([Ultralytics QNN](https://docs.ultralytics.com/integrations/qnn)). This is the core argument for the NPU path.

**Thermal throttling — the decisive constraint.** ADAS runs the perception model continuously, so *sustained* (post-throttle) performance, not peak, is what matters:

- **Snapdragon 8 Gen 1 (S22+ dev target):** notorious for poor sustained performance — heavy power draw and significant throttling within **under five minutes** of sustained load, with gaming benchmarks dropping from ~60 FPS to the low-40s/below-40 over a 10-minute run ([Notebookcheck](https://www.notebookcheck.net/The-Snapdragon-8-Gen-1-disappoints-in-early-real-world-gaming-tests.588442.0.html)).
- **Snapdragon 8 Elite Gen 5 (S26 Ultra deploy target):** record peak performance, but **severe throttling under sustained load** — testing shows mainstream passively-cooled flagships cut performance to **under ~30%** (GPU stability ~25%) without robust cooling, with outcomes highly dependent on chassis/thermal design and OEM tuning ([Android Headlines](https://www.androidheadlines.com/2025/11/qualcomm-snapdragon-8-elite-gen-5-thermal-throttling-heat-hot-tests.html), [PhoneArena](https://www.phonearena.com/news/the-snapdragon-8-elite-gen-5-is-fast-but-not-all-phones-can-handle-the-heat_id175544)).

**Why post-throttle sustained FPS drives the deploy decision.** Peak-clock benchmarks flatter both chips and are irrelevant to an always-on perception loop. The model must hold its FPS target after the SoC has reached steady-state thermal — which on these phones can be a small fraction of peak. The NPU helps here too: at fixed accuracy the Hexagon NPU does the same work at far lower energy than CPU/GPU, so it both hits the latency target *and* generates less heat, pushing the throttle point later. **Recommendation:** validate the FPS target with a **≥15–30 minute sustained on-device run** on the actual S26 Ultra chassis and design to the *post-throttle* number with margin; keep a dynamic resolution/skip-frame fallback for when thermal limits engage.

---

## 7. Decision guidance

### Comparison: QNN (Hexagon) vs LiteRT (NNAPI/GPU/QNN accel) vs ONNX Runtime + QNN EP

| Dimension | **QNN / AI Engine Direct (Hexagon)** | **LiteRT** | **ONNX Runtime Mobile + QNN EP** |
|---|---|---|---|
| Peak NPU performance | **Highest** — direct HTP, per-arch context binary (~11 ms YOLO on 8 Elite Gen 5) | High via QNN accelerator (≈10× GPU, 100× CPU on 8 Elite Gen 5) | High — same HTP backend, slightly more overhead |
| Portability (non-Snapdragon) | **Poor** — Snapdragon-only | **Best** — GPU/CPU/XNNPACK fallback, cross-vendor | Good — CPU/other EPs fallback |
| Dev complexity | **Highest** — raw QNN C API, manage arch/context binaries | **Lowest** — unified Android API, Play delivery, AI Hub models | Medium — ONNX-centric, EP options |
| Quantization required | INT8/W8A16 (HTP int-only) | INT8 for NPU path | INT8/QDQ for HTP |
| Context-binary control | Full (`qnn-context-binary-generator`, per `htp_arch`) | AOT toolchain, less manual | Full (`ep.context_*` caching) |
| Best fit | **Primary ADAS realtime path** | Portability / fallback / fast bring-up | ONNX toolchains; the engine behind Ultralytics export |

Sources: [Ultralytics QNN](https://docs.ultralytics.com/integrations/qnn), [LiteRT Qualcomm NPU](https://developers.google.com/edge/litert/android/npu/qualcomm), [Google Developers Blog](https://developers.googleblog.com/unlocking-peak-performance-on-qualcomm-npu-with-litert/), [ONNX Runtime QNN EP](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html), [Android Migration Guide](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide).

### The Exynos risk

The entire Hexagon path is **Snapdragon-exclusive**. Galaxy S22+ and S26 Ultra ship in some regions with **Samsung Exynos** SoCs instead of Snapdragon; an Exynos unit has **no Hexagon NPU** and therefore cannot load QNN context binaries or use the LiteRT Qualcomm accelerator — it must fall back to GPU/CPU or a vendor-specific (Exynos NPU) path. Mitigations:

1. **Detect SoC at runtime** (e.g., via `Build.SOC_MANUFACTURER` / `soc_id`) and select the QNN/Hexagon path only on Snapdragon.
2. **Ship a LiteRT GPU/CPU fallback** so Exynos units still run (at reduced FPS).
3. **Pin procurement to Snapdragon SKUs** for both dev (S22+) and deploy (S26 Ultra) where the deployment allows it, since US-market Galaxy flagships are typically Snapdragon while EU/global units may be Exynos.

### Recommended plan

- **Primary:** YOLO11n → ONNX → **INT8 (W8A16) PTQ with driving-frame calibration** → **QNN context binary per arch** (v69 for S22+ dev, v81 for S26 Ultra deploy) on the **Hexagon NPU**, validated on **Qualcomm AI Hub** before hardware arrives.
- **Fallback:** **LiteRT** with the Qualcomm AI Engine Direct accelerator (NPU) and a GPU/CPU delegate fallback for Exynos / unsupported devices.
- **Validate on sustained, post-throttle FPS** on the real S26 Ultra chassis, with a dynamic-resolution / frame-skip degradation strategy for thermal events.

---

## Sources

1. Qualcomm — AI Engine Direct SDK: https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk
2. Ultralytics — Qualcomm QNN Export for YOLO: https://docs.ultralytics.com/integrations/qnn
3. Ultralytics — QNN export reference (utils/export/qnn): https://docs.ultralytics.com/reference/utils/export/qnn
4. ONNX Runtime — QNN Execution Provider: https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html
5. Android Developers — NNAPI Migration Guide: https://developer.android.com/ndk/guides/neuralnetworks/migration-guide
6. Google Developers Blog — Unlocking Peak Performance on Qualcomm NPU with LiteRT: https://developers.googleblog.com/unlocking-peak-performance-on-qualcomm-npu-with-litert/
7. Google AI Edge — LiteRT Qualcomm NPU (AI Engine Direct accelerator): https://developers.google.com/edge/litert/android/npu/qualcomm
8. Qualcomm AI Hub — Get Started: https://aihub.qualcomm.com/get-started
9. Qualcomm AI Hub Models (GitHub): https://github.com/qualcomm/ai-hub-models
10. Qualcomm AI Hub — Profiling FAQ: https://workbench.aihub.qualcomm.com/docs/hub/faq.html
11. NVIDIA — Achieving FP32 Accuracy for INT8 Inference Using QAT (TensorRT): https://developer.nvidia.com/blog/achieving-fp32-accuracy-for-int8-inference-using-quantization-aware-training-with-tensorrt/
12. SabrePC — QAT vs PTQ: https://www.sabrepc.com/blog/deep-learning-and-ai/what-is-quantization-aware-training-qat-vs-ptq
13. NVIDIA/arXiv — Integer Quantization for Deep Learning Inference: https://arxiv.org/pdf/2004.09602
14. Android Headlines — Snapdragon 8 Elite Gen 5 thermal throttling tests: https://www.androidheadlines.com/2025/11/qualcomm-snapdragon-8-elite-gen-5-thermal-throttling-heat-hot-tests.html
15. PhoneArena — 8 Elite Gen 5 heat / not all phones can handle it: https://www.phonearena.com/news/the-snapdragon-8-elite-gen-5-is-fast-but-not-all-phones-can-handle-the-heat_id175544
16. Notebookcheck — Snapdragon 8 Gen 1 sustained gaming throttling: https://www.notebookcheck.net/The-Snapdragon-8-Gen-1-disappoints-in-early-real-world-gaming-tests.588442.0.html
17. LLVM cfe-commits — Hexagon V79 compiler support: https://lists.llvm.org/pipermail/cfe-commits/Week-of-Mon-20241223/657260.html
18. Wikipedia — Qualcomm Hexagon (version history): https://en.wikipedia.org/wiki/Qualcomm_Hexagon
