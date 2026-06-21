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
    private val laneDetector = laneRunner?.let {
        LaneDetector(it, horizonRatio = calib.horizonRatio, roadBottomRatio = calib.roadBottomRatio)
    }
    private val classicalLanes = ClassicalLaneFallback()

    // Speed-limit sign recognition (task 7.6): OpenCV circles + GTSRB CNN. Optional —
    // null when gtsrb.onnx is absent (over-speed simply never fires then).
    private val signRunner: ModelRunner? = runCatching {
        EngineFactory.create(context, "gtsrb", 3, 48, 48)
    }.getOrNull()
    private val speedLimit = signRunner?.let { SpeedLimitRecognizer(it) }
    private var frameNo = 0L
    private var lastSigns: List<com.adasedge.app.model.Detection> = emptyList()

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
            val detected = detector.detect(detPrepared, w, h)

            // Speed-limit signs: run the (heavier) recognizer every 3rd frame and
            // carry the result on intermediate frames; the limit persists in TSR anyway.
            speedLimit?.let { if (frameNo++ % 3L == 0L) lastSigns = runCatching { it.detect(frame) }.getOrDefault(emptyList()) }
            val detections = if (lastSigns.isEmpty()) detected else detected + lastSigns

            val lanes = laneDetector?.let { ld ->
                ld.detect(Preprocess.toLaneInput(frame, Config.LANE_INPUT_W, Config.LANE_INPUT_H, Config.LANE_CROP_RATIO))
            } ?: classicalLanes.detect(frame)
            lanesAvailableLastFrame = lanes != null

            val lead = selectLead(detections, w, h, tsNanos)
            PerceptionResult(tsNanos, detections, lanes, lead, w, h)
        } catch (t: Throwable) {
            Log.w(TAG, "frame perception failed", t)
            PerceptionResult.empty(tsNanos, w, h)
        }
    }

    /** Lead = nearest in-path vehicle: center-band (around the calibrated straight-ahead
     *  column for an off-centre camera), lowest in frame (closest). */
    private fun selectLead(dets: List<Detection>, w: Int, h: Int, tsNanos: Long): LeadEstimate? {
        val lo = calib.centerRatio - 0.20f; val hi = calib.centerRatio + 0.20f
        val candidates = dets.filter {
            it.cls.isVehicle && it.box.centerX() in lo..hi
        }
        val lead = candidates.maxByOrNull { it.box.bottom } ?: run { ttc.reset(); return null }
        val dist = distance.estimate(lead, w, h) ?: return null
        val ttcSeconds = ttc.update(lead.box.height(), tsNanos)
        return LeadEstimate(lead, dist, ttcSeconds)
    }

    override fun close() {
        detectorRunner.close(); laneRunner?.close(); signRunner?.close()
    }

    companion object { private const val TAG = "PerceptionEngine" }
}
