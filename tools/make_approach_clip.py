#!/usr/bin/env python3
"""Synthesize a hard forward-approach clip from a real lead-vehicle clip by applying an
accelerating zoom-in — the lead vehicle looms fast, driving TTC (from box-height growth)
down through the FCW advisory (2.7 s) and imminent (1.4 s) thresholds so the escalation
scenario can be exercised. Loops the approach so it repeats.

  python3 make_approach_clip.py <in.mp4> <out.mp4> [seconds]
"""
import sys, cv2

src, dst = sys.argv[1], sys.argv[2]
seconds = float(sys.argv[3]) if len(sys.argv) > 3 else 15.0
PERIOD = 3.0      # s per approach (then cut back to wide and repeat)
ZMAX = 1.7        # peak extra zoom -> up to ~2.7x (strong, accelerating looming)

cap = cv2.VideoCapture(src)
fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
W = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)); H = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
frames = []
while True:
    ok, f = cap.read()
    if not ok: break
    frames.append(f)
cap.release()
if not frames:
    raise SystemExit("no frames read")

out = cv2.VideoWriter(dst, cv2.VideoWriter_fourcc(*"mp4v"), fps, (W, H))
cx, cy = W / 2.0, H * 0.5
n_out = int(seconds * fps)
for i in range(n_out):
    f = frames[i % len(frames)]
    frac = (i % int(PERIOD * fps)) / (PERIOD * fps)   # 0..1 each period
    zoom = 1.0 + ZMAX * frac * frac                    # accelerating (TTC falls toward end)
    M = cv2.getRotationMatrix2D((cx, cy), 0, zoom)
    out.write(cv2.warpAffine(f, M, (W, H), borderMode=cv2.BORDER_REPLICATE))
out.release()
print(f"wrote {dst}: {W}x{H} @{fps:.0f}fps, {n_out} frames, zoom 1.0->{1+ZMAX:.1f} over {PERIOD}s (accelerating)")
