#!/usr/bin/env bash
# Fetch + export a decoder-compatible YOLO11n detector and drop it into the app.
#
# Produces app/src/main/assets/models/detector.onnx — the stock Ultralytics
# float ONNX (input 1x3x640x640, output 1x84x8400) that ObjectDetector.kt and
# Preprocess.kt are written against. This is the no-SDK / no-device path: the app
# runs it via ONNX-Runtime (CPU everywhere, QNN-EP on Snapdragon).
#
# NOTE: Ultralytics YOLO11n is AGPL-3.0 (design D9 / task 9.5) — fine for dev,
# resolve licensing before distribution.
#
# Why not the Qualcomm AI Hub INT8 model? It splits the detection head into
# separate boxes/scores tensors for the QNN native path and does NOT match this
# single-tensor [1,84,8400] float decoder. Use that model only with the QNN HTP
# path (tools/README_qnn.md), not this fallback.
#
# Usage:  tools/fetch_detector.sh [--imgsz 640]
set -euo pipefail

IMGSZ=640
[[ "${1:-}" == "--imgsz" ]] && IMGSZ="${2:?}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT/app/src/main/assets/models"
OUT="$OUT_DIR/detector.onnx"
TMP_ONNX="$ROOT/build/detector.onnx"

VENV="$ROOT/build/.export-venv"
PY="$VENV/bin/python"
if [[ ! -x "$PY" ]]; then
  echo ">> Creating export venv at $VENV…"
  python3 -m venv "$VENV"
fi
echo ">> Installing exporter deps (ultralytics + onnx, CPU torch)…"
"$PY" -m pip install --quiet --upgrade pip
"$PY" -m pip install --quiet ultralytics onnx onnxsim onnxruntime

echo ">> Exporting YOLO11n -> ONNX (imgsz=$IMGSZ)…"
"$PY" "$ROOT/tools/export_yolo11n.py" --imgsz "$IMGSZ" --out "$TMP_ONNX"

mkdir -p "$OUT_DIR"
cp "$TMP_ONNX" "$OUT"

echo ">> Validating I/O against the in-app decoder contract…"
"$PY" - "$OUT" "$IMGSZ" <<'PY'
import sys, onnxruntime as ort
path, imgsz = sys.argv[1], int(sys.argv[2])
s = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
i = s.get_inputs()[0]; o = s.get_outputs()[0]
print(f"   input  {i.name}: {i.shape}")
print(f"   output {o.name}: {o.shape}")
assert list(i.shape)[-3:] == [3, imgsz, imgsz], f"input must be 1x3x{imgsz}x{imgsz}"
# ObjectDetector expects [1,84,8400] or [1,8400,84] with C = 4 + 80 classes.
dims = [d for d in o.shape if isinstance(d, int)]
assert 84 in dims, f"expected a 84-channel head (4 box + 80 COCO), got {o.shape}"
print("   OK — matches ObjectDetector.kt ([1,84,8400], COCO classes).")
PY

SZ=$(du -h "$OUT" | cut -f1)
echo ">> Done: $OUT ($SZ)"
echo "   Build & run on a device; EngineFactory will pick the ORT path"
echo "   (logcat: 'detector -> ORT_QNN' on Snapdragon, else 'detector -> CPU')."
