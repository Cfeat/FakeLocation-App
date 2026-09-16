package com.fakelocation.app.mock

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import com.fakelocation.app.geo.CoordTransform
import com.fakelocation.app.model.GeoPoint

/**
 * Writes mock samples into system LocationManager test providers.
 *
 * Map points are GCJ-02 (Gaode). By default they are converted to WGS-84 before write,
 * which matches typical Android + WeChat (type=gcj02) conversion.
 */
class MockLocationWriter(context: Context) {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /** When true, treat input as GCJ-02 and convert to WGS-84 for the OS. */
    @Volatile
    var convertGcjToWgs: Boolean = true

    private val providers = linkedSetOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        "fused"
    )

    @SuppressLint("MissingPermission")
    fun enable() {
        providers.forEach { name ->
            try {
                locationManager.removeTestProvider(name)
            } catch (_: Throwable) {
            }
            try {
                addProvider(name)
                locationManager.setTestProviderEnabled(name, true)
            } catch (_: Throwable) {
                // Some OEMs disallow mocking "fused"; GPS/NETWORK may still work.
            }
        }
    }

    private fun addProvider(name: String) {
        val isNetwork = name == LocationManager.NETWORK_PROVIDER || name == "fused"
        val isGps = name == LocationManager.GPS_PROVIDER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            locationManager.addTestProvider(
                name,
                /* requiresNetwork = */ isNetwork,
                /* requiresSatellite = */ isGps,
                /* requiresCell = */ isNetwork,
                /* hasMonetaryCost = */ false,
                /* supportsAltitude = */ true,
                /* supportsSpeed = */ true,
                /* supportsBearing = */ true,
                ProviderProperties.POWER_USAGE_LOW,
                ProviderProperties.ACCURACY_FINE
            )
        } else {
            @Suppress("DEPRECATION")
            locationManager.addTestProvider(
                name,
                isNetwork,
                isGps,
                isNetwork,
                false,
                true,
                true,
                true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE
            )
        }
    }

    fun disable() {
        providers.forEach { name ->
            try {
                locationManager.setTestProviderEnabled(name, false)
            } catch (_: Throwable) {
            }
            try {
                locationManager.removeTestProvider(name)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * @param mapPoint coordinate as shown on Gaode map (GCJ-02)
     * @return WGS-84 point actually written to the OS
     */
    fun push(
        mapPoint: GeoPoint,
        bearing: Float,
        speedMps: Float,
        accuracyMeters: Float = 8f
    ): GeoPoint {
        val writePoint = if (convertGcjToWgs) {
            CoordTransform.gcj02ToWgs84(mapPoint)
        } else {
            mapPoint
        }
        var anySuccess = false
        var lastError: Throwable? = null
        providers.forEach { name ->
            try {
                val location = Location(name).apply {
                    latitude = writePoint.latitude
                    longitude = writePoint.longitude
                    altitude = 10.0
                    this.accuracy = accuracyMeters
                    this.bearing = bearing
                    speed = speedMps.coerceAtLeast(0f)
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        bearingAccuracyDegrees = 12f
                        speedAccuracyMetersPerSecond = 1.0f
                        verticalAccuracyMeters = 8f
                    }
                }
                locationManager.setTestProviderLocation(name, location)
                anySuccess = true
            } catch (t: Throwable) {
                lastError = t
            }
        }
        if (!anySuccess) {
            throw lastError ?: IllegalStateException("所有 provider 写入失败，请确认已指定模拟位置应用")
        }
        lastWrittenWgs = writePoint
        lastWrittenMap = mapPoint
        return writePoint
    }

    @SuppressLint("MissingPermission")
    fun readBack(): GeoPoint? {
        val gps = try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (_: Throwable) {
            null
        }
        val net = try {
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: Throwable) {
            null
        }
        val best = listOfNotNull(gps, net).maxByOrNull { it.time } ?: return null
        return GeoPoint(best.latitude, best.longitude)
    }

    companion object {
        @Volatile
        var lastWrittenWgs: GeoPoint? = null
            private set

        @Volatile
        var lastWrittenMap: GeoPoint? = null
            private set
    }
}
