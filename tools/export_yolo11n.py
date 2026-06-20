#!/usr/bin/env python3
"""Export YOLO11n to ONNX for the ADAS detector (task 4.1).

Produces a static-shape 640x640 ONNX graph suitable for the QNN INT8 pipeline.
NOTE: Ultralytics YOLO is AGPL-3.0 — resolve licensing before distribution
(design D9 / task 9.5).

Usage:
    pip install ultralytics onnx onnxsim
    python tools/export_yolo11n.py --imgsz 640 --out build/detector.onnx
"""
import argparse
from pathlib import Path


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights", default="yolo11n.pt")
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--out", default="build/detector.onnx")
    args = ap.parse_args()

    from ultralytics import YOLO

    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    model = YOLO(args.weights)
    # opset 17, static shapes, simplified — QNN converter prefers static graphs.
    path = model.export(format="onnx", imgsz=args.imgsz, opset=17,
                        dynamic=False, simplify=True, nms=False)
    Path(path).replace(args.out)
    print(f"exported -> {args.out}  (input 1x3x{args.imgsz}x{args.imgsz}, output 1x84x8400)")


if __name__ == "__main__":
    main()
