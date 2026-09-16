package com.fakelocation.app.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex

/**
 * Tile sources known to work for mainland China when request headers look like a browser.
 * Gaode unofficial XYZ endpoints — for local testing only.
 */
object AppTileSources {

    /** 高德矢量（详图）— 国内默认 */
    val GAODE_VECTOR: OnlineTileSourceBase = object : XYTileSource(
        "GaodeVectorWprd",
        1,
        20,
        256,
        ".png",
        arrayOf(
            "https://wprd01.is.autonavi.com/appmaptile?",
            "https://wprd02.is.autonavi.com/appmaptile?",
            "https://wprd03.is.autonavi.com/appmaptile?",
            "https://wprd04.is.autonavi.com/appmaptile?"
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return getBaseUrl() +
                "x=$x&y=$y&z=$z&lang=zh_cn&size=1&scl=1&style=7&ltype=7"
        }
    }

    /** 高德卫星 */
    val GAODE_SATELLITE: OnlineTileSourceBase = object : XYTileSource(
        "GaodeSatellite",
        1,
        20,
        256,
        ".jpg",
        arrayOf(
            "https://webst01.is.autonavi.com/appmaptile?",
            "https://webst02.is.autonavi.com/appmaptile?",
            "https://webst03.is.autonavi.com/appmaptile?",
            "https://webst04.is.autonavi.com/appmaptile?"
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return getBaseUrl() + "style=6&x=$x&y=$y&z=$z"
        }
    }

    /** GeoQ 社区图（ArcGIS z/y/x），作备用 */
    val GEOQ: OnlineTileSourceBase = object : XYTileSource(
        "GeoQCommunity",
        1,
        16,
        256,
        ".png",
        arrayOf("https://map.geoq.cn/ArcGIS/rest/services/ChinaOnlineCommunity/MapServer/tile/")
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            // ArcGIS REST: /tile/{z}/{y}/{x}
            return getBaseUrl() + "$z/$y/$x"
        }
    }

    fun default(): OnlineTileSourceBase = GAODE_VECTOR
}
