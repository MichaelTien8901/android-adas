package com.adasedge.app.perception

import com.adasedge.app.core.Calibration
import com.adasedge.app.model.Detection

/**
 * Monocular distance to a vehicle (adas-perception: "Per-target distance and TTC
 * estimation"). Uses two cues and takes the more reliable:
 *  - known-width triangle similarity: Z = f * W_real / w_px
 *  - ground-plane: Z = f * H_cam / (y_bottom_px - horizon_px)
 * No depth network is used (design D5). Returns meters, or null if implausible.
 */
class DistanceEstimator(private val calib: Calibration) {

    fun estimate(det: Detection, frameW: Int, frameH: Int): Float? {
        val wPx = det.box.width() * frameW
        val bottomPx = det.box.bottom * frameH
        val horizonPx = calib.horizonRatio * frameH

        val byWidth = if (wPx > 1f && det.cls.isVehicle)
            calib.focalLengthPx * calib.typicalVehicleWidthM / wPx else null

        val byGround = if (bottomPx > horizonPx + 1f)
            calib.focalLengthPx * calib.mountHeightM / (bottomPx - horizonPx) else null

        val z = when {
            byWidth != null && byGround != null -> (byWidth + byGround) / 2f
            else -> byWidth ?: byGround ?: return null
        }
        return if (z in 1f..200f) z else null
    }
}
