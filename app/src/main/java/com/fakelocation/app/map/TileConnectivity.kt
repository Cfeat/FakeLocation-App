package com.fakelocation.app.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object TileConnectivity {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    data class ProbeResult(val ok: Boolean, val source: String, val detail: String)

    suspend fun probe(): ProbeResult = withContext(Dispatchers.IO) {
        val candidates = listOf(
            "高德" to "https://wprd01.is.autonavi.com/appmaptile?x=54658&y=26799&z=16&lang=zh_cn&size=1&scl=1&style=7&ltype=7",
            "GeoQ" to "https://map.geoq.cn/ArcGIS/rest/services/ChinaOnlineCommunity/MapServer/tile/10/412/843"
        )
        for ((name, url) in candidates) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
                    )
                    .header("Referer", "https://www.amap.com/")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val type = resp.header("Content-Type").orEmpty()
                    if (resp.isSuccessful && type.contains("image")) {
                        return@withContext ProbeResult(true, name, "HTTP ${resp.code} $type")
                    }
                }
            } catch (t: Throwable) {
                // try next
            }
        }
        ProbeResult(false, "无", "瓦片服务器不可达，请检查网络/代理")
    }
}
