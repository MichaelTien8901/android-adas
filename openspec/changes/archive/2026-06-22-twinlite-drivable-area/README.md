# twinlite-drivable-area

Spike: replace UFLDv2 with TwinLiteNet+ drivable-area + lane-line segmentation. Derive ego-lane boundaries from the drivable road mask (sidesteps UFLDv2's broken ego-slot assignment). Run on ONNX-RT/CPU, score on the paint-deviation benchmark vs the UFLDv2+tracker baseline.
