# 02 — Perception Models for On-Device Realtime ADAS

**Scope:** Computer-vision perception models for an Android ADAS app running on Qualcomm Snapdragon phones (Galaxy S22+ — Snapdragon 8 Gen 1; Galaxy S26 Ultra — Snapdragon 8-class flagship). The target is realtime (≥15–30 FPS) inference on the Hexagon NPU via TFLite/LiteRT with the QNN delegate or NNAPI. Every key claim is cited inline.

> **Latency context for this document:** A 30 FPS budget allows ~33 ms per frame end-to-end, shared across capture, pre-processing, *all* perception models, post-processing, and rendering. Per-model NPU inference budgets are therefore in the single-digit-to-low-tens-of-milliseconds range. Snapdragon 8-class NPUs deliver this for nano CNNs but not for heavy transformer depth backbones (see §4).

---

## 1. Object Detection for Road Scenes

The detection backbone is the core of the perception stack: it localizes cars, trucks, pedestrians, cyclists, traffic lights, and stop signs each frame. For mobile NPUs the dominant choice in 2024–2026 is the **nano** variant of a modern YOLO family.

### YOLO11n (Ultralytics) — primary recommendation

YOLO11 is Ultralytics' 2024 detection family. The official COCO val2017 numbers are ([Ultralytics YOLO11 docs](https://docs.ultralytics.com/models/yolo11/)):

| Model | Input | mAP<sup>val</sup> 50-95 | Params (M) | FLOPs (B) | CPU ONNX (ms) | T4 TensorRT (ms) |
|-------|-------|------------------------|-----------|-----------|---------------|------------------|
| YOLO11n | 640 | **39.5** | 2.6 | 6.5 | 56.1 | 1.5 |
| YOLO11s | 640 | 47.0 | 9.4 | 21.5 | 90.0 | 2.5 |
| YOLO11m | 640 | 51.5 | 20.1 | 68.0 | 183.2 | 4.7 |

YOLO11n carries only **2.6 M parameters / 6.5 GFLOPs**, "about the size of a JPEG," making it the natural fit for edge/NPU deployment ([Ultralytics YOLO11 blog](https://www.ultralytics.com/blog/all-you-need-to-know-about-ultralytics-yolo11-and-its-applications)).

**Mobile NPU feasibility is excellent.** Qualcomm's AI Hub publishes a profiled INT8 (W8A8) YOLOv11-Detection build at 640×640 with 2.64 M params and a ~2.83 MB quantized model. Measured NPU latency via TFLite is **~1.1 ms on Snapdragon 8 Gen 3, ~1.9 ms on Snapdragon 8 Gen 1, and ~0.7–0.8 ms on Snapdragon 8 Elite** ([Qualcomm AI Hub — YOLOv11-Detection](https://huggingface.co/qualcomm/YOLOv11-Detection)). Even on the Galaxy S22+ (8 Gen 1), the detector alone leaves ample headroom inside a 33 ms frame; the S26 Ultra has several-fold more margin. Note these are *inference-only* numbers — real end-to-end frame time adds pre/post-processing and NMS.

**COCO classes relevant to driving.** YOLO11 ships trained on the 80-class COCO set, which directly includes the driving-relevant classes: `car`, `truck`, `bus`, `motorcycle`, `bicycle`, `person`, `traffic light`, and `stop sign` ([Ultralytics YOLO11 docs](https://docs.ultralytics.com/models/yolo11/)). For an MVP these COCO weights are usable out-of-the-box; for production, fine-tuning on a driving dataset (§5) sharply improves small-object and night recall.

### Alternatives

- **YOLOv8n** — the prior-generation Ultralytics nano. Slightly lower mAP than YOLO11n at similar size (~37 mAP, 3.2 M params); YOLOv8n runs at ~1.5 ms on a T4 (TensorRT) and is also well-supported on mobile ([Ultralytics YOLO evolution survey, arXiv 2510.09653](https://arxiv.org/pdf/2510.09653)). A safe fallback with the largest tooling/community base.
- **RT-DETR** — a real-time detection transformer reaching ~51 AP, but transformer pipelines "incur higher computational complexity and are less favorable for CPU-bound edge scenarios"; it is generally heavier/slower than YOLO-nano on mobile NPUs and not recommended for the nano budget here ([DigitalOcean object-detection guide, 2025](https://www.digitalocean.com/community/tutorials/best-object-detection-models-guide)).
- **MobileNet-SSD** — a classic lightweight CPU/mobile detector; viable on very constrained hardware but materially lower accuracy than modern YOLO-nano ([DigitalOcean guide](https://www.digitalocean.com/community/tutorials/best-object-detection-models-guide)).
- **NanoDet** — an ultra-light anchor-free detector (~1 M params) designed for mobile; competitive latency but lower accuracy ceiling than YOLO11n. Useful if the NPU budget is shared with several other heads.

**License caution:** Ultralytics YOLO (v8 / 11) is dual-licensed under **AGPL-3.0** or a commercial Enterprise license ([Ultralytics YOLO11 docs](https://docs.ultralytics.com/models/yolo11/)). AGPL-3.0 has network-copyleft obligations; shipping a closed-source commercial Android app on Ultralytics weights/code requires the paid Enterprise license. NanoDet (Apache-2.0) and MobileNet-SSD are permissively licensed alternatives worth keeping in reserve.

---

## 2. Lane Detection Models

Lane detection feeds lane-departure warning and lane-keeping overlays. Options range from classical CV to compact deep nets.

### Ultra-Fast-Lane-Detection (UFLD v1 / v2) — recommended deep approach

UFLD reframes lane detection as **row-wise classification over a coarse grid** rather than per-pixel segmentation, which is what makes it fast. UFLD v1 reports **95.87% accuracy on TuSimple and 68.4 F1 on CULane**, running up to ~300+ FPS and "58× faster than SCNN" ([UFLD, arXiv 2004.11757](https://arxiv.org/pdf/2004.11757)). UFLD **v2** ("Hybrid Anchor Driven Ordinal Classification," TPAMI 2022) improves this to **~0.959 TuSimple accuracy and ~73.6 F1 on CULane while sustaining ~148 FPS** real-time on a desktop GPU ([UFLD-v2 repo, cfzd](https://github.com/cfzd/Ultra-Fast-Lane-Detection-v2), [UFLD-v2 paper, ResearchGate](https://www.researchgate.net/publication/361283406_Ultra_Fast_Deep_Lane_Detection_With_Hybrid_Anchor_Driven_Ordinal_Classification)).

| Model | Dataset | Accuracy / F1 | Reported speed | Notes |
|-------|---------|---------------|----------------|-------|
| UFLD v1 | TuSimple / CULane | 95.87% acc / 68.4 F1 | ~300+ FPS (GPU) | Row-anchor classification; ResNet-18/34 backbone |
| UFLD v2 | TuSimple / CULane | ~95.9% acc / 73.6 F1 | ~148 FPS (GPU) | Hybrid anchor, better F1 |

UFLD's lightweight ResNet-18 backbone and small classification heads make it the most realistic deep lane model for a mobile NPU, though desktop FPS figures will drop substantially on-device — quantize to INT8 and validate the achieved frame rate on the S22+.

### LaneNet

LaneNet uses an **instance-segmentation + embedding-clustering** formulation (binary segmentation branch + pixel-embedding branch, clustered into lane instances). It handles a variable number of lanes well but the per-pixel segmentation + clustering post-process is heavier and slower than UFLD's row classification, making it a weaker fit for a tight realtime mobile budget.

### Classical CV (no ML)

A pure OpenCV pipeline — **grayscale → Canny edge detection → region-of-interest mask → Hough transform** for line fitting, or **inverse-perspective (bird's-eye) transform → sliding-window polynomial fit** — is extremely cheap, runs comfortably realtime on CPU, and needs no training data or model license. Its weakness is robustness: it degrades under faded markings, shadows, glare, night, and curves. A pragmatic strategy is to **ship classical CV for the MVP** (fast, license-free, deterministic) and upgrade to UFLD where accuracy demands it.

### Datasets

- **TuSimple** — ~6.4 k highway clips, clean lane annotations; the standard accuracy benchmark.
- **CULane** — ~133 k frames across 9 challenging scenarios (night, crowded, no-line, shadow); the standard F1/robustness benchmark ([UFLD, arXiv 2004.11757](https://arxiv.org/pdf/2004.11757)).

---

## 3. Traffic Sign & Traffic Light Recognition

Two architectural patterns:

**(a) Detection classes inside the main YOLO detector.** COCO already provides `traffic light` and `stop sign` classes, so YOLO11n detects them for free in the same forward pass ([Ultralytics YOLO11 docs](https://docs.ultralytics.com/models/yolo11/)). This is the cheapest path and is recommended for the MVP: no extra model, no extra latency. Limitation: COCO has *one* generic `traffic light` class (no red/green/yellow state) and only US-style `stop sign`, so it cannot read sign *semantics* (speed limits, etc.).

**(b) Detector + dedicated classifier (two-stage).** A general detector proposes sign/light regions, then a small CNN classifier reads the specific class or light state. The reference dataset is **GTSRB (German Traffic Sign Recognition Benchmark): ~50 k images across 43 classes** (speed limits, prohibitory, mandatory, warning) under varied lighting/weather ([GTSRB CNN study, arXiv 2403.08283](https://arxiv.org/pdf/2403.08283)). A modest CNN classifier reaches **~99% top-1 on GTSRB**, and CNNs with spatial transformers hit **~99.7%** ([GTSRB CNN study, arXiv 2403.08283](https://arxiv.org/pdf/2403.08283)).

| Approach | Model | Input | Accuracy | Mobile feasibility |
|----------|-------|-------|----------|--------------------|
| Single-stage (in detector) | YOLO11n COCO classes | 640 | n/a (detection mAP) | Free — same pass |
| Two-stage classifier | Small CNN on GTSRB | ~32×32–48×48 crops | ~99% top-1 ([arXiv 2403.08283](https://arxiv.org/pdf/2403.08283)) | Very cheap per crop |

For traffic-*light state* (red/yellow/green), a small color/state classifier on the detected light crop — or a custom YOLO model with per-state classes fine-tuned on BDD100K (§5, which annotates light color) — is the standard approach. **Recommendation:** Phase 1 uses COCO `traffic light`/`stop sign` detection; Phase 2 adds a GTSRB-style classifier head and a light-state classifier on cropped regions. GTSRB is research-licensed (verify terms before commercial use); BDD100K offers a permissive driving-specific alternative.

---

## 4. Monocular Distance & Time-To-Collision (TTC) Estimation

With a single camera there is no direct depth; distance is recovered geometrically or learned.

### Geometry-based distance (recommended for realtime)

Using the **pinhole camera model and triangle similarity**, the distance to an object of known real-world dimension is:

```
distance Z = (f_px × W_real) / w_pixels
```

where `f_px` is focal length in pixels (from camera intrinsics), `W_real` is the object's true width/height (e.g. typical car width ≈ 1.8 m), and `w_pixels` is the bounding-box width. This pinhole/triangle-similarity approximation is the classical, well-validated ADAS technique for FCW/AEB systems ([Monocular pinhole distance estimation, IEEE](https://ieeexplore.ieee.org/document/7727017/); [Mono-camera distance for FCW/AEB, Springer IJAT](https://link.springer.com/article/10.1007/s12239-016-0050-9)). An alternative is the **ground-plane assumption**: with known camera height and pitch, the pixel where an object's wheels meet the road maps to a ground distance via the homography. Production FCW/AEB systems typically fuse the bounding-box estimate with a **Kalman filter** to recover position, velocity, acceleration, and TTC ([Springer IJAT](https://link.springer.com/article/10.1007/s12239-016-0050-9)). This is essentially free computationally and is the right primary method for on-device ADAS.

### TTC from bounding-box scale change

TTC can be computed **directly from how fast the lead vehicle's bounding box grows between frames** — no absolute distance needed. For a constant-velocity approach, the scale ratio of the box across two frames gives:

```
TTC ≈ Δt / (s − 1),   where s = w_t / w_{t-1}  (bounding-box width ratio)
```

The scale of the leading vehicle in the image changes due to relative motion, and "this variation of scale is used to estimate the TTC" — the scale ratio of objects between adjacent frames is the fundamental TTC parameter ([FP-TTC, IEEE Xplore](https://ieeexplore.ieee.org/document/10695124/); [Forecasting TTC from monocular video, IROS 2019](https://eshed1.github.io/papers/Time2Col_IROS19.pdf)). This is cheap, robust, and pairs naturally with the YOLO detector's per-frame boxes; smoothing the scale ratio (e.g. via the same Kalman track) reduces jitter.

### Learned monocular depth — realtime caveat

Foundation depth models give dense depth but are **expensive**:

| Model | Notes | Realtime on mobile? |
|-------|-------|---------------------|
| **MiDaS** | Robust zero-shot relative depth via mixed-dataset training ([isl-org/MiDaS](https://github.com/isl-org/MiDaS)) | Marginal — large variants ~2 FPS on commodity HW ([Hologram, arXiv 2405.07178](https://arxiv.org/pdf/2405.07178)) |
| **Depth Anything V2** | Higher accuracy, stable error across distances ([Roboflow depth guide](https://blog.roboflow.com/depth-estimation-models/)) | Heavy ViT backbones; full models not realtime on phone NPUs |

Recent depth estimators "rely on heavy backbones and high-resolution inference, which limits real-time edge deployment" ([AsyncMDE, arXiv 2603.10438](https://arxiv.org/pdf/2603.10438)); MiDaS/Depth-Anything also output *relative* (un-metric) depth, needing extra scaling for absolute distance. **Recommendation:** do **not** put a full monocular depth model on the realtime path for the S22+/S26 Ultra. Use geometry (pinhole + ground plane) for metric distance and bounding-box scale for TTC; reserve a small/distilled depth model as an optional, lower-rate enhancement only if profiling on the S26 Ultra shows spare NPU budget.

---

## 5. Datasets & Training

For fine-tuning detectors, training classifiers, and validating distance/lane logic, the standard driving datasets are:

| Dataset | Scale | What it provides | Best used for |
|---------|-------|------------------|---------------|
| **KITTI** | >12 k images, stereo + LiDAR | 2D/3D object detection, segmentation, depth, odometry | Distance/depth validation, 3D boxes, calibration ([nuScenes paper, CVPR 2020](https://openaccess.thecvf.com/content_CVPR_2020/papers/Caesar_nuScenes_A_Multimodal_Dataset_for_Autonomous_Driving_CVPR_2020_paper.pdf)) |
| **BDD100K** | 100 k videos / images @1280×720, 70 k train / 10 k val | Detection, lane marking, drivable area, semantic & instance seg, tracking, **traffic-light color**, diverse weather/time-of-day | Fine-tuning the YOLO detector + light-state and lane heads ([BDD100K, ResearchGate](https://www.researchgate.net/publication/343454448_BDD100K_A_Diverse_Driving_Dataset_for_Heterogeneous_Multitask_Learning)) |
| **nuScenes** | 1 k 20 s scenes, 23 classes, 6 cameras + LiDAR + radar, HD maps | 3D detection, tracking, multimodal fusion, depth | 3D/360° perception, sensor fusion research ([nuScenes, CVPR 2020](https://openaccess.thecvf.com/content_CVPR_2020/papers/Caesar_nuScenes_A_Multimodal_Dataset_for_Autonomous_Driving_CVPR_2020_paper.pdf)) |
| **Cityscapes** | 5 k fine + 20 k coarse images, 30 classes, 50 cities | Pixel-level semantic & instance segmentation | Segmentation pretraining, drivable-area / lane context ([nuScenes paper, CVPR 2020](https://openaccess.thecvf.com/content_CVPR_2020/papers/Caesar_nuScenes_A_Multimodal_Dataset_for_Autonomous_Driving_CVPR_2020_paper.pdf)) |

**Practical guidance:** **BDD100K** is the single most valuable dataset for this app — it is large, image-and-video, 720p (close to phone capture), spans day/night/weather, and uniquely annotates lane markings, drivable area, road objects, *and* traffic-light color in one corpus. Use it to fine-tune the YOLO11n detector and any lane/light heads. Use **KITTI** to validate monocular distance/TTC accuracy against its calibrated depth/LiDAR ground truth. Check each dataset's license (BDD100K, KITTI, nuScenes, Cityscapes each have non-commercial / research-leaning terms) before any commercial training run.

---

## Summary of Recommendations

1. **Detection:** YOLO11n INT8 at 640×640 on the Hexagon NPU (QNN/TFLite) — ~1–2 ms inference even on the S22+; budget the AGPL-3.0/Enterprise license decision early.
2. **Lanes:** classical CV for MVP, UFLD v2 (INT8) for the accuracy upgrade.
3. **Signs/lights:** COCO classes first, GTSRB-style CNN + light-state classifier later.
4. **Distance/TTC:** pinhole geometry + ground-plane for metric distance, bounding-box scale ratio for TTC; avoid full monocular-depth nets on the realtime path.
5. **Data:** fine-tune on BDD100K, validate distance on KITTI.

---

## Sources

1. [Ultralytics YOLO11 docs](https://docs.ultralytics.com/models/yolo11/)
2. [Ultralytics YOLO11 blog](https://www.ultralytics.com/blog/all-you-need-to-know-about-ultralytics-yolo11-and-its-applications)
3. [Qualcomm AI Hub — YOLOv11-Detection (Snapdragon NPU latencies)](https://huggingface.co/qualcomm/YOLOv11-Detection)
4. [Ultralytics YOLO Evolution survey, arXiv 2510.09653](https://arxiv.org/pdf/2510.09653)
5. [DigitalOcean — Best Object Detection Models 2025 (RT-DETR / MobileNet-SSD)](https://www.digitalocean.com/community/tutorials/best-object-detection-models-guide)
6. [Ultra-Fast-Lane-Detection (v1), arXiv 2004.11757](https://arxiv.org/pdf/2004.11757)
7. [Ultra-Fast-Lane-Detection v2 repo (cfzd)](https://github.com/cfzd/Ultra-Fast-Lane-Detection-v2)
8. [UFLD v2 / Hybrid Anchor Ordinal Classification, ResearchGate](https://www.researchgate.net/publication/361283406_Ultra_Fast_Deep_Lane_Detection_With_Hybrid_Anchor_Driven_Ordinal_Classification)
9. [GTSRB CNN traffic-sign study, arXiv 2403.08283](https://arxiv.org/pdf/2403.08283)
10. [Monocular pinhole distance estimation, IEEE 7727017](https://ieeexplore.ieee.org/document/7727017/)
11. [Mono-camera distance for FCW/AEB, Springer IJAT](https://link.springer.com/article/10.1007/s12239-016-0050-9)
12. [FP-TTC: Fast Prediction of Time-to-Collision, IEEE Xplore](https://ieeexplore.ieee.org/document/10695124/)
13. [Forecasting TTC from Monocular Video, IROS 2019](https://eshed1.github.io/papers/Time2Col_IROS19.pdf)
14. [MiDaS (isl-org)](https://github.com/isl-org/MiDaS)
15. [Roboflow — depth estimation models (Depth Anything V2)](https://blog.roboflow.com/depth-estimation-models/)
16. [AsyncMDE: Real-Time Monocular Depth Estimation, arXiv 2603.10438](https://arxiv.org/pdf/2603.10438)
17. [Hologram (MiDaS realtime FPS), arXiv 2405.07178](https://arxiv.org/pdf/2405.07178)
18. [nuScenes: A Multimodal Dataset for Autonomous Driving, CVPR 2020](https://openaccess.thecvf.com/content_CVPR_2020/papers/Caesar_nuScenes_A_Multimodal_Dataset_for_Autonomous_Driving_CVPR_2020_paper.pdf)
19. [BDD100K, ResearchGate](https://www.researchgate.net/publication/343454448_BDD100K_A_Diverse_Driving_Dataset_for_Heterogeneous_Multitask_Learning)
