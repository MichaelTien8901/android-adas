package com.adasedge.app.inference

import android.os.Build
import android.util.Log

/**
 * Reads on-device SoC identity to drive the acceleration-path decision and pick
 * the correct HTP context binary (realtime-inference: "Per-device model
 * targeting"; doc/project_draft target matrix).
 *
 * Values such as `ro.board.platform` / `ro.soc.model` are read via the system
 * property table. The exact S26 Ultra (8 Elite Gen 5) platform id / HTP arch
 * MUST be confirmed on the physical unit and added to [htpArchFor].
 */
object DeviceInfo {
    private const val TAG = "DeviceInfo"

    val boardPlatform: String by lazy { sysprop("ro.board.platform") }
    val socModel: String by lazy { sysprop("ro.soc.model") }
    val hardware: String by lazy { sysprop("ro.hardware") }

    /** Exynos / s5e parts have no Hexagon NPU — QNN path is impossible. */
    val isSnapdragon: Boolean by lazy {
        val p = boardPlatform.lowercase()
        hardware.lowercase().contains("qcom") &&
            !p.contains("exynos") && !p.startsWith("s5e")
    }

    /**
     * Maps the SoC to its Hexagon HTP architecture string used when selecting the
     * pre-built context binary. Returns null when unknown (forces fallback).
     */
    fun htpArch(): String? = htpArchFor(boardPlatform.lowercase(), socModel.uppercase())

    private fun htpArchFor(platform: String, soc: String): String? = when {
        // Galaxy S22+ — SM8450, confirmed on-device.
        platform == "taro" || soc == "SM8450" -> "v69"
        // Galaxy S26 Ultra — SM8850 (8 Elite Gen 5), confirmed on-device (platform=canoe).
        platform == "canoe" || soc == "SM8850" -> "v81"
        // Best-effort for nearby Snapdragons (dev convenience only).
        platform == "kalama" || soc == "SM8550" -> "v73"   // 8 Gen 2
        platform == "pineapple" || soc == "SM8650" -> "v75" // 8 Gen 3
        soc == "SM8750" -> "v79"                            // 8 Elite
        else -> null
    }

    /**
     * QNN soc_id used by the context-binary loader. S22+ (SM8450) is 43, confirmed
     * in doc/project_draft. Elite Gen 5 is TBD — read it on the unit and add here.
     * Returns -1 when unknown.
     */
    fun socId(): Int = when (socModel.uppercase()) {
        "SM8450" -> 36        // S22+ (confirmed; v69 binary built with soc_id 36)
        "SM8850" -> 660       // S26 Ultra, 8 Elite Gen 5 (informational; v81 binary keys off dsp_arch)
        "SM8550" -> 57        // 8 Gen 2
        "SM8650" -> 69        // 8 Gen 3
        else -> -1            // unknown — falls back
    }

    fun summary(): String =
        "platform=$boardPlatform soc=$socModel hw=$hardware snapdragon=$isSnapdragon htp=${htpArch()} socId=${socId()}"

    private fun sysprop(key: String): String = try {
        @Suppress("PrivateApi")
        val c = Class.forName("android.os.SystemProperties")
        val get = c.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String).orEmpty()
    } catch (t: Throwable) {
        Log.w(TAG, "sysprop $key failed", t)
        when (key) { // fall back to public Build fields where possible
            "ro.soc.model" -> Build.SOC_MODEL
            "ro.hardware" -> Build.HARDWARE
            else -> ""
        }
    }
}
