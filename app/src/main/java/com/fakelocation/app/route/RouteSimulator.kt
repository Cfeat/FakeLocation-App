package com.fakelocation.app.route

import com.fakelocation.app.model.GeoPoint

/**
 * Walks a polyline at a constant ground speed, emitting positions every tick.
 * Zero-length paths (duplicated single point) stay put and finish on first tick.
 */
class RouteSimulator(
    private val points: List<GeoPoint>,
    private var speedMetersPerSecond: Double
) {
    private val segmentLengths: List<Double>
    private val totalLength: Double
    private val stationary: Boolean
    private var traveled = 0.0

    init {
        require(points.size >= 2) { "Need at least 2 points" }
        segmentLengths = points.zipWithNext { a, b -> GeoMath.distanceMeters(a, b) }
        val raw = segmentLengths.sum()
        stationary = raw < 0.01
        totalLength = if (stationary) 0.0 else raw
    }

    fun updateSpeed(speed: Double) {
        speedMetersPerSecond = speed.coerceAtLeast(0.1)
    }

    fun reset() {
        traveled = 0.0
    }

    fun isFinished(): Boolean = stationary || traveled >= totalLength

    fun progress(): Float {
        if (stationary || totalLength <= 0.0) return 1f
        return (traveled / totalLength).toFloat().coerceIn(0f, 1f)
    }

    fun tick(deltaSeconds: Double): Sample {
        if (stationary) {
            return sampleAt(0.0)
        }
        traveled = (traveled + speedMetersPerSecond * deltaSeconds).coerceAtMost(totalLength)
        return sampleAt(traveled)
    }

    fun sampleAt(distance: Double): Sample {
        if (stationary) {
            return Sample(points.first(), 0f, 1f)
        }
        val target = distance.coerceIn(0.0, totalLength)
        var remaining = target
        for (i in segmentLengths.indices) {
            val seg = segmentLengths[i]
            if (remaining <= seg || i == segmentLengths.lastIndex) {
                val fraction = if (seg <= 0) 1.0 else (remaining / seg).coerceIn(0.0, 1.0)
                val a = points[i]
                val b = points[i + 1]
                return Sample(
                    point = GeoMath.interpolate(a, b, fraction),
                    bearing = GeoMath.bearingDegrees(a, b),
                    progress = (target / totalLength).toFloat()
                )
            }
            remaining -= seg
        }
        val last = points.last()
        val prev = points[points.lastIndex - 1]
        return Sample(last, GeoMath.bearingDegrees(prev, last), 1f)
    }

    data class Sample(
        val point: GeoPoint,
        val bearing: Float,
        val progress: Float
    )
}
