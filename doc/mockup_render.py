#!/usr/bin/env python3
"""Render a design-board mockup of the ADAS Edge UI (driving overlay, HUD mirror,
start/disclaimer, settings). Output: doc/ui_mockup.png"""
from PIL import Image, ImageDraw, ImageFont

F = "/snap/gimp/552/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FB = "/snap/gimp/552/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
def font(sz, bold=False): return ImageFont.truetype(FB if bold else F, sz)

BG = (13, 27, 42)
PANEL = (10, 14, 20)
CYAN = (51, 195, 255)
AMBER = (255, 179, 0)
GREEN = (105, 240, 174)
RED = (255, 82, 82)
RED_BANNER = (211, 47, 47)
WHITE = (240, 244, 248)
GREY = (150, 160, 170)
CHIP_BG = (34, 34, 34)

def rrect(d, box, r, fill=None, outline=None, w=1):
    d.rounded_rectangle(box, radius=r, fill=fill, outline=outline, width=w)

def text(d, xy, s, f, fill=WHITE, anchor=None):
    d.text(xy, s, font=f, fill=fill, anchor=anchor)

# ---------- driving overlay panel ----------
def driving_panel(mirror=False):
    W, H = 760, 428
    im = Image.new("RGB", (W, H), (40, 44, 52))
    d = ImageDraw.Draw(im)
    # faux road
    d.polygon([(W*0.5, H*0.42), (W*0.13, H), (W*0.87, H)], fill=(58, 62, 70))
    for i in range(5):  # dashed center line
        y0 = H*0.55 + i*38
        d.polygon([(W*0.5-3, y0), (W*0.5+3, y0), (W*0.5+5, y0+22), (W*0.5-5, y0+22)], fill=(120,120,120))
    d.rectangle([0, int(H*0.42), W, int(H*0.44)], fill=(70, 80, 95))  # horizon

    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    # lane lines (cyan)
    ld.line([(W*0.30, H), (W*0.46, H*0.46)], fill=CYAN+(255,), width=6)
    ld.line([(W*0.70, H), (W*0.54, H*0.46)], fill=CYAN+(255,), width=6)
    # lead vehicle box (green) + distance
    ld.rectangle([W*0.42, H*0.50, W*0.58, H*0.70], outline=GREEN+(255,), width=4)
    ld.text((W*0.42, H*0.50-22), "car 92%", font=font(20, True), fill=GREEN+(255,))
    ld.text((W*0.50, H*0.72), "28 m", font=font(22, True), fill=WHITE+(255,), anchor="ma")
    # pedestrian box (red)
    ld.rectangle([W*0.16, H*0.52, W*0.23, H*0.74], outline=RED+(255,), width=4)
    ld.text((W*0.16, H*0.52-22), "person 78%", font=font(18, True), fill=RED+(255,))
    if mirror:
        layer = layer.transpose(Image.FLIP_LEFT_RIGHT)
    im.paste(layer, (0, 0), layer)
    d = ImageDraw.Draw(im)

    if mirror:
        # HUD: simplified high-contrast, big glyphs only
        d.rectangle([0, 0, W, H], fill=None)
        ov = Image.new("RGBA", (W, H), (0, 0, 0, 150)); im.paste(Image.alpha_composite(im.convert("RGBA"), ov).convert("RGB"), (0,0))
        d = ImageDraw.Draw(im)
        text(d, (W/2, 70), "COLLISION", font(64, True), RED, anchor="ma")
        text(d, (W/2, 150), "1.2 s   28 m", font(40, True), WHITE, anchor="ma")
        text(d, (W/2, H-70), "92  km/h", font(48, True), AMBER, anchor="ma")
        text(d, (16, 16), "HUD MIRROR", font(20, True), GREY)
        return im

    # imminent warning banner (red, full width)
    d.rectangle([0, 0, W, 56], fill=RED_BANNER)
    text(d, (16, 14), "⚠ COLLISION RISK  1.2 s / 28 m", font(26, True), WHITE)
    # status chips bottom-left
    chips = [("SPEED EST", AMBER), ("THERMAL", AMBER), ("24 FPS", GREEN)]
    y = H - 36
    for label, col in chips:
        w = d.textlength(label, font=font(18, True)) + 22
        rrect(d, [14, y-26, 14+w, y+4], 8, fill=CHIP_BG)
        text(d, (25, y-22), label, font(18, True), col)
        y -= 38
    # stop button top-right
    rrect(d, [W-92, 12, W-16, 48], 10, fill=(60, 64, 72))
    text(d, (W-54, 18), "Stop", font(20, True), WHITE, anchor="ma")
    return im

# ---------- start / disclaimer panel ----------
def start_panel():
    W, H = 760, 428
    im = Image.new("RGB", (W, H), PANEL); d = ImageDraw.Draw(im)
    text(d, (40, 36), "Driver-Assistance Aid — Read Before Use", font(26, True), WHITE)
    body = ["ADAS Edge is a driver-assistance aid, NOT a certified or",
            "autonomous system. It provides warnings only and never",
            "controls the vehicle. Warnings may be late or absent —",
            "especially where GPS is weak. You remain fully responsible",
            "for safe driving at all times."]
    for i, line in enumerate(body):
        text(d, (40, 88 + i*30), line, font(19), GREY)
    # checkbox
    rrect(d, [40, 252, 64, 276], 5, outline=CYAN, w=2)
    d.line([(45, 264), (52, 271)], fill=CYAN, width=3); d.line([(52, 271), (60, 257)], fill=CYAN, width=3)
    text(d, (76, 252), "I understand and accept", font(20, True), WHITE)
    rrect(d, [40, 312, W-40, 360], 12, fill=CYAN)
    text(d, (W/2, 324), "START DRIVING MODE", font(22, True), (8, 16, 24), anchor="ma")
    rrect(d, [40, 372, W-40, 410], 10, outline=GREY, w=2)
    text(d, (W/2, 382), "Settings", font(20), GREY, anchor="ma")
    return im

# ---------- settings panel ----------
def settings_panel():
    W, H = 760, 428
    im = Image.new("RGB", (W, H), PANEL); d = ImageDraw.Draw(im)
    text(d, (40, 30), "Settings", font(28, True), WHITE)
    rows = [("Audible alerts", True), ("Haptic alerts", True), ("HUD mirror mode", False),
            ("Forward collision warning", True), ("Lane departure warning", True),
            ("Tailgating / headway", True), ("Traffic sign / over-speed", False)]
    y = 86
    for label, on in rows:
        text(d, (40, y), label, font(20), WHITE)
        track = CYAN if on else (70, 74, 82)
        rrect(d, [W-110, y-2, W-58, y+26], 14, fill=track)
        knob_x = W-72 if on else W-104
        d.ellipse([knob_x, y+0, knob_x+24, y+24], fill=WHITE)
        y += 46
    return im

# ---------- compose board ----------
PAD, GAP = 28, 24
title_h = 70
pw, ph = 760, 428
W = PAD*2 + pw*2 + GAP
H = title_h + PAD*2 + ph*2 + GAP + 30
board = Image.new("RGB", (W, H), BG); d = ImageDraw.Draw(board)
text(d, (PAD, 22), "ADAS Edge — UI Design Board", font(34, True), WHITE)
text(d, (PAD, 60), "landscape · Material 3 dark · glanceable safety HMI", font(18), GREY)

def place(panel, col, row, caption):
    x = PAD + col*(pw+GAP); y = title_h + PAD + row*(ph+GAP+30)
    rrect(d, [x-2, y-2, x+pw+2, y+ph+2], 14, outline=(40,48,60), w=2)
    board.paste(panel, (x, y))
    text(d, (x+4, y+ph+6), caption, font(18, True), CYAN)

place(driving_panel(False), 0, 0, "1 · Driving overlay (live perception + warning banner + status chips)")
place(driving_panel(True), 1, 0, "2 · HUD mirror mode (high-contrast, windshield reflection)")
place(start_panel(), 0, 1, "3 · Start / safety-disclaimer gate")
place(settings_panel(), 1, 1, "4 · Settings (alerts + per-function toggles)")

board.save("doc/ui_mockup.png")
print("wrote doc/ui_mockup.png", board.size)
