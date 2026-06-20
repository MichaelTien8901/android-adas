package com.adasedge.app.inference.qnn

import android.util.Log

/**
 * JNI bridge to the Qualcomm AI Engine Direct (QNN) HTP runtime.
 *
 * The native library `libadas_qnn.so` (a thin wrapper over libQnnHtp.so +
 * QnnSystem, built with the Qualcomm QNN SDK — see tools/README_qnn.md) is
 * loaded lazily and optionally: if it is absent, [available] is false and the
 * engine factory falls back. This keeps the APK buildable without the SDK.
 */
object QnnNative {
    private const val TAG = "QnnNative"

    val available: Boolean by lazy {
        try {
            System.loadLibrary("adas_qnn")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "libadas_qnn.so not present; QNN HTP path unavailable", t)
            false
        }
    }

    /** Load a pre-built HTP context binary. Returns a handle, or 0 on failure. */
    external fun loadContext(contextBinaryPath: String, nativeLibDir: String, socId: Int, dspArch: String): Long

    /** Run inference. [input] is row-major NCHW float. Returns flattened outputs. */
    external fun run(handle: Long, input: FloatArray): Array<FloatArray>

    /** Output shapes parallel to [run]'s returned arrays. */
    external fun outputShapes(handle: Long): Array<IntArray>

    external fun release(handle: Long)
}
