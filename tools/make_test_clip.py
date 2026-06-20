#!/usr/bin/env python3
"""Synthesize validation clips for the debug replay source (tasks 9.6 / 9.2).

Renders simple but YOLO11n-detectable road scenes so warning functions can be
exercised without real dashcam footage. Push the output to the device:

    adb push build/test_signs.mp4 \
        /sdcard/Android/data/com.adasedge.app/files/replay.mp4

then enable Settings > "Replay test video" and start driving mode.

Detection note: YOLO11n is COCO-trained, so it recognizes `stop sign` and
`traffic light` (validated: ~0.85+ conf on these renders) but has NO speed-limit
class -- the "50" disc is drawn for realism only and stays undetected (over-speed
needs the Phase-2 GTSRB classifier; research/02).

    pip install pillow imageio imageio-ffmpeg numpy
    python tools/make_test_clip.py --scene signs --out build/test_signs.mp4
"""
import argparse
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
import imageio.v2 as imageio

W, H, FPS, SEC = 1280, 720, 15, 5


def _font(sz):
    for p in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",):
        try:
            return ImageFont.truetype(p, sz)
        except OSError:
            pass
    return ImageFont.load_default()


def _octagon(d, cx, cy, r, fill, outline):
    pts = [(cx + r * math.cos(math.pi / 8 + i * math.pi / 4),
            cy + r * math.sin(math.pi / 8 + i * math.pi / 4)) for i in range(8)]
    d.polygon(pts, fill=fill, outline=outline)
    d.line(pts + [pts[0]], fill=outline, width=max(2, int(r * 0.1)))


def signs_frame(t):
    """A road scene with a traffic light, stop sign, and (undetected) speed sign."""
    img = Image.new("RGB", (W, H), (120, 140, 160))
    d = ImageDraw.Draw(img)
    d.rectangle([0, int(H * 0.6), W, H], fill=(70, 70, 75))
    d.polygon([(W * 0.45, H * 0.6), (W * 0.55, H * 0.6), (W * 0.75, H), (W * 0.25, H)], fill=(90, 90, 95))
    s = 0.6 + t * 0.9  # signs grow as the ego approaches

    # Traffic light (red lit), left
    lw, lh = int(34 * s), int(92 * s); lx, ly = int(W * 0.18), int(H * 0.20)
    d.rectangle([lx, ly, lx + lw, ly + lh], fill=(20, 20, 20), outline=(60, 60, 60), width=2)
    rr = lw * 0.32
    for k, col in enumerate([(230, 40, 40), (60, 60, 40), (40, 60, 40)]):
        cy = ly + lh * (0.2 + 0.3 * k)
        d.ellipse([lx + lw * 0.18, cy - rr, lx + lw * 0.82, cy + rr], fill=col)

    # Stop sign, right
    r = int(46 * s); cx, cy = int(W * 0.80), int(H * 0.42)
    _octagon(d, cx, cy, r, (196, 30, 30), (255, 255, 255))
    f = _font(int(r * 0.6)); tw = d.textlength("STOP", font=f)
    d.text((cx - tw / 2, cy - r * 0.35), "STOP", fill=(255, 255, 255), font=f)

    # Speed-limit disc (NOT a COCO class -> stays undetected), center
    r2 = int(40 * s); sx, sy = int(W * 0.62), int(H * 0.34)
    d.ellipse([sx - r2, sy - r2, sx + r2, sy + r2], fill=(255, 255, 255),
              outline=(210, 30, 30), width=max(3, int(r2 * 0.18)))
    f2 = _font(int(r2 * 0.9)); tw = d.textlength("50", font=f2)
    d.text((sx - tw / 2, sy - r2 * 0.6), "50", fill=(10, 10, 10), font=f2)
    return np.array(img)


SCENES = {"signs": signs_frame}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scene", choices=SCENES, default="signs")
    ap.add_argument("--out", default="build/test_signs.mp4")
    args = ap.parse_args()
    render = SCENES[args.scene]
    frames = [render(i / (FPS * SEC - 1)) for i in range(FPS * SEC)]
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    imageio.mimwrite(args.out, frames, fps=FPS, codec="libx264", quality=8)
    print(f"wrote {args.out} ({len(frames)} frames @ {FPS}fps)")
    print("push:  adb push", args.out,
          "/sdcard/Android/data/com.adasedge.app/files/replay.mp4")


if __name__ == "__main__":
    main()
