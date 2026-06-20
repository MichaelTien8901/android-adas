package com.adasedge.app.inference

import android.content.Context
import android.util.Log
import com.adasedge.app.inference.qnn.QnnModelRunner

/**
 * Selects the acceleration path at startup in priority order
 * (realtime-inference: "Per-device model targeting" / "Non-Snapdragon fallback"):
 *   QNN HTP  →  ONNX-Runtime QNN-EP / CPU  →  LiteRT GPU / CPU.
 * The first one that constructs wins. Never silently no-ops: if everything fails
 * an [IllegalStateException] is thrown so the caller can surface a hard error.
 */
object EngineFactory {
    private const val TAG = "EngineFactory"

    /**
     * @param modelBase base asset name (e.g. "detector", "lane").
     * @param channels/h/w expected model input shape, used by the ORT path.
     */
    fun create(ctx: Context, modelBase: String, channels: Int, h: Int, w: Int): ModelRunner {
        Log.i(TAG, "device: ${DeviceInfo.summary()}")

        QnnModelRunner.tryCreate(ctx, modelBase)?.let {
            Log.i(TAG, "$modelBase -> QNN_HTP"); return it
        }
        OrtModelRunner.tryCreate(ctx, modelBase, channels, h, w)?.let {
            Log.i(TAG, "$modelBase -> ${it.accelPath}"); return it
        }
        LiteRtModelRunner.tryCreate(ctx, modelBase)?.let {
            Log.i(TAG, "$modelBase -> ${it.accelPath}"); return it
        }
        throw IllegalStateException("No inference engine available for '$modelBase' (no model asset found)")
    }
}
