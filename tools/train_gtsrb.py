#!/usr/bin/env python3
"""Train a compact GTSRB traffic-sign classifier and export to ONNX (task 7.6).

Feeds the app's speed-limit recognition: OpenCV proposes circular sign regions,
this CNN reads the value. 43 GTSRB classes; classes 0-8 are the speed limits.

Input: 1x3x48x48 RGB [0,1].  Output: 43 logits.

    pip install torch torchvision onnx
    python tools/train_gtsrb.py --epochs 10 --out app/src/main/assets/models/gtsrb.onnx
"""
import argparse
from pathlib import Path

import torch
import torch.nn as nn
import torchvision
from torchvision import transforms
from torch.utils.data import DataLoader

# GTSRB class id -> speed-limit value (km/h); others are not speed limits.
CLASS_TO_LIMIT = {0: 20, 1: 30, 2: 50, 3: 60, 4: 70, 5: 80, 7: 100, 8: 120}


class SignNet(nn.Module):
    def __init__(self, n=43):
        super().__init__()
        def blk(i, o): return nn.Sequential(nn.Conv2d(i, o, 3, padding=1), nn.BatchNorm2d(o),
                                            nn.ReLU(inplace=True), nn.MaxPool2d(2))
        self.f = nn.Sequential(blk(3, 32), blk(32, 64), blk(64, 128))
        self.c = nn.Sequential(nn.Flatten(), nn.Dropout(0.3),
                               nn.Linear(128 * 6 * 6, 256), nn.ReLU(inplace=True), nn.Linear(256, n))

    def forward(self, x): return self.c(self.f(x))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--epochs", type=int, default=10)
    ap.add_argument("--root", default="build/gtsrb")
    ap.add_argument("--out", default="app/src/main/assets/models/gtsrb.onnx")
    args = ap.parse_args()

    # The venv torch is a CUDA build; on this CPU box oneDNN conv errors out
    # ("unable to find an engine") — force the native CPU path.
    torch.backends.mkldnn.enabled = False
    torch.set_num_threads(max(2, (torch.get_num_threads() or 4)))
    dev = "cpu"
    tf_tr = transforms.Compose([transforms.Resize((48, 48)), transforms.ColorJitter(0.3, 0.3, 0.3),
                                transforms.RandomAffine(10, (0.08, 0.08), (0.9, 1.1)), transforms.ToTensor()])
    tf_te = transforms.Compose([transforms.Resize((48, 48)), transforms.ToTensor()])
    tr = torchvision.datasets.GTSRB(args.root, "train", transform=tf_tr, download=True)
    te = torchvision.datasets.GTSRB(args.root, "test", transform=tf_te, download=True)
    trl = DataLoader(tr, 128, shuffle=True, num_workers=0)
    tel = DataLoader(te, 256, num_workers=0)

    net = SignNet().to(dev)
    opt = torch.optim.Adam(net.parameters(), 1e-3, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, args.epochs)
    lossf = nn.CrossEntropyLoss()
    for ep in range(args.epochs):
        net.train()
        for x, y in trl:
            x, y = x.to(dev), y.to(dev)
            opt.zero_grad(); lossf(net(x), y).backward(); opt.step()
        sched.step()
        net.eval(); ok = tot = 0
        with torch.no_grad():
            for x, y in tel:
                ok += (net(x.to(dev)).argmax(1).cpu() == y).sum().item(); tot += len(y)
        print(f"epoch {ep+1}/{args.epochs}  test acc {ok/tot:.3%}", flush=True)

    net.eval().cpu()
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(net, torch.zeros(1, 3, 48, 48), args.out, opset_version=17,
                      input_names=["input"], output_names=["logits"], dynamo=False)
    print(f"exported -> {args.out}")
    print("CLASS_TO_LIMIT =", CLASS_TO_LIMIT)


if __name__ == "__main__":
    main()
