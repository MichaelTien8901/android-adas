#!/usr/bin/env python3
"""Synthesize a lane-DEPARTURE clip from real dashcam footage by panning the frame
laterally over time — the real road texture keeps TwinLite lane detection working, while
the pan sweeps the lanes across centre so the ego "drifts" toward each line and back.

Pan dx(t) = A*sin(2*pi*t/T):  dx<0 shifts content left -> right line approaches centre
(LDW RIGHT); dx>0 -> left line approaches centre (LDW LEFT); dx≈0 -> centred (clears).

  python3 make_drift_clip.py <in.mp4> <out.mp4> [seconds]
"""
import sys, math
import numpy as np, cv2

src, dst = sys.argv[1], sys.argv[2]
seconds = float(sys.argv[3]) if len(sys.argv) > 3 else 14.0
PERIOD = 3.5            # s per full oscillation (fast-ish drift to test responsiveness)
AMP_FRAC = 0.16         # pan amplitude as fraction of width (brings a ~0.66 line to ~0.50)

cap = cv2.VideoCapture(src)
fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
W = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)); H = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
amp = AMP_FRAC * W
n_out = int(seconds * fps)
fourcc = cv2.VideoWriter_fourcc(*"mp4v")
out = cv2.VideoWriter(dst, fourcc, fps, (W, H))

frames = []
while True:
    ok, f = cap.read()
    if not ok: break
    frames.append(f)
cap.release()
if not frames:
    raise SystemExit("no frames read")

for i in range(n_out):
    f = frames[i % len(frames)]
    dx = amp * math.sin(2 * math.pi * (i / fps) / PERIOD)
    M = np.float32([[1, 0, dx], [0, 1, 0]])
    shifted = cv2.warpAffine(f, M, (W, H), borderMode=cv2.BORDER_REPLICATE)
    out.write(shifted)
out.release()
print(f"wrote {dst}: {W}x{H} @{fps:.0f}fps, {n_out} frames, pan ±{amp:.0f}px, period {PERIOD}s")
