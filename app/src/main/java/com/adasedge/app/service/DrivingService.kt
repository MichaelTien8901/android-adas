package com.adasedge.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Size
import androidx.camera.core.Preview
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.adasedge.app.R
import com.adasedge.app.alert.AlertController
import com.adasedge.app.camera.CameraController
import com.adasedge.app.core.Prefs
import com.adasedge.app.inference.FrameScheduler
import com.adasedge.app.inference.ThermalGovernor
import com.adasedge.app.model.PerceptionResult
import com.adasedge.app.model.RuntimeStatus
import com.adasedge.app.model.Warning
import com.adasedge.app.perception.PerceptionEngine
import com.adasedge.app.speed.SpeedContext
import com.adasedge.app.warnings.WarningManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service that owns the realtime driving session (driver-alert-hmi:
 * "Foreground operation and screen-on"). It runs the camera → perception →
 * warnings pipeline off the UI thread and publishes state flows the
 * DrivingActivity observes to render the overlay. Survives the activity so the
 * session is robust.
 */
class DrivingService : LifecycleService() {

    private lateinit var prefs: Prefs
    private val camera by lazy { CameraController(this) }
    private val scheduler = FrameScheduler()
    private lateinit var governor: ThermalGovernor
    private lateinit var speed: SpeedContext
    private lateinit var warnings: WarningManager
    private lateinit var alert: AlertController
    private var perception: PerceptionEngine? = null

    private val frameCounter = AtomicLong(0)
    private var started = false

    private val _perception = MutableStateFlow<PerceptionResult?>(null)
    val perceptionState: StateFlow<PerceptionResult?> = _perception.asStateFlow()
    private val _warnings = MutableStateFlow<List<Warning>>(emptyList())
    val warningState: StateFlow<List<Warning>> = _warnings.asStateFlow()
    private val _status = MutableStateFlow(RuntimeStatus())
    val runtimeStatus: StateFlow<RuntimeStatus> = _status.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    inner class LocalBinder : Binder() { val service get() = this@DrivingService }
    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder { super.onBind(intent); return binder }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        governor = ThermalGovernor(this)
        speed = SpeedContext(this)
        warnings = WarningManager(prefs)
        alert = AlertController(this, prefs)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundCompat()
        return START_STICKY
    }

    /** Called by the activity once its PreviewView surface is ready. */
    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        if (started) return
        started = true
        perception = try {
            PerceptionEngine(this)
        } catch (t: Throwable) {
            Log.e(TAG, "perception init failed", t)
            _error.value = "Model assets missing — see tools/README. Showing camera only."
            null
        }
        speed.start()
        camera.start(this, surfaceProvider, Size(1280, 720)) { bmp, ts -> onFrame(bmp, ts) }
    }

    private fun onFrame(bitmap: Bitmap, tsNanos: Long) {
        val engine = perception ?: run { bitmap.recycle(); return }
        val tier = governor.tier(scheduler.fps)
        if (frameCounter.incrementAndGet() % governor.frameStride(tier) != 0L) { bitmap.recycle(); return }
        if (!scheduler.tryBegin()) { bitmap.recycle(); return }
        try {
            val result = engine.process(bitmap, tsNanos)
            val spd = speed.tick()
            val warns = warnings.evaluate(result, spd)
            alert.update(warns)
            _perception.value = result
            _warnings.value = warns
            _status.value = RuntimeStatus(
                accelPath = engine.accelPath,
                thermalThrottled = governor.throttled,
                fps = scheduler.fps,
                lanesAvailable = engine.lanesAvailableLastFrame,
                speedValidity = spd.validity,
                speedKmh = spd.kmh,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "pipeline frame error", t)
        } finally {
            scheduler.end()
            bitmap.recycle()
        }
    }

    override fun onDestroy() {
        camera.stop(); speed.stop(); alert.release(); perception?.close()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val TAG = "DrivingService"
        private const val CHANNEL = "driving_session"
        private const val NOTIF_ID = 1001
    }
}
