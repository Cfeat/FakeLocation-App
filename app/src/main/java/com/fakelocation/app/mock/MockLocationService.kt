package com.fakelocation.app.mock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.fakelocation.app.R
import com.fakelocation.app.bridge.LocationBridgeStore
import com.fakelocation.app.model.GeoPoint
import com.fakelocation.app.route.RouteSimulator
import com.fakelocation.app.safety.SafetyPolicy
import com.fakelocation.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class MockLocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private lateinit var writer: MockLocationWriter
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        writer = MockLocationWriter(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Sticky restart with null intent must not leave an FGS without startForeground.
        if (intent == null) {
            ensureForeground("模拟已中断")
            stopInternal(notifyIdle = true)
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                ensureForeground("正在停止")
                stopInternal(notifyIdle = true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_SPEED -> {
                val speed = intent.getDoubleExtra(EXTRA_SPEED, SafetyPolicy.DEFAULT_SPEED_MPS.toDouble())
                    .coerceIn(0.1, SafetyPolicy.MAX_SPEED_MPS.toDouble())
                currentSpeed = speed
                simulator?.updateSpeed(speed)
            }
            ACTION_START -> {
                val lats = intent.getDoubleArrayExtra(EXTRA_LATS)
                val lngs = intent.getDoubleArrayExtra(EXTRA_LNGS)
                if (lats == null || lngs == null || lats.size < 2 || lats.size != lngs.size) {
                    ensureForeground("参数无效")
                    stopInternal(notifyIdle = true)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val speed = intent.getDoubleExtra(EXTRA_SPEED, SafetyPolicy.DEFAULT_SPEED_MPS.toDouble())
                    .coerceIn(0.1, SafetyPolicy.MAX_SPEED_MPS.toDouble())
                currentSpeed = speed
                writer.convertGcjToWgs = intent.getBooleanExtra(EXTRA_CONVERT_GCJ, true)
                val points = lats.indices.map { GeoPoint(lats[it], lngs[it]) }
                startMock(points, speed)
            }
            else -> {
                ensureForeground("未知请求")
                stopInternal(notifyIdle = true)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureForeground(content: String) {
        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, buildNotification(content))
            foregroundStarted = true
        } else {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(content))
        }
    }

    private fun startMock(points: List<GeoPoint>, speed: Double) {
        val session = sessionEpoch.incrementAndGet()
        loopJob?.cancel()

        // Must call startForeground promptly after startForegroundService.
        ensureForeground("模拟定位运行中")

        try {
            writer.enable()
        } catch (t: Throwable) {
            broadcastStatus(false, "无法启用模拟定位：${t.message ?: "请检查开发者选项"}")
            stopInternal(notifyIdle = false)
            stopSelf()
            return
        }

        activePoints = points
        simulator = RouteSimulator(points, speed)
        isRunning = true
        sessionStartedAtMs = System.currentTimeMillis()
        broadcastStatus(true, "模拟中 0%")

        loopJob = scope.launch {
            val tickMs = 500L
            val routePoints = activePoints.orEmpty()
            val sim = simulator ?: return@launch
            // Immediate first fix so WeChat can see a location ASAP.
            try {
                val first = sim.sampleAt(0.0)
                val wgs = writer.push(first.point, first.bearing, currentSpeed.toFloat())
                latestPoint = first.point
                LocationBridgeStore.publish(
                    mapGcj = first.point,
                    wgs = wgs,
                    bearing = first.bearing,
                    speedMps = currentSpeed.toFloat(),
                    progress = first.progress,
                    running = true
                )
                broadcastPosition(first.point, first.bearing, first.progress)
                broadcastDiag(wgs, writer.readBack())
            } catch (t: Throwable) {
                broadcastStatus(false, "写入失败：${t.message ?: "未知错误"}")
                stopInternal(notifyIdle = false)
                stopSelf()
                return@launch
            }

            while (isActive && session == sessionEpoch.get()) {
                val elapsed = System.currentTimeMillis() - sessionStartedAtMs
                if (elapsed >= SafetyPolicy.MAX_SESSION_MS) {
                    broadcastStatus(false, "已达单次最长 ${SafetyPolicy.MAX_SESSION_MS / 60000} 分钟，已自动停止（账号安全）")
                    stopInternal(notifyIdle = false)
                    stopSelf()
                    break
                }

                val sample = sim.tick(tickMs / 1000.0)
                try {
                    val moving = !sim.isFinished()
                    val wgs = writer.push(
                        mapPoint = sample.point,
                        bearing = sample.bearing,
                        speedMps = if (moving) currentSpeed.toFloat() else 0f
                    )
                    latestPoint = sample.point
                    LocationBridgeStore.publish(
                        mapGcj = sample.point,
                        wgs = wgs,
                        bearing = sample.bearing,
                        speedMps = if (moving) currentSpeed.toFloat() else 0f,
                        progress = sample.progress,
                        running = true
                    )
                    broadcastPosition(sample.point, sample.bearing, sample.progress)
                    broadcastDiag(wgs, writer.readBack())
                    if (moving) {
                        broadcastStatus(true, "模拟中 ${(sample.progress * 100).toInt()}%")
                    }

                    if (sim.isFinished()) {
                        val holdMs = if (pointsAreStationary(routePoints)) {
                            SafetyPolicy.STATIONARY_HOLD_MS
                        } else {
                            SafetyPolicy.HOLD_AT_END_MS
                        }
                        broadcastStatus(true, "保持位置中，稍后自动停止（账号安全）")
                        val holdUntil = System.currentTimeMillis() + holdMs
                        while (isActive &&
                            session == sessionEpoch.get() &&
                            System.currentTimeMillis() < holdUntil
                        ) {
                            val sessionElapsed = System.currentTimeMillis() - sessionStartedAtMs
                            if (sessionElapsed >= SafetyPolicy.MAX_SESSION_MS) break
                            val holdWgs = writer.push(sample.point, sample.bearing, 0f)
                            LocationBridgeStore.publish(
                                mapGcj = sample.point,
                                wgs = holdWgs,
                                bearing = sample.bearing,
                                speedMps = 0f,
                                progress = 1f,
                                running = true
                            )
                            broadcastDiag(holdWgs, writer.readBack())
                            delay(tickMs)
                        }
                        if (session == sessionEpoch.get()) {
                            broadcastStatus(false, "已自动停止。请关闭模拟定位后再用日常微信")
                            stopInternal(notifyIdle = false)
                            stopSelf()
                        }
                        break
                    }
                } catch (t: Throwable) {
                    broadcastStatus(false, "写入失败：${t.message ?: "未知错误"}")
                    stopInternal(notifyIdle = false)
                    stopSelf()
                    break
                }
                delay(tickMs)
            }
        }
    }

    private fun pointsAreStationary(points: List<GeoPoint>): Boolean {
        if (points.size < 2) return true
        return com.fakelocation.app.route.GeoMath.pathLengthMeters(points) < 0.01
    }

    private fun stopInternal(notifyIdle: Boolean) {
        sessionEpoch.incrementAndGet()
        loopJob?.cancel()
        loopJob = null
        try {
            writer.disable()
        } catch (_: Throwable) {
        }
        isRunning = false
        simulator = null
        activePoints = null
        LocationBridgeStore.clearRunning()
        if (notifyIdle) {
            broadcastStatus(false, getString(R.string.status_idle))
        }
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
    }

    override fun onDestroy() {
        stopInternal(notifyIdle = false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "模拟定位",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop_mock), stop)
            .setOngoing(true)
            .build()
    }

    private fun broadcastStatus(running: Boolean, message: String) {
        sendBroadcast(
            Intent(ACTION_STATUS).setPackage(packageName)
                .putExtra(EXTRA_RUNNING, running)
                .putExtra(EXTRA_MESSAGE, message)
        )
    }

    private fun broadcastPosition(point: GeoPoint, bearing: Float, progress: Float) {
        sendBroadcast(
            Intent(ACTION_POSITION).setPackage(packageName)
                .putExtra(EXTRA_LAT, point.latitude)
                .putExtra(EXTRA_LNG, point.longitude)
                .putExtra(EXTRA_BEARING, bearing)
                .putExtra(EXTRA_PROGRESS, progress)
        )
    }

    private fun broadcastDiag(writtenWgs: GeoPoint, readBack: GeoPoint?) {
        sendBroadcast(
            Intent(ACTION_DIAG).setPackage(packageName)
                .putExtra(EXTRA_WGS_LAT, writtenWgs.latitude)
                .putExtra(EXTRA_WGS_LNG, writtenWgs.longitude)
                .putExtra(EXTRA_READ_LAT, readBack?.latitude ?: Double.NaN)
                .putExtra(EXTRA_READ_LNG, readBack?.longitude ?: Double.NaN)
        )
    }

    companion object {
        const val ACTION_START = "com.fakelocation.app.action.START"
        const val ACTION_STOP = "com.fakelocation.app.action.STOP"
        const val ACTION_UPDATE_SPEED = "com.fakelocation.app.action.UPDATE_SPEED"
        const val ACTION_STATUS = "com.fakelocation.app.action.STATUS"
        const val ACTION_POSITION = "com.fakelocation.app.action.POSITION"
        const val ACTION_DIAG = "com.fakelocation.app.action.DIAG"

        const val EXTRA_LATS = "lats"
        const val EXTRA_LNGS = "lngs"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_CONVERT_GCJ = "convert_gcj"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_BEARING = "bearing"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_WGS_LAT = "wgs_lat"
        const val EXTRA_WGS_LNG = "wgs_lng"
        const val EXTRA_READ_LAT = "read_lat"
        const val EXTRA_READ_LNG = "read_lng"

        private const val CHANNEL_ID = "mock_location"
        private const val NOTIFICATION_ID = 42

        /** Bumped on each start/stop so in-flight loops exit cleanly (fixes STOP/START races). */
        private val sessionEpoch = AtomicInteger(0)

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var latestPoint: GeoPoint? = null
            private set

        @Volatile
        private var simulator: RouteSimulator? = null

        @Volatile
        private var activePoints: List<GeoPoint>? = null

        @Volatile
        private var currentSpeed: Double = SafetyPolicy.DEFAULT_SPEED_MPS.toDouble()

        @Volatile
        private var sessionStartedAtMs: Long = 0L
    }
}
