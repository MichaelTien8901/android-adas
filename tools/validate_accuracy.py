#!/usr/bin/env python3
"""Quantization accuracy gate (task 4.3).

Compares the INT8-quantized detector's mAP against the FP32 ONNX baseline on a
validation set and FAILS (exit 1) if the drop exceeds the configured threshold,
matching Config.MAX_QUANT_MAP_DROP (0.03) in the app.

Usage:
    python tools/validate_accuracy.py --fp build/detector.onnx \
        --int8-out qnn_eval.json --data data/bdd100k_val --max-drop 0.03
"""
import argparse
import json
import sys


def evaluate_map(model_ref: str, data: str) -> float:
    """Run inference over `data` and return COCO mAP@0.5:0.95.

    Wire this to your eval harness (Ultralytics val for the FP ONNX; qnn-net-run
    outputs decoded + scored for the INT8 graph). Returns a float in [0,1].
    """
    raise NotImplementedError(
        "Connect to your detection eval harness (Ultralytics val / pycocotools).")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fp", required=True, help="FP32 ONNX baseline")
    ap.add_argument("--int8", required=True, help="INT8 graph id or eval output")
    ap.add_argument("--data", required=True)
    ap.add_argument("--max-drop", type=float, default=0.03)
    args = ap.parse_args()

    fp_map = evaluate_map(args.fp, args.data)
    int8_map = evaluate_map(args.int8, args.data)
    drop = fp_map - int8_map
    result = {"fp_map": fp_map, "int8_map": int8_map, "drop": drop, "max_drop": args.max_drop}
    print(json.dumps(result, indent=2))

    if drop > args.max_drop:
        print(f"FAIL: mAP drop {drop:.4f} exceeds {args.max_drop}", file=sys.stderr)
        return 1
    print("PASS: quantized accuracy within budget")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
