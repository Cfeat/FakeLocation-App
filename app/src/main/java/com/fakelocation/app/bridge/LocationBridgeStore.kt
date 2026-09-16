package com.fakelocation.app.bridge

import com.fakelocation.app.model.GeoPoint

/**
 * Shared snapshot of the current simulated location for the LAN HTTP bridge.
 * Mini programs poll this instead of relying on WeChat reading system mock GPS.
 */
object LocationBridgeStore {
    @Volatile
    var running: Boolean = false

    @Volatile
    var mapGcj: GeoPoint? = null

    @Volatile
    var wgs: GeoPoint? = null

    @Volatile
    var bearing: Float = 0f

    @Volatile
    var speedMps: Float = 0f

    @Volatile
    var progress: Float = 0f

    @Volatile
    var updatedAtMs: Long = 0L

    fun publish(
        mapGcj: GeoPoint,
        wgs: GeoPoint,
        bearing: Float,
        speedMps: Float,
        progress: Float,
        running: Boolean
    ) {
        this.mapGcj = mapGcj
        this.wgs = wgs
        this.bearing = bearing
        this.speedMps = speedMps
        this.progress = progress
        this.running = running
        this.updatedAtMs = System.currentTimeMillis()
    }

    fun clearRunning() {
        running = false
        updatedAtMs = System.currentTimeMillis()
    }
}
