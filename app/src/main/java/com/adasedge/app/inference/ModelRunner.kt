package com.adasedge.app.inference

import com.adasedge.app.model.AccelPath
import java.io.Closeable

/** A single output tensor: flattened row-major data plus its shape. */
class TensorOut(val name: String, val data: FloatArray, val shape: IntArray)

/**
 * Runs one model with a single NCHW float input. Implementations wrap a specific
 * accelerator (QNN HTP context binary, ONNX Runtime QNN-EP, LiteRT GPU, or CPU).
 * Decoding of the raw output tensors is the perception layer's job.
 */
interface ModelRunner : Closeable {
    val accelPath: AccelPath
    /** @param input row-major NCHW float input already normalized for the model. */
    fun run(input: FloatArray): List<TensorOut>
}
