#!/usr/bin/env python3
"""Synthesize validation clips for the debug replay source (tasks 9.6 / 9.2).

Renders simple but detectable road scenes so warning functions can be exercised
without real dashcam footage. Push the output to the device:

    adb push build/test_speed.mp4 \
        /sdcard/Android/data/com.adasedge.app/files/replay.mp4

then enable Settings > "Replay test video" and start driving mode.

Two scenes:
- `signs`  : traffic light + stop sign (COCO classes YOLO11n detects ~0.85+ conf).
- `speed`  : composites REAL GTSRB sign crops (50 then 30 km/h, each approaching)
             onto the road scene so the GTSRB classifier (task 7.6) actually reads
             them and the over-speed warning fires (replay speed 70 > 55, > 35).
             A *drawn* disc won't do -- the closed-set classifier is trained on real
             GTSRB photos, so the test sign must be in-distribution. Needs the GTSRB
             dataset under build/gtsrb (downloaded by tools/train_gtsrb.py).

    pip install pillow imageio imageio-ffmpeg numpy
    python tools/make_test_clip.py --scene speed --out build/test_speed.mp4
"""
import argparse
import glob
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
import imageio.v2 as imageio

W, H, FPS, SEC = 1280, 720, 15, 5

# GTSRB class id -> speed limit (km/h); mirrors tools/train_gtsrb.py.
GTSRB_ROOT = "build/gtsrb/gtsrb/GTSRB/Training"
LIMIT_TO_CLASS = {20: 0, 30: 1, 50: 2, 60: 3, 70: 4, 80: 5, 100: 7, 120: 8}


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


def _road_bg():
    """Static road backdrop (sky + asphalt + perspective lane lines) for the sign scenes."""
    img = Image.new("RGB", (W, H), (120, 140, 160))
    d = ImageDraw.Draw(img)
    d.rectangle([0, int(H * 0.6), W, H], fill=(70, 70, 75))
    d.polygon([(W * 0.45, H * 0.6), (W * 0.55, H * 0.6), (W * 0.75, H), (W * 0.25, H)], fill=(90, 90, 95))
    for k in range(4):  # dashed centre line receding to the horizon
        y0 = H * (0.62 + 0.09 * k); y1 = y0 + H * 0.04
        wv = 3 + k * 4
        d.polygon([(W * 0.5 - wv, y0), (W * 0.5 + wv, y0),
                   (W * 0.5 + wv * 1.4, y1), (W * 0.5 - wv * 1.4, y1)], fill=(220, 210, 120))
    return img


def _gtsrb_sign(cls):
    """The largest-native real GTSRB crop for `cls`. Largest = sharpest red ring when
    composited; GTSRB crops are tiny (25–160 px), and upscaling blurs the ring edge
    until HoughCircles can't lock onto it — so we only ever downscale these."""
    paths = glob.glob(f"{GTSRB_ROOT}/{cls:05d}/*.ppm")
    if not paths:
        raise SystemExit(f"GTSRB class {cls} not found under {GTSRB_ROOT} — run tools/train_gtsrb.py first")
    best = max(paths, key=lambda p: Image.open(p).size[0])   # .size reads header only
    return Image.open(best).convert("RGB")


def speed_frame(t, sign_a, sign_b):
    """50 then 30 km/h signs, each approaching roadside-right. Both exceed the 70 km/h
    replay speed, so over-speed fires for each — and the limit updates 50 -> 30. The
    sign is only ever downscaled from its native size, keeping the ring crisp enough
    for HoughCircles across the whole approach."""
    img = _road_bg()
    phase_b = t >= 0.5
    local = (t - 0.5) / 0.5 if phase_b else t / 0.5
    sign = sign_b if phase_b else sign_a
    native = sign.size[0]
    s = int(native * (0.62 + 0.38 * local))          # 0.62x -> 1.0x native (downscale only)
    sg = sign.resize((s, s), Image.LANCZOS)
    x = int(W * 0.66 + local * W * 0.06)             # drifts right as you pass it
    y = int(H * 0.30 - s * 0.1)
    img.paste(sg, (x, y))
    return np.array(img)


SCENES = {"signs": signs_frame, "speed": "speed"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scene", choices=SCENES, default="signs")
    ap.add_argument("--out", default="build/test_signs.mp4")
    ap.add_argument("--seconds", type=int, default=0, help="override clip length (speed scene defaults to 8s)")
    ap.add_argument("--limits", default="50,30",
                    help="speed scene: two limits (km/h) shown in sequence, e.g. 30,120 to fire then clear over-speed")
    args = ap.parse_args()

    if args.scene == "speed":
        sec = args.seconds or 8
        la, lb = (int(v) for v in args.limits.split(","))
        sa, sb = _gtsrb_sign(LIMIT_TO_CLASS[la]), _gtsrb_sign(LIMIT_TO_CLASS[lb])
        render = lambda u: speed_frame(u, sa, sb)
    else:
        sec = args.seconds or SEC
        render = SCENES[args.scene]
    frames = [render(i / (FPS * sec - 1)) for i in range(FPS * sec)]
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    imageio.mimwrite(args.out, frames, fps=FPS, codec="libx264", quality=8)
    print(f"wrote {args.out} ({len(frames)} frames @ {FPS}fps)")
    print("push:  adb push", args.out,
          "/sdcard/Android/data/com.adasedge.app/files/replay.mp4")


if __name__ == "__main__":
    main()
