package com.adasedge.app.perception

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.adasedge.app.core.Calibration
import com.adasedge.app.core.Config
import com.adasedge.app.model.AccelPath
import com.adasedge.app.model.Detection
import com.adasedge.app.model.LeadEstimate
import com.adasedge.app.model.ObjectClass
import com.adasedge.app.model.PerceptionResult
import com.adasedge.app.inference.EngineFactory
import com.adasedge.app.inference.ModelRunner
import java.io.Closeable

/**
 * Orchestrates the perception pipeline and publishes the per-frame
 * [PerceptionResult] contract consumed by all warnings. Owns the model runners
 * (selected per device by [EngineFactory]) and the UFLDv2 lane model with the
 * OpenCV classical fallback.
 */
class PerceptionEngine(
    context: Context,
    private val calib: Calibration = Calibration.DEFAULT,
) : Closeable {

    private val detectorRunner: ModelRunner =
        EngineFactory.create(context, "detector", 3, Config.MODEL_INPUT_SIZE, Config.MODEL_INPUT_SIZE)
    private val detector = ObjectDetector(detectorRunner)

    private val laneRunner: ModelRunner? = runCatching {
        EngineFactory.create(context, "lane", 3, Config.LANE_INPUT_H, Config.LANE_INPUT_W)
    }.getOrNull()
    private val laneDetector = laneRunner?.let { LaneDetector(it, horizonRatio = calib.horizonRatio) }
    private val classicalLanes = ClassicalLaneFallback()

    private val distance = DistanceEstimator(calib)
    private val ttc = TtcEstimator()

    val accelPath: AccelPath get() = detectorRunner.accelPath
    @Volatile var lanesAvailableLastFrame: Boolean = false
        private set

    /** Run the full pipeline on one frame. Never throws on a single bad frame. */
    fun process(frame: Bitmap, tsNanos: Long): PerceptionResult {
        val w = frame.width; val h = frame.height
        return try {
            val detPrepared = Preprocess.toNchw(frame, Config.MODEL_INPUT_SIZE, Config.MODEL_INPUT_SIZE)
            val detections = detector.detect(detPrepared, w, h)

            val lanes = laneDetector?.let { ld ->
                ld.detect(Preprocess.toLaneInput(frame, Config.LANE_INPUT_W, Config.LANE_INPUT_H, calib.horizonRatio, Config.LANE_CROP_RATIO))
            } ?: classicalLanes.detect(frame)
            lanesAvailableLastFrame = lanes != null

            val lead = selectLead(detections, w, h, tsNanos)
            PerceptionResult(tsNanos, detections, lanes, lead, w, h)
        } catch (t: Throwable) {
            Log.w(TAG, "frame perception failed", t)
            PerceptionResult.empty(tsNanos, w, h)
        }
    }

    /** Lead = nearest in-path vehicle: center-band, lowest in frame (closest). */
    private fun selectLead(dets: List<Detection>, w: Int, h: Int, tsNanos: Long): LeadEstimate? {
        val candidates = dets.filter {
            it.cls.isVehicle && it.box.centerX() in 0.30f..0.70f
        }
        val lead = candidates.maxByOrNull { it.box.bottom } ?: run { ttc.reset(); return null }
        val dist = distance.estimate(lead, w, h) ?: return null
        val ttcSeconds = ttc.update(lead.box.height(), tsNanos)
        return LeadEstimate(lead, dist, ttcSeconds)
    }

    override fun close() {
        detectorRunner.close(); laneRunner?.close()
    }

    companion object { private const val TAG = "PerceptionEngine" }
}
