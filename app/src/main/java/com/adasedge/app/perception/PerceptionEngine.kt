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
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
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
    private val laneMarkingSnap: Boolean = false,
    birdEyeLaneFit: Boolean = false,
    laneStabilityTracker: Boolean = false,
) : Closeable {

    private val detectorRunner: ModelRunner =
        EngineFactory.create(context, "detector", 3, Config.MODEL_INPUT_SIZE, Config.MODEL_INPUT_SIZE)
    private val detector = ObjectDetector(detectorRunner)

    private val laneRunner: ModelRunner? = runCatching {
        EngineFactory.create(context, "lane", 3, Config.LANE_INPUT_H, Config.LANE_INPUT_W)
    }.getOrNull()
    private val laneDetector = laneRunner?.let {
        LaneDetector(it, horizonRatio = calib.horizonRatio, roadBottomRatio = calib.roadBottomRatio,
            centerRatio = calib.centerRatio, birdEyeFit = birdEyeLaneFit,
            stabilityTracker = laneStabilityTracker)
    }
    private val classicalLanes = ClassicalLaneFallback()
    private val grayBuf = ByteArray(GRAY_W * GRAY_H)   // reused per-frame for the lane marking-snap

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
    // Replay validation: right lane RAW-decode vs OUTPUT, and left OUTPUT (near/mid/far x).
    val laneDbgRRaw: FloatArray? get() = laneDetector?.dbgRRaw
    val laneDbgROut: FloatArray? get() = laneDetector?.dbgROut
    val laneDbgLOut: FloatArray? get() = laneDetector?.dbgLOut
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
                val input = Preprocess.toLaneInput(frame, Config.LANE_INPUT_W, Config.LANE_INPUT_H, Config.LANE_CROP_RATIO)
                // Optional hybrid marking-snap (skip the grayscale cost when off).
                if (laneMarkingSnap) ld.detect(input, frameGray(frame), GRAY_W, GRAY_H) else ld.detect(input)
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

    /** Downscaled full-frame grayscale (row-major 0..255) for the lane marking-snap. */
    private fun frameGray(frame: Bitmap): ByteArray {
        val rgba = Mat(); val gray = Mat(); val small = Mat()
        try {
            Utils.bitmapToMat(frame, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.resize(gray, small, Size(GRAY_W.toDouble(), GRAY_H.toDouble()))
            small.get(0, 0, grayBuf)
        } finally {
            rgba.release(); gray.release(); small.release()
        }
        return grayBuf
    }

    override fun close() {
        detectorRunner.close(); laneRunner?.close(); signRunner?.close()
    }

    companion object {
        private const val TAG = "PerceptionEngine"
        private const val GRAY_W = 512   // marking-snap grayscale resolution (preserves thin markings)
        private const val GRAY_H = 288
    }
}
