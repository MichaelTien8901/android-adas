package com.adasedge.app.inference

import android.content.Context
import android.util.Log
import com.adasedge.app.model.AccelPath
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fallback path for non-Snapdragon / no-NPU devices: LiteRT (TFLite) with the
 * GPU delegate when supported, else CPU (realtime-inference: "Non-Snapdragon
 * fallback path").
 */
class LiteRtModelRunner private constructor(
    private val interpreter: Interpreter,
    private val gpuDelegate: GpuDelegate?,
    override val accelPath: AccelPath,
) : ModelRunner {

    override fun run(input: FloatArray): List<TensorOut> {
        val inBuf = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder())
        inBuf.asFloatBuffer().put(input)
        val outputs = HashMap<Int, Any>()
        val results = ArrayList<TensorOut>()
        val outBuffers = Array(interpreter.outputTensorCount) { i ->
            val t = interpreter.getOutputTensor(i)
            ByteBuffer.allocateDirect(t.numBytes()).order(ByteOrder.nativeOrder()).also { outputs[i] = it }
        }
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inBuf), outputs)
        for (i in outBuffers.indices) {
            val t = interpreter.getOutputTensor(i)
            val fb = (outputs[i] as ByteBuffer).apply { rewind() }.asFloatBuffer()
            val data = FloatArray(fb.remaining()); fb.get(data)
            results += TensorOut("out$i", data, t.shape())
        }
        return results
    }

    override fun close() {
        interpreter.close(); gpuDelegate?.close()
    }

    companion object {
        private const val TAG = "LiteRtModelRunner"

        fun tryCreate(ctx: Context, modelBase: String): LiteRtModelRunner? {
            val asset = "$modelBase.tflite"
            if (!AssetModels.exists(ctx, asset)) return null
            return try {
                val bytes = AssetModels.readBytes(ctx, asset)
                val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                buf.put(bytes); buf.rewind()
                val opts = Interpreter.Options()
                var gpu: GpuDelegate? = null
                var path = AccelPath.CPU
                if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                    gpu = GpuDelegate(); opts.addDelegate(gpu); path = AccelPath.LITERT_GPU
                } else {
                    opts.setNumThreads(4)
                }
                LiteRtModelRunner(Interpreter(buf, opts), gpu, path)
            } catch (t: Throwable) {
                Log.w(TAG, "LiteRT create failed for $asset", t); null
            }
        }
    }
}
