package com.adasedge.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX capture pipeline (adas-perception: "Forward road camera capture").
 * Binds a Preview to the activity's surface and an ImageAnalysis use case with
 * STRATEGY_KEEP_ONLY_LATEST so a slow analyzer drops stale frames instead of
 * queuing them. Each frame is delivered to [onFrame] as an upright RGB Bitmap.
 */
class CameraController(private val context: Context) {

    fun interface FrameSink {
        /** @param tsNanos frame timestamp; ownership of [bitmap] passes to the sink. */
        fun onFrame(bitmap: Bitmap, tsNanos: Long)
    }

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null

    /**
     * @param videoCapture optional dashcam VideoCapture to bind as a 3rd use case (only when
     *   recording is enabled). If the device can't bind Preview+Analysis+Video together, we
     *   fall back to Preview+Analysis and invoke [onVideoBindFailed] so recording is disabled
     *   gracefully (perception keeps running).
     */
    fun start(
        owner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        analysisTarget: Size,
        videoCapture: UseCase? = null,
        onVideoReady: () -> Unit = {},
        onVideoBindFailed: () -> Unit = {},
        sink: FrameSink,
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameraProvider = future.get().also { provider = it }

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(surfaceProvider)
            }

            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(analysisTarget, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                ).build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(analysisExecutor) { proxy ->
                try {
                    val bmp = proxy.toUprightBitmap()
                    sink.onFrame(bmp, proxy.imageInfo.timestamp)
                } catch (t: Throwable) {
                    Log.w(TAG, "frame convert failed", t)
                } finally {
                    proxy.close()
                }
            }

            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            try {
                if (videoCapture != null) {
                    cameraProvider.bindToLifecycle(owner, selector, preview, analysis, videoCapture)
                    onVideoReady()
                } else {
                    cameraProvider.bindToLifecycle(owner, selector, preview, analysis)
                }
            } catch (t: Throwable) {
                if (videoCapture != null) {
                    // 3-use-case combo unsupported on this device → keep perception, drop recording.
                    Log.w(TAG, "binding with VideoCapture failed; falling back to preview+analysis", t)
                    runCatching { cameraProvider.unbindAll() }
                    cameraProvider.bindToLifecycle(owner, selector, preview, analysis)
                    onVideoBindFailed()
                } else throw t
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        provider?.unbindAll()
        analysisExecutor.shutdown()
    }

    private fun ImageProxy.toUprightBitmap(): Bitmap {
        val bmp = toBitmap() // camera-core 1.3+ ; RGBA_8888 output
        val deg = imageInfo.rotationDegrees
        if (deg == 0) return bmp
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    companion object { private const val TAG = "CameraController" }
}
