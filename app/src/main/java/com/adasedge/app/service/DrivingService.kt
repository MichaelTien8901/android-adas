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
import com.adasedge.app.core.Calibration
import com.adasedge.app.core.Prefs
import com.adasedge.app.inference.FrameScheduler
import com.adasedge.app.inference.ThermalGovernor
import com.adasedge.app.model.PerceptionResult
import com.adasedge.app.model.RuntimeStatus
import com.adasedge.app.model.SpeedSample
import com.adasedge.app.model.SpeedValidity
import com.adasedge.app.model.Warning
import com.adasedge.app.model.WarningLevel
import com.adasedge.app.perception.PerceptionEngine
import com.adasedge.app.replay.ReplaySource
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
    private val replay by lazy { ReplaySource(this) }
    private val scheduler = FrameScheduler()
    private lateinit var governor: ThermalGovernor
    private lateinit var speed: SpeedContext
    private lateinit var warnings: WarningManager
    private lateinit var alert: AlertController
    private var perception: PerceptionEngine? = null

    private val frameCounter = AtomicLong(0)
    private var started = false
    private var replayActive = false
    private var lastWarnKeys = emptySet<String>()

    /** When false, skip publishing the frame backdrop (overlay-only — saves the
        per-frame scaled-copy; toggled from the driving screen to speed up FPS). */
    @Volatile var showFeed: Boolean = true

    /** Replay-mode only: the decoded frame, for the overlay backdrop (null = live camera). */
    private val _replayFrame = MutableStateFlow<Bitmap?>(null)
    val replayFrame: StateFlow<Bitmap?> = _replayFrame.asStateFlow()

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
            PerceptionEngine(this, Calibration(horizonRatio = prefs.horizonRatio, roadBottomRatio = prefs.roadBottomRatio))
        } catch (t: Throwable) {
            Log.e(TAG, "perception init failed", t)
            _error.value = "Model assets missing — see tools/README. Showing camera only."
            null
        }
        // Validation path (task 9.6): replay a clip instead of the live camera.
        if (prefs.replayMode && replay.available()) {
            replayActive = true
            Log.i(TAG, "REPLAY MODE: ${replay.file().name} @ ${prefs.replaySpeedKmh} km/h (synthetic speed)")
            replay.start(sink = { bmp, ts -> onFrame(bmp, ts) })
            return
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
            val computeStart = System.nanoTime()
            val result = engine.process(bitmap, tsNanos)
            if (replayActive && showFeed) publishReplayFrame(bitmap)
            val spd = if (replayActive) syntheticSpeed() else speed.tick()
            val warns = warnings.evaluate(result, spd)
            // Latency budget (task 9.1): perception + warning compute per frame.
            val computeMs = (System.nanoTime() - computeStart) / 1_000_000.0
            if (frameCounter.get() % 30L == 0L)
                Log.i(TAG, "latency: perception+warn ${"%.1f".format(computeMs)} ms (path=${engine.accelPath}, ${scheduler.fps.toInt()} fps, dropped=${scheduler.dropped})")
            if (replayActive) logWarnings(warns, result)
            alert.update(warns)
            _perception.value = result
            _warnings.value = warns
            _status.value = RuntimeStatus(
                accelPath = engine.accelPath,
                thermalThrottled = governor.thermalSevere,
                lowFps = governor.starved,
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

    /** Constant VALID speed during replay so speed-gated warnings can fire. */
    private fun syntheticSpeed(): SpeedSample =
        SpeedSample(
            metersPerSecond = prefs.replaySpeedKmh / 3.6f,
            validity = SpeedValidity.VALID,
            accuracyMps = 0.5f,
            stale = false,
            timestampNanos = System.nanoTime(),
        )

    /** Hand a downscaled copy of the replay frame to the overlay backdrop. */
    private fun publishReplayFrame(src: Bitmap) {
        val maxW = 640
        val scale = if (src.width > maxW) maxW.toFloat() / src.width else 1f
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        var copy = try { Bitmap.createScaledBitmap(src, w, h, true) } catch (t: Throwable) { return }
        if (copy === src) copy = src.copy(Bitmap.Config.ARGB_8888, false) // never hand over the soon-recycled src
        _replayFrame.value = copy   // ownership passes to the overlay (recycles its previous)
    }

    /** Log warning transitions so replay validation is observable in logcat. */
    private fun logWarnings(warns: List<Warning>, r: PerceptionResult) {
        val keys = warns.filter { it.level >= WarningLevel.INFO }
            .map { "${it.type}:${it.level}" + if (it.side != com.adasedge.app.model.Side.NONE) ":${it.side}" else "" }.toSet()
        if (keys != lastWarnKeys) {
            val added = keys - lastWarnKeys
            if (added.isNotEmpty()) {
                val lead = r.lead?.let { " lead=%.0fm ttc=%.1fs".format(it.distanceMeters, it.ttcSeconds) } ?: ""
                Log.i(TAG, "WARN ${added.joinToString()}$lead dets=${r.detections.size} lanes=${r.lanes != null}")
            }
            // Log clears too, so a warning's activation AND clear behaviour is observable
            // for replay validation (task 9.2). Skip INFO cues (transient sign/announce).
            val cleared = (lastWarnKeys - keys).filterNot { it.endsWith(":INFO") }
            if (cleared.isNotEmpty()) Log.i(TAG, "CLEAR ${cleared.joinToString()}")
            lastWarnKeys = keys
        }
    }

    override fun onDestroy() {
        replay.stop(); camera.stop(); speed.stop(); alert.release(); perception?.close()
        _replayFrame.value = null
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
