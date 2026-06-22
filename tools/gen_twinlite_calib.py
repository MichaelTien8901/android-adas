#!/usr/bin/env python3
"""Build the TwinLite INT8 calibration set.

Samples ~N frames evenly across a road video and preprocesses each EXACTLY like
the app's Preprocess.toSegInput: resize the whole frame to 640x360, RGB order,
/255, NCHW float32. Writes one .raw per frame + input_list.txt for qairt-quantizer.

  python3 gen_twinlite_calib.py <video.mp4> <out_dir> [N]
"""
import sys, os
import numpy as np
import cv2

W, H, N_DEFAULT = 640, 360, 64

def main():
    video = sys.argv[1]
    out_dir = sys.argv[2]
    n = int(sys.argv[3]) if len(sys.argv) > 3 else N_DEFAULT
    os.makedirs(out_dir, exist_ok=True)

    cap = cv2.VideoCapture(video)
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or 0
    if total <= 0:
        raise SystemExit(f"no frames in {video}")
    # even sample across the clip
    idxs = np.linspace(0, total - 1, num=min(n, total), dtype=int)
    idxset = set(int(i) for i in idxs)

    listf = open(os.path.join(out_dir, "input_list.txt"), "w")
    saved = 0
    f = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if f in idxset:
            # cv2 gives BGR; toSegInput uses RGB channel order (R,G,B planes)
            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            rgb = cv2.resize(rgb, (W, H), interpolation=cv2.INTER_LINEAR)
            arr = (rgb.astype(np.float32) / 255.0)          # HWC RGB
            chw = np.transpose(arr, (2, 0, 1)).copy()        # NCHW (3,360,640)
            path = os.path.join(out_dir, f"twin{saved:03d}.raw")
            chw.tofile(path)
            listf.write(os.path.abspath(path) + "\n")
            saved += 1
        f += 1
    cap.release()
    listf.close()
    print(f"wrote {saved} calib frames ({W}x{H} RGB /255 NCHW) -> {out_dir}")

if __name__ == "__main__":
    main()
