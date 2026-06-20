package com.adasedge.app.inference.qnn

import android.content.Context
import com.adasedge.app.inference.AssetModels
import com.adasedge.app.inference.DeviceInfo
import com.adasedge.app.inference.ModelRunner
import com.adasedge.app.inference.TensorOut
import com.adasedge.app.model.AccelPath
import java.io.File

/**
 * Primary path: runs a pre-built HTP context binary on the Hexagon NPU via the
 * QNN JNI bridge. The binary is selected by the device's HTP arch
 * (v69 → S22+, v81 → S26 Ultra). Returns null from [tryCreate] when the native
 * lib, the matching binary, or a usable Snapdragon SoC is absent, so the factory
 * can fall back (realtime-inference: "Mismatched or missing context binary").
 */
class QnnModelRunner private constructor(
    private val handle: Long,
) : ModelRunner {

    override val accelPath = AccelPath.QNN_HTP

    override fun run(input: FloatArray): List<TensorOut> {
        val outs = QnnNative.run(handle, input)
        val shapes = QnnNative.outputShapes(handle)
        return outs.mapIndexed { i, data ->
            TensorOut("out$i", data, shapes.getOrElse(i) { intArrayOf(data.size) })
        }
    }

    override fun close() = QnnNative.release(handle)

    companion object {
        /** @param modelBase e.g. "detector" → loads detector_<arch>.bin */
        fun tryCreate(ctx: Context, modelBase: String): QnnModelRunner? {
            if (!DeviceInfo.isSnapdragon || !QnnNative.available) return null
            val arch = DeviceInfo.htpArch() ?: return null
            val asset = "${modelBase}_$arch.bin"
            if (!AssetModels.exists(ctx, asset)) return null
            val path = AssetModels.materialize(ctx, asset)
            // The Hexagon skel ships as an asset (DSP arch, not arm64). Materialize
            // it so ADSP_LIBRARY_PATH can point at a real dir; fall back to the
            // native lib dir if absent.
            val skel = "dsp/libQnnHtp${arch.uppercase()}Skel.so"
            val skelDir = if (AssetModels.exists(ctx, skel))
                File(AssetModels.materialize(ctx, skel)).parent ?: ctx.applicationInfo.nativeLibraryDir
            else ctx.applicationInfo.nativeLibraryDir
            val handle = QnnNative.loadContext(path, skelDir, DeviceInfo.socId(), arch)
            if (handle == 0L) return null
            return QnnModelRunner(handle)
        }
    }
}
