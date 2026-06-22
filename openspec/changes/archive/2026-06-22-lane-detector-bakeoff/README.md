# lane-detector-bakeoff

Evaluate alternatives to UFLDv2 for ego-lane detection (objective paint-deviation benchmark on the replay feed). UFLDv2's right ego-slot mis-detects on multi-lane roads (model-level, confirmed). Candidates each get their own branch + spike: drivable-area segmentation (TwinLiteNet+/YOLOP), CLRerNet anchor lines, vs the UFLDv2+tracker baseline.
