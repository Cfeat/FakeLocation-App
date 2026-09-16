package com.fakelocation.app.map

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import com.fakelocation.app.model.GeoPoint
import com.fakelocation.app.route.GeoMath
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Finger-draw a route on the map. While [drawingEnabled], map gestures are consumed.
 */
class FreehandDrawOverlay(
    private val context: Context,
    private val onRouteFinished: (List<GeoPoint>) -> Unit,
    private val onDrawingChanged: (Boolean) -> Unit = {}
) : Overlay() {

    var drawingEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) {
                active = false
                stroke.clear()
            }
            onDrawingChanged(value)
        }

    private var active = false
    private val stroke = mutableListOf<GeoPoint>()
    private val minSampleMeters = 4.0

    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
        if (!drawingEnabled) return false
        val projection = mapView.projection
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = true
                stroke.clear()
                val gp = projection.fromPixels(event.x.toInt(), event.y.toInt())
                stroke.add(GeoPoint(gp.latitude, gp.longitude))
                mapView.invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!active) return true
                val gp = projection.fromPixels(event.x.toInt(), event.y.toInt())
                val next = GeoPoint(gp.latitude, gp.longitude)
                val last = stroke.lastOrNull()
                if (last == null || GeoMath.distanceMeters(last, next) >= minSampleMeters) {
                    stroke.add(next)
                    mapView.invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!active) return true
                active = false
                val result = stroke.toList()
                stroke.clear()
                mapView.invalidate()
                if (result.size >= 2) {
                    onRouteFinished(result)
                }
                return true
            }
        }
        return true
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || stroke.size < 2) return
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#0B6E4F")
            strokeWidth = 10f
            style = android.graphics.Paint.Style.STROKE
            isAntiAlias = true
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        val path = android.graphics.Path()
        stroke.forEachIndexed { index, point ->
            val p = mapView.projection.toPixels(OsmGeoPoint(point.latitude, point.longitude), null)
            if (index == 0) path.moveTo(p.x.toFloat(), p.y.toFloat())
            else path.lineTo(p.x.toFloat(), p.y.toFloat())
        }
        canvas.drawPath(path, paint)
    }
}
