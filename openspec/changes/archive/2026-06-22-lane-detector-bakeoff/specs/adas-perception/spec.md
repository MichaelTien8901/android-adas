## ADDED Requirements

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
