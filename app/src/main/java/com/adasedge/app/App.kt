package com.adasedge.app

import android.app.Application
import android.util.Log

/**
 * Application entry point. Initializes OpenCV (used by the classical-CV lane
 * fallback and image preprocessing) once for the process.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            // OpenCV 4.10 Maven AAR self-loads its native lib via initLocal().
            org.opencv.android.OpenCVLoader.initLocal()
        } catch (t: Throwable) {
            Log.w(TAG, "OpenCV init failed; classical-CV lane fallback disabled", t)
        }
    }

    companion object {
        private const val TAG = "App"
        lateinit var instance: App
            private set
    }
}
