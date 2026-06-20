package com.adasedge.app.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.adasedge.app.core.Config
import com.adasedge.app.model.AccelPath
import java.nio.FloatBuffer

/**
 * Middle path: ONNX Runtime Mobile. On Snapdragon it adds the QNN Execution
 * Provider (HTP); otherwise it runs on CPU. This is also the path the Ultralytics
 * QNN export uses under the hood (research/03).
 */
class OrtModelRunner private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    override val accelPath: AccelPath,
    private val inputName: String,
    private val inputShape: LongArray,
) : ModelRunner {

    override fun run(input: FloatArray): List<TensorOut> {
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), inputShape)
        tensor.use {
            session.run(mapOf(inputName to it)).use { result ->
                return result.map { (name, value) ->
                    val onnx = value as OnnxTensor
                    val shape = onnx.info.shape.map { d -> d.toInt() }.toIntArray()
                    val data = FloatArray(shape.fold(1) { a, b -> a * b })
                    onnx.floatBuffer.get(data)
                    TensorOut(name, data, shape)
                }
            }
        }
    }

    override fun close() {
        session.close(); env.close()
    }

    companion object {
        private const val TAG = "OrtModelRunner"

        fun tryCreate(ctx: Context, modelBase: String, channels: Int, h: Int, w: Int): OrtModelRunner? {
            val asset = "$modelBase.onnx"
            if (!AssetModels.exists(ctx, asset)) return null
            return try {
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions()
                var path = AccelPath.CPU
                if (DeviceInfo.isSnapdragon) {
                    try {
                        // addQnn(Map) is only present in ORT builds compiled with the
                        // QNN EP; invoke reflectively so we compile against any AAR and
                        // fall back to CPU when it is absent.
                        val m = opts.javaClass.getMethod("addQnn", Map::class.java)
                        m.invoke(opts, mapOf("backend_path" to "libQnnHtp.so"))
                        path = AccelPath.ORT_QNN
                    } catch (t: Throwable) {
                        Log.w(TAG, "QNN EP not available in this ORT build; using CPU", t)
                    }
                }
                val session = env.createSession(AssetModels.readBytes(ctx, asset), opts)
                val inName = session.inputNames.first()
                OrtModelRunner(env, session, path, inName, longArrayOf(1, channels.toLong(), h.toLong(), w.toLong()))
            } catch (t: Throwable) {
                Log.w(TAG, "ORT session create failed for $asset", t); null
            }
        }
    }
}
