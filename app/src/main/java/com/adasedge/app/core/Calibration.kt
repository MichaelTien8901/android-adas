package com.adasedge.app.core

/**
 * Monocular camera calibration used by the geometry-based distance estimator
 * (adas-perception: "Per-target distance and TTC estimation"). Defaults are
 * rough phone-on-windshield values; a one-time guided calibration should refine
 * [focalLengthPx], [mountHeightM], and [horizonRatio] (design Open Question).
 */
data class Calibration(
    /** Effective focal length in pixels at the analysis resolution. */
    val focalLengthPx: Float = 1000f,
    /** Camera height above the road, meters. */
    val mountHeightM: Float = 1.2f,
    /** Normalized image row (0..1) of the horizon line. */
    val horizonRatio: Float = 0.45f,
    /** Normalized image row (0..1) where the road ends / the car hood begins; the
     *  lane band is [horizonRatio, roadBottomRatio]. 1.0 = no hood (road to bottom). */
    val roadBottomRatio: Float = 1.0f,
    /** Normalized image column (0..1) of "straight ahead" for an off-centre / angled
     *  camera; the ego reference for lane-departure + the lead in-path band. 0.5 = centred. */
    val centerRatio: Float = 0.5f,
    /** Assumed physical width of a typical car, meters (known-width method). */
    val typicalVehicleWidthM: Float = 1.8f,
) {
    companion object { val DEFAULT = Calibration() }
}
