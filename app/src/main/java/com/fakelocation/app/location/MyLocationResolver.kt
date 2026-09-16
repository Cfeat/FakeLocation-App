package com.fakelocation.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.fakelocation.app.geo.CoordTransform
import com.fakelocation.app.model.GeoPoint
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolves a usable "my location" even when GPS is cold or mock providers interfered.
 */
class MyLocationResolver(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val handlerToken = Any()

    @SuppressLint("MissingPermission")
    fun request(
        timeoutMs: Long = 20_000L,
        onSuccess: (GeoPoint, Location) -> Unit,
        onError: (String) -> Unit
    ) {
        val finished = AtomicBoolean(false)
        var listener: LocationListener? = null
        val cancellation =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) CancellationSignal() else null

        fun cleanup() {
            listener?.let {
                try {
                    lm.removeUpdates(it)
                } catch (_: Throwable) {
                }
            }
            listener = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    cancellation?.cancel()
                } catch (_: Throwable) {
                }
            }
            mainHandler.removeCallbacksAndMessages(handlerToken)
        }

        fun ok(location: Location) {
            if (!finished.compareAndSet(false, true)) return
            cleanup()
            val gcj = CoordTransform.wgs84ToGcj02(
                GeoPoint(location.latitude, location.longitude)
            )
            onSuccess(gcj, location)
        }

        fun err(msg: String) {
            if (!finished.compareAndSet(false, true)) return
            cleanup()
            onError(msg)
        }

        mainHandler.postAtTime(
            { err("定位超时，请到开阔处重试，或先停止模拟定位") },
            handlerToken,
            SystemClock.uptimeMillis() + timeoutMs
        )

        val cached = bestCachedLocation()
        if (cached != null && ageMs(cached) <= 45_000L && !isMock(cached)) {
            ok(cached)
            return
        }

        val providers = enabledProviders()
        if (providers.isEmpty()) {
            err("系统定位未开启，请打开 GPS/网络定位")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val order = providers.sortedBy { priority(it) }
            fun tryProvider(index: Int) {
                if (finished.get() || index >= order.size) return
                val provider = order[index]
                try {
                    lm.getCurrentLocation(provider, cancellation, context.mainExecutor) { loc ->
                        if (finished.get()) return@getCurrentLocation
                        if (loc != null && !isMock(loc)) {
                            ok(loc)
                        } else {
                            tryProvider(index + 1)
                        }
                    }
                } catch (_: Throwable) {
                    tryProvider(index + 1)
                }
            }
            tryProvider(0)
        }

        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!isMock(location)) ok(location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        var subscribed = false
        for (provider in providers.sortedBy { priority(it) }) {
            try {
                lm.requestLocationUpdates(provider, 400L, 0f, listener!!, Looper.getMainLooper())
                subscribed = true
            } catch (_: Throwable) {
            }
        }
        if (!subscribed && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            err("无法订阅定位更新")
            return
        }

        // Last resort: accept any recent cache (even mock) after 8s so UI isn't stuck forever.
        mainHandler.postAtTime({
            if (finished.get()) return@postAtTime
            bestCachedLocation()?.let { ok(it) }
        }, handlerToken, SystemClock.uptimeMillis() + 8_000L)
    }

    @SuppressLint("MissingPermission")
    private fun bestCachedLocation(): Location? {
        val all = mutableListOf<Location>()
        for (p in listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
            "fused"
        )) {
            try {
                lm.getLastKnownLocation(p)?.let { all.add(it) }
            } catch (_: Throwable) {
            }
        }
        return all
            .filter { ageMs(it) < 10 * 60_000L }
            .sortedWith(
                compareBy<Location> { isMock(it) }
                    .thenBy { ageMs(it) }
                    .thenBy { it.accuracy }
            )
            .firstOrNull()
    }

    private fun enabledProviders(): List<String> {
        val result = mutableListOf<String>()
        for (p in listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            "fused"
        )) {
            try {
                if (lm.isProviderEnabled(p)) result.add(p)
            } catch (_: Throwable) {
            }
        }
        if (result.isEmpty()) {
            result += listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        }
        return result.distinct()
    }

    private fun priority(provider: String): Int = when (provider) {
        LocationManager.NETWORK_PROVIDER -> 0
        "fused" -> 1
        LocationManager.GPS_PROVIDER -> 2
        else -> 3
    }

    private fun ageMs(location: Location): Long =
        (System.currentTimeMillis() - location.time).coerceAtLeast(0L)

    private fun isMock(location: Location): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
    }
}
