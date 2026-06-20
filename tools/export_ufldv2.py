#!/usr/bin/env python3
"""Export Ultra-Fast-Lane-Detection v2 to a decoder-compatible ONNX (task 4.6).

UFLDv2 (https://github.com/cfzd/Ultra-Fast-Lane-Detection-v2) natively emits four
hybrid-anchor tensors (loc_row/loc_col/exist_row/exist_col). The app's
LaneDetector.kt instead consumes a simplified TWO-tensor contract:

    loc   : [1, numLanes, numRow, griding]   argmax over griding -> column
    exist : [1, numLanes, numRow]            value > 0.5 -> row present

So this exporter wraps the upstream net in an adapter that:
  * uses the ROW-anchor branch (near-vertical ego lanes),
  * permutes loc_row (B,grid,row,lane) -> (B,lane,row,grid),
  * softmaxes exist_row (B,2,row,lane) -> P(present) -> (B,lane,row),
  * BAKES ImageNet normalization in front, because the app's Preprocess feeds
    [0,1] RGB (no mean/std) while UFLDv2 was trained on normalized input.

Dims are read from the chosen config; the TuSimple ResNet18 config (num_row=56,
num_cell_row=100, num_lanes=4, 320x800) is the one that matches LaneDetector.kt's
defaults. CULane is 72/200 @320x1600 and would NOT match without editing the app.

Caveat (documented, not fixed here): UFLDv2 inference crops the top (crop_ratio)
before resizing; the app's Preprocess letterboxes the full frame. Lanes still
decode, but row-anchor alignment is approximate until Preprocess replicates the
crop. See tools/README_qnn.md.

Usage (see tools/fetch_lane.sh for the automated path):
    python tools/export_ufldv2.py --repo build/UFLDv2 \
        --config build/UFLDv2/configs/tusimple_res18.py \
        --ckpt build/tusimple_res18.pth --out app/src/main/assets/models/lane.onnx
"""
import argparse
import sys
import types
from pathlib import Path


def read_config(path: str) -> dict:
    """UFLDv2 configs are plain `name = value` python; exec to grab the dims."""
    ns: dict = {}
    exec(compile(Path(path).read_text(), path, "exec"), {}, ns)
    return ns


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="path to the cloned UFLDv2 repo")
    ap.add_argument("--config", required=True, help="UFLDv2 config .py (dims source)")
    ap.add_argument("--ckpt", required=True, help="trained .pth checkpoint")
    ap.add_argument("--out", default="app/src/main/assets/models/lane.onnx")
    ap.add_argument("--opset", type=int, default=17)
    ap.add_argument("--fp32", action="store_true",
                    help="skip INT8 dynamic quantization (emits the full ~385MB fp32 model)")
    args = ap.parse_args()

    cfg = read_config(args.config)
    num_row = cfg["num_row"]; num_cell_row = cfg["num_cell_row"]
    num_col = cfg["num_col"]; num_cell_col = cfg["num_cell_col"]
    num_lanes = cfg["num_lanes"]; backbone = str(cfg["backbone"])
    h = cfg["train_height"]; w = cfg["train_width"]
    fc_norm = cfg.get("fc_norm", False); use_aux = cfg.get("use_aux", False)

    # --- break the DALI import chain: model.* only needs initialize_weights ---
    sys.path.insert(0, args.repo)
    stub = types.ModuleType("utils.common")
    stub.initialize_weights = lambda *m: None  # checkpoint overrides init anyway
    sys.modules["utils.common"] = stub

    import torch
    from torch import nn
    from model.model_culane import parsingNet  # tusimple reuses this class

    net = parsingNet(
        pretrained=False, backbone=backbone,
        num_grid_row=num_cell_row, num_cls_row=num_row,
        num_grid_col=num_cell_col, num_cls_col=num_col,
        num_lane_on_row=num_lanes, num_lane_on_col=num_lanes,
        use_aux=use_aux, input_height=h, input_width=w, fc_norm=fc_norm,
    ).eval()

    sd = torch.load(args.ckpt, map_location="cpu")["model"]
    compat = {(k[7:] if k.startswith("module.") else k): v for k, v in sd.items()}
    missing, unexpected = net.load_state_dict(compat, strict=False)
    # aux/seg heads are training-only; some misses are expected. Backbone+cls must load.
    print(f"   loaded checkpoint: {len(compat)} tensors "
          f"({len(missing)} missing, {len(unexpected)} unexpected)")
    bad = [k for k in missing if k.startswith(("model.", "cls."))]
    if bad:
        raise SystemExit(f"FATAL: core weights missing -> {bad[:5]} … wrong config/ckpt pair")

    class AppAdapter(nn.Module):
        """Native UFLDv2 -> LaneDetector.kt 2-tensor contract, normalization baked in."""
        def __init__(self, net):
            super().__init__()
            self.net = net
            self.register_buffer("mean", torch.tensor([0.485, 0.456, 0.406]).view(1, 3, 1, 1))
            self.register_buffer("std", torch.tensor([0.229, 0.224, 0.225]).view(1, 3, 1, 1))

        def forward(self, x):                      # x: [B,3,H,W] RGB in [0,1]
            x = (x - self.mean) / self.std
            p = self.net(x)
            loc = p["loc_row"].permute(0, 3, 2, 1).contiguous()          # B,lane,row,grid
            exist = torch.softmax(p["exist_row"], dim=1)[:, 1]           # B,row,lane
            exist = exist.permute(0, 2, 1).contiguous()                  # B,lane,row
            return loc, exist

    adapter = AppAdapter(net).eval()
    dummy = torch.zeros(1, 3, h, w)
    with torch.no_grad():
        loc, exist = adapter(dummy)
    assert tuple(loc.shape) == (1, num_lanes, num_row, num_cell_row), loc.shape
    assert tuple(exist.shape) == (1, num_lanes, num_row), exist.shape

    out = Path(args.out); out.parent.mkdir(parents=True, exist_ok=True)
    # The app loads the asset as a byte buffer (OrtModelRunner reads bytes, not a
    # path), so weights MUST be embedded in ONE file. dynamo=False uses the legacy
    # TorchScript exporter, which inlines initializers (model is <2GB). Remove any
    # external-data sidecar left by a previous dynamo export.
    for stale in out.parent.glob(out.name + "*.data"):
        stale.unlink()
    fp32 = out.with_name("lane_fp32.onnx")
    torch.onnx.export(
        adapter, dummy, str(fp32), opset_version=args.opset,
        input_names=["input"], output_names=["loc", "exist"],
        dynamic_axes=None, dynamo=False,
    )

    if args.fp32:
        fp32.replace(out)
    else:
        # UFLDv2's FC head is ~96M params -> 385MB fp32, too large to mmap/heap on
        # a phone. Dynamic INT8 (weight-only, no calibration) shrinks it ~4x and
        # keeps the ORT fallback viable; the QNN path stays the production route.
        from onnxruntime.quantization import quantize_dynamic, QuantType
        quantize_dynamic(str(fp32), str(out), weight_type=QuantType.QInt8)
        fp32.unlink()

    mb = out.stat().st_size / 1e6
    print(f"   exported -> {out}  ({mb:.0f} MB, {'fp32' if args.fp32 else 'int8-dynamic'}) "
          f"input 1x3x{h}x{w}, loc 1x{num_lanes}x{num_row}x{num_cell_row}, exist 1x{num_lanes}x{num_row}")
    print(f"   LaneDetector.kt must use numLanes={num_lanes} numRow={num_row} "
          f"griding={num_cell_row} (input W={w} H={h}).")


if __name__ == "__main__":
    main()
