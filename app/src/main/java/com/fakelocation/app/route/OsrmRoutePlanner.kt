package com.fakelocation.app.route

import com.fakelocation.app.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Uses the public OSRM demo server for MVP auto-routing.
 * Replace with your own backend (Amap / Tencent) for production.
 */
class OsrmRoutePlanner(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    suspend fun planWalking(waypoints: List<GeoPoint>): List<GeoPoint> = withContext(Dispatchers.IO) {
        require(waypoints.size >= 2) { "Need at least start and end" }
        val coords = waypoints.joinToString(";") { "${it.longitude},${it.latitude}" }
        val url =
            "https://router.project-osrm.org/route/v1/walking/$coords?overview=full&geometries=geojson"
        val request = Request.Builder().url(url).header("User-Agent", "FakeLocation/0.1").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            parseGeoJsonLine(body)
        }
    }

    private fun parseGeoJsonLine(json: String): List<GeoPoint> {
        val root = JSONObject(json)
        val code = root.optString("code")
        if (code != "Ok") {
            error(root.optString("message", code.ifEmpty { "route failed" }))
        }
        val routes = root.getJSONArray("routes")
        if (routes.length() == 0) error("empty routes")
        val geometry = routes.getJSONObject(0).getJSONObject("geometry")
        val coordinates = geometry.getJSONArray("coordinates")
        val points = ArrayList<GeoPoint>(coordinates.length())
        for (i in 0 until coordinates.length()) {
            val pair = coordinates.getJSONArray(i)
            val lon = pair.getDouble(0)
            val lat = pair.getDouble(1)
            points.add(GeoPoint(lat, lon))
        }
        if (points.size < 2) error("polyline too short")
        return points
    }
}
