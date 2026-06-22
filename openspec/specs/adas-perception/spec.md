# adas-perception Specification

## Purpose
TBD - created by archiving change twinlite-drivable-area. Update Purpose after archive.
## Requirements
### Requirement: TwinLiteNet ego-lane source
The system SHALL be able to derive ego-lane geometry from a segmentation model
(TwinLiteNet drivable-area + lane-line masks) as a selectable alternative to the
row-anchor detector, producing the same `LaneGeometry` contract. Ego boundaries SHALL
be taken from the lane-line mask (nearest line each side of the ego centre), falling
back to the drivable-area corridor edge when the lane mask is blank on a row.

#### Scenario: Segmentation lane source selected
- **WHEN** the lane-model selector is `twinlite`
- **THEN** ego boundaries are decoded from the segmentation masks and consumed unchanged
  by the warnings and overlay

#### Scenario: No slot mis-assignment
- **WHEN** the road has multiple lanes to one side
- **THEN** the ego boundary is the nearest detected lane line to the ego centre, so it
  cannot be assigned to a non-adjacent lane the way a fixed line-slot can

### Requirement: Objective lane-model evaluation
The system SHALL be able to score a lane-detection model against independently detected
lane paint, so a model change is justified by measured correctness rather than by
stability or parallelism proxies. The score SHALL compare the predicted ego-left and
ego-right boundaries to the nearest detected lane paint at near/mid/far rows, on the
replay feed.

#### Scenario: Candidate scored against paint
- **WHEN** a lane model runs on the replay feed
- **THEN** the system logs, per row, the detected lane paint positions and the predicted
  ego-left/ego-right x, from which a paint-deviation score is computed

#### Scenario: Right-slot mis-detection is observable
- **WHEN** a model's predicted ego boundary does not lie on any detected paint, or lies
  on a neighbouring lane's paint
- **THEN** the score reflects the deviation, making the mis-detection measurable rather
  than hidden by a wrong-but-stable line

### Requirement: Pluggable lane source for evaluation
The system SHALL allow an alternative lane model to be selected for evaluation without
changing the shipped default or the downstream warning/overlay contract. A candidate
model SHALL produce the same ego-lane `LaneGeometry` output the warnings consume.

#### Scenario: Candidate selected
- **WHEN** the lane-model selector is set to a candidate
- **THEN** that model produces the lane geometry and all downstream warnings/overlay
  operate unchanged

#### Scenario: Default unchanged
- **WHEN** the selector is left at its default
- **THEN** the shipped UFLDv2 path runs exactly as before

