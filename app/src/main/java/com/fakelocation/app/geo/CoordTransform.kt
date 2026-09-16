package com.fakelocation.app.geo

import com.fakelocation.app.model.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84 ↔ GCJ-02 (火星坐标). Gaode/Tencent maps use GCJ-02;
 * Android LocationManager typically uses WGS-84. WeChat type=gcj02 converts from system.
 */
object CoordTransform {
    private const val PI = Math.PI
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun outOfChina(lat: Double, lon: Double): Boolean {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    fun wgs84ToGcj02(point: GeoPoint): GeoPoint {
        if (outOfChina(point.latitude, point.longitude)) return point
        val (dLat, dLon) = delta(point.latitude, point.longitude)
        return GeoPoint(point.latitude + dLat, point.longitude + dLon)
    }

    fun gcj02ToWgs84(point: GeoPoint): GeoPoint {
        if (outOfChina(point.latitude, point.longitude)) return point
        // Iterative inverse (adequate for navigation-scale accuracy).
        var wgs = point
        repeat(3) {
            val gcj = wgs84ToGcj02(wgs)
            wgs = GeoPoint(
                latitude = wgs.latitude - (gcj.latitude - point.latitude),
                longitude = wgs.longitude - (gcj.longitude - point.longitude)
            )
        }
        return wgs
    }

    private fun delta(lat: Double, lon: Double): Pair<Double, Double> {
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return dLat to dLon
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
