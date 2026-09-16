package com.fakelocation.app

import android.app.Application
import com.fakelocation.app.bridge.LocationHttpServer
import org.osmdroid.config.Configuration
import java.io.File

class FakeLocationApp : Application() {
    lateinit var locationServer: LocationHttpServer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        val base = File(cacheDir, "osmdroid").apply { mkdirs() }
        val tiles = File(base, "tiles").apply { mkdirs() }
        val prefs = getSharedPreferences("osmdroid", MODE_PRIVATE)
        Configuration.getInstance().apply {
            load(this@FakeLocationApp, prefs)
            userAgentValue =
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            additionalHttpRequestProperties["Referer"] = "https://www.amap.com/"
            additionalHttpRequestProperties["Accept"] = "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
            osmdroidBasePath = base
            osmdroidTileCache = tiles
            tileDownloadThreads = 8
            tileFileSystemThreads = 8
            expirationOverrideDuration = 1000L * 60 * 60 * 24 * 7
        }

        locationServer = LocationHttpServer(LocationHttpServer.DEFAULT_PORT)
        locationServer.start()
    }

    companion object {
        lateinit var instance: FakeLocationApp
            private set
    }
}
