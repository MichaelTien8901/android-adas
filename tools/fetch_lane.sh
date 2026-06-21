#!/usr/bin/env bash
# Fetch + export a decoder-compatible UFLDv2 lane model into the app.
#
# Produces app/src/main/assets/models/lane.onnx from the official TuSimple
# ResNet18 weights, wrapped to LaneDetector.kt's 2-tensor contract
# (loc [1,4,56,100] + exist [1,4,56], input 1x3x320x800, ImageNet norm baked in).
# Runs on the ONNX-Runtime path (CPU everywhere, QNN-EP on Snapdragon); until it
# exists the app uses the OpenCV classical-CV lane fallback.
#
# The checkpoint is on the authors' Google Drive (no direct URL). This script
# pulls it via the usercontent confirm endpoint; if Google rate-limits, download
# `tusimple_res18.pth` manually from the repo README and pass it with CKPT=...
#
# Usage:  tools/fetch_lane.sh            # auto-download TuSimple res18
#         CKPT=/path/to/tusimple_res18.pth tools/fetch_lane.sh   # use local ckpt
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/build"
REPO="$BUILD/UFLDv2"
CONFIG="$REPO/configs/tusimple_res18.py"
CKPT="${CKPT:-$BUILD/tusimple_res18.pth}"
GDRIVE_ID="1Clnj9-dLz81S3wXiYtlkc4HVusCb978t"   # TuSimple ResNet18 (README)
OUT="$ROOT/app/src/main/assets/models/lane.onnx"

VENV="$BUILD/.export-venv"
PY="$VENV/bin/python"
mkdir -p "$BUILD"
if [[ ! -x "$PY" ]]; then python3 -m venv "$VENV"; fi
echo ">> Installing exporter deps (torch + torchvision + onnx, CPU)…"
"$PY" -m pip install --quiet --upgrade pip
"$PY" -m pip install --quiet torch torchvision onnx onnxruntime onnxscript numpy

if [[ ! -d "$REPO" ]]; then
  echo ">> Cloning UFLDv2 repo…"
  git clone --depth 1 https://github.com/cfzd/Ultra-Fast-Lane-Detection-v2.git "$REPO"
fi

if [[ ! -f "$CKPT" ]]; then
  echo ">> Downloading TuSimple ResNet18 checkpoint from Google Drive…"
  curl -sL --max-time 600 \
    "https://drive.usercontent.google.com/download?id=${GDRIVE_ID}&export=download&confirm=t" \
    -o "$CKPT"
  if ! "$PY" -c "import sys; f=open(sys.argv[1],'rb'); assert f.read(2)==b'\x80\x02', 'not a torch pickle'" "$CKPT" 2>/dev/null; then
    echo "ERROR: download did not yield a torch checkpoint (Google rate-limit?)." >&2
    echo "       Download tusimple_res18.pth manually and re-run with CKPT=<path>." >&2
    rm -f "$CKPT"; exit 2
  fi
fi

echo ">> Exporting UFLDv2 -> $OUT…"
"$PY" "$ROOT/tools/export_ufldv2.py" --repo "$REPO" --config "$CONFIG" --ckpt "$CKPT" --out "$OUT"

echo ">> Validating (loaded from bytes, exactly like the app)…"
"$PY" - "$OUT" <<'PY'
import sys, os, numpy as np, onnxruntime as ort
path = sys.argv[1]
assert not os.path.exists(path + ".data"), "external-data sidecar present — won't load in-app!"
# OrtModelRunner does env.createSession(readBytes(asset)); load from bytes to prove
# the model is self-contained (external data can't be resolved from a buffer).
s = ort.InferenceSession(open(path, "rb").read(), providers=["CPUExecutionProvider"])
outs = s.run(None, {s.get_inputs()[0].name: np.zeros((1,3,320,800), np.float32)})
shapes = {o.name: list(t.shape) for o, t in zip(s.get_outputs(), outs)}
print("   outputs:", shapes)
assert shapes["loc"] == [1,4,56,100] and shapes["exist"] == [1,4,56], shapes
print("   OK — single-file, matches LaneDetector.kt (numLanes=4 numRow=56 griding=100).")
PY

echo ">> Done: $OUT ($(du -h "$OUT" | cut -f1)). Lane NPU path active on next build."
