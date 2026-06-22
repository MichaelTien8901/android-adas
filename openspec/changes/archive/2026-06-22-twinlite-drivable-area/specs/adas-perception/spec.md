## ADDED Requirements

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
